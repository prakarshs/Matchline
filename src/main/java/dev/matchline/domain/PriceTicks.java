package dev.matchline.domain;

import java.util.Objects;

/**
 * Positive integer price in abstract ticks (not fiat units or a floating-point price).
 * A limit order accepts 1..1,000,000,000 ticks inclusive.
 */
public record PriceTicks(long value) implements Comparable<PriceTicks> {
    public static final long MIN = 1L;
    public static final long MAX = 1_000_000_000L;

    public PriceTicks {
        if (value < MIN || value > MAX) {
            throw invalid("price must be between " + MIN + " and " + MAX + " ticks");
        }
    }

    public PriceTicks plusTicks(long delta) {
        try {
            return new PriceTicks(Math.addExact(value, delta));
        } catch (ArithmeticException ex) {
            throw invalid("price tick addition overflow");
        }
    }

    public PriceTicks minusTicks(long delta) {
        try {
            return new PriceTicks(Math.subtractExact(value, delta));
        } catch (ArithmeticException ex) {
            throw invalid("price tick subtraction overflow");
        }
    }

    /** Signed difference, {@code this - other}, in ticks. Safe under price bounds. */
    public long differenceFrom(PriceTicks other) {
        return Math.subtractExact(value, Objects.requireNonNull(other, "other").value);
    }

    /**
     * Price-ticks times quantity. An abstract notional, NOT a currency amount;
     * at the allowed per-order limits this is <= 10^15, safely inside a long.
     */
    public long notionalTicks(OrderQuantity quantity) {
        return Math.multiplyExact(value, Objects.requireNonNull(quantity, "quantity").value());
    }

    @Override
    public int compareTo(PriceTicks other) {
        return Long.compare(value, Objects.requireNonNull(other, "other").value);
    }

    private static OrderValidationException invalid(String message) {
        return new OrderValidationException(RejectionCode.INVALID_PRICE, message);
    }
}
