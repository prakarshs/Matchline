package dev.matchline.spring.api;

/** Expected transport-level failure, safe to show to clients. No Java stack traces. */
public final class SpringApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final Long sequence;

    public SpringApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public SpringApiException(int status, String code, String message, Long sequence) {
        super(message);
        this.status = status;
        this.code = code;
        this.sequence = sequence;
    }

    public int status() { return status; }
    public String code() { return code; }
    public Long sequence() { return sequence; }
}
