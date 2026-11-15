package dev.matchline.book;

import dev.matchline.domain.CancelOrder;

import java.util.Objects;

/**
 * A successful, in-memory cancellation with a detached view of the quantity
 * removed from the active book. No durability/sequence acknowledgement yet.
 */
public record CancelResult(CancelOrder command, RestingOrderView cancelledOrder) {
    public CancelResult {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(cancelledOrder, "cancelledOrder");
        if (!command.orderId().equals(cancelledOrder.orderId())) {
            throw new IllegalArgumentException("cancelled order ID must match cancel command");
        }
    }

    public long cancelledQuantity() {
        return cancelledOrder.remainingQuantity();
    }
}
