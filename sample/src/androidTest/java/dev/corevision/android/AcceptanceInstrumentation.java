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
import java.util.List;
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
            expectError(client.detectFaces(null), CoreVisionException.Code.INVALID_INPUT);
            expectError(client.compareFaces(null, null), CoreVisionException.Code.INVALID_INPUT);

            String mode = arguments.getString("mode", "probe");
            switch (mode) {
                case "probe", "matting-only" -> validateMissingModels(client, mode.equals("probe"));
                case "baseline" -> validateMatting(client);
                case "faces" -> report.putInt("faceCount", validateFaces(client));
                case "comparison" -> report.putDouble("similarity", validateFaceComparison(client));
                default -> throw new IllegalArgumentException("Unknown acceptance mode " + mode);
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

    private void validateMissingModels(CoreVision client, boolean allModelsMissing) throws Exception {
        Bitmap pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        try {
            if (allModelsMissing) expectError(client.segment(pixel), CoreVisionException.Code.RUNTIME_MISSING);
            expectError(client.detectFaces(pixel), CoreVisionException.Code.RUNTIME_MISSING);
            expectError(client.compareFaces(pixel, pixel), CoreVisionException.Code.RUNTIME_MISSING);
        } finally {
            pixel.recycle();
        }
    }

    private int validateFaces(CoreVision client) throws Exception {
        int width = Integer.parseInt(arguments.getString("width"));
        int height = Integer.parseInt(arguments.getString("height"));
        int expectedFaces = Integer.parseInt(arguments.getString("faceCount"));
        File directory = new File(getTargetContext().getFilesDir(), "corevision-validation");
        byte[] pixels = readFixture(directory, "input.rgba", width, height);
        Bitmap bitmap = bitmapFromRgba(width, height, pixels);
        Bitmap blank = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888);
        try {
            blank.eraseColor(0xff808080);
            List<CoreVision.Face> faces = client.detectFaces(bitmap).get(60, TimeUnit.SECONDS);
            require(faces.size() == expectedFaces, "Expected " + expectedFaces + " faces, got " + faces.size());
            float lastArea = Float.POSITIVE_INFINITY;
            for (CoreVision.Face face : faces) {
                CoreVision.FaceBounds bounds = face.bounds();
                float area = (bounds.right() - bounds.left()) * (bounds.bottom() - bounds.top());
                require(bounds.left() >= 0 && bounds.top() >= 0 && bounds.right() <= width && bounds.bottom() <= height
                        && area > 0 && area <= lastArea, "Face bounds are invalid or out of order");
                require(face.probability() > 0.1f && face.probability() <= 1f, "Invalid face confidence");
                require(face.landmarks().size() == 5, "Expected five face landmarks");
                for (CoreVision.FacePoint point : face.landmarks()) {
                    require(point.x() >= 0 && point.x() < width && point.y() >= 0 && point.y() < height,
                            "Landmark is outside the input image");
                }
                CoreVision.FacePose pose = face.pose();
                require(Math.abs(pose.pitch()) <= 180 && Math.abs(pose.yaw()) <= 180 && Math.abs(pose.roll()) <= 180,
                        "Invalid facial pose");
                lastArea = area;
            }
            require(client.detectFaces(blank).get(60, TimeUnit.SECONDS).isEmpty(), "Blank image returned a face");
            return faces.size();
        } finally {
            blank.recycle();
            bitmap.recycle();
        }
    }

    private double validateFaceComparison(CoreVision client) throws Exception {
        int width = Integer.parseInt(arguments.getString("width"));
        int height = Integer.parseInt(arguments.getString("height"));
        boolean expectMatch = Boolean.parseBoolean(arguments.getString("expectMatch"));
        File directory = new File(getTargetContext().getFilesDir(), "corevision-validation");
        Bitmap first = bitmapFromRgba(width, height, readFixture(directory, "input.rgba", width, height));
        Bitmap second = bitmapFromRgba(width, height, readFixture(directory, "second.rgba", width, height));
        Bitmap blank = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
        try {
            blank.eraseColor(0xff808080);
            CoreVision.FaceComparison result = client.compareFaces(first, second).get(100, TimeUnit.SECONDS);
            require(result.similarity() >= 0 && result.similarity() <= 1, "Invalid face similarity");
            require(result.samePerson() == expectMatch, "Unexpected face match with similarity " + result.similarity());
            expectError(client.compareFaces(blank, second), CoreVisionException.Code.INVALID_INPUT);
            return result.similarity();
        } finally {
            blank.recycle();
            second.recycle();
            first.recycle();
        }
    }

    private void validateMatting(CoreVision client) throws Exception {
        int width = Integer.parseInt(arguments.getString("width"));
        int height = Integer.parseInt(arguments.getString("height"));
        File directory = new File(getTargetContext().getFilesDir(), "corevision-validation");
        byte[] pixels = readFixture(directory, "input.rgba", width, height);
        Bitmap bitmap = bitmapFromRgba(width, height, pixels);
        try {
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

    private static byte[] readFixture(File directory, String name, int width, int height) throws IOException {
        require(Wire.validDimensions(width, height), "Unsupported fixture dimensions");
        File input = new File(directory, name);
        require(input.length() == (long) width * height * 4, "Incorrect fixture byte length");
        return Files.readAllBytes(input.toPath());
    }

    private static Bitmap bitmapFromRgba(int width, int height, byte[] pixels) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPremultiplied(false);
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
        return bitmap;
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
