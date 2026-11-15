package dev.matchline.http;

import dev.matchline.book.CancelResult;
import dev.matchline.book.LevelDepth;
import dev.matchline.book.MatchResult;
import dev.matchline.book.RestingOrderView;
import dev.matchline.book.TradeExecution;
import dev.matchline.sequencer.BookReadView;
import dev.matchline.sequencer.CommandDecision;
import dev.matchline.sequencer.SequencedResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Wire-specific projections; never expose mutable book objects or journal internals. */
final class ApiView {
    private ApiView() { }

    static Map<String, Object> of(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    static Map<String, Object> level(LevelDepth d) {
        return of("priceTicks", d.price().value(), "quantity", d.totalQuantity(),
                "orders", d.orderCount());
    }

    static Map<String, Object> order(RestingOrderView o) {
        return of("orderId", o.orderId().value(), "side", o.side().name(),
                "priceTicks", o.price().value(), "originalQuantity", o.originalQuantity(),
                "remainingQuantity", o.remainingQuantity(), "fifoOrdinal", o.insertionOrdinal());
    }

    static Map<String, Object> trade(long sequence, int fillIndex, TradeExecution t) {
        return of("sequence", sequence, "fillIndex", fillIndex,
                "instrument", t.instrument().value(), "makerOrderId", t.makerOrderId().value(),
                "takerOrderId", t.takerOrderId().value(), "takerSide", t.takerSide().name(),
                "priceTicks", t.price().value(), "quantity", t.quantity());
    }

    static Map<String, Object> book(BookReadView b) {
        return of("instrument", b.instrument().value(), "asOfSequence", b.lastProcessedSequence(),
                "restingOrders", b.restingOrders(), "acceptedOrderIds", b.acceptedOrderIds(),
                "bids", b.bids().stream().map(ApiView::level).toList(),
                "asks", b.asks().stream().map(ApiView::level).toList(),
                "bestBid", b.bestBid().map(ApiView::order).orElse(null),
                "bestAsk", b.bestAsk().map(ApiView::order).orElse(null));
    }

    static Map<String, Object> decision(SequencedResult processed) {
        Map<String, Object> output = of("sequence", processed.sequence(),
                "instrument", processed.command().instrument().value(),
                "orderId", processed.command().orderId().value(),
                "durable", false); // overwritten by the HTTP adapter using configured durability
        CommandDecision decision = processed.decision();
        if (decision instanceof CommandDecision.Placed placed) {
            MatchResult r = placed.result();
            output.put("decision", "PLACED");
            output.put("executedQuantity", r.executedQuantity());
            output.put("restingQuantity", r.restingQuantity());
            List<Map<String, Object>> executions = new java.util.ArrayList<>();
            for (int i = 0; i < r.trades().size(); i++) {
                executions.add(trade(processed.sequence(), i + 1, r.trades().get(i)));
            }
            output.put("trades", executions);
        } else if (decision instanceof CommandDecision.Cancelled cancelled) {
            CancelResult r = cancelled.result();
            output.put("decision", "CANCELLED");
            output.put("cancelledQuantity", r.cancelledQuantity());
            output.put("cancelledOrder", order(r.cancelledOrder()));
        } else if (decision instanceof CommandDecision.Rejected rejected) {
            output.put("decision", "REJECTED");
            output.put("rejection", of("code", rejected.rejection().code().name(),
                    "message", rejected.rejection().message()));
        } else throw new IllegalStateException("unrecognized decision");
        return output;
    }
}
