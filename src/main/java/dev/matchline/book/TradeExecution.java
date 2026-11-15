package dev.matchline.book;

import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.Side;

import java.util.Objects;

/**
 * One immutable maker/taker execution, at the resting maker's price.
 *
 * <p>List order within {@link MatchResult#trades()} is execution order.
 * No durable event ID, acknowledgement sequence, or timestamp exists yet;
 * those belong to the command gateway and journal increments.
 */
public record TradeExecution(
        InstrumentId instrument,
        OrderId makerOrderId,
        OrderId takerOrderId,
        Side takerSide,
        PriceTicks price,
        long quantity
) {
    public TradeExecution {
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(makerOrderId, "makerOrderId");
        Objects.requireNonNull(takerOrderId, "takerOrderId");
        Objects.requireNonNull(takerSide, "takerSide");
        Objects.requireNonNull(price, "price");
        if (makerOrderId.equals(takerOrderId)) {
            throw new IllegalArgumentException("maker and taker IDs must differ");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("an execution must have positive quantity");
        }
    }

    public Side makerSide() {
        return takerSide.opposite();
    }
}
