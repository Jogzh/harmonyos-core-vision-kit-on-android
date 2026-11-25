package dev.corevision.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.WindowManager;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class AcceptanceInstrumentation extends Instrumentation {
    private Bundle arguments;

    @Override
    public void onCreate(Bundle arguments) {
        this.arguments = arguments;
        start();
    }

    @Override
    public void onStart() {
        Bundle report = new Bundle();
        try (CoreVision client = new CoreVision(getTargetContext())) {
            if (arguments.getString("expectWorkerError") != null) keepSampleVisible();
            CoreVision.ProbeResult first = client.probe().get(15, TimeUnit.SECONDS);
            require(first.uid() >= 10_000 && first.selinuxContext().startsWith("u:r:untrusted_app"),
                    "Probe did not run under ordinary app identity");
            CoreVision.ProbeResult second = client.probe().get(15, TimeUnit.SECONDS);
            require(first.pid() != second.pid(), "Each request must launch a fresh native worker");
            expectError(client.segment(null), CoreVisionException.Code.INVALID_INPUT);

            String mode = arguments.getString("mode", "probe");
            if (mode.equals("probe")) {
                Bitmap pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                try {
                    expectError(client.segment(pixel), CoreVisionException.Code.RUNTIME_MISSING);
                } finally {
                    pixel.recycle();
                }
            } else {
                validateMatting(client);
            }
            client.close();
            expectError(client.probe(), CoreVisionException.Code.CLOSED);
            report.putString("corevision", "PASS mode=" + mode + " uid=" + first.uid() + " pageSize=" + first.pageSize());
            finish(Activity.RESULT_OK, report);
        } catch (Throwable error) {
            report.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, report);
        }
    }

    private void keepSampleVisible() {
        String packageName = getTargetContext().getPackageName();
        Intent intent = new Intent(Intent.ACTION_MAIN).setClassName(packageName, packageName + ".MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = startActivitySync(intent);
        runOnMainSync(() -> activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
    }

    private void validateMatting(CoreVision client) throws Exception {
        int width = Integer.parseInt(arguments.getString("width"));
        int height = Integer.parseInt(arguments.getString("height"));
        require(Wire.validDimensions(width, height), "Unsupported fixture dimensions");
        File directory = new File(getTargetContext().getFilesDir(), "corevision-validation");
        File input = new File(directory, "input.rgba");
        require(input.length() == (long) width * height * 4, "Incorrect fixture byte length");
        byte[] pixels = Files.readAllBytes(input.toPath());
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            bitmap.setPremultiplied(false);
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));

            String expected = arguments.getString("expectWorkerError");
            if (expected != null) {
                expectError(client.segment(bitmap), CoreVisionException.Code.valueOf(expected));
            }

            long start = android.os.SystemClock.elapsedRealtime();
            CoreVision.Result result = client.segmentForValidation(bitmap).get(60, TimeUnit.SECONDS);
            long firstMillis = android.os.SystemClock.elapsedRealtime() - start;
            require(result.getWidth() == width && result.getHeight() == height, "Result dimensions changed");
            byte[] alpha = result.getAlpha();
            write(directory, "alpha.u8", alpha);
            write(directory, "coarse.u8", result.coarse);
            Bitmap foreground = result.getForeground();
            require(foreground != null && foreground.isPremultiplied(), "Expected a premultiplied foreground bitmap");
            try {
                ByteBuffer buffer = ByteBuffer.allocate(foreground.getByteCount());
                foreground.copyPixelsToBuffer(buffer);
                buffer.rewind();
                byte[] rgba = new byte[pixels.length];
                int stride = width * 4;
                for (int row = 0; row < height; ++row) {
                    buffer.position(row * foreground.getRowBytes());
                    buffer.get(rgba, row * stride, stride);
                }
                write(directory, "foreground.rgba", rgba);
            } finally {
                foreground.recycle();
            }

            start = android.os.SystemClock.elapsedRealtime();
            CoreVision.Result alphaOnly = client.segment(bitmap).get(60, TimeUnit.SECONDS);
            long secondMillis = android.os.SystemClock.elapsedRealtime() - start;
            require(alphaOnly.getForeground() == null && Arrays.equals(alpha, alphaOnly.getAlpha()),
                    "Repeated alpha-only output differs from the foreground request");
            write(directory, "runtime.txt", (result.audit + "firstAarMillis=" + firstMillis + "\nsecondAarMillis=" + secondMillis + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        } finally {
            bitmap.recycle();
        }
    }

    private static void write(File directory, String file, byte[] contents) throws IOException {
        Files.write(new File(directory, file).toPath(), contents);
    }

    private static void expectError(CompletableFuture<?> future, CoreVisionException.Code code) throws Exception {
        try {
            future.get(60, TimeUnit.SECONDS);
            throw new AssertionError("Expected " + code);
        } catch (ExecutionException error) {
            if (!(error.getCause() instanceof CoreVisionException actual) || actual.getCode() != code) throw error;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
