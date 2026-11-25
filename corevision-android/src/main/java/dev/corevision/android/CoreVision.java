package dev.corevision.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.sqlite.SQLiteException;
import android.graphics.Bitmap;
import android.graphics.Rect;
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
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
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
import java.util.regex.Pattern;

import static dev.corevision.android.CoreVisionException.Code.*;

public final class CoreVision implements AutoCloseable {
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final SkeletonPointType[] SKELETON_TYPES = SkeletonPointType.values();
    private static final Pattern SEARCH_SCOPE = Pattern.compile("[A-Za-z0-9]{1,32}");
    private static final int MAX_SEARCH_RESULTS = 100;
    private static final List<String> TEXT_LANGUAGES = Collections.unmodifiableList(Arrays.asList("zh-CN", "en", "ja", "ko", "zh-TW"));

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
        return segment(bitmap, new SegmentationOptions(Wire.DEFAULT_SUBJECTS, false, includeForeground));
    }

    public CompletableFuture<Result> segment(Bitmap bitmap, SegmentationOptions options) {
        return segmentInternal(bitmap, options, false);
    }

    CompletableFuture<Result> segmentForValidation(Bitmap bitmap) {
        return segmentInternal(bitmap, new SegmentationOptions(Wire.DEFAULT_SUBJECTS, false, true), true);
    }

    private CompletableFuture<Result> segmentInternal(Bitmap bitmap, SegmentationOptions options, boolean audit) {
        if (options == null || options.maxCount() < 1 || options.maxCount() > Wire.MAX_SUBJECTS) {
            return submit((id, future) -> { throw new CoreVisionException(INVALID_INPUT, "maxCount must be between 1 and 20"); });
        }
        int flags = options.maxCount() << Wire.SUBJECT_COUNT_SHIFT;
        if (options.enableSubjectDetails()) flags |= Wire.SUBJECT_DETAILS;
        if (options.enableSubjectForegroundImage()) flags |= Wire.FOREGROUND;
        if (audit) flags |= Wire.COARSE;
        return processImage(bitmap, Wire.SEGMENT, flags,
                (stream, response, metadata) -> readMask(stream, response, metadata, options.maxCount()));
    }

    public CompletableFuture<List<Face>> detectFaces(Bitmap bitmap) {
        return processImage(bitmap, Wire.DETECT_FACES, 0, CoreVision::readFaces);
    }

    public CompletableFuture<List<DetectedObject>> detectObjects(Bitmap bitmap) {
        return processImage(bitmap, Wire.DETECT_OBJECTS, 0, CoreVision::readObjects);
    }

    public CompletableFuture<List<Skeleton>> detectSkeletons(Bitmap bitmap) {
        return processImage(bitmap, Wire.DETECT_SKELETONS, 0, CoreVision::readSkeletons);
    }

    public CompletableFuture<Bitmap> superResolve(Bitmap bitmap) {
        return processImage(bitmap, Wire.SUPER_RESOLUTION, 0, (stream, response, metadata) -> readForeground(stream, response));
    }

    public CompletableFuture<TextRecognition> recognizeText(Bitmap bitmap) {
        return recognizeText(bitmap, true);
    }

    public CompletableFuture<TextRecognition> recognizeText(Bitmap bitmap, boolean detectDirection) {
        return processImage(bitmap, Wire.RECOGNIZE_TEXT, detectDirection ? Wire.TEXT_DIRECTION : 0,
                (stream, response, metadata) -> OcrResultReader.read(stream, response));
    }

    public static List<String> getSupportedTextLanguages() {
        return TEXT_LANGUAGES;
    }

    public CompletableFuture<Void> insertImage(String imageId, String scope, Bitmap bitmap) {
        return submit((id, future) -> {
            validateScope(scope);
            validateImageId(imageId);
            validateBitmap(bitmap);
            Wire.Request request = new Wire.Request(id, Wire.IMAGE_FEATURES, 0, bitmap.getWidth(), bitmap.getHeight());
            float[] features = exchange(request, future, output -> writeBitmap(bitmap, output), CoreVision::readSearchFeatures);
            if (future.isCancelled()) throw new CoreVisionException(CLOSED, "Image indexing cancelled");
            try (ImageSearchIndex index = new ImageSearchIndex(context)) {
                index.insert(imageId, scope, features);
            } catch (SQLiteException error) {
                throw new CoreVisionException(IMAGE_SEARCH, "Cannot index image", error);
            }
            return null;
        });
    }

    public CompletableFuture<List<ImageMatch>> searchImages(String query, String scope) {
        return searchImages(query, scope, MAX_SEARCH_RESULTS);
    }

    public CompletableFuture<List<ImageMatch>> searchImages(String query, String scope, int topKey) {
        return submit((id, future) -> {
            validateScope(scope);
            if (query == null || query.trim().isEmpty() || query.length() > Wire.MAX_QUERY_UNITS || query.indexOf('\0') >= 0
                    || topKey < 0 || topKey > MAX_SEARCH_RESULTS) {
                throw new CoreVisionException(INVALID_INPUT, "Expected 1–100 query characters and topKey between 0 and 100");
            }
            if (topKey == 0) return Collections.emptyList();
            byte[] input = query.trim().getBytes(StandardCharsets.UTF_8);
            Wire.Request request = new Wire.Request(id, Wire.TEXT_FEATURES, input.length, 0, 0);
            float[] features = exchange(request, future, output -> output.write(input), CoreVision::readSearchFeatures);
            try (ImageSearchIndex index = new ImageSearchIndex(context)) {
                return index.search(scope, features, topKey, future::isCancelled);
            } catch (SQLiteException error) {
                throw new CoreVisionException(IMAGE_SEARCH, "Cannot search indexed images", error);
            }
        });
    }

    public CompletableFuture<Boolean> deleteImage(String imageId, String scope) {
        return submit((id, future) -> {
            validateScope(scope);
            validateImageId(imageId);
            try (ImageSearchIndex index = new ImageSearchIndex(context)) {
                return index.delete(imageId, scope);
            } catch (SQLiteException error) {
                throw new CoreVisionException(IMAGE_SEARCH, "Cannot delete indexed image", error);
            }
        });
    }

    public CompletableFuture<Void> clearImageSearch() {
        return submit((id, future) -> {
            try (ImageSearchIndex index = new ImageSearchIndex(context)) {
                index.clear();
            } catch (SQLiteException error) {
                throw new CoreVisionException(IMAGE_SEARCH, "Cannot clear the image index", error);
            }
            return null;
        });
    }

    private static void validateScope(String scope) throws CoreVisionException {
        if (scope == null || !SEARCH_SCOPE.matcher(scope).matches()) {
            throw new CoreVisionException(INVALID_INPUT, "Image scope must contain 1–32 ASCII letters or digits");
        }
    }

    private static void validateImageId(String imageId) throws CoreVisionException {
        if (imageId == null || imageId.trim().isEmpty() || imageId.length() > 128 || imageId.indexOf('\0') >= 0) {
            throw new CoreVisionException(INVALID_INPUT, "Image ID must contain 1–128 characters");
        }
    }

    private <Value> CompletableFuture<Value> processImage(Bitmap bitmap, int operation, int flags, ResultReader<Value> reader) {
        return submit((id, future) -> {
            validateBitmap(bitmap);
            if (operation == Wire.SUPER_RESOLUTION && !Wire.validSuperResolutionInput(bitmap.getWidth(), bitmap.getHeight())) {
                throw new CoreVisionException(INVALID_INPUT, "Fourfold image output exceeds the supported size");
            }
            Wire.Request request = new Wire.Request(id, operation, flags, bitmap.getWidth(), bitmap.getHeight());
            return exchange(request, future, output -> writeBitmap(bitmap, output), reader);
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
            Result result = exchange(request, future, output -> output.write(Wire.PROBE_INPUT),
                    (stream, response, metadata) -> readMask(stream, response, metadata, 0));
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

    private static Result readMask(FileInputStream stream, Wire.Response response, String metadata, int subjectLimit) throws IOException {
        int width = response.width(), height = response.height();
        int pixels = width * height;
        byte[] alpha = Wire.readBytes(stream, pixels);
        byte[] labels = response.payload() == pixels ? null : Wire.readBytes(stream, pixels);
        ByteBuffer packed = response.foreground() == 0 ? null : mapForeground(stream, response.foreground());
        Bitmap foreground = packed == null ? null : bitmapFromPacked(packed.duplicate(), width, height);
        byte[] coarse = response.coarse() == 0 ? null : Wire.readBytes(stream, response.coarse());
        int count = 0;
        if (subjectLimit > 0) {
            try {
                count = Integer.parseInt(value(metadata, "subjectCount"));
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid subject count", error);
            }
            if (count < 0 || count > subjectLimit) throw new IOException("Unexpected subject count");
        }
        Rect full = new Rect();
        Rect[] bounds = labels == null ? null : new Rect[count];
        if (bounds != null) Arrays.setAll(bounds, index -> new Rect());
        if (count > 0 || labels != null) {
            for (int row = 0; row < height; ++row) {
                for (int column = 0; column < width; ++column) {
                    int pixel = row * width + column;
                    if (alpha[pixel] != 0 && count > 0) full.union(column, row, column + 1, row + 1);
                    if (labels == null || labels[pixel] == 0) continue;
                    int label = Byte.toUnsignedInt(labels[pixel]);
                    if (label > count || alpha[pixel] == 0) throw new IOException("Invalid subject label");
                    bounds[label - 1].union(column, row, column + 1, row + 1);
                }
            }
        }
        List<Subject> subjects = new ArrayList<>(count);
        if (bounds != null) {
            for (int index = 0; index < bounds.length; ++index) {
                if (!bounds[index].isEmpty()) {
                    subjects.add(new Subject(width, height, subjectBounds(bounds[index]), alpha, labels, index + 1, packed));
                }
            }
            count = subjects.size();
        }
        return new Result(width, height, alpha, foreground, coarse, metadata, count, subjectBounds(full), subjects);
    }

    private static SubjectBounds subjectBounds(Rect rect) {
        return new SubjectBounds(rect.left, rect.top, rect.width(), rect.height());
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

    private static float[] readSearchFeatures(FileInputStream stream, Wire.Response response, String metadata) throws IOException {
        ByteBuffer buffer = Wire.littleEndian(Wire.readBytes(stream, response.payload()));
        float[] features = new float[Wire.SEARCH_FEATURES];
        double norm = 0;
        for (int index = 0; index < features.length; ++index) {
            float value = buffer.getFloat();
            features[index] = value;
            norm += (double) value * value;
        }
        if (!Double.isFinite(norm) || norm <= 0) throw new IOException("Invalid image search vector");
        double length = Math.sqrt(norm);
        for (int index = 0; index < features.length; ++index) features[index] /= length;
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

    private static ImageBounds readBounds(ByteBuffer buffer, Wire.Response response) throws IOException {
        ImageBounds bounds = new ImageBounds(buffer.getFloat(), buffer.getFloat(), buffer.getFloat(), buffer.getFloat());
        if (!(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= response.width() && bounds.bottom <= response.height()
                && bounds.left < bounds.right && bounds.top < bounds.bottom)) {
            throw new IOException("Invalid detection bounds");
        }
        return bounds;
    }

    private static float readScore(ByteBuffer buffer) throws IOException {
        float score = buffer.getFloat();
        if (!(score >= 0 && score <= 1)) throw new IOException("Invalid detection score");
        return score;
    }

    private static List<DetectedObject> readObjects(FileInputStream stream, Wire.Response response, String metadata) throws IOException {
        ByteBuffer buffer = Wire.littleEndian(Wire.readBytes(stream, response.payload()));
        List<DetectedObject> objects = new ArrayList<>(response.payload() / Wire.OBJECT_BYTES);
        while (buffer.hasRemaining()) {
            ImageBounds bounds = readBounds(buffer, response);
            float score = readScore(buffer);
            int label = buffer.getInt();
            if (label < 0) throw new IOException("Invalid object label");
            objects.add(new DetectedObject(objects.size(), bounds, score, label));
        }
        return Collections.unmodifiableList(objects);
    }

    private static List<Skeleton> readSkeletons(FileInputStream stream, Wire.Response response, String metadata) throws IOException {
        ByteBuffer buffer = Wire.littleEndian(Wire.readBytes(stream, response.payload()));
        List<Skeleton> skeletons = new ArrayList<>(response.payload() / Wire.SKELETON_BYTES);
        while (buffer.hasRemaining()) {
            ImageBounds bounds = readBounds(buffer, response);
            float score = readScore(buffer);
            List<SkeletonPoint> points = new ArrayList<>(Wire.SKELETON_POINTS);
            for (SkeletonPointType type : SKELETON_TYPES) {
                int horizontal = buffer.getInt(), vertical = buffer.getInt();
                if (horizontal < 0 || horizontal >= response.width() || vertical < 0 || vertical >= response.height()) {
                    throw new IOException("Invalid skeleton point");
                }
                points.add(new SkeletonPoint(type, horizontal, vertical, readScore(buffer)));
            }
            skeletons.add(new Skeleton(bounds, score, points));
        }
        return Collections.unmodifiableList(skeletons);
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
        return bitmapFromPacked(mapForeground(stream, response.foreground()), response.width(), response.height());
    }

    private static ByteBuffer mapForeground(FileInputStream stream, int bytes) throws IOException {
        FileChannel channel = stream.getChannel();
        long position = channel.position();
        ByteBuffer packed = channel.map(FileChannel.MapMode.READ_ONLY, position, bytes);
        channel.position(position + bytes);
        return packed;
    }

    private static Bitmap bitmapFromPacked(ByteBuffer packed, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int stride = width * 4;
        if (bitmap.getRowBytes() != stride) {
            ByteBuffer padded = ByteBuffer.allocateDirect(bitmap.getByteCount());
            byte[] row = new byte[stride];
            for (int index = 0; index < height; ++index) {
                packed.get(row);
                padded.position(index * bitmap.getRowBytes());
                padded.put(row);
            }
            padded.position(0);
            packed = padded;
        }
        bitmap.copyPixelsFromBuffer(packed);
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

    public record ImageBounds(float left, float top, float right, float bottom) {}

    public record DetectedObject(int id, ImageBounds bounds, float score, int label) {
        public List<Integer> labels() { return Collections.singletonList(label); }
    }

    public enum SkeletonPointType {
        NOSE, LEFT_EYE, RIGHT_EYE, LEFT_EAR, RIGHT_EAR, LEFT_SHOULDER, RIGHT_SHOULDER,
        LEFT_ELBOW, RIGHT_ELBOW, LEFT_WRIST, RIGHT_WRIST, LEFT_HIP, RIGHT_HIP, LEFT_KNEE, RIGHT_KNEE,
        LEFT_ANKLE, RIGHT_ANKLE
    }

    public record SkeletonPoint(SkeletonPointType type, int horizontal, int vertical, float score) {}

    public record Skeleton(ImageBounds bounds, float score, List<SkeletonPoint> points) {
        public Skeleton {
            points = Collections.unmodifiableList(new ArrayList<>(points));
        }
    }

    public record TextPoint(int x, int y) {}

    public record TextWord(String value, List<TextPoint> cornerPoints) {
        public TextWord {
            cornerPoints = Collections.unmodifiableList(new ArrayList<>(cornerPoints));
        }
    }

    public record TextLine(String value, List<TextPoint> cornerPoints, List<TextWord> words) {
        public TextLine {
            cornerPoints = Collections.unmodifiableList(new ArrayList<>(cornerPoints));
            words = Collections.unmodifiableList(new ArrayList<>(words));
        }
    }

    public record TextBlock(String value, List<TextLine> lines) {
        public TextBlock {
            lines = Collections.unmodifiableList(new ArrayList<>(lines));
        }
    }

    public record TextRecognition(String value, List<TextBlock> blocks) {
        public TextRecognition {
            blocks = Collections.unmodifiableList(new ArrayList<>(blocks));
        }
    }

    public record ImageMatch(String imageId, String scope, double similarity) {}

    public record SegmentationOptions(int maxCount, boolean enableSubjectDetails, boolean enableSubjectForegroundImage) {}

    public record SubjectBounds(int left, int top, int width, int height) {}

    public static final class Subject {
        private final int width;
        private final int height;
        private final SubjectBounds bounds;
        private final byte[] alpha;
        private final byte[] labels;
        private final int label;
        private final ByteBuffer packed;

        private Subject(int width, int height, SubjectBounds bounds, byte[] alpha, byte[] labels, int label, ByteBuffer packed) {
            this.width = width;
            this.height = height;
            this.bounds = bounds;
            this.alpha = alpha;
            this.labels = labels;
            this.label = label;
            this.packed = packed == null ? null : packed.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        }

        public SubjectBounds getBounds() { return bounds; }

        public byte[] getAlpha() {
            byte[] matte = new byte[alpha.length];
            for (int row = bounds.top; row < bounds.top + bounds.height; ++row) {
                int start = row * width + bounds.left;
                for (int pixel = start; pixel < start + bounds.width; ++pixel) {
                    if (labels[pixel] == label) matte[pixel] = alpha[pixel];
                }
            }
            return matte;
        }

        public Bitmap getForeground() {
            if (packed == null) return null;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            int[] row = new int[bounds.width];
            for (int vertical = bounds.top; vertical < bounds.top + bounds.height; ++vertical) {
                int start = vertical * width + bounds.left;
                for (int column = 0; column < row.length; ++column) {
                    int pixel = start + column;
                    row[column] = labels[pixel] == label ? unpremultipliedArgb(packed.getInt(pixel * 4)) : 0;
                }
                bitmap.setPixels(row, 0, row.length, bounds.left, vertical, row.length, 1);
            }
            return bitmap;
        }

        private static int unpremultipliedArgb(int rgba) {
            int alpha = rgba >>> 24;
            if (alpha == 0) return 0;
            int red = ((rgba & 255) * 255 + alpha / 2) / alpha;
            int green = ((rgba >>> 8 & 255) * 255 + alpha / 2) / alpha;
            int blue = ((rgba >>> 16 & 255) * 255 + alpha / 2) / alpha;
            return (alpha << 24) | (red << 16) | (green << 8) | blue;
        }
    }

    public static final class Result {
        private final int width;
        private final int height;
        private final byte[] alpha;
        private final Bitmap foreground;
        private final int subjectCount;
        private final SubjectBounds subjectBounds;
        private final List<Subject> subjectDetails;
        final byte[] coarse;
        final String audit;

        private Result(int width, int height, byte[] alpha, Bitmap foreground, byte[] coarse, String audit,
                       int subjectCount, SubjectBounds subjectBounds, List<Subject> subjectDetails) {
            this.width = width;
            this.height = height;
            this.alpha = alpha;
            this.foreground = foreground;
            this.subjectCount = subjectCount;
            this.subjectBounds = subjectBounds;
            this.subjectDetails = Collections.unmodifiableList(subjectDetails);
            this.coarse = coarse;
            this.audit = audit;
        }

        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public byte[] getAlpha() { return alpha.clone(); }
        public Bitmap getForeground() { return foreground; }
        public int getSubjectCount() { return subjectCount; }
        public SubjectBounds getSubjectBounds() { return subjectBounds; }
        public List<Subject> getSubjectDetails() { return subjectDetails; }
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
