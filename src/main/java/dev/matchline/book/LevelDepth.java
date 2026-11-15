package dev.matchline.book;

import dev.matchline.domain.PriceTicks;

import java.util.Objects;

/** A read-only aggregate for one nonempty price level. */
public record LevelDepth(PriceTicks price, long totalQuantity, int orderCount) {
    public LevelDepth {
        Objects.requireNonNull(price, "price");
        if (totalQuantity <= 0 || orderCount <= 0) {
            throw new IllegalArgumentException("a visible price level must have orders and positive quantity");
        }
    }
}
