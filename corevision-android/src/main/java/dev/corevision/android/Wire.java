package dev.corevision.android;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class Wire {
    static final int MAGIC = 0x31564341;
    static final int VERSION = 1;
    static final int PROBE = 1;
    static final int SEGMENT = 2;
    static final int DETECT_FACES = 3;
    static final int FACE_EMBEDDING = 4;
    static final int DETECT_OBJECTS = 5;
    static final int DETECT_SKELETONS = 6;
    static final int SUPER_RESOLUTION = 7;
    static final int RECOGNIZE_TEXT = 8;
    static final int IMAGE_FEATURES = 9;
    static final int TEXT_FEATURES = 10;
    static final int SR_SCALE = 4;
    static final int TEXT_DIRECTION = 1;
    static final int FOREGROUND = 1;
    static final int COARSE = 2;
    static final int SUBJECT_DETAILS = 4;
    static final int SUBJECT_COUNT_SHIFT = 8;
    static final int SUBJECT_COUNT_MASK = 31 << SUBJECT_COUNT_SHIFT;
    static final int MAX_SUBJECTS = 20;
    static final int DEFAULT_SUBJECTS = 6;
    static final int MAX_DIMENSION = 8192;
    static final int MAX_PIXELS = 12_582_912;
    static final int MAX_METADATA = 32_768;
    static final int MAX_OCR_BYTES = 1_048_576;
    static final int MAX_QUERY_UNITS = 100;
    static final int MAX_QUERY_BYTES = 400;
    static final int SEARCH_FEATURES = 256;
    static final int MAX_FACES = 256;
    static final int FACE_BYTES = 76;
    static final int FACE_FEATURES = 256;
    static final int MAX_DETECTIONS = 256;
    static final int OBJECT_BYTES = 24;
    static final int SKELETON_POINTS = 17;
    static final int SKELETON_BYTES = 20 + 12 * SKELETON_POINTS;
    static final int BUFFER_SIZE = 65_536;
    static final byte[] PROBE_INPUT = {1, 2, 4, 8, 16, 32, 64, -128, 9, 8, 7, 6, 1, 3, 5, 7};
    static final byte[] PROBE_OUTPUT = {15, -16, 0, 0};

    static boolean validDimensions(int width, int height) {
        return width > 0 && height > 0 && width <= MAX_DIMENSION && height <= MAX_DIMENSION
                && (long) width * height <= MAX_PIXELS;
    }

    static boolean validSuperResolutionInput(int width, int height) {
        return width > 0 && height > 0 && width <= MAX_DIMENSION / SR_SCALE && height <= MAX_DIMENSION / SR_SCALE
                && (long) width * height <= MAX_PIXELS / (SR_SCALE * SR_SCALE);
    }

    record Request(int id, int operation, int flags, int width, int height) {
        int pixels() { return width * height; }
        int bytes() { return operation == TEXT_FEATURES ? flags : pixels() * 4; }

        void write(OutputStream output) throws IOException {
            output.write(buffer(36).putInt(MAGIC).putInt(VERSION).putInt(id).putInt(operation)
                    .putInt(flags).putInt(width).putInt(height).putInt(width * 4).putInt(bytes()).array());
        }

        static Request read(InputStream input) throws IOException {
            ByteBuffer header = littleEndian(readBytes(input, 36));
            validatePreamble(header);
            Request request = new Request(header.getInt(), header.getInt(), header.getInt(),
                    header.getInt(), header.getInt());
            boolean validOperation = switch (request.operation) {
                case PROBE -> request.flags == 0 && request.width == 2 && request.height == 2;
                case SEGMENT -> (request.flags & ~(FOREGROUND | COARSE | SUBJECT_DETAILS | SUBJECT_COUNT_MASK)) == 0
                        && (request.flags >>> SUBJECT_COUNT_SHIFT) <= MAX_SUBJECTS;
                case DETECT_FACES, FACE_EMBEDDING, DETECT_OBJECTS, DETECT_SKELETONS, IMAGE_FEATURES -> request.flags == 0;
                case SUPER_RESOLUTION -> request.flags == 0 && validSuperResolutionInput(request.width, request.height);
                case RECOGNIZE_TEXT -> (request.flags & ~TEXT_DIRECTION) == 0;
                case TEXT_FEATURES -> request.width == 0 && request.height == 0
                        && request.flags > 0 && request.flags <= MAX_QUERY_BYTES;
                default -> false;
            };
            if (request.id == 0 || (request.operation != TEXT_FEATURES && !validDimensions(request.width, request.height))
                    || header.getInt() != request.width * 4 || header.getInt() != request.bytes() || !validOperation) {
                throw new IOException("Invalid request header");
            }
            return request;
        }
    }

    record Response(int status, int width, int height, int payload, int foreground, int coarse, int metadata) {
        static Response read(InputStream input, Request request) throws IOException {
            ByteBuffer header = littleEndian(readBytes(input, 40));
            validatePreamble(header);
            int id = header.getInt();
            Response response = new Response(header.getInt(), header.getInt(), header.getInt(),
                    header.getInt(), header.getInt(), header.getInt(), header.getInt());
            boolean validLengths = response.status == 0 ? response.matches(request)
                    : response.status > 0 && response.status <= CoreVisionException.Code.IMAGE_SEARCH.value
                        && response.width == 0 && response.height == 0
                        && response.payload == 0 && response.foreground == 0 && response.coarse == 0;
            if (id != request.id || !validLengths || response.metadata < 0 || response.metadata > MAX_METADATA) {
                throw new IOException("Invalid response header");
            }
            return response;
        }

        private boolean matches(Request request) {
            if (request.operation == SUPER_RESOLUTION) {
                return width == request.width * SR_SCALE && height == request.height * SR_SCALE
                        && payload == 0 && coarse == 0 && foreground == width * height * 4;
            }
            if (width != request.width || height != request.height) return false;
            return switch (request.operation) {
                case DETECT_FACES -> matchesRecords(FACE_BYTES, MAX_FACES);
                case DETECT_OBJECTS -> matchesRecords(OBJECT_BYTES, MAX_DETECTIONS);
                case DETECT_SKELETONS -> matchesRecords(SKELETON_BYTES, MAX_DETECTIONS);
                case FACE_EMBEDDING -> payload == FACE_FEATURES * Float.BYTES && foreground == 0 && coarse == 0;
                case RECOGNIZE_TEXT -> payload >= 0 && payload <= MAX_OCR_BYTES && foreground == 0 && coarse == 0;
                case IMAGE_FEATURES, TEXT_FEATURES -> payload == SEARCH_FEATURES * Float.BYTES && foreground == 0 && coarse == 0;
                default -> payload == request.pixels() * ((request.flags & SUBJECT_DETAILS) != 0 ? 2 : 1)
                        && foreground == ((request.flags & FOREGROUND) != 0 ? request.bytes() : 0)
                        && coarse == ((request.flags & COARSE) != 0 ? request.pixels() : 0);
            };
        }

        private boolean matchesRecords(int recordBytes, int maxRecords) {
            return foreground == 0 && coarse == 0 && payload >= 0 && payload <= recordBytes * maxRecords
                    && payload % recordBytes == 0;
        }

        void write(OutputStream output, int id, int metadataLength) throws IOException {
            output.write(buffer(40).putInt(MAGIC).putInt(VERSION).putInt(id).putInt(status)
                    .putInt(width).putInt(height).putInt(payload).putInt(foreground).putInt(coarse)
                    .putInt(metadataLength).array());
        }
    }

    static byte[] readBytes(InputStream input, int size) throws IOException {
        byte[] bytes = new byte[size];
        int offset = 0;
        while (offset < size) {
            int count = input.read(bytes, offset, size - offset);
            if (count < 0) throw new EOFException("Truncated data");
            offset += count;
        }
        return bytes;
    }

    static void copy(InputStream input, OutputStream output, int size) throws IOException {
        byte[] buffer = new byte[Math.min(BUFFER_SIZE, size)];
        while (size > 0) {
            int count = input.read(buffer, 0, Math.min(buffer.length, size));
            if (count < 0) throw new EOFException("Truncated data");
            output.write(buffer, 0, count);
            size -= count;
        }
    }

    static String metadata(InputStream input, Response response) throws IOException {
        return new String(readBytes(input, response.metadata), StandardCharsets.UTF_8);
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    static ByteBuffer littleEndian(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void validatePreamble(ByteBuffer header) throws IOException {
        if (header.getInt() != MAGIC || header.getInt() != VERSION) {
            throw new IOException("Unsupported protocol");
        }
    }

    private Wire() {}
}
