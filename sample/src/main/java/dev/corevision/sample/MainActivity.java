package dev.corevision.sample;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Function;

import dev.corevision.android.CoreVision;
import dev.corevision.android.CoreVisionException;

public final class MainActivity extends Activity {
    private static final int PICK_SEGMENT = 1;
    private static final int PICK_FACES = 2;
    private static final int PICK_COMPARE_FIRST = 3;
    private static final int PICK_COMPARE_SECOND = 4;
    private static final int PICK_OBJECTS = 5;
    private static final int PICK_SKELETONS = 6;
    private static final int PICK_TEXT = 7;
    private static final int PICK_SR = 8;
    private static final int PICK_INDEX = 9;
    private static final int PICK_DELETE = 10;
    private static final String FIRST_COMPARE_IMAGE = "firstCompareImage";
    private static final String SEARCH_SCOPE = "Sample";
    private CoreVision coreVision;
    private Button segment;
    private Button faces;
    private Button compare;
    private Button probe;
    private Button more;
    private TextView status;
    private ResultView output;
    private ResultView secondOutput;
    private Uri firstCompareImage;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        coreVision = new CoreVision(this);
        if (state != null) firstCompareImage = state.getParcelable(FIRST_COMPARE_IMAGE);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);

        probe = new Button(this);
        probe.setText("测试工作进程");
        probe.setOnClickListener(view -> {
            busy(true, "正在测试工作进程");
            coreVision.probe().whenComplete((result, error) -> runOnUiThread(() -> {
                if (!isDestroyed()) busy(false, error == null ? "本地 IPC 正常，页大小 " + result.pageSize() : message(error));
            }));
        });
        segment = new Button(this);
        segment.setText("选择图片并抠图");
        segment.setOnClickListener(view -> pickImage(PICK_SEGMENT));
        faces = new Button(this);
        faces.setText("选择图片并检测人脸");
        faces.setOnClickListener(view -> pickImage(PICK_FACES));
        compare = new Button(this);
        compare.setText("选择两张图片并比对人脸");
        compare.setOnClickListener(view -> pickImage(PICK_COMPARE_FIRST));
        more = new Button(this);
        more.setText("更多视觉能力");
        more.setOnClickListener(view -> showOperations());
        status = new TextView(this);
        status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setMaxLines(6);
        status.setTextIsSelectable(true);
        LinearLayout previews = new LinearLayout(this);
        output = new ResultView(this);
        secondOutput = new ResultView(this);
        secondOutput.setVisibility(View.GONE);
        previews.addView(output, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        previews.addView(secondOutput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        content.addView(probe);
        content.addView(segment);
        content.addView(faces);
        content.addView(compare);
        content.addView(more);
        content.addView(status);
        content.addView(previews, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(content);
    }

    private void pickImage(int request) {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), request);
    }

    private void showOperations() {
        new AlertDialog.Builder(this).setItems(new String[]{"物体检测", "骨骼检测", "文字识别", "图像超分（4 倍）",
                "添加图片到索引", "以文搜图", "从索引移除图片", "清空图片索引"}, (dialog, position) -> {
            switch (position) {
                case 0 -> pickImage(PICK_OBJECTS);
                case 1 -> pickImage(PICK_SKELETONS);
                case 2 -> pickImage(PICK_TEXT);
                case 3 -> pickImage(PICK_SR);
                case 4 -> pickImage(PICK_INDEX);
                case 5 -> showSearch();
                case 6 -> pickImage(PICK_DELETE);
                case 7 -> clearSearch();
            }
        }).show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        Uri uri = result == RESULT_OK && data != null ? data.getData() : null;
        if (request == PICK_COMPARE_SECOND) {
            Uri first = firstCompareImage;
            firstCompareImage = null;
            if (first != null && uri != null) compareImages(first, uri);
            else status.setText("已取消比对");
            return;
        }
        if (uri == null) return;
        if (request == PICK_COMPARE_FIRST) {
            firstCompareImage = uri;
            status.setText("请选择第二张照片");
            pickImage(PICK_COMPARE_SECOND);
            return;
        }
        switch (request) {
            case PICK_SEGMENT -> processImage(uri, "正在抠图", bitmap -> coreVision.segment(bitmap, true), (bitmap, matte) -> {
                output.show(matte.getForeground(), Collections.emptyList(), "抠图前景");
                busy(false, "主体 " + matte.getSubjectCount() + " 个 · " + matte.getWidth() + " × " + matte.getHeight());
            });
            case PICK_FACES -> processImage(uri, "正在检测人脸", coreVision::detectFaces, (bitmap, detections) -> {
                output.show(bitmap, detections, "人脸检测结果");
                busy(false, detections.isEmpty() ? "未检测到人脸" : "检测到 " + detections.size() + " 张人脸");
            });
            case PICK_OBJECTS -> processImage(uri, "正在检测物体", coreVision::detectObjects, (bitmap, detections) -> {
                output.showBoxes(bitmap, detections.stream().map(CoreVision.DetectedObject::bounds)
                        .collect(java.util.stream.Collectors.toList()), "物体检测结果");
                busy(false, "物体 " + detections.size() + " 个");
            });
            case PICK_SKELETONS -> processImage(uri, "正在检测骨骼", coreVision::detectSkeletons, (bitmap, skeletons) -> {
                output.showSkeletons(bitmap, skeletons);
                busy(false, "骨骼 " + skeletons.size() + " 个");
            });
            case PICK_TEXT -> processImage(uri, "正在识别文字", coreVision::recognizeText, (bitmap, recognized) -> {
                output.show(bitmap, Collections.emptyList(), "文字识别原图");
                busy(false, recognized.value().isEmpty() ? "未识别到文字" : recognized.value());
            });
            case PICK_SR -> processImage(uri, "正在放大图片", coreVision::superResolve, (bitmap, enlarged) -> {
                output.show(enlarged, Collections.emptyList(), "4 倍超分结果");
                busy(false, enlarged.getWidth() + " × " + enlarged.getHeight());
            });
            case PICK_INDEX -> addToSearch(uri);
            case PICK_DELETE -> removeFromSearch(uri);
        }
    }

    private <Result> void processImage(Uri uri, String label, Function<Bitmap, CompletableFuture<Result>> operation,
                                       BiConsumer<Bitmap, Result> show) {
        busy(true, label);
        CompletableFuture.supplyAsync(() -> readImage(uri))
                .thenCompose(bitmap -> operation.apply(bitmap).thenAccept(result -> runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    hideSecondImage();
                    show.accept(bitmap, result);
                }))).exceptionally(this::showError);
    }

    private SharedPreferences searchImages() {
        return getSharedPreferences("image-search", MODE_PRIVATE);
    }

    private static String imageId(Uri uri) {
        return UUID.nameUUIDFromBytes(uri.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void addToSearch(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException error) {
            busy(false, "此图片来源未提供持久读取权限");
            return;
        }
        String imageId = imageId(uri);
        processImage(uri, "正在索引图片", bitmap -> coreVision.insertImage(imageId, SEARCH_SCOPE, bitmap), (bitmap, result) -> {
            searchImages().edit().putString(imageId, uri.toString()).apply();
            output.show(bitmap, Collections.emptyList(), "已索引图片");
            busy(false, "已加入图片索引");
        });
    }

    private void removeFromSearch(Uri uri) {
        String imageId = imageId(uri);
        busy(true, "正在移除索引");
        coreVision.deleteImage(imageId, SEARCH_SCOPE).thenAccept(deleted -> runOnUiThread(() -> {
            if (isDestroyed()) return;
            searchImages().edit().remove(imageId).apply();
            busy(false, deleted ? "已移除图片索引" : "索引中没有这张图片");
        })).exceptionally(this::showError);
    }

    private void clearSearch() {
        busy(true, "正在清空索引");
        coreVision.clearImageSearch().thenRun(() -> runOnUiThread(() -> {
            if (isDestroyed()) return;
            searchImages().edit().clear().apply();
            busy(false, "已清空图片索引");
        })).exceptionally(this::showError);
    }

    private void showSearch() {
        EditText query = new EditText(this);
        query.setHint("输入描述，例如猫咪");
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        query.setSingleLine();
        new AlertDialog.Builder(this).setTitle("以文搜图").setView(query).setNegativeButton("取消", null)
                .setPositiveButton("搜索", (dialog, button) -> search(query.getText().toString())).show();
    }

    private void search(String query) {
        busy(true, "正在搜索图片");
        coreVision.searchImages(query, SEARCH_SCOPE).thenCompose(matches -> {
            if (matches.isEmpty()) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) busy(false, "当前索引为空");
                });
                return CompletableFuture.completedFuture(null);
            }
            CoreVision.ImageMatch first = matches.get(0);
            String stored = searchImages().getString(first.imageId(), null);
            return CompletableFuture.supplyAsync(() -> stored == null ? null : readImage(Uri.parse(stored)))
                    .thenAccept(bitmap -> runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        hideSecondImage();
                        if (bitmap != null) output.show(bitmap, Collections.emptyList(), "最相似的图片");
                        busy(false, String.format(Locale.getDefault(), "%d 张结果 · 最高相似度 %.3f", matches.size(), first.similarity()));
                    }));
        }).exceptionally(this::showError);
    }

    private void compareImages(Uri first, Uri second) {
        busy(true, "正在比对人脸");
        CompletableFuture.supplyAsync(() -> new Images(readImage(first), readImage(second)))
                .thenCompose(images -> coreVision.compareFaces(images.first(), images.second())
                        .thenAccept(result -> runOnUiThread(() -> {
                            if (isDestroyed()) return;
                            output.show(images.first(), Collections.emptyList(), "第一张比对照片");
                            secondOutput.show(images.second(), Collections.emptyList(), "第二张比对照片");
                            secondOutput.setVisibility(View.VISIBLE);
                            String match = result.samePerson() ? "判定为同一人" : "判定为不同人";
                            busy(false, String.format(Locale.getDefault(), "%s · 相似度 %.1f%%", match, result.similarity() * 100));
                        }))).exceptionally(this::showError);
    }

    private Bitmap readImage(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri),
                        (decoder, info, source) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
            }
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                if (bitmap == null) throw new IOException("无法读取图片");
                return bitmap;
            }
        } catch (IOException error) {
            throw new CompletionException(error);
        }
    }

    private void hideSecondImage() {
        secondOutput.setVisibility(View.GONE);
        secondOutput.setImageDrawable(null);
    }

    private Void showError(Throwable error) {
        runOnUiThread(() -> {
            if (!isDestroyed()) busy(false, message(error));
        });
        return null;
    }

    private void busy(boolean running, String text) {
        probe.setEnabled(!running);
        segment.setEnabled(!running);
        faces.setEnabled(!running);
        compare.setEnabled(!running);
        more.setEnabled(!running);
        status.setText(text);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelable(FIRST_COMPARE_IMAGE, firstCompareImage);
    }

    private static String message(Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException ? throwable.getCause() : throwable;
        return cause instanceof CoreVisionException error ? error.getCode() + " · " + error.getMessage() : cause.getMessage();
    }

    @Override
    protected void onDestroy() {
        coreVision.close();
        super.onDestroy();
    }

    private record Images(Bitmap first, Bitmap second) {}

    private static final class ResultView extends ImageView {
        private final Paint overlay = new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<CoreVision.Face> detections = Collections.emptyList();
        private List<CoreVision.ImageBounds> boxes = Collections.emptyList();
        private List<CoreVision.Skeleton> skeletons = Collections.emptyList();
        private int imageWidth;
        private int imageHeight;

        ResultView(Activity context) {
            super(context);
            setScaleType(ScaleType.FIT_CENTER);
        }

        void show(Bitmap bitmap, List<CoreVision.Face> faces, String description) {
            detections = faces;
            boxes = Collections.emptyList();
            skeletons = Collections.emptyList();
            imageWidth = bitmap.getWidth();
            imageHeight = bitmap.getHeight();
            setImageBitmap(bitmap);
            setContentDescription(description);
        }

        void showBoxes(Bitmap bitmap, List<CoreVision.ImageBounds> boxes, String description) {
            show(bitmap, Collections.emptyList(), description);
            this.boxes = boxes;
        }

        void showSkeletons(Bitmap bitmap, List<CoreVision.Skeleton> skeletons) {
            showBoxes(bitmap, skeletons.stream().map(CoreVision.Skeleton::bounds)
                    .collect(java.util.stream.Collectors.toList()), "骨骼检测结果");
            this.skeletons = skeletons;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if ((detections.isEmpty() && boxes.isEmpty()) || getDrawable() == null) return;
            Rect drawableBounds = getDrawable().getBounds();
            canvas.save();
            canvas.translate(getPaddingLeft(), getPaddingTop());
            canvas.concat(getImageMatrix());
            canvas.scale((float) drawableBounds.width() / imageWidth, (float) drawableBounds.height() / imageHeight);
            float markerSize = Math.max(imageWidth, imageHeight) * 0.0035f;
            overlay.setStrokeWidth(markerSize);
            for (CoreVision.Face face : detections) {
                CoreVision.FaceBounds bounds = face.bounds();
                overlay.setColor(Color.rgb(37, 224, 178));
                overlay.setStyle(Paint.Style.STROKE);
                canvas.drawRect(bounds.left(), bounds.top(), bounds.right(), bounds.bottom(), overlay);
                overlay.setColor(Color.rgb(255, 216, 87));
                overlay.setStyle(Paint.Style.FILL);
                for (CoreVision.FacePoint point : face.landmarks()) {
                    canvas.drawCircle(point.x(), point.y(), markerSize, overlay);
                }
            }
            overlay.setStyle(Paint.Style.STROKE);
            overlay.setColor(Color.rgb(37, 224, 178));
            for (CoreVision.ImageBounds bounds : boxes) {
                canvas.drawRect(bounds.left(), bounds.top(), bounds.right(), bounds.bottom(), overlay);
            }
            overlay.setStyle(Paint.Style.FILL);
            overlay.setColor(Color.rgb(255, 216, 87));
            for (CoreVision.Skeleton skeleton : skeletons) {
                for (CoreVision.SkeletonPoint point : skeleton.points()) {
                    if (point.score() > 0.3f) canvas.drawCircle(point.horizontal(), point.vertical(), markerSize, overlay);
                }
            }
            canvas.restore();
        }
    }
}
