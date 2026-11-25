package dev.corevision.sample;

import android.app.Activity;
import android.content.Intent;
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
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import dev.corevision.android.CoreVision;
import dev.corevision.android.CoreVisionException;

public final class MainActivity extends Activity {
    private static final int PICK_SEGMENT = 1;
    private static final int PICK_FACES = 2;
    private static final int PICK_COMPARE_FIRST = 3;
    private static final int PICK_COMPARE_SECOND = 4;
    private static final String FIRST_COMPARE_IMAGE = "firstCompareImage";
    private CoreVision coreVision;
    private Button segment;
    private Button faces;
    private Button compare;
    private Button probe;
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
        status = new TextView(this);
        status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
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
        content.addView(status);
        content.addView(previews, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(content);
    }

    private void pickImage(int request) {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), request);
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
        if (request != PICK_SEGMENT && request != PICK_FACES) return;
        boolean detectFaces = request == PICK_FACES;
        busy(true, detectFaces ? "正在检测人脸" : "正在抠图");
        CompletableFuture<Bitmap> image = CompletableFuture.supplyAsync(() -> readImage(uri));
        if (detectFaces) {
            image.thenCompose(bitmap -> coreVision.detectFaces(bitmap).thenAccept(detections -> runOnUiThread(() -> {
                if (isDestroyed()) return;
                hideSecondImage();
                output.show(bitmap, detections, "人脸检测结果");
                busy(false, detections.isEmpty() ? "未检测到人脸" : "检测到 " + detections.size() + " 张人脸");
            }))).exceptionally(this::showError);
        } else {
            image.thenCompose(bitmap -> coreVision.segment(bitmap, true)).thenAccept(matte -> runOnUiThread(() -> {
                if (isDestroyed()) return;
                hideSecondImage();
                output.show(matte.getForeground(), Collections.emptyList(), "抠图前景");
                busy(false, "抠图完成 · " + matte.getWidth() + " × " + matte.getHeight());
            })).exceptionally(this::showError);
        }
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
        private int imageWidth;
        private int imageHeight;

        ResultView(Activity context) {
            super(context);
            setScaleType(ScaleType.FIT_CENTER);
        }

        void show(Bitmap bitmap, List<CoreVision.Face> faces, String description) {
            detections = faces;
            imageWidth = bitmap.getWidth();
            imageHeight = bitmap.getHeight();
            setImageBitmap(bitmap);
            setContentDescription(description);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (detections.isEmpty() || getDrawable() == null) return;
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
            canvas.restore();
        }
    }
}
