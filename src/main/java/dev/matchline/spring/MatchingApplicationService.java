package dev.matchline.spring;

import dev.matchline.book.BookSnapshot;
import dev.matchline.book.RestingOrderView;
import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderCommand;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.Side;
import dev.matchline.observability.EngineMetrics;
import dev.matchline.sequencer.BookReadView;
import dev.matchline.sequencer.CommandTicket;
import dev.matchline.sequencer.SingleWriterSequencer;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.springframework.stereotype.Service;

/**
 * Spring-facing application API for the engine. S03 HTTP controllers should
 * depend on this facade, never on LimitOrderBook or CommandProcessor.
 *
 * <p>Every command and read flows through the SAME bounded writer inbox.
 * submit() acknowledges queue admission only; the ticket's completion stage
 * observes the ordered decision. In this S02 volatile mode, completion does
 * NOT imply persistence or fsync. Never block the writer from a completion callback.
 */
@Service
public final class MatchingApplicationService {
    private final SingleWriterSequencer writer;

    public MatchingApplicationService(SingleWriterSequencer writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    public InstrumentId instrument() { return writer.instrument(); }
    public CommandTicket submit(OrderCommand command) { return writer.submit(command); }
    public CompletableFuture<BookReadView> inspect(int depthLevels) { return writer.inspect(depthLevels); }
    public CompletableFuture<Optional<RestingOrderView>> find(OrderId id) { return writer.find(id); }
    public CompletableFuture<List<RestingOrderView>> ordersAt(Side side, PriceTicks price) {
        return writer.ordersAt(side, price);
    }
    public CompletableFuture<BookSnapshot> snapshotState() { return writer.snapshotState(); }
    public EngineMetrics.Snapshot metricsSnapshot() { return writer.metricsSnapshot(); }
    public long lastProcessedSequence() { return writer.lastProcessedSequence(); }
    public int queuedWorkCount() { return writer.queuedWorkCount(); }
    public int queueCapacity() { return writer.queueCapacity(); }
    public boolean accepting() { return writer.accepting(); }
    public Optional<Throwable> fatalFailure() { return writer.fatalFailure(); }
}
