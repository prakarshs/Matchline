package dev.matchline.domain;

/**
 * Stable, machine-readable rejection categories. This enum does not imply that
 * the bootstrap application currently accepts or processes commands.
 *
 * <p>Validation codes can be raised when creating domain values. State-dependent
 * codes are reserved for the book and sequencer increments.</p>
 */
public enum RejectionCode {
    INVALID_INSTRUMENT,
    INVALID_ORDER_ID,
    INVALID_SIDE,
    INVALID_PRICE,
    INVALID_QUANTITY,
    INVALID_COMMAND,
    DUPLICATE_ORDER_ID,
    ORDER_NOT_FOUND,
    INSTRUMENT_MISMATCH,
    BOOK_CAPACITY_EXCEEDED,
    ORDER_ID_HISTORY_CAPACITY_EXCEEDED,
    INGRESS_OVERLOADED
}
