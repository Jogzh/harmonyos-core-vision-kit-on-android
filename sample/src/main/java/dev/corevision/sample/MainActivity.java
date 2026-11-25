package dev.corevision.sample;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import dev.corevision.android.CoreVision;
import dev.corevision.android.CoreVisionException;

public final class MainActivity extends Activity {
    private static final int PICK_IMAGE = 1;
    private CoreVision coreVision;
    private Button pick;
    private Button probe;
    private TextView status;
    private ImageView output;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        coreVision = new CoreVision(this);
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
        pick = new Button(this);
        pick.setText("选择图片并抠图");
        pick.setOnClickListener(view -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_IMAGE));
        status = new TextView(this);
        output = new ImageView(this);
        output.setContentDescription("抠图前景");
        output.setScaleType(ImageView.ScaleType.FIT_CENTER);
        content.addView(probe);
        content.addView(pick);
        content.addView(status);
        content.addView(output, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(content);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_IMAGE || result != RESULT_OK || data == null || data.getData() == null) return;
        busy(true, "正在抠图");
        CompletableFuture.supplyAsync(() -> {
            try (InputStream input = getContentResolver().openInputStream(data.getData())) {
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                if (bitmap == null) throw new IOException("无法读取图片");
                return bitmap;
            } catch (IOException error) {
                throw new CompletionException(error);
            }
        }).thenCompose(bitmap -> coreVision.segment(bitmap, true))
                .whenComplete((matte, error) -> runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (error == null) output.setImageBitmap(matte.getForeground());
                    busy(false, error == null ? "抠图完成 · " + matte.getWidth() + " × " + matte.getHeight() : message(error));
                }));
    }

    private void busy(boolean running, String text) {
        probe.setEnabled(!running);
        pick.setEnabled(!running);
        status.setText(text);
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
}
