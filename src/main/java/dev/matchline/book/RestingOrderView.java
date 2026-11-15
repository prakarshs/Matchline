package dev.matchline.book;

import dev.matchline.domain.OrderId;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.Side;

import java.util.Objects;

/**
 * Immutable read-only representation of one order resting in the book.
 * Insertion ordinal is local to a book, NOT an ingress/accepted-command sequence.
 */
public record RestingOrderView(
        OrderId orderId,
        Side side,
        PriceTicks price,
        long originalQuantity,
        long remainingQuantity,
        long insertionOrdinal
) {
    public RestingOrderView {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(price, "price");
        if (originalQuantity < 1 || remainingQuantity < 1 || remainingQuantity > originalQuantity) {
            throw new IllegalArgumentException("resting quantities must be positive, with remaining <= original");
        }
        if (insertionOrdinal < 1) {
            throw new IllegalArgumentException("insertion ordinal must be positive");
        }
    }
}
