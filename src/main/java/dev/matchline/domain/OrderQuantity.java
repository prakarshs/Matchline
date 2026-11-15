package dev.matchline.domain;

/**
 * Quantity for a NEW order: positive whole units, never zero or fractional.
 * A matching book will hold the remaining quantity as a separate long (zero
 * is a valid internal state after a full fill, not a valid new-order request).
 */
public record OrderQuantity(long value) {
    public static final long MIN = 1L;
    public static final long MAX = 1_000_000L;

    public OrderQuantity {
        if (value < MIN || value > MAX) {
            throw new OrderValidationException(RejectionCode.INVALID_QUANTITY,
                    "quantity must be between " + MIN + " and " + MAX + " whole units");
        }
    }
}
