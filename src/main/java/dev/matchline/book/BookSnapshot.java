package dev.matchline.book;

import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, complete logical book state at a completed command boundary.
 * Accepted IDs include fully filled takers and removed makers. Resting orders
 * are sorted by original insertion ordinal, which preserves FIFO on restore.
 * This is NOT a persisted file: SnapshotStore adds a journal anchor and checksum.
 */
public record BookSnapshot(InstrumentId instrument, long lastSequence,
                           int maxRestingOrders, int maxOrderIds,
                           long lastInsertionOrdinal, List<OrderId> acceptedOrderIds,
                           List<RestingOrderView> restingOrders) {
    public BookSnapshot {
        Objects.requireNonNull(instrument, "instrument");
        if (lastSequence < 0 || lastInsertionOrdinal < 0) {
            throw new IllegalArgumentException("snapshot counters cannot be negative");
        }
        if (maxRestingOrders < 1 || maxRestingOrders > LimitOrderBook.HARD_MAX_RESTING_ORDERS
                || maxOrderIds < 1 || maxOrderIds > LimitOrderBook.HARD_MAX_ORDER_IDS) {
            throw new IllegalArgumentException("invalid snapshot capacity configuration");
        }
        acceptedOrderIds = List.copyOf(Objects.requireNonNull(acceptedOrderIds, "acceptedOrderIds"));
        restingOrders = List.copyOf(Objects.requireNonNull(restingOrders, "restingOrders"));
        if (acceptedOrderIds.size() > maxOrderIds || restingOrders.size() > maxRestingOrders) {
            throw new IllegalArgumentException("snapshot exceeds configured capacity");
        }
    }
}
