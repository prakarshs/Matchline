package dev.matchline.sequencer;

import dev.matchline.domain.CancelOrder;
import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.RejectionCode;
import dev.matchline.domain.Side;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SingleWriterSequencerConcurrencyTest {
    private static final InstrumentId INSTRUMENT = new InstrumentId("DEMO");
    private static final PriceTicks PRICE = new PriceTicks(100);

    private static PlaceLimitOrder buy(String id, long qty) {
        return new PlaceLimitOrder(INSTRUMENT, new OrderId(id), Side.BUY, PRICE,
                new OrderQuantity(qty));
    }

    private static PlaceLimitOrder sell(String id, long qty) {
        return new PlaceLimitOrder(INSTRUMENT, new OrderId(id), Side.SELL, PRICE,
                new OrderQuantity(qty));
    }

    @Test
    void concurrentProducerLinearizationMatchesFifoAndOnlyOneThreadMutates() throws Exception {
        int producers = 12;
        int perProducer = 100;
        int total = producers * perProducer;
        Set<Thread> writerThreads = ConcurrentHashMap.newKeySet();
        try (var sequencer = new SingleWriterSequencer(INSTRUMENT, 2048, 1500, 1500,
                ignored -> writerThreads.add(Thread.currentThread()))) {
            ExecutorService pool = Executors.newFixedThreadPool(producers);
            try {
                var ready = new CountDownLatch(producers);
                var start = new CountDownLatch(1);
                List<Future<List<CommandTicket>>> batches = new ArrayList<>();
                for (int i = 0; i < producers; i++) {
                    int producer = i;
                    batches.add(pool.submit(() -> {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("producer start gate timeout");
                        }
                        List<CommandTicket> tickets = new ArrayList<>();
                        for (int j = 0; j < perProducer; j++) {
                            tickets.add(sequencer.submit(buy("P" + producer + "N" + j, 1)));
                        }
                        return tickets;
                    }));
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                List<CommandTicket> ordered = new ArrayList<>();
                for (Future<List<CommandTicket>> batch : batches) {
                    ordered.addAll(batch.get(10, TimeUnit.SECONDS));
                }
                ordered.sort(Comparator.comparingLong(CommandTicket::sequence));
                assertThat(ordered).hasSize(total);
                for (int i = 0; i < total; i++) {
                    assertThat(ordered.get(i).sequence()).isEqualTo(i + 1L);
                    var result = ordered.get(i).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    assertThat(result.sequence()).isEqualTo(i + 1L);
                    assertThat(result.successful()).isTrue();
                }
                var observed = sequencer.ordersAt(Side.BUY, PRICE).get(10, TimeUnit.SECONDS);
                assertThat(observed).hasSize(total);
                for (int i = 0; i < total; i++) {
                    assertThat(observed.get(i).orderId()).isEqualTo(ordered.get(i).completion().toCompletableFuture().join().command().orderId());
                }
                assertThat(writerThreads).hasSize(1);
                var snapshot = sequencer.inspect(2).get(10, TimeUnit.SECONDS);
                assertThat(snapshot.lastProcessedSequence()).isEqualTo(total);
                assertThat(snapshot.restingOrders()).isEqualTo(total);
                assertThat(snapshot.bids()).singleElement().satisfies(depth -> {
                    assertThat(depth.totalQuantity()).isEqualTo(total);
                    assertThat(depth.orderCount()).isEqualTo(total);
                });
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void simultaneousCancelAndFillAreResolvedOnlyByAdmissionSequence() throws Exception {
        for (int iteration = 0; iteration < 50; iteration++) {
            try (var sequencer = new SingleWriterSequencer(INSTRUMENT, 8)) {
                assertThat(sequencer.submit(buy("B1", 3)).completion().toCompletableFuture().get().successful()).isTrue();
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    var start = new CountDownLatch(1);
                    Callable<CommandTicket> cancel = () -> {
                        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("cancel gate");
                        return sequencer.submit(new CancelOrder(INSTRUMENT, new OrderId("B1")));
                    };
                    Callable<CommandTicket> fill = () -> {
                        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("fill gate");
                        return sequencer.submit(sell("S1", 3));
                    };
                    var cancelTicket = pool.submit(cancel);
                    var fillTicket = pool.submit(fill);
                    start.countDown();
                    CommandTicket c = cancelTicket.get(5, TimeUnit.SECONDS);
                    CommandTicket f = fillTicket.get(5, TimeUnit.SECONDS);
                    assertThat(Set.of(c.sequence(), f.sequence())).containsExactlyInAnyOrder(2L, 3L);
                    var cancelResult = c.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    var fillResult = f.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    var tradeResult = ((CommandDecision.Placed) fillResult.decision()).result();
                    if (c.sequence() < f.sequence()) {
                        assertThat(cancelResult.decision()).isInstanceOf(CommandDecision.Cancelled.class);
                        assertThat(tradeResult.trades()).isEmpty();
                        assertThat(tradeResult.restingQuantity()).isEqualTo(3);
                        assertThat(sequencer.find(new OrderId("S1")).get()).isPresent();
                    } else {
                        assertThat(((CommandDecision.Rejected) cancelResult.decision())
                                .rejection().code()).isEqualTo(RejectionCode.ORDER_NOT_FOUND);
                        assertThat(tradeResult.trades()).singleElement()
                                .satisfies(trade -> assertThat(trade.quantity()).isEqualTo(3));
                        assertThat(sequencer.find(new OrderId("S1")).get()).isEmpty();
                    }
                    assertThat(sequencer.find(new OrderId("B1")).get()).isEmpty();
                    assertThat(sequencer.lastProcessedSequence()).isEqualTo(3);
                } finally {
                    pool.shutdownNow();
                }
            }
        }
    }

    @Test
    void concurrentDuplicateIdSubmissionsHaveOneWinnerAndNoLostSequences() throws Exception {
        int contenders = 40;
        try (var sequencer = new SingleWriterSequencer(INSTRUMENT, 64)) {
            var pool = Executors.newFixedThreadPool(8);
            try {
                var tickets = new ArrayList<Future<CommandTicket>>();
                var counter = new AtomicInteger();
                for (int i = 0; i < contenders; i++) {
                    tickets.add(pool.submit(() -> {
                        counter.incrementAndGet();
                        return sequencer.submit(buy("B1", 1));
                    }));
                }
                assertThat(counter.get()).isBetween(0, contenders);
                var sorted = new ArrayList<CommandTicket>();
                for (var future : tickets) sorted.add(future.get(5, TimeUnit.SECONDS));
                sorted.sort(Comparator.comparingLong(CommandTicket::sequence));
                int placed = 0;
                for (int i = 0; i < contenders; i++) {
                    assertThat(sorted.get(i).sequence()).isEqualTo(i + 1L);
                    var decision = sorted.get(i).completion().toCompletableFuture().get(5, TimeUnit.SECONDS).decision();
                    if (decision instanceof CommandDecision.Placed) {
                        placed++;
                    } else {
                        assertThat(((CommandDecision.Rejected) decision).rejection().code())
                                .isEqualTo(RejectionCode.DUPLICATE_ORDER_ID);
                    }
                }
                assertThat(placed).isEqualTo(1);
                assertThat(sequencer.inspect(1).get().restingOrders()).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
