package dev.corevision.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

import dev.corevision.android.CoreVision.ImageMatch;

final class ImageSearchIndex implements AutoCloseable {
    private static final String TABLE = "image_features";
    private static final int FEATURE_BYTES = Wire.SEARCH_FEATURES * Float.BYTES;
    private static final Comparator<ImageMatch> RANKING = Comparator.comparingDouble(ImageMatch::similarity)
            .reversed().thenComparing(ImageMatch::imageId);

    private final SQLiteDatabase database;

    ImageSearchIndex(Context context) {
        database = SQLiteDatabase.openOrCreateDatabase(new File(context.getNoBackupFilesDir(), "corevision-image-search-v1.db"), null);
        try {
            database.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " (scope TEXT NOT NULL," +
                    " image_id TEXT NOT NULL, feature BLOB NOT NULL, PRIMARY KEY (scope, image_id)) WITHOUT ROWID");
        } catch (RuntimeException error) {
            database.close();
            throw error;
        }
    }

    void insert(String imageId, String scope, float[] features) {
        ByteBuffer packed = Wire.littleEndian(new byte[FEATURE_BYTES]);
        packed.asFloatBuffer().put(features);
        ContentValues row = new ContentValues();
        row.put("scope", scope);
        row.put("image_id", imageId);
        row.put("feature", packed.array());
        database.replaceOrThrow(TABLE, null, row);
    }

    List<ImageMatch> search(String scope, float[] query, int limit, BooleanSupplier cancelled)
            throws IOException, CoreVisionException {
        PriorityQueue<ImageMatch> matches = new PriorityQueue<>(limit, RANKING.reversed());
        try (Cursor rows = database.query(TABLE, new String[]{"image_id", "feature"}, "scope = ?",
                new String[]{scope}, null, null, null)) {
            while (rows.moveToNext()) {
                if (cancelled.getAsBoolean()) throw new CoreVisionException(CoreVisionException.Code.CLOSED, "Image search cancelled");
                byte[] feature = rows.getBlob(1);
                if (feature == null || feature.length != FEATURE_BYTES) throw new IOException("Invalid indexed image vector");
                ByteBuffer packed = Wire.littleEndian(feature);
                double dot = 0;
                for (float value : query) dot += value * (double) packed.getFloat();
                if (!Double.isFinite(dot)) throw new IOException("Invalid image similarity");
                ImageMatch candidate = new ImageMatch(rows.getString(0), scope, Math.max(-1, Math.min(1, dot)));
                if (matches.size() == limit) {
                    if (RANKING.compare(candidate, matches.peek()) >= 0) continue;
                    matches.remove();
                }
                matches.add(candidate);
            }
        }
        List<ImageMatch> sorted = new ArrayList<>(matches);
        sorted.sort(RANKING);
        return Collections.unmodifiableList(sorted);
    }

    boolean delete(String imageId, String scope) {
        return database.delete(TABLE, "scope = ? AND image_id = ?", new String[]{scope, imageId}) != 0;
    }

    void clear() {
        database.delete(TABLE, null, null);
    }

    @Override
    public void close() {
        database.close();
    }
}
