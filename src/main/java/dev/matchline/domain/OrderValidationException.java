package dev.matchline.domain;

import java.util.Objects;

/** Error creating an invalid domain value or command; no state was changed. */
public final class OrderValidationException extends IllegalArgumentException {
    private final RejectionCode code;

    public OrderValidationException(RejectionCode code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
        if (message.isBlank()) {
            throw new IllegalArgumentException("Validation error must have a message");
        }
    }

    public RejectionCode code() {
        return code;
    }
}
