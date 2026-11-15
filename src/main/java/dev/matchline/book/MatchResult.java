package dev.matchline.book;

import dev.matchline.domain.PlaceLimitOrder;

import java.util.List;
import java.util.Objects;

/**
 * A single successfully processed limit order against one in-memory book.
 * A zero remainder means fully executed; a positive remainder is now resting.
 * This is NOT a durable command acknowledgement.
 */
public record MatchResult(
        PlaceLimitOrder command,
        long executedQuantity,
        long restingQuantity,
        List<TradeExecution> trades
) {
    public MatchResult {
        Objects.requireNonNull(command, "command");
        trades = List.copyOf(Objects.requireNonNull(trades, "trades"));
        if (executedQuantity < 0 || restingQuantity < 0
                || executedQuantity + restingQuantity != command.quantity().value()) {
            throw new IllegalArgumentException("executed + resting must equal incoming order quantity");
        }
        long summed = 0;
        for (TradeExecution trade : trades) {
            if (!trade.instrument().equals(command.instrument())
                    || !trade.takerOrderId().equals(command.orderId())
                    || trade.takerSide() != command.side()
                    || !command.side().crosses(command.price(), trade.price())) {
                throw new IllegalArgumentException("trade does not belong to this incoming limit order");
            }
            summed = Math.addExact(summed, trade.quantity());
        }
        if (summed != executedQuantity) {
            throw new IllegalArgumentException("execution quantities must equal trade quantities");
        }
    }

    public boolean fullyExecuted() {
        return restingQuantity == 0;
    }
}
