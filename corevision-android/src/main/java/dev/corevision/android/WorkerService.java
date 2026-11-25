package dev.corevision.android;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static dev.corevision.android.CoreVisionException.Code.*;

public final class WorkerService extends Service {
    private static final int PROBE_TIMEOUT_MILLIS = 5_000;
    private static final int WORKER_TIMEOUT_MILLIS = 45_000;

    private final AtomicReference<Job> active = new AtomicReference<>();

    private final IWorker.Stub binder = new IWorker.Stub() {
        @Override
        public Bundle process(ParcelFileDescriptor input, ParcelFileDescriptor output) {
            int caller = Binder.getCallingPid();
            try (InputStream requestData = new ParcelFileDescriptor.AutoCloseInputStream(input);
                 OutputStream responseData = new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
                if (Binder.getCallingUid() != Process.myUid()) throw new SecurityException("Private Core Vision service");
                Wire.Request request;
                try {
                    request = Wire.Request.read(requestData);
                    if (input.getStatSize() != 36L + request.bytes() || output.getStatSize() != 0) {
                        throw new IOException("Incorrect IPC file length");
                    }
                } catch (IOException error) {
                    throw error(INVALID_INPUT, error.getMessage());
                }
                Job job = new Job(caller, request.id());
                if (!active.compareAndSet(null, job)) throw error(BUSY, "Core Vision worker is busy");
                try {
                    execute(job, request, requestData, responseData);
                    return null;
                } finally {
                    active.compareAndSet(job, null);
                }
            } catch (IOException failure) {
                return error(PROTOCOL, failure.getMessage()).bundle();
            } catch (WorkerFailure failure) {
                return failure.bundle();
            }
        }

        @Override
        public void cancel(int requestId) {
            Job job = active.get();
            if (job != null && job.id == requestId && job.caller == Binder.getCallingPid()) job.stop();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        Job job = active.get();
        if (job != null) job.stop();
        super.onDestroy();
    }

    private void execute(Job job, Wire.Request request, InputStream input, OutputStream output) {
        if (!Process.is64Bit() || !Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a")
                || Os.sysconf(OsConstants._SC_PAGESIZE) != 4096) {
            throw error(UNSUPPORTED_DEVICE, "Requires an ARM64 app process and 4 KiB pages");
        }
        boolean probe = request.operation() == Wire.PROBE;
        long deadline = SystemClock.elapsedRealtime() + (probe ? PROBE_TIMEOUT_MILLIS : WORKER_TIMEOUT_MILLIS);
        File work = null;
        File stderr = null;
        try {
            work = Files.createTempDirectory(getCacheDir().toPath(), "corevision-run-").toFile();
            stderr = new File(work, "worker.stderr");
            File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
            String launcher = executable(nativeDir, "libcorevision_probe.so");
            String servicePid = Integer.toString(Process.myPid());
            RuntimePack runtime = probe ? null : RuntimePack.verify(this, request.operation());
            String timeout = Long.toString(remainingMillis(deadline));
            ProcessBuilder builder;
            if (probe) {
                builder = new ProcessBuilder(launcher, "--supervise", servicePid, timeout, launcher);
            } else {
                builder = new ProcessBuilder(launcher, "--supervise", servicePid, timeout,
                        executable(nativeDir, "libcorevision_musl.so"), "--library-path",
                        nativeDir.getAbsolutePath(), executable(nativeDir, "libcorevision_worker.so"),
                        new File(nativeDir, "libcobackbone.so").getAbsolutePath(), runtime.modelDir.getAbsolutePath(),
                        work.getAbsolutePath());
            }
            builder.directory(work).redirectError(stderr);
            builder.environment().remove("LD_PRELOAD");
            builder.environment().remove("LD_LIBRARY_PATH");
            java.lang.Process child;
            try {
                child = job.start(builder);
            } catch (IOException failure) {
                throw error(WORKER_START, failure.getMessage());
            }

            try (OutputStream pipe = child.getOutputStream()) {
                request.write(pipe);
                Wire.copy(input, pipe, request.bytes());
            }
            try (InputStream pipe = child.getInputStream()) {
                Wire.Response response = Wire.Response.read(pipe, request);
                String metadata = Wire.metadata(pipe, response);
                if (response.status() != 0) throw error(CoreVisionException.Code.fromValue(response.status()), metadata);
                if (runtime != null) metadata = runtime.checkedMetadata(metadata, (request.flags() & Wire.COARSE) != 0);
                byte[] encoded = metadata.getBytes(StandardCharsets.UTF_8);
                if (encoded.length > Wire.MAX_METADATA) throw error(PROTOCOL, "Worker report is too large");
                response.write(output, request.id(), encoded.length);
                output.write(encoded);
                Wire.copy(pipe, output, response.payload() + response.foreground() + response.coarse());
                if (pipe.read() != -1) throw error(PROTOCOL, "Unexpected extra response bytes");
            }
            int status = child.waitFor();
            job.checkStopped();
            remainingMillis(deadline);
            if (status != 0) throw error(WORKER_FAILED, "Native worker exited unsuccessfully");
        } catch (CoreVisionException failure) {
            job.checkStopped();
            throw error(failure.getCode(), failure.getMessage());
        } catch (IOException failure) {
            job.checkStopped();
            remainingMillis(deadline);
            String diagnostic = readDiagnostic(stderr);
            throw error(WORKER_FAILED, diagnostic.isEmpty() ? failure.getMessage() : diagnostic);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw error(WORKER_FAILED, "Worker wait interrupted");
        } finally {
            job.stop();
            deleteTree(work);
        }
    }

    private static long remainingMillis(long deadline) {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw error(TIMEOUT, "Native worker deadline expired");
        return remaining;
    }

    private static String executable(File directory, String name) {
        File file = new File(directory, name);
        if (!file.isFile() || !file.canExecute()) {
            throw error(WORKER_START, name + " is not executable in nativeLibraryDir. Enable legacy JNI packaging");
        }
        return file.getAbsolutePath();
    }

    private static void deleteTree(File file) {
        if (file == null) return;
        if (!Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }

    private static String readDiagnostic(File file) {
        if (file == null) return "";
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[1024];
            int size = input.read(buffer);
            return size > 0 ? new String(buffer, 0, size, StandardCharsets.UTF_8).trim() : "";
        } catch (IOException ignored) {
            return "";
        }
    }

    private static WorkerFailure error(CoreVisionException.Code code, String message) {
        return new WorkerFailure(code, message);
    }

    private static final class WorkerFailure extends RuntimeException {
        private final CoreVisionException.Code code;

        WorkerFailure(CoreVisionException.Code code, String message) {
            super(message);
            this.code = code;
        }

        Bundle bundle() {
            Bundle details = new Bundle();
            details.putInt("code", code.value);
            details.putString("message", getMessage());
            return details;
        }
    }

    private static final class Job {
        final int caller;
        final int id;
        private boolean stopped;
        private java.lang.Process child;

        Job(int caller, int id) {
            this.caller = caller;
            this.id = id;
        }

        synchronized java.lang.Process start(ProcessBuilder builder) throws IOException {
            checkStopped();
            child = builder.start();
            return child;
        }

        synchronized void checkStopped() {
            if (stopped) throw error(CLOSED, "Core Vision request cancelled");
        }

        void stop() {
            java.lang.Process running;
            synchronized (this) {
                if (stopped) return;
                stopped = true;
                running = child;
            }
            if (running != null && running.isAlive()) running.destroyForcibly();
        }
    }
}
