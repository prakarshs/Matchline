package dev.matchline.domain;

import java.util.Objects;

/**
 * Immutable machine code + human explanation for a rejected command or input.
 * Rejecting never implies a command was assigned a sequence or persisted.
 */
public record CommandRejection(RejectionCode code, String message) {
    public CommandRejection {
        Objects.requireNonNull(code, "code");
        if (message == null || message.isBlank() || message.length() > 512) {
            throw new IllegalArgumentException("rejection message must be 1..512 nonblank characters");
        }
    }

    public static CommandRejection fromValidation(OrderValidationException exception) {
        Objects.requireNonNull(exception, "exception");
        return new CommandRejection(exception.code(), exception.getMessage());
    }
}
