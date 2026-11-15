package dev.matchline.sequencer;

import dev.matchline.book.LimitOrderBook;
import dev.matchline.domain.CancelOrder;
import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.OrderValidationException;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.RejectionCode;
import dev.matchline.domain.Side;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleWriterSequencerTest {
    private static final InstrumentId INSTRUMENT = new InstrumentId("DEMO");
    private static final PriceTicks PRICE = new PriceTicks(100);

    private static PlaceLimitOrder buy(String id, long quantity) {
        return new PlaceLimitOrder(INSTRUMENT, new OrderId(id), Side.BUY, PRICE,
                new OrderQuantity(quantity));
    }

    private static PlaceLimitOrder sell(String id, long quantity) {
        return new PlaceLimitOrder(INSTRUMENT, new OrderId(id), Side.SELL, PRICE,
                new OrderQuantity(quantity));
    }

    private static SequencedResult completed(CommandTicket ticket) throws Exception {
        SequencedResult result = ticket.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(result.sequence()).isEqualTo(ticket.sequence());
        return result;
    }

    @Test
    void admissionReturnsGaplessSequencesAndDecisions() throws Exception {
        try (var sequencer = new SingleWriterSequencer(INSTRUMENT)) {
            var first = sequencer.submit(buy("B1", 4));
            var second = sequencer.submit(buy("B2", 3));
            var third = sequencer.submit(sell("S1", 5));
            assertThat(List.of(first.sequence(), second.sequence(), third.sequence()))
                    .containsExactly(1L, 2L, 3L);
            assertThat(completed(first).decision()).isInstanceOf(CommandDecision.Placed.class);
            assertThat(completed(second).decision()).isInstanceOf(CommandDecision.Placed.class);
            var result = completed(third);
            var match = ((CommandDecision.Placed) result.decision()).result();
            assertThat(match.trades()).hasSize(2);
            assertThat(match.trades().get(0).makerOrderId()).isEqualTo(new OrderId("B1"));
            assertThat(match.trades().get(1).makerOrderId()).isEqualTo(new OrderId("B2"));
            assertThat(match.executedQuantity()).isEqualTo(5);
            assertThat(sequencer.lastAdmittedSequence()).isEqualTo(3);
            assertThat(sequencer.lastProcessedSequence()).isEqualTo(3);
        }
    }

    @Test
    void businessRejectionsHaveSequenceAndLeaveTheBookUnmodified() throws Exception {
        try (var sequencer = new SingleWriterSequencer(INSTRUMENT)) {
            completed(sequencer.submit(buy("B1", 2)));
            var duplicate = completed(sequencer.submit(buy("B1", 1)));
            var missingCancel = completed(sequencer.submit(new CancelOrder(INSTRUMENT, new OrderId("X"))));
            assertRejected(duplicate, 2L, RejectionCode.DUPLICATE_ORDER_ID);
            assertRejected(missingCancel, 3L, RejectionCode.ORDER_NOT_FOUND);
            var view = sequencer.inspect(5).get(5, TimeUnit.SECONDS);
            assertThat(view.lastProcessedSequence()).isEqualTo(3);
            assertThat(view.acceptedOrderIds()).isEqualTo(1);
            assertThat(view.restingOrders()).isEqualTo(1);
            assertThat(view.bids().getFirst().totalQuantity()).isEqualTo(2);
        }
    }

    @Test
    void wrongInstrumentIsAnOrderedBusinessRejection() throws Exception {
        var wrong = new PlaceLimitOrder(new InstrumentId("OTHER"), new OrderId("B1"),
                Side.BUY, PRICE, new OrderQuantity(2));
        try (var sequencer = new SingleWriterSequencer(INSTRUMENT)) {
            assertRejected(completed(sequencer.submit(wrong)), 1, RejectionCode.INSTRUMENT_MISMATCH);
            assertThat(completed(sequencer.submit(buy("B1", 2))).successful()).isTrue();
        }
    }

    @Test
    void nullCommandRejectedBeforeAdmission() throws Exception {
        try (var sequencer = new SingleWriterSequencer(INSTRUMENT)) {
            assertThatThrownBy(() -> sequencer.submit(null))
                    .isInstanceOf(OrderValidationException.class)
                    .satisfies(error -> assertThat(((OrderValidationException) error).code())
                            .isEqualTo(RejectionCode.INVALID_COMMAND));
            assertThat(sequencer.lastAdmittedSequence()).isZero();
        }
    }

    @Test
    void consistentReadIsOrderedBetweenWrites() throws Exception {
        try (var sequencer = new SingleWriterSequencer(INSTRUMENT)) {
            var first = sequencer.submit(buy("B1", 2));
            var second = sequencer.submit(buy("B2", 3));
            var between = sequencer.inspect(5);
            var third = sequencer.submit(buy("B3", 4));
            assertThat(between.get(5, TimeUnit.SECONDS).lastProcessedSequence()).isEqualTo(2);
            assertThat(between.get().bids().getFirst().totalQuantity()).isEqualTo(5);
            assertThat(completed(first).successful()).isTrue();
            assertThat(completed(second).successful()).isTrue();
            assertThat(completed(third).successful()).isTrue();
            assertThat(sequencer.inspect(5).get().bids().getFirst().totalQuantity()).isEqualTo(9);
            assertThat(sequencer.lastAdmittedSequence()).isEqualTo(3);
        }
    }

    @Test
    void fifoAndLookupAreOnlyReadOnTheWriter() throws Exception {
        try (var sequencer = new SingleWriterSequencer(INSTRUMENT)) {
            completed(sequencer.submit(buy("B1", 2)));
            completed(sequencer.submit(buy("B2", 3)));
            assertThat(sequencer.ordersAt(Side.BUY, PRICE).get(5, TimeUnit.SECONDS))
                    .extracting(x -> x.orderId().value()).containsExactly("B1", "B2");
            assertThat(sequencer.find(new OrderId("B1")).get().orElseThrow().remainingQuantity())
                    .isEqualTo(2);
            assertThat(sequencer.find(new OrderId("MISSING")).get()).isEmpty();
        }
    }

    @Test
    void cancellationAndTradeRemainMutuallyConsistent() throws Exception {
        try (var sequencer = new SingleWriterSequencer(INSTRUMENT)) {
            completed(sequencer.submit(buy("B1", 4)));
            var trade = completed(sequencer.submit(sell("S1", 2)));
            assertThat(((CommandDecision.Placed) trade.decision()).result().trades())
                    .singleElement().satisfies(fill -> assertThat(fill.quantity()).isEqualTo(2));
            var cancelled = completed(sequencer.submit(new CancelOrder(INSTRUMENT, new OrderId("B1"))));
            assertThat(((CommandDecision.Cancelled) cancelled.decision()).result().cancelledQuantity())
                    .isEqualTo(2);
            assertRejected(completed(sequencer.submit(new CancelOrder(INSTRUMENT, new OrderId("B1")))),
                    4, RejectionCode.ORDER_NOT_FOUND);
            assertThat(sequencer.inspect(1).get().restingOrders()).isZero();
        }
    }

    @Test
    void orderIdCapacityRejectsInSequenceEvenWhenNoActiveOrderRemains() throws Exception {
        try (var sequencer = new SingleWriterSequencer(INSTRUMENT, 4, 4, 2)) {
            completed(sequencer.submit(buy("B1", 1)));
            completed(sequencer.submit(sell("S1", 1)));
            assertRejected(completed(sequencer.submit(buy("B2", 1))),
                    3, RejectionCode.ORDER_ID_HISTORY_CAPACITY_EXCEEDED);
            assertThat(sequencer.inspect(2).get().acceptedOrderIds()).isEqualTo(2);
        }
    }

    @Test
    void boundedInboxRejectsWithoutSequenceAndCanRecover() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstOnly = new AtomicBoolean(true);
        try (var sequencer = new SingleWriterSequencer(INSTRUMENT, 1, 10, 10, command -> {
            if (firstOnly.compareAndSet(true, false)) {
                entered.countDown();
                awaitLatch(release);
            }
        })) {
            var first = sequencer.submit(buy("B1", 1));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                var second = sequencer.submit(buy("B2", 1));
                assertThat(sequencer.queuedWorkCount()).isEqualTo(1);
                assertThatThrownBy(() -> sequencer.submit(buy("B3", 1)))
                        .isInstanceOf(OrderValidationException.class)
                        .satisfies(error -> assertThat(((OrderValidationException) error).code())
                                .isEqualTo(RejectionCode.INGRESS_OVERLOADED));
                assertThatThrownBy(() -> sequencer.inspect(1))
                        .isInstanceOf(OrderValidationException.class)
                        .satisfies(error -> assertThat(((OrderValidationException) error).code())
                                .isEqualTo(RejectionCode.INGRESS_OVERLOADED));
                assertThat(sequencer.lastAdmittedSequence()).isEqualTo(2);
                release.countDown();
                assertThat(completed(first).successful()).isTrue();
                assertThat(completed(second).successful()).isTrue();
                assertThat(completed(sequencer.submit(buy("B3", 1))).sequence()).isEqualTo(3);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void stopAcceptingDrainsAdmittedWorkBeforeTermination() throws Exception {
        var sequencer = new SingleWriterSequencer(INSTRUMENT);
        try {
            var first = sequencer.submit(buy("B1", 2));
            var second = sequencer.submit(buy("B2", 3));
            sequencer.stopAccepting();
            assertThat(sequencer.accepting()).isFalse();
            assertThatThrownBy(() -> sequencer.submit(buy("B3", 1)))
                    .isInstanceOf(OrderValidationException.class)
                    .satisfies(error -> assertThat(((OrderValidationException) error).code())
                        .isEqualTo(RejectionCode.INGRESS_CLOSED));
            assertThat(completed(first).successful()).isTrue();
            assertThat(completed(second).successful()).isTrue();
            assertThat(sequencer.awaitTermination(Duration.ofSeconds(5))).isTrue();
            assertThat(sequencer.lastProcessedSequence()).isEqualTo(2);
            assertThat(sequencer.fatalFailure()).isEmpty();
        } finally {
            sequencer.close();
        }
    }

    @Test
    void closeIsIdempotentAndRejectsFurtherReads() {
        var sequencer = new SingleWriterSequencer(INSTRUMENT);
        sequencer.close();
        sequencer.close();
        assertThatThrownBy(() -> sequencer.inspect(2))
                .isInstanceOf(OrderValidationException.class)
                .satisfies(error -> assertThat(((OrderValidationException) error).code())
                        .isEqualTo(RejectionCode.INGRESS_CLOSED));
    }

    @Test
    void unexpectedFailureFailsPendingWorkAndStopsRatherThanContinuing() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new IllegalStateException("simulated unexpected writer failure");
        try (var sequencer = new SingleWriterSequencer(INSTRUMENT, 2, 10, 10, ignored -> {
            entered.countDown();
            awaitLatch(release);
            throw failure;
        })) {
            var first = sequencer.submit(buy("B1", 1));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                var queued = sequencer.submit(buy("B2", 1));
                var read = sequencer.inspect(1);
                release.countDown();
                assertFailure(first.completion(), failure);
                assertFailure(queued.completion(), failure);
                assertFailure(read, failure);
                assertThat(sequencer.awaitTermination(Duration.ofSeconds(5))).isTrue();
                assertThat(sequencer.fatalFailure().orElseThrow()).isSameAs(failure);
                assertThatThrownBy(() -> sequencer.submit(buy("B3", 1)))
                        .isInstanceOf(OrderValidationException.class)
                        .satisfies(error -> assertThat(((OrderValidationException) error).code())
                        .isEqualTo(RejectionCode.INGRESS_CLOSED));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void rejectsInvalidCapacityConfigurationWithoutStartingWorker() {
        assertThatThrownBy(() -> new SingleWriterSequencer(INSTRUMENT, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SingleWriterSequencer(INSTRUMENT,
                SingleWriterSequencer.MAX_QUEUE_CAPACITY + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SingleWriterSequencer(INSTRUMENT, 2, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SingleWriterSequencer(INSTRUMENT, 2, 10, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readValidationDoesNotConsumeSequences() throws Exception {
        try (var sequencer = new SingleWriterSequencer(INSTRUMENT)) {
            assertThatThrownBy(() -> sequencer.inspect(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> sequencer.inspect(LimitOrderBook.MAX_DEPTH_LEVELS + 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> sequencer.find(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> sequencer.ordersAt(null, PRICE)).isInstanceOf(NullPointerException.class);
            assertThat(sequencer.inspect(1).get().lastProcessedSequence()).isZero();
            assertThat(sequencer.lastAdmittedSequence()).isZero();
        }
    }

    private static void assertRejected(SequencedResult result, long seq, RejectionCode code) {
        assertThat(result.sequence()).isEqualTo(seq);
        assertThat(result.successful()).isFalse();
        assertThat(((CommandDecision.Rejected) result.decision()).rejection().code()).isEqualTo(code);
    }

    private static void assertFailure(java.util.concurrent.CompletionStage<?> future,
                                      Throwable expected) {
        assertThatThrownBy(() -> future.toCompletableFuture().get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCause(expected);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out awaiting test-controlled gate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test-controlled gate interrupted", interrupted);
        }
    }
}
