package dev.corevision.android;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.corevision.android.CoreVision.TextBlock;
import dev.corevision.android.CoreVision.TextLine;
import dev.corevision.android.CoreVision.TextPoint;
import dev.corevision.android.CoreVision.TextRecognition;
import dev.corevision.android.CoreVision.TextWord;

final class OcrResultReader {
    private static final String FIELD_SEPARATOR = "\u2487";
    private static final String LINE_SEPARATOR = "\u247e";
    private static final String WORD_SEPARATOR = "\u247f";
    private static final String WORD_VALUE_SEPARATOR = "\u2480";

    static TextRecognition read(InputStream input, Wire.Response response) throws IOException {
        String raw = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Wire.readBytes(input, response.payload()))).toString();
        if (raw.isEmpty()) return new TextRecognition("", Collections.emptyList());
        String[] fields = raw.split(FIELD_SEPARATOR, -1);
        if (fields.length != 6) throw new IOException("Unexpected OCR result layout");
        if (fields[0].isEmpty() && fields[1].isEmpty()) return new TextRecognition("", Collections.emptyList());

        String[] geometry = fields[0].split(LINE_SEPARATOR, -1);
        String[] values = fields[1].split(LINE_SEPARATOR, -1);
        String[] words = fields[3].split(LINE_SEPARATOR, -1);
        String[] blockIds = fields[4].split(LINE_SEPARATOR, -1);
        if (geometry.length != values.length || blockIds.length != values.length
                || (!fields[3].isEmpty() && words.length != values.length)) {
            throw new IOException("Inconsistent OCR line count");
        }

        Map<Integer, List<TextLine>> grouped = new LinkedHashMap<>();
        for (int index = 0; index < values.length; ++index) {
            List<TextWord> lineWords = fields[3].isEmpty() ? Collections.emptyList() : readWords(words[index], response);
            TextLine line = new TextLine(values[index], readPoints(geometry[index], ",", response), lineWords);
            int blockId;
            try {
                blockId = Integer.parseInt(blockIds[index]);
            } catch (NumberFormatException error) {
                throw new IOException("Invalid OCR block ID", error);
            }
            if (blockId < 0 || blockId >= values.length) throw new IOException("Unexpected OCR block ID");
            grouped.computeIfAbsent(blockId, ignored -> new ArrayList<>()).add(line);
        }
        List<TextBlock> blocks = new ArrayList<>(grouped.size());
        for (List<TextLine> lines : grouped.values()) {
            List<String> contents = new ArrayList<>(lines.size());
            for (TextLine line : lines) contents.add(line.value());
            blocks.add(new TextBlock(String.join("\n", contents), lines));
        }
        return new TextRecognition(String.join("\n", values), blocks);
    }

    private static List<TextWord> readWords(String encoded, Wire.Response response) throws IOException {
        if (encoded.isEmpty()) return Collections.emptyList();
        String[] parts = encoded.split(WORD_SEPARATOR, -1);
        List<TextWord> words = new ArrayList<>(parts.length);
        for (String part : parts) {
            String[] word = part.split(WORD_VALUE_SEPARATOR, 2);
            if (word.length != 2) throw new IOException("Unexpected OCR word layout");
            words.add(new TextWord(word[1], readPoints(word[0], "#", response)));
        }
        return words;
    }

    private static List<TextPoint> readPoints(String encoded, String separator, Wire.Response response) throws IOException {
        String[] coordinates = encoded.split(separator, -1);
        if (coordinates.length != 8) throw new IOException("Unexpected OCR quadrilateral layout");
        List<TextPoint> points = new ArrayList<>(4);
        try {
            for (int index = 0; index < coordinates.length; index += 2) {
                int x = Integer.parseInt(coordinates[index]);
                int y = Integer.parseInt(coordinates[index + 1]);
                points.add(new TextPoint(Math.max(0, Math.min(response.width(), x)),
                        Math.max(0, Math.min(response.height(), y))));
            }
        } catch (NumberFormatException error) {
            throw new IOException("Invalid OCR coordinate", error);
        }
        return points;
    }

    private OcrResultReader() {}
}
