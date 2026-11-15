package dev.matchline.sequencer;

import dev.matchline.book.LevelDepth;
import dev.matchline.book.RestingOrderView;
import dev.matchline.domain.InstrumentId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, writer-linearized diagnostic view; not a persisted/recovery snapshot. */
public record BookReadView(
        InstrumentId instrument,
        long lastProcessedSequence,
        int restingOrders,
        int acceptedOrderIds,
        List<LevelDepth> bids,
        List<LevelDepth> asks,
        Optional<RestingOrderView> bestBid,
        Optional<RestingOrderView> bestAsk
) {
    public BookReadView {
        Objects.requireNonNull(instrument, "instrument");
        if (lastProcessedSequence < 0 || restingOrders < 0 || acceptedOrderIds < 0) {
            throw new IllegalArgumentException("read counters cannot be negative");
        }
        bids = List.copyOf(Objects.requireNonNull(bids, "bids"));
        asks = List.copyOf(Objects.requireNonNull(asks, "asks"));
        Objects.requireNonNull(bestBid, "bestBid");
        Objects.requireNonNull(bestAsk, "bestAsk");
    }
}
