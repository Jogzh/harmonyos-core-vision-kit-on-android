package dev.corevision.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
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
            expectError(client.detectObjects(null), CoreVisionException.Code.INVALID_INPUT);
            expectError(client.detectSkeletons(null), CoreVisionException.Code.INVALID_INPUT);
            expectError(client.superResolve(null), CoreVisionException.Code.INVALID_INPUT);
            expectError(client.recognizeText(null), CoreVisionException.Code.INVALID_INPUT);
            expectError(client.segment(null, new CoreVision.SegmentationOptions(21, true, true)), CoreVisionException.Code.INVALID_INPUT);
            expectError(client.insertImage("example", "Scope", null), CoreVisionException.Code.INVALID_INPUT);
            expectError(client.searchImages("example", "invalid scope"), CoreVisionException.Code.INVALID_INPUT);
            expectError(client.searchImages("example", "Scope", 101), CoreVisionException.Code.INVALID_INPUT);

            String mode = arguments.getString("mode", "probe");
            switch (mode) {
                case "probe", "matting-only" -> validateMissingModels(client, mode.equals("probe"));
                case "baseline" -> validateMatting(client);
                case "faces" -> report.putInt("faceCount", validateFaces(client));
                case "comparison" -> report.putDouble("similarity", validateFaceComparison(client));
                case "objects" -> report.putInt("objectCount", validateDetections(client, false));
                case "skeletons" -> report.putInt("skeletonCount", validateDetections(client, true));
                case "super-resolution" -> validateSuperResolution(client);
                case "ocr" -> validateOcr(client);
                case "subjects" -> validateSubjects(client);
                case "image-search" -> validateImageSearch();
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
            expectError(client.detectObjects(pixel), CoreVisionException.Code.RUNTIME_MISSING);
            expectError(client.detectSkeletons(pixel), CoreVisionException.Code.RUNTIME_MISSING);
            expectError(client.superResolve(pixel), CoreVisionException.Code.RUNTIME_MISSING);
            expectError(client.recognizeText(pixel), CoreVisionException.Code.RUNTIME_MISSING);
            expectError(client.insertImage("example", "Scope", pixel), CoreVisionException.Code.RUNTIME_MISSING);
            expectError(client.searchImages("example", "Scope"), CoreVisionException.Code.RUNTIME_MISSING);
        } finally {
            pixel.recycle();
        }
    }

    private void validateOcr(CoreVision client) throws Exception {
        int width = Integer.parseInt(arguments.getString("width"));
        int height = Integer.parseInt(arguments.getString("height"));
        String expected = arguments.getString("expectedText");
        File directory = new File(getTargetContext().getFilesDir(), "corevision-validation");
        Bitmap bitmap = bitmapFromRgba(width, height, readFixture(directory, "input.rgba", width, height));
        Bitmap blank = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888);
        try {
            blank.eraseColor(0xffffffff);
            CoreVision.TextRecognition result = client.recognizeText(bitmap).get(60, TimeUnit.SECONDS);
            require(result.value().contains(expected), "Unexpected OCR text: " + result.value());
            require(result.blocks().stream().flatMap(block -> block.lines().stream()).anyMatch(line ->
                    line.value().contains(expected) && line.cornerPoints().size() == 4 && !line.words().isEmpty()
                            && line.words().stream().allMatch(word -> word.cornerPoints().size() == 4)),
                    "Expected OCR lines and word geometry");
            int minimumBlockLines = Integer.parseInt(arguments.getString("ocrMinBlockLines", "1"));
            require(result.blocks().stream().anyMatch(block -> block.lines().size() >= minimumBlockLines),
                    "Expected a native text block with at least " + minimumBlockLines + " lines");
            CoreVision.TextRecognition empty = client.recognizeText(blank, false).get(60, TimeUnit.SECONDS);
            require(empty.value().isEmpty() && empty.blocks().isEmpty(), "Blank image returned text");
        } finally {
            blank.recycle();
            bitmap.recycle();
        }
    }

    private void validateSuperResolution(CoreVision client) throws Exception {
        int width = Integer.parseInt(arguments.getString("width"));
        int height = Integer.parseInt(arguments.getString("height"));
        File directory = new File(getTargetContext().getFilesDir(), "corevision-validation");
        Bitmap input = bitmapFromRgba(width, height, readFixture(directory, "input.rgba", width, height));
        Bitmap oversized = Bitmap.createBitmap(2049, 1, Bitmap.Config.ARGB_8888);
        try {
            Bitmap upscaled = client.superResolve(input).get(60, TimeUnit.SECONDS);
            try {
                require(upscaled.getWidth() == width * 4 && upscaled.getHeight() == height * 4, "Expected a 4× result");
                require((upscaled.getPixel(width * 2, height * 2) >>> 24) == 255, "Expected opaque RGBA output");
                Bitmap premultiplied = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                int[] colors = new int[width * height];
                input.getPixels(colors, 0, width, 0, 0, width, height);
                premultiplied.setPixels(colors, 0, width, 0, 0, width, height);
                Bitmap resized = Bitmap.createScaledBitmap(premultiplied, width * 4, height * 4, true);
                try {
                    require(!upscaled.sameAs(resized), "Expected model output to differ from a bitmap resize");
                } finally {
                    resized.recycle();
                    premultiplied.recycle();
                }
            } finally {
                upscaled.recycle();
            }
            expectError(client.superResolve(oversized), CoreVisionException.Code.INVALID_INPUT);
            Bitmap small = Bitmap.createBitmap(17, 9, Bitmap.Config.ARGB_8888);
            try {
                small.eraseColor(0xff345678);
                Bitmap result = client.superResolve(small).get(60, TimeUnit.SECONDS);
                try {
                    require(result.getWidth() == 68 && result.getHeight() == 36, "Expected small non-square image to upscale");
                } finally {
                    result.recycle();
                }
            } finally {
                small.recycle();
            }
        } finally {
            oversized.recycle();
            input.recycle();
        }
    }

    private void validateSubjects(CoreVision client) throws Exception {
        int width = Integer.parseInt(arguments.getString("width"));
        int height = Integer.parseInt(arguments.getString("height"));
        int expected = Integer.parseInt(arguments.getString("subjectCount"));
        File directory = new File(getTargetContext().getFilesDir(), "corevision-validation");
        Bitmap bitmap = bitmapFromRgba(width, height, readFixture(directory, "input.rgba", width, height));
        Bitmap blank = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888);
        try {
            CoreVision.Result detailed = client.segment(bitmap, new CoreVision.SegmentationOptions(6, true, true)).get(60, TimeUnit.SECONDS);
            byte[] alpha = detailed.getAlpha();
            require(detailed.getSubjectCount() == expected && detailed.getSubjectDetails().size() == expected,
                    "Unexpected subject count: " + detailed.getSubjectCount());
            require(detailed.getSubjectBounds().width() > 0 && detailed.getSubjectBounds().height() > 0, "Missing combined subject bounds");
            detailed.getForeground().recycle();
            byte[] seen = new byte[alpha.length];
            int largest = alpha.length;
            for (CoreVision.Subject subject : detailed.getSubjectDetails()) {
                byte[] mask = subject.getAlpha();
                CoreVision.SubjectBounds bounds = subject.getBounds();
                require(bounds.left() >= 0 && bounds.top() >= 0 && bounds.width() > 0 && bounds.height() > 0
                        && bounds.left() + bounds.width() <= width && bounds.top() + bounds.height() <= height, "Invalid subject bounds");
                Bitmap foreground = subject.getForeground();
                int strong = 0;
                try {
                    require(foreground.getWidth() == width && foreground.getHeight() == height, "Expected full-size subject foreground");
                    for (int pixel = 0; pixel < mask.length; ++pixel) {
                        if (mask[pixel] == 0) continue;
                        require(seen[pixel] == 0 && mask[pixel] == alpha[pixel], "Subject masks overlap or differ from the combined alpha");
                        seen[pixel] = mask[pixel];
                        if (Byte.toUnsignedInt(mask[pixel]) > 128 && strong++ == 0) {
                            require(foreground.getPixel(pixel % width, pixel / width) >>> 24 == Byte.toUnsignedInt(mask[pixel]),
                                    "Subject foreground alpha differs from the mask");
                        }
                    }
                } finally {
                    foreground.recycle();
                }
                require(strong > 0 && strong <= largest, "Subject order is not descending by area");
                largest = strong;
            }

            CoreVision.Result limited = client.segment(bitmap, new CoreVision.SegmentationOptions(1, true, false)).get(60, TimeUnit.SECONDS);
            require(limited.getSubjectCount() == 1 && limited.getSubjectDetails().size() == 1, "maxCount was ignored");
            require(limited.getSubjectDetails().get(0).getForeground() == null, "Foreground was generated without being requested");
            require(Arrays.equals(alpha, limited.getAlpha()), "Limiting details changed the combined matte");
            blank.eraseColor(0xff808080);
            CoreVision.Result empty = client.segment(blank).get(60, TimeUnit.SECONDS);
            require(empty.getSubjectCount() == 0 && empty.getSubjectBounds().width() == 0, "Blank image has a subject");
        } finally {
            blank.recycle();
            bitmap.recycle();
        }
    }

    private void validateImageSearch() throws Exception {
        int width = Integer.parseInt(arguments.getString("width"));
        int height = Integer.parseInt(arguments.getString("height"));
        File fixtures = new File(getTargetContext().getFilesDir(), "corevision-validation");
        Bitmap astronaut = bitmapFromRgba(width, height, readFixture(fixtures, "input.rgba", width, height));
        Bitmap cat = bitmapFromRgba(width, height, readFixture(fixtures, "second.rgba", width, height));
        File store = Files.createTempDirectory(getTargetContext().getCacheDir().toPath(), "index-test-").toFile();
        Context context = new ContextWrapper(getTargetContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getNoBackupFilesDir() { return store; }
        };
        try {
            try (CoreVision writer = new CoreVision(context)) {
                writer.insertImage("astronaut", "Photos", astronaut).get(60, TimeUnit.SECONDS);
                writer.insertImage("cat", "Photos", cat).get(60, TimeUnit.SECONDS);
                writer.insertImage("cat", "Other", cat).get(60, TimeUnit.SECONDS);
            }
            try (CoreVision reader = new CoreVision(context)) {
                List<CoreVision.ImageMatch> space = reader.searchImages("宇航员", "Photos").get(60, TimeUnit.SECONDS);
                require(space.size() == 2 && space.get(0).imageId().equals("astronaut")
                        && space.get(0).similarity() > space.get(1).similarity(), "Astronaut query ranked the wrong image: " + space);
                List<CoreVision.ImageMatch> feline = reader.searchImages("猫咪", "Photos", 1).get(60, TimeUnit.SECONDS);
                require(feline.size() == 1 && feline.get(0).imageId().equals("cat") && feline.get(0).scope().equals("Photos"),
                        "Cat query ranked the wrong image: " + feline);
                require(reader.searchImages("猫咪", "Photos", 0).get(5, TimeUnit.SECONDS).isEmpty(), "Expected empty topKey 0");
                require(reader.deleteImage("cat", "Photos").get(5, TimeUnit.SECONDS), "Image was not deleted");
                require(!reader.deleteImage("cat", "Photos").get(5, TimeUnit.SECONDS), "Repeated delete did not report absent key");
                require(reader.searchImages("猫咪", "Other").get(60, TimeUnit.SECONDS).size() == 1, "Deleting from one scope affected another");
                require(reader.searchImages("猫咪", "Photos").get(60, TimeUnit.SECONDS).size() == 1, "Deleted image is still searchable");
                reader.clearImageSearch().get(5, TimeUnit.SECONDS);
                require(reader.searchImages("猫咪", "Other").get(60, TimeUnit.SECONDS).isEmpty(), "Clearing the index left results");
            }
        } finally {
            cat.recycle();
            astronaut.recycle();
            File[] files = store.listFiles();
            if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(store.toPath());
        }
    }

    private int validateDetections(CoreVision client, boolean skeletonMode) throws Exception {
        int width = Integer.parseInt(arguments.getString("width"));
        int height = Integer.parseInt(arguments.getString("height"));
        File directory = new File(getTargetContext().getFilesDir(), "corevision-validation");
        Bitmap bitmap = bitmapFromRgba(width, height, readFixture(directory, "input.rgba", width, height));
        Bitmap blank = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888);
        try {
            blank.eraseColor(0xff808080);
            if (skeletonMode) {
                int count = Integer.parseInt(arguments.getString("skeletonCount"));
                List<CoreVision.Skeleton> skeletons = client.detectSkeletons(bitmap).get(60, TimeUnit.SECONDS);
                require(skeletons.size() == count, "Unexpected skeleton count: " + skeletons.size());
                for (CoreVision.Skeleton skeleton : skeletons) {
                    require(skeleton.points().size() == 17, "Expected 17 skeleton points");
                    CoreVision.SkeletonPoint nose = skeleton.points().get(0);
                    require(nose.type() == CoreVision.SkeletonPointType.NOSE && nose.horizontal() > 0
                            && nose.vertical() > 0 && nose.score() > 0.5f, "Expected a confident nose point");
                }
                require(client.detectSkeletons(blank).get(60, TimeUnit.SECONDS).isEmpty(), "Blank image returned a skeleton");
                return skeletons.size();
            }
            int label = Integer.parseInt(arguments.getString("objectLabel"));
            List<CoreVision.DetectedObject> objects = client.detectObjects(bitmap).get(60, TimeUnit.SECONDS);
            require(objects.stream().anyMatch(object -> object.label() == label), "Expected object label " + label);
            for (int index = 0; index < objects.size(); ++index) {
                require(objects.get(index).id() == index, "Expected consecutive image-local object IDs");
            }
            require(client.detectObjects(blank).get(60, TimeUnit.SECONDS).isEmpty(), "Blank image returned an object");
            return objects.size();
        } finally {
            blank.recycle();
            bitmap.recycle();
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
            require(result.getSubjectCount() == 1 && result.getSubjectDetails().isEmpty(), "Unexpected default subject result");
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
