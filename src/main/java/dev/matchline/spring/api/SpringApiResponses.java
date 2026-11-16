package dev.matchline.spring.api;

import dev.matchline.book.LevelDepth;
import dev.matchline.book.RestingOrderView;
import dev.matchline.book.TradeExecution;
import dev.matchline.sequencer.BookReadView;
import dev.matchline.sequencer.CommandDecision;
import dev.matchline.sequencer.SequencedResult;
import java.util.List;

/**
 * Typed immutable HTTP response DTOs. The wire names mirror the original adapter
 * so clients can move to Spring without changing existing order-book JSON access.
 * Keep presentation logic here; never serialize mutable engine internals directly.
 */
public final class SpringApiResponses {
    private SpringApiResponses() { }

    public record DepthLevel(long priceTicks, long quantity, int orders) { }
    public record RestingOrder(String orderId, String side, long priceTicks,
                               long originalQuantity, long remainingQuantity, long fifoOrdinal) { }
    public record Trade(long sequence, int fillIndex, String instrument, String makerOrderId,
                        String takerOrderId, String takerSide, long priceTicks, long quantity) { }
    public record Rejection(String code, String message) { }
    public record Book(String instrument, long asOfSequence, int restingOrders, int acceptedOrderIds,
                       List<DepthLevel> bids, List<DepthLevel> asks,
                       RestingOrder bestBid, RestingOrder bestAsk) { }
    public record Lookup(String instrument, RestingOrder order) { }
    public sealed interface Decision permits Placed, Cancelled, Rejected { }
    public record Placed(long sequence, String instrument, String orderId, boolean durable,
                         String decision, long executedQuantity, long restingQuantity,
                         List<Trade> trades) implements Decision { }
    public record Cancelled(long sequence, String instrument, String orderId, boolean durable,
                            String decision, long cancelledQuantity,
                            RestingOrder cancelledOrder) implements Decision { }
    public record Rejected(long sequence, String instrument, String orderId, boolean durable,
                           String decision, Rejection rejection) implements Decision { }

    public static DepthLevel level(LevelDepth d) {
        return new DepthLevel(d.price().value(), d.totalQuantity(), d.orderCount());
    }

    public static RestingOrder order(RestingOrderView o) {
        return new RestingOrder(o.orderId().value(), o.side().name(), o.price().value(),
                o.originalQuantity(), o.remainingQuantity(), o.insertionOrdinal());
    }

    public static Trade trade(long sequence, int index, TradeExecution t) {
        return new Trade(sequence, index, t.instrument().value(), t.makerOrderId().value(),
                t.takerOrderId().value(), t.takerSide().name(), t.price().value(), t.quantity());
    }

    public static Book book(BookReadView b) {
        return new Book(b.instrument().value(), b.lastProcessedSequence(), b.restingOrders(),
                b.acceptedOrderIds(), b.bids().stream().map(SpringApiResponses::level).toList(),
                b.asks().stream().map(SpringApiResponses::level).toList(),
                b.bestBid().map(SpringApiResponses::order).orElse(null),
                b.bestAsk().map(SpringApiResponses::order).orElse(null));
    }

    public static Decision decision(SequencedResult processed) {
        String instrument = processed.command().instrument().value();
        String orderId = processed.command().orderId().value();
        long sequence = processed.sequence();
        // S03 Spring writer is volatile. Never pretend a successful response is durable.
        if (processed.decision() instanceof CommandDecision.Placed placed) {
            var result = placed.result();
            List<Trade> trades = new java.util.ArrayList<>();
            for (int i = 0; i < result.trades().size(); i++) {
                trades.add(trade(sequence, i + 1, result.trades().get(i)));
            }
            return new Placed(sequence, instrument, orderId, false, "PLACED",
                    result.executedQuantity(), result.restingQuantity(), List.copyOf(trades));
        }
        if (processed.decision() instanceof CommandDecision.Cancelled cancelled) {
            var result = cancelled.result();
            return new Cancelled(sequence, instrument, orderId, false, "CANCELLED",
                    result.cancelledQuantity(), order(result.cancelledOrder()));
        }
        if (processed.decision() instanceof CommandDecision.Rejected rejected) {
            var reason = rejected.rejection();
            return new Rejected(sequence, instrument, orderId, false, "REJECTED",
                    new Rejection(reason.code().name(), reason.message()));
        }
        throw new IllegalStateException("unrecognized decision type");
    }
}
