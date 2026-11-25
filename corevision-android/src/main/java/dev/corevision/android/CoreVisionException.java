package dev.corevision.android;

public final class CoreVisionException extends Exception {
    public enum Code {
        INVALID_INPUT(1), UNSUPPORTED_DEVICE(2), RUNTIME_MISSING(3), RUNTIME_INCOMPATIBLE(4),
        WORKER_START(5), TIMEOUT(6), WORKER_FAILED(7), PROTOCOL(8), SERVICE_UNAVAILABLE(9),
        BUSY(10), CLOSED(11), CPU_CONFIGURATION(12), SCENE_301(13), SCENE_302(14), FACE_DETECTION(15), FACE_COMPARISON(16),
        OBJECT_DETECTION(17), SKELETON_DETECTION(18), IMAGE_SUPER_RESOLUTION(19), TEXT_RECOGNITION(20), IMAGE_SEARCH(21);

        final int value;

        Code(int value) {
            this.value = value;
        }

        static Code fromValue(int value) {
            for (Code code : values()) {
                if (code.value == value) return code;
            }
            return PROTOCOL;
        }
    }

    private final Code code;

    CoreVisionException(Code code, String message) {
        super(message);
        this.code = code;
    }

    CoreVisionException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
