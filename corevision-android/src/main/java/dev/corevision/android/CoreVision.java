package dev.corevision.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.corevision.android.CoreVisionException.Code.*;

public final class CoreVision implements AutoCloseable {
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private final Context context;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentHashMap<Integer, CompletableFuture<?>> requests = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), runnable -> new Thread(runnable, "CoreVision-client"));
    private volatile Call active;

    public CoreVision(Context context) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
    }

    public CompletableFuture<Result> segment(Bitmap bitmap) {
        return segment(bitmap, false);
    }

    public CompletableFuture<Result> segment(Bitmap bitmap, boolean includeForeground) {
        return segmentInternal(bitmap, includeForeground, false);
    }

    CompletableFuture<Result> segmentForValidation(Bitmap bitmap) {
        return segmentInternal(bitmap, true, true);
    }

    private CompletableFuture<Result> segmentInternal(Bitmap bitmap, boolean includeForeground, boolean audit) {
        return submit((id, future) -> {
            validateBitmap(bitmap);
            int flags = (includeForeground ? Wire.FOREGROUND : 0) | (audit ? Wire.COARSE : 0);
            Wire.Request request = new Wire.Request(id, Wire.SEGMENT, flags, bitmap.getWidth(), bitmap.getHeight());
            return exchange(request, future, output -> writeBitmap(bitmap, output), CoreVision::readMask);
        });
    }

    public CompletableFuture<List<Face>> detectFaces(Bitmap bitmap) {
        return submit((id, future) -> {
            validateBitmap(bitmap);
            Wire.Request request = new Wire.Request(id, Wire.DETECT_FACES, 0, bitmap.getWidth(), bitmap.getHeight());
            return exchange(request, future, output -> writeBitmap(bitmap, output), CoreVision::readFaces);
        });
    }

    public CompletableFuture<FaceComparison> compareFaces(Bitmap first, Bitmap second) {
        return submit((id, future) -> {
            validateBitmap(first);
            validateBitmap(second);
            float[] firstFeatures = faceFeatures(id, first, future);
            float[] secondFeatures = faceFeatures(id, second, future);
            return compareFeatures(firstFeatures, secondFeatures);
        });
    }

    public CompletableFuture<ProbeResult> probe() {
        return submit((id, future) -> {
            Wire.Request request = new Wire.Request(id, Wire.PROBE, 0, 2, 2);
            Result result = exchange(request, future, output -> output.write(Wire.PROBE_INPUT), CoreVision::readMask);
            if (!Arrays.equals(result.alpha, Wire.PROBE_OUTPUT)) {
                throw new CoreVisionException(PROTOCOL, "Unexpected native probe payload");
            }
            try {
                ProbeResult probe = new ProbeResult(Integer.parseInt(value(result.audit, "pid")),
                        Integer.parseInt(value(result.audit, "uid")), Integer.parseInt(value(result.audit, "pageSize")),
                        value(result.audit, "selinux"));
                if (probe.pid() == android.os.Process.myPid() || probe.uid() != android.os.Process.myUid() || probe.pageSize() != 4096) {
                    throw new CoreVisionException(PROTOCOL, "Unexpected native probe identity");
                }
                return probe;
            } catch (IllegalArgumentException error) {
                throw new CoreVisionException(PROTOCOL, "Invalid native probe metadata", error);
            }
        });
    }

    private <Value> Value exchange(Wire.Request request, CompletableFuture<?> future, PixelWriter writer,
                                   ResultReader<Value> reader)
            throws IOException, CoreVisionException, InterruptedException {
        try (Spool input = new Spool(context); Spool output = new Spool(context)) {
            try (OutputStream stream = new BufferedOutputStream(
                    new ParcelFileDescriptor.AutoCloseOutputStream(input.takeWriter()), Wire.BUFFER_SIZE)) {
                request.write(stream);
                writer.write(stream);
            }
            if (future.isCancelled()) throw new CoreVisionException(CLOSED, "Core Vision request cancelled");
            Connection connection = new Connection();
            if (!context.bindService(new Intent(context, WorkerService.class), connection, Context.BIND_AUTO_CREATE)) {
                throw new CoreVisionException(SERVICE_UNAVAILABLE, "Cannot bind the private Core Vision service");
            }
            try {
                IWorker worker = connection.await();
                active = new Call(request.id(), worker);
                if (future.isCancelled()) throw new CoreVisionException(CLOSED, "Core Vision request cancelled");
                Bundle failure = worker.process(input.reader, output.writer);
                if (failure != null) {
                    throw new CoreVisionException(CoreVisionException.Code.fromValue(failure.getInt("code")),
                            failure.getString("message"));
                }
            } catch (RemoteException failure) {
                throw new CoreVisionException(SERVICE_UNAVAILABLE, "Core Vision service disconnected", failure);
            } finally {
                active = null;
                context.unbindService(connection);
            }
            try (FileInputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(output.takeReader())) {
                Wire.Response response = Wire.Response.read(stream, request);
                String metadata = Wire.metadata(stream, response);
                if (response.status() != 0) throw new CoreVisionException(PROTOCOL, "Unexpected worker status");
                Value result = reader.read(stream, response, metadata);
                if (stream.read() != -1) throw new CoreVisionException(PROTOCOL, "Unexpected extra response bytes");
                return result;
            }
        }
    }

    private static void validateBitmap(Bitmap bitmap) throws CoreVisionException {
        if (bitmap == null || bitmap.isRecycled() || !Wire.validDimensions(bitmap.getWidth(), bitmap.getHeight())) {
            throw new CoreVisionException(INVALID_INPUT, "Bitmap is missing, recycled, or exceeds the supported size");
        }
    }

    private static Result readMask(FileInputStream stream, Wire.Response response, String metadata) throws IOException {
        byte[] alpha = Wire.readBytes(stream, response.payload());
        Bitmap foreground = response.foreground() == 0 ? null : readForeground(stream, response);
        byte[] coarse = response.coarse() == 0 ? null : Wire.readBytes(stream, response.coarse());
        return new Result(response.width(), response.height(), alpha, foreground, coarse, metadata);
    }

    private float[] faceFeatures(int id, Bitmap bitmap, CompletableFuture<?> future)
            throws IOException, CoreVisionException, InterruptedException {
        if (future.isCancelled()) throw new CoreVisionException(CLOSED, "Core Vision request cancelled");
        Wire.Request request = new Wire.Request(id, Wire.FACE_EMBEDDING, 0, bitmap.getWidth(), bitmap.getHeight());
        return exchange(request, future, output -> writeBitmap(bitmap, output), CoreVision::readFeatures);
    }

    private static float[] readFeatures(FileInputStream stream, Wire.Response response, String metadata) throws IOException {
        ByteBuffer buffer = Wire.littleEndian(Wire.readBytes(stream, response.payload()));
        float[] features = new float[Wire.FACE_FEATURES];
        for (int index = 0; index < features.length; ++index) {
            features[index] = buffer.getFloat();
            if (!Float.isFinite(features[index])) throw new IOException("Invalid face feature");
        }
        return features;
    }

    private static FaceComparison compareFeatures(float[] first, float[] second) throws CoreVisionException {
        double dot = 0, firstNorm = 0, secondNorm = 0;
        for (int index = 0; index < first.length; ++index) {
            double firstValue = first[index], secondValue = second[index];
            dot += firstValue * secondValue;
            firstNorm += firstValue * firstValue;
            secondNorm += secondValue * secondValue;
        }
        if (firstNorm <= 0 || secondNorm <= 0) {
            throw new CoreVisionException(FACE_COMPARISON, "Empty face feature vector");
        }
        double similarity = Math.max(0, Math.min(1, 0.5 + 0.5 * dot / (Math.sqrt(firstNorm) * Math.sqrt(secondNorm))));
        return new FaceComparison(similarity, similarity >= 0.62);
    }

    private static List<Face> readFaces(FileInputStream stream, Wire.Response response, String metadata) throws IOException {
        ByteBuffer buffer = Wire.littleEndian(Wire.readBytes(stream, response.payload()));
        List<Face> faces = new ArrayList<>(response.payload() / Wire.FACE_BYTES);
        while (buffer.hasRemaining()) {
            FaceBounds bounds = new FaceBounds(buffer.getFloat(), buffer.getFloat(), buffer.getFloat(), buffer.getFloat());
            float probability = buffer.getFloat();
            if (!(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= response.width()
                    && bounds.bottom <= response.height() && bounds.left < bounds.right && bounds.top < bounds.bottom
                    && probability >= 0 && probability <= 1)) {
                throw new IOException("Invalid face bounds or probability");
            }
            List<FacePoint> landmarks = new ArrayList<>(5);
            for (int index = 0; index < 5; ++index) {
                int horizontal = buffer.getInt(), vertical = buffer.getInt();
                if (horizontal < 0 || horizontal >= response.width() || vertical < 0 || vertical >= response.height()) {
                    throw new IOException("Invalid face landmark");
                }
                landmarks.add(new FacePoint(horizontal, vertical));
            }
            FacePose pose = new FacePose(buffer.getFloat(), buffer.getFloat(), buffer.getFloat());
            int occluded = buffer.getInt();
            if (!Float.isFinite(pose.yaw) || !Float.isFinite(pose.pitch) || !Float.isFinite(pose.roll)
                    || (occluded != 0 && occluded != 1)) {
                throw new IOException("Invalid face pose or occlusion result");
            }
            faces.add(new Face(bounds, probability, landmarks, pose, occluded == 1));
        }
        return Collections.unmodifiableList(faces);
    }

    private static void writeBitmap(Bitmap source, OutputStream output) throws IOException, CoreVisionException {
        Bitmap bitmap = source;
        try {
            if (source.getConfig() == Bitmap.Config.HARDWARE) bitmap = source.copy(Bitmap.Config.ARGB_8888, false);
            if (bitmap == null) throw new CoreVisionException(INVALID_INPUT, "Cannot read bitmap pixels");
            int width = bitmap.getWidth();
            int[] colors = new int[width];
            byte[] rgba = new byte[width * 4];
            for (int row = 0; row < bitmap.getHeight(); ++row) {
                bitmap.getPixels(colors, 0, width, 0, row, width, 1);
                for (int col = 0; col < width; ++col) {
                    int color = colors[col];
                    int offset = col * 4;
                    rgba[offset] = (byte) (color >>> 16);
                    rgba[offset + 1] = (byte) (color >>> 8);
                    rgba[offset + 2] = (byte) color;
                    rgba[offset + 3] = (byte) (color >>> 24);
                }
                output.write(rgba);
            }
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new CoreVisionException(INVALID_INPUT, "Cannot read bitmap pixels", failure);
        } finally {
            if (bitmap != null && bitmap != source) bitmap.recycle();
        }
    }

    private static Bitmap readForeground(FileInputStream stream, Wire.Response response) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(response.width(), response.height(), Bitmap.Config.ARGB_8888);
        FileChannel channel = stream.getChannel();
        long position = channel.position();
        ByteBuffer packed = channel.map(FileChannel.MapMode.READ_ONLY, position, response.foreground());
        int stride = response.width() * 4;
        if (bitmap.getRowBytes() != stride) {
            ByteBuffer padded = ByteBuffer.allocateDirect(bitmap.getByteCount());
            byte[] row = new byte[stride];
            for (int index = 0; index < response.height(); ++index) {
                packed.get(row);
                padded.position(index * bitmap.getRowBytes());
                padded.put(row);
            }
            padded.position(0);
            packed = padded;
        }
        bitmap.copyPixelsFromBuffer(packed);
        channel.position(position + response.foreground());
        return bitmap;
    }

    private <Value> CompletableFuture<Value> submit(Operation<Value> operation) {
        CompletableFuture<Value> future = new CompletableFuture<>();
        if (closed.get()) {
            future.completeExceptionally(new CoreVisionException(CLOSED, "Core Vision session is closed"));
            return future;
        }
        int generated = NEXT_ID.incrementAndGet();
        int id = generated != 0 ? generated : NEXT_ID.incrementAndGet();
        requests.put(id, future);
        future.whenComplete((result, failure) -> {
            requests.remove(id);
            if (future.isCancelled()) {
                Call call = active;
                if (call != null && call.id == id) {
                    try {
                        call.worker.cancel(id);
                    } catch (RemoteException ignored) {
                    }
                }
            }
        });
        try {
            executor.execute(() -> {
                if (future.isDone()) return;
                try {
                    future.complete(operation.run(id, future));
                } catch (CoreVisionException failure) {
                    future.completeExceptionally(failure);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    future.completeExceptionally(new CoreVisionException(CLOSED, "Core Vision request interrupted", failure));
                } catch (IOException | RuntimeException failure) {
                    future.completeExceptionally(new CoreVisionException(PROTOCOL, "Core Vision I/O failed", failure));
                } catch (OutOfMemoryError failure) {
                    future.completeExceptionally(new CoreVisionException(WORKER_FAILED, "Not enough memory for bitmap buffers", failure));
                }
            });
        } catch (RejectedExecutionException failure) {
            future.completeExceptionally(closed.get() ? new CoreVisionException(CLOSED, "Core Vision session is closed")
                    : new CoreVisionException(BUSY, "Core Vision request queue is full"));
        }
        return future;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            requests.values().forEach(future -> future.cancel(false));
            executor.shutdownNow();
        }
    }

    private static String value(String metadata, String key) {
        String prefix = key + "=";
        for (String line : metadata.split("\n")) {
            if (line.startsWith(prefix)) return line.substring(prefix.length());
        }
        throw new IllegalArgumentException("Missing " + key);
    }

    public record ProbeResult(int pid, int uid, int pageSize, String selinuxContext) {}

    public record FaceBounds(float left, float top, float right, float bottom) {}

    public record FacePoint(int x, int y) {}

    public record FacePose(float yaw, float pitch, float roll) {}

    public record FaceComparison(double similarity, boolean samePerson) {}

    public record Face(FaceBounds bounds, float probability, List<FacePoint> landmarks, FacePose pose, boolean occluded) {
        public Face {
            landmarks = Collections.unmodifiableList(new ArrayList<>(landmarks));
        }
    }

    public static final class Result {
        private final int width;
        private final int height;
        private final byte[] alpha;
        private final Bitmap foreground;
        final byte[] coarse;
        final String audit;

        private Result(int width, int height, byte[] alpha, Bitmap foreground, byte[] coarse, String audit) {
            this.width = width;
            this.height = height;
            this.alpha = alpha;
            this.foreground = foreground;
            this.coarse = coarse;
            this.audit = audit;
        }

        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public byte[] getAlpha() { return alpha.clone(); }
        public Bitmap getForeground() { return foreground; }
    }

    private record Call(int id, IWorker worker) {}

    private interface Operation<Value> {
        Value run(int id, CompletableFuture<?> future) throws CoreVisionException, IOException, InterruptedException;
    }

    private interface PixelWriter {
        void write(OutputStream output) throws IOException, CoreVisionException;
    }

    private interface ResultReader<Value> {
        Value read(FileInputStream input, Wire.Response response, String metadata) throws IOException;
    }

    private static final class Connection implements ServiceConnection {
        private final CountDownLatch connected = new CountDownLatch(1);
        private volatile IWorker worker;

        IWorker await() throws CoreVisionException, InterruptedException {
            if (!connected.await(10, TimeUnit.SECONDS) || worker == null) {
                throw new CoreVisionException(SERVICE_UNAVAILABLE, "Private Core Vision service did not connect");
            }
            return worker;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            worker = IWorker.Stub.asInterface(binder);
            connected.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            worker = null;
            connected.countDown();
        }

        @Override
        public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }

        @Override
        public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
    }

    private static final class Spool implements AutoCloseable {
        ParcelFileDescriptor reader;
        ParcelFileDescriptor writer;

        Spool(Context context) throws IOException {
            File file = File.createTempFile("corevision-ipc-", ".tmp", context.getCacheDir());
            try {
                reader = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                writer = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY);
                if (!file.delete()) throw new IOException("Cannot unlink IPC buffer");
            } catch (IOException failure) {
                close();
                file.delete();
                throw failure;
            }
        }

        ParcelFileDescriptor takeReader() {
            ParcelFileDescriptor descriptor = reader;
            reader = null;
            return descriptor;
        }

        ParcelFileDescriptor takeWriter() {
            ParcelFileDescriptor descriptor = writer;
            writer = null;
            return descriptor;
        }

        @Override
        public void close() throws IOException {
            try {
                if (reader != null) reader.close();
            } finally {
                if (writer != null) writer.close();
            }
        }
    }
}
