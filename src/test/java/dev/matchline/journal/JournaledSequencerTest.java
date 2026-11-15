package dev.matchline.journal;

import dev.matchline.book.LimitOrderBook;
import dev.matchline.domain.CancelOrder;
import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.RejectionCode;
import dev.matchline.domain.Side;
import dev.matchline.sequencer.CommandDecision;
import dev.matchline.sequencer.CommandTicket;
import dev.matchline.sequencer.SequencedResult;
import dev.matchline.sequencer.SingleWriterSequencer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournaledSequencerTest {
    private static final InstrumentId DEMO = new InstrumentId("DEMO");
    @TempDir Path temp;

    private static PlaceLimitOrder order(String id, Side side, int price, int qty) {
        return new PlaceLimitOrder(DEMO, new OrderId(id), side,
                new PriceTicks(price), new OrderQuantity(qty));
    }

    private Path journalFile() { return temp.resolve("sequencer.mlj"); }

    private static SequencedResult await(CommandTicket ticket) throws Exception {
        return ticket.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void journaledSequencerPersistsPriceTimeTradesCancelsAndRejections() throws Exception {
        List<SequencedResult> observed = new ArrayList<>();
        try (var journal = FileCommandJournal.createNew(journalFile(), DEMO, DurabilityPolicy.SYNC_EACH_RECORD);
             var sequencer = new SingleWriterSequencer(DEMO, 8, 20, 20, journal)) {
            assertThat(sequencer.journalDurabilityPolicy()).contains(DurabilityPolicy.SYNC_EACH_RECORD);
            observed.add(await(sequencer.submit(order("B1", Side.BUY, 100, 4))));
            observed.add(await(sequencer.submit(order("B2", Side.BUY, 100, 2))));
            observed.add(await(sequencer.submit(order("S1", Side.SELL, 99, 5))));
            observed.add(await(sequencer.submit(new CancelOrder(DEMO, new OrderId("B2")))));
            observed.add(await(sequencer.submit(order("B1", Side.BUY, 100, 1))));
            assertThat(observed.stream().map(SequencedResult::sequence)).containsExactly(1L, 2L, 3L, 4L, 5L);
            assertThat(((CommandDecision.Placed) observed.get(2).decision()).result().trades())
                    .extracting(trade -> trade.makerOrderId().value())
                    .containsExactly("B1", "B2");
            assertThat(observed.get(4).decision()).isInstanceOf(CommandDecision.Rejected.class);
            assertThat(((CommandDecision.Rejected) observed.get(4).decision()).rejection().code())
                    .isEqualTo(RejectionCode.DUPLICATE_ORDER_ID);
        }
        JournalScan scan = FileCommandJournal.scan(journalFile(), TailPolicy.STRICT);
        assertThat(scan.completedDecisionCount()).isEqualTo(5);
        assertThat(scan.entries()).hasSize(10);
        assertThat(scan.pendingCommandSequence()).isZero();
        for (int i = 0; i < observed.size(); i++) {
            assertThat(scan.entries().get(2 * i)).isEqualTo(new JournalEntry.Command(i + 1, observed.get(i).command()));
            assertThat(scan.entries().get(2 * i + 1)).isEqualTo(new JournalEntry.Decision(i + 1, observed.get(i).decision()));
        }
    }

    @Test
    void cancelledOrderDetailsArePersistedNotJustCancelId() throws Exception {
        try (var journal = FileCommandJournal.createNew(journalFile(), DEMO, DurabilityPolicy.SYNC_EACH_RECORD);
             var sequencer = new SingleWriterSequencer(DEMO, 8, 100, 100, journal)) {
            await(sequencer.submit(order("B1", Side.BUY, 101, 8)));
            await(sequencer.submit(order("S1", Side.SELL, 101, 3)));
            var cancel = await(sequencer.submit(new CancelOrder(DEMO, new OrderId("B1"))));
            assertThat(((CommandDecision.Cancelled) cancel.decision()).result().cancelledQuantity()).isEqualTo(5);
        }
        var decision = (CommandDecision.Cancelled) ((JournalEntry.Decision) FileCommandJournal.scan(
                journalFile(), TailPolicy.STRICT).entries().get(5)).decision();
        assertThat(decision.result().cancelledQuantity()).isEqualTo(5);
        assertThat(decision.result().cancelledOrder().price().value()).isEqualTo(101);
        assertThat(decision.result().cancelledOrder().insertionOrdinal()).isEqualTo(1);
    }

    @Test
    void everyCompletedSyncFutureHasItsOutcomeFrameOnDisk() throws Exception {
        var journal = FileCommandJournal.createNew(journalFile(), DEMO, DurabilityPolicy.SYNC_EACH_RECORD);
        try (var sequencer = new SingleWriterSequencer(DEMO, 8, 100, 100, journal)) {
            for (int i = 1; i <= 30; i++) {
                assertThat(await(sequencer.submit(order("B" + i, Side.BUY, 100, 1))).sequence()).isEqualTo(i);
            }
            // Read-only scanner must be used after close, but physical length already grew.
            assertThat(java.nio.file.Files.size(journalFile())).isGreaterThan(23);
        }
        JournalScan scan = FileCommandJournal.scan(journalFile(), TailPolicy.STRICT);
        assertThat(scan.completedDecisionCount()).isEqualTo(30);
        assertThat(scan.entries()).hasSize(60);
    }

    @Test
    void existingJournalIsNotSilentlyTreatedAsRecoveredBook() throws Exception {
        try (var journal = FileCommandJournal.createNew(journalFile(), DEMO, DurabilityPolicy.OS_BUFFERED)) {
            var place = order("B1", Side.BUY, 100, 1);
            journal.appendCommand(1, place);
            assertThatThrownBy(() -> new SingleWriterSequencer(DEMO, 4, 10, 10, journal))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fresh");
        }
    }

    @Test
    void wrongInstrumentJournalIsRejectedBeforeStartingWriter() throws Exception {
        try (var journal = FileCommandJournal.createNew(journalFile(), new InstrumentId("OTHER"),
                DurabilityPolicy.OS_BUFFERED)) {
            assertThatThrownBy(() -> new SingleWriterSequencer(DEMO, 4, 10, 10, journal))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void closedJournalFailsStopWithoutMutatingBook() throws Exception {
        var journal = FileCommandJournal.createNew(journalFile(), DEMO, DurabilityPolicy.SYNC_EACH_RECORD);
        var sequencer = new SingleWriterSequencer(DEMO, 8, 10, 10, journal);
        journal.close(); // Simulates disk sink becoming unavailable before append.
        try {
            var ticket = sequencer.submit(order("B1", Side.BUY, 100, 1));
            assertThatThrownBy(() -> ticket.completion().toCompletableFuture().get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IOException.class);
            assertThat(sequencer.fatalFailure()).isPresent();
            assertThat(sequencer.lastProcessedSequence()).isZero();
            assertThat(sequencer.accepting()).isFalse();
        } finally {
            sequencer.close();
        }
    }

    @Test
    void concurrentSubmissionsRecordGaplessCommandDecisionPairs() throws Exception {
        var journal = FileCommandJournal.createNew(journalFile(), DEMO, DurabilityPolicy.OS_BUFFERED);
        List<CommandTicket> tickets = new ArrayList<>();
        try (var sequencer = new SingleWriterSequencer(DEMO, 512, 512, 512, journal);
             var executor = Executors.newFixedThreadPool(8)) {
            List<CompletableFuture<CommandTicket>> submissions = new ArrayList<>();
            for (int i = 1; i <= 160; i++) {
                int n = i;
                submissions.add(CompletableFuture.supplyAsync(() -> sequencer.submit(
                        order("B" + n, Side.BUY, 100, 1)), executor));
            }
            for (var submitted : submissions) tickets.add(submitted.get(10, TimeUnit.SECONDS));
            for (var ticket : tickets) assertThat(await(ticket).successful()).isTrue();
        }
        tickets.sort(Comparator.comparingLong(CommandTicket::sequence));
        JournalScan scan = FileCommandJournal.scan(journalFile(), TailPolicy.STRICT);
        assertThat(scan.entries()).hasSize(320);
        assertThat(scan.completedDecisionCount()).isEqualTo(160);
        for (int i = 0; i < tickets.size(); i++) {
            assertThat(tickets.get(i).sequence()).isEqualTo(i + 1L);
            assertThat(scan.entries().get(2 * i).sequence()).isEqualTo(i + 1L);
            assertThat(scan.entries().get(2 * i + 1).sequence()).isEqualTo(i + 1L);
        }
    }

    @Test
    void onlyOneSequencerCanClaimTheSameFreshJournal() throws Exception {
        var journal = FileCommandJournal.createNew(journalFile(), DEMO, DurabilityPolicy.OS_BUFFERED);
        try (var sequencer = new SingleWriterSequencer(DEMO, 8, 10, 10, journal)) {
            assertThatThrownBy(() -> new SingleWriterSequencer(DEMO, 8, 10, 10, journal))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unclaimed");
            assertThat(await(sequencer.submit(order("B1", Side.BUY, 100, 1))).successful()).isTrue();
        }
    }

    @Test
    void repairRefusesToRaceAnOpenWriter() throws Exception {
        try (var journal = FileCommandJournal.createNew(journalFile(), DEMO, DurabilityPolicy.OS_BUFFERED)) {
            assertThatThrownBy(() -> FileCommandJournal.scan(journalFile(), TailPolicy.TRUNCATE_INCOMPLETE_TAIL))
                    .isInstanceOf(IOException.class).hasMessageContaining("writer holds lock");
        }
    }

    @Test
    void volatileEngineAndBufferedJournalAreExplicitlyDifferentFromSyncJournal() throws Exception {
        try (var volatileEngine = new SingleWriterSequencer(DEMO)) {
            assertThat(volatileEngine.journalDurabilityPolicy()).isEmpty();
        }
        var journal = FileCommandJournal.createNew(journalFile(), DEMO, DurabilityPolicy.OS_BUFFERED);
        try (var sequencer = new SingleWriterSequencer(DEMO, 4,
                LimitOrderBook.DEFAULT_MAX_RESTING_ORDERS, LimitOrderBook.DEFAULT_MAX_ORDER_IDS, journal)) {
            assertThat(sequencer.journalDurabilityPolicy()).contains(DurabilityPolicy.OS_BUFFERED);
            assertThat(await(sequencer.submit(order("B1", Side.BUY, 100, 1))).successful()).isTrue();
        }
        assertThat(FileCommandJournal.scan(journalFile(), TailPolicy.STRICT).completedDecisionCount()).isEqualTo(1);
        // This file happens to be readable after a normal close, not proof of crash durability.
    }
}
