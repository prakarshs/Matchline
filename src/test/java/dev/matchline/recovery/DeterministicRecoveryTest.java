package dev.matchline.recovery;

import dev.matchline.book.BookSnapshot;
import dev.matchline.book.MatchResult;
import dev.matchline.book.TradeExecution;
import dev.matchline.domain.CancelOrder;
import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.RejectionCode;
import dev.matchline.domain.Side;
import dev.matchline.journal.DurabilityPolicy;
import dev.matchline.journal.FileCommandJournal;
import dev.matchline.journal.JournalEntry;
import dev.matchline.journal.JournalScan;
import dev.matchline.journal.TailPolicy;
import dev.matchline.sequencer.CommandDecision;
import dev.matchline.sequencer.RecoveredSequencer;
import dev.matchline.sequencer.SingleWriterSequencer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class DeterministicRecoveryTest {
    private static final InstrumentId DEMO = new InstrumentId("DEMO");
    private static final int MAX_ORDERS = 40;
    private static final int MAX_IDS = 2000;
    @TempDir Path temp;

    private Path journal() { return temp.resolve("engine.mlj"); }
    private Path snapshot() { return temp.resolve("engine.mls"); }
    private SnapshotSchedule schedule(long every) { return new SnapshotSchedule(snapshot(), every); }
    private static PlaceLimitOrder place(String id, Side side, long price, long quantity) {
        return new PlaceLimitOrder(DEMO, new OrderId(id), side, new PriceTicks(price),
                new OrderQuantity(quantity));
    }
    private static CancelOrder cancel(String id) { return new CancelOrder(DEMO, new OrderId(id)); }
    private static void submit(SingleWriterSequencer writer, dev.matchline.domain.OrderCommand command) {
        writer.submit(command).completion().toCompletableFuture().join();
    }
    private static BookSnapshot state(SingleWriterSequencer writer) {
        return writer.snapshotState().join();
    }
    private SingleWriterSequencer fresh(SnapshotSchedule schedule) throws IOException {
        FileCommandJournal file = FileCommandJournal.createNew(journal(), DEMO, DurabilityPolicy.OS_BUFFERED);
        return schedule == null
                ? new SingleWriterSequencer(DEMO, 128, MAX_ORDERS, MAX_IDS, file)
                : new SingleWriterSequencer(DEMO, 128, MAX_ORDERS, MAX_IDS, file, schedule);
    }
    private RecoveredSequencer recover(SnapshotSchedule schedule) throws IOException {
        return SingleWriterSequencer.recover(journal(), DEMO, 128, MAX_ORDERS, MAX_IDS,
                DurabilityPolicy.OS_BUFFERED, schedule);
    }

    @Test
    void fullReplayAndSnapshotSuffixAgreeOnAllOrdersIdsAndOrdinals() throws Exception {
        BookSnapshot expected;
        try (SingleWriterSequencer writer = fresh(schedule(3))) {
            submit(writer, place("S1", Side.SELL, 100, 6));
            submit(writer, place("S2", Side.SELL, 100, 4));
            submit(writer, place("B1", Side.BUY, 101, 8)); // trades S1 6, S2 2
            submit(writer, cancel("S2"));
            submit(writer, place("B2", Side.BUY, 98, 5));
            submit(writer, place("B3", Side.BUY, 98, 3));
            submit(writer, cancel("NO_SUCH_ID")); // rejection
            submit(writer, place("B2", Side.BUY, 90, 2)); // duplicate rejection
            submit(writer, place("S3", Side.SELL, 98, 7)); // B2 5, B3 2
            submit(writer, place("B4", Side.BUY, 97, 2));
            expected = state(writer);
        }
        assertEquals(10, expected.lastSequence());
        assertEquals(7, expected.acceptedOrderIds().size());
        assertEquals(9, SnapshotStore.read(snapshot()).state().lastSequence());

        try (RecoveredSequencer resumed = recover(schedule(3))) {
            assertEquals(9, resumed.report().snapshotSequence());
            assertEquals(1, resumed.report().replayedDecisions());
            assertEquals(10, resumed.report().recoveredSequence());
            assertFalse(resumed.report().resolvedPendingCommand());
            assertEquals(expected, state(resumed.sequencer()));
            assertEquals(11, resumed.sequencer().submit(place("B5", Side.BUY, 97, 1)).sequence());
            assertEquals(11, state(resumed.sequencer()).lastSequence());
        }
        try (RecoveredSequencer fromBeginning = recover(null)) {
            assertEquals(0, fromBeginning.report().snapshotSequence());
            assertEquals(11, fromBeginning.report().replayedDecisions());
            assertEquals(11, state(fromBeginning.sequencer()).lastSequence());
        }
    }

    @Test
    void historicalTakerIdStillCannotBeReusedAfterSnapshotRestore() throws Exception {
        try (SingleWriterSequencer writer = fresh(schedule(2))) {
            submit(writer, place("SELL", Side.SELL, 100, 2));
            submit(writer, place("TAKER", Side.BUY, 101, 2));
            assertEquals(0, state(writer).restingOrders().size());
        }
        try (RecoveredSequencer resumed = recover(schedule(2))) {
            var result = resumed.sequencer().submit(place("TAKER", Side.BUY, 90, 1))
                    .completion().toCompletableFuture().join();
            CommandDecision.Rejected decision = assertInstanceOf(CommandDecision.Rejected.class, result.decision());
            assertEquals(RejectionCode.DUPLICATE_ORDER_ID, decision.rejection().code());
        }
    }

    @Test
    void completedJournalWithNoSnapshotRebuildsFromZero() throws Exception {
        BookSnapshot expected;
        try (SingleWriterSequencer writer = fresh(null)) {
            submit(writer, place("B1", Side.BUY, 100, 9));
            submit(writer, cancel("B1"));
            submit(writer, place("S1", Side.SELL, 101, 5));
            expected = state(writer);
        }
        try (RecoveredSequencer resumed = recover(null)) {
            assertEquals(expected, state(resumed.sequencer()));
            assertEquals(3, resumed.report().replayedDecisions());
            assertEquals(4, resumed.sequencer().submit(cancel("S1")).sequence());
        }
    }

    @Test
    void pendingCommandIsResolvedExactlyOnceBeforeNewAdmission() throws Exception {
        try (FileCommandJournal file = FileCommandJournal.createNew(journal(), DEMO,
                DurabilityPolicy.SYNC_EACH_RECORD)) {
            file.appendCommand(1, place("A", Side.BUY, 100, 3));
        }
        try (RecoveredSequencer resumed = recover(null)) {
            assertTrue(resumed.report().resolvedPendingCommand());
            assertEquals(1, resumed.report().recoveredSequence());
            assertEquals(0, resumed.report().replayedDecisions());
            assertEquals(1, state(resumed.sequencer()).restingOrders().size());
            assertEquals(2, resumed.sequencer().submit(place("B", Side.SELL, 100, 2)).sequence());
        }
        JournalScan scan = FileCommandJournal.scan(journal(), TailPolicy.STRICT);
        assertEquals(2, scan.completedDecisionCount());
        assertFalse(scan.hasUnacknowledgedCommand());
        assertEquals(4, scan.entries().size());
        try (RecoveredSequencer second = recover(null)) {
            assertFalse(second.report().resolvedPendingCommand());
            assertEquals(2, state(second.sequencer()).lastSequence());
            assertEquals(1, state(second.sequencer()).restingOrders().getFirst().remainingQuantity());
        }
    }

    @Test
    void pendingCancellationRemovesOnlyUnfilledRemainder() throws Exception {
        try (SingleWriterSequencer writer = fresh(null)) {
            submit(writer, place("SELL", Side.SELL, 100, 10));
            submit(writer, place("BUY", Side.BUY, 100, 4));
        }
        try (FileCommandJournal reopened = FileCommandJournal.openExisting(
                journal(), DEMO, DurabilityPolicy.SYNC_EACH_RECORD)) {
            reopened.appendCommand(3, cancel("SELL"));
        }
        try (RecoveredSequencer resumed = recover(null)) {
            assertTrue(resumed.report().resolvedPendingCommand());
            assertTrue(state(resumed.sequencer()).restingOrders().isEmpty());
            assertEquals(2, state(resumed.sequencer()).acceptedOrderIds().size());
        }
        var decision = (JournalEntry.Decision) FileCommandJournal.scan(journal(), TailPolicy.STRICT)
                .entries().getLast();
        var cancelled = assertInstanceOf(CommandDecision.Cancelled.class, decision.decision());
        assertEquals(6, cancelled.result().cancelledQuantity());
    }

    @Test
    void tornDecisionRequiresExplicitRepairAndThenResolvesPendingCommand() throws Exception {
        try (FileCommandJournal file = FileCommandJournal.createNew(journal(), DEMO,
                DurabilityPolicy.OS_BUFFERED)) {
            PlaceLimitOrder order = place("B1", Side.BUY, 90, 3);
            file.appendCommand(1, order);
            file.appendDecision(1, new CommandDecision.Placed(new MatchResult(order, 0, 3, List.of())));
        }
        try (FileChannel channel = FileChannel.open(journal(), StandardOpenOption.WRITE)) {
            channel.truncate(channel.size() - 1);
        }
        assertThrows(IOException.class, () -> recover(null));
        JournalScan repaired = FileCommandJournal.scan(journal(), TailPolicy.TRUNCATE_INCOMPLETE_TAIL);
        assertEquals(1, repaired.pendingCommandSequence());
        assertTrue(repaired.truncatedBytes() > 0);
        try (RecoveredSequencer resumed = recover(null)) {
            assertEquals(1, resumed.report().recoveredSequence());
            assertTrue(resumed.report().resolvedPendingCommand());
        }
        assertEquals(2, FileCommandJournal.scan(journal(), TailPolicy.STRICT).entries().size());
    }

    @Test
    void replayRejectsLogicallyInconsistentButCrcValidDecisions() throws Exception {
        PlaceLimitOrder order = place("B1", Side.BUY, 100, 3);
        try (FileCommandJournal file = FileCommandJournal.createNew(journal(), DEMO,
                DurabilityPolicy.OS_BUFFERED)) {
            file.appendCommand(1, order);
            // A syntactically valid trade against a maker that never rested.
            var fabricated = new TradeExecution(DEMO, new OrderId("MISSING"), order.orderId(),
                    Side.BUY, new PriceTicks(100), 3);
            file.appendDecision(1, new CommandDecision.Placed(new MatchResult(order, 3, 0,
                    List.of(fabricated))));
        }
        IOException failure = assertThrows(IOException.class, () -> recover(null));
        assertTrue(failure.getMessage().contains("replay decision differs"));
        assertEquals(1, FileCommandJournal.scan(journal(), TailPolicy.STRICT).completedDecisionCount());
    }

    @Test
    void snapshotWithWrongJournalPrefixIsNeverAccepted() throws Exception {
        try (SingleWriterSequencer writer = fresh(schedule(1))) {
            submit(writer, place("B1", Side.BUY, 100, 2));
        }
        Path other = temp.resolve("different.mlj");
        try (FileCommandJournal different = FileCommandJournal.createNew(other, DEMO, DurabilityPolicy.OS_BUFFERED)) {
            var order = place("A1", Side.BUY, 99, 2);
            different.appendCommand(1, order);
            different.appendDecision(1, new CommandDecision.Placed(new MatchResult(order, 0, 2, List.of())));
        }
        assertThrows(IOException.class, () -> SingleWriterSequencer.recover(other, DEMO, 32,
                MAX_ORDERS, MAX_IDS, DurabilityPolicy.OS_BUFFERED, schedule(1)));
        assertEquals(1, FileCommandJournal.scan(other, TailPolicy.STRICT).completedDecisionCount());
    }

    @Test
    void snapshotChecksumCorruptionStopsRecoveryInsteadOfDiscardingSnapshot() throws Exception {
        try (SingleWriterSequencer writer = fresh(schedule(1))) {
            submit(writer, place("B1", Side.BUY, 100, 2));
        }
        byte[] bytes = Files.readAllBytes(snapshot());
        bytes[22] ^= 0x22;
        Files.write(snapshot(), bytes);
        assertThrows(IOException.class, () -> recover(schedule(1)));
        assertEquals(1, FileCommandJournal.scan(journal(), TailPolicy.STRICT).completedDecisionCount());
    }

    @Test
    void recoveryRefusesDifferentSnapshotCapacities() throws Exception {
        try (SingleWriterSequencer writer = fresh(schedule(1))) {
            submit(writer, place("B1", Side.BUY, 100, 2));
        }
        assertThrows(IOException.class, () -> SingleWriterSequencer.recover(journal(), DEMO,
                128, MAX_ORDERS + 1, MAX_IDS, DurabilityPolicy.OS_BUFFERED, schedule(1)));
    }

    @Test
    void cannotRecoverWhileOriginalWriterOwnsJournal() throws Exception {
        try (SingleWriterSequencer writer = fresh(null)) {
            submit(writer, place("B1", Side.BUY, 100, 2));
            assertThrows(IOException.class, () -> recover(null));
            assertEquals(1, state(writer).lastSequence());
        }
    }

    @Test
    void emptyJournalRecoversAndNextSequenceIsOne() throws Exception {
        try (FileCommandJournal ignored = FileCommandJournal.createNew(journal(), DEMO,
                DurabilityPolicy.OS_BUFFERED)) { }
        try (RecoveredSequencer resumed = recover(null)) {
            assertEquals(0, resumed.report().recoveredSequence());
            assertEquals(1, resumed.sequencer().submit(place("B1", Side.BUY, 100, 2)).sequence());
        }
    }

    @Test
    void periodicSnapshotIsWriterConsistentAfterBusinessRejection() throws Exception {
        try (SingleWriterSequencer writer = fresh(schedule(2))) {
            submit(writer, place("B1", Side.BUY, 100, 3));
            submit(writer, place("B1", Side.BUY, 100, 3));
            assertEquals(2, SnapshotStore.read(snapshot()).state().lastSequence());
            assertEquals(1, SnapshotStore.read(snapshot()).state().acceptedOrderIds().size());
        }
        try (RecoveredSequencer resumed = recover(schedule(2))) {
            assertEquals(2, resumed.report().snapshotSequence());
            assertEquals(0, resumed.report().replayedDecisions());
            assertEquals(1, state(resumed.sequencer()).restingOrders().size());
        }
    }

    @Test
    void snapshotNeverPointsBeyondTruncatedJournal() throws Exception {
        try (SingleWriterSequencer writer = fresh(schedule(2))) {
            submit(writer, place("B1", Side.BUY, 100, 3));
            submit(writer, place("S1", Side.SELL, 101, 2));
        }
        // Truncate at the first completed decision, leaving a valid shorter prefix.
        JournalScan original = FileCommandJournal.scan(journal(), TailPolicy.STRICT);
        long first = JournalAnchors.atCompletedSequence(journal(), original, 1).offset();
        try (FileChannel channel = FileChannel.open(journal(), StandardOpenOption.WRITE)) {
            channel.truncate(first);
        }
        assertThrows(IOException.class, () -> recover(schedule(2)));
    }

    @Test
    void deterministicRandomCommandStreamMatchesSnapshotAndFullReplay() throws Exception {
        Random random = new Random(73219);
        BookSnapshot expected;
        try (SingleWriterSequencer writer = fresh(schedule(25))) {
            for (int i = 0; i < 350; i++) {
                String id = "ID" + i;
                if (i > 2 && i % 9 == 0) {
                    submit(writer, cancel("ID" + random.nextInt(i)));
                } else if (i > 2 && i % 17 == 0) {
                    submit(writer, place("ID" + random.nextInt(i), Side.BUY, 99, 2));
                } else {
                    submit(writer, place(id, random.nextBoolean() ? Side.BUY : Side.SELL,
                            95 + random.nextInt(12), 1 + random.nextInt(9)));
                }
            }
            expected = state(writer);
        }
        try (RecoveredSequencer snap = recover(schedule(25))) {
            assertEquals(350, snap.report().snapshotSequence());
            assertEquals(expected, state(snap.sequencer()));
        }
        try (RecoveredSequencer full = recover(null)) {
            assertEquals(350, full.report().replayedDecisions());
            assertEquals(expected, state(full.sequencer()));
        }
    }

    @Test
    void originalFifoPriorityIsPreservedBySnapshotNotRegenerated() throws Exception {
        try (SingleWriterSequencer writer = fresh(schedule(3))) {
            submit(writer, place("S1", Side.SELL, 100, 10));
            submit(writer, place("S2", Side.SELL, 100, 10));
            submit(writer, cancel("S1"));
        }
        try (RecoveredSequencer resumed = recover(schedule(3))) {
            var result = resumed.sequencer().submit(place("B1", Side.BUY, 101, 5))
                    .completion().toCompletableFuture().join();
            var executed = assertInstanceOf(CommandDecision.Placed.class, result.decision());
            assertEquals("S2", executed.result().trades().getFirst().makerOrderId().value());
            assertEquals(2, state(resumed.sequencer()).restingOrders().getFirst().insertionOrdinal());
        }
    }

    @Test
    void freshJournalRefusesToReplaceAnOldSnapshotFromAnotherEpoch() throws Exception {
        Files.write(snapshot(), new byte[]{7, 8, 9});
        try (FileCommandJournal file = FileCommandJournal.createNew(journal(), DEMO,
                DurabilityPolicy.OS_BUFFERED)) {
            assertThrows(IllegalArgumentException.class, () -> new SingleWriterSequencer(DEMO,
                    32, MAX_ORDERS, MAX_IDS, file, schedule(1)));
            assertEquals(3, Files.size(snapshot()));
        }
    }

    @Test
    void missingSnapshotDirectoryRejectedBeforeWriterStarts() throws Exception {
        try (FileCommandJournal file = FileCommandJournal.createNew(journal(), DEMO,
                DurabilityPolicy.OS_BUFFERED)) {
            var missing = new SnapshotSchedule(temp.resolve("missing").resolve("snapshot.mls"), 1);
            assertThrows(IllegalArgumentException.class, () -> new SingleWriterSequencer(DEMO,
                    32, MAX_ORDERS, MAX_IDS, file, missing));
        }
    }

    @Test
    void snapshotIoFailureStopsWriterAndFullJournalCanStillRecover() throws Exception {
        Path snapshotsFolder = temp.resolve("snapshots");
        Files.createDirectory(snapshotsFolder);
        SnapshotSchedule fragile = new SnapshotSchedule(snapshotsFolder.resolve("state.mls"), 2);
        try (SingleWriterSequencer writer = fresh(fragile)) {
            submit(writer, place("B1", Side.BUY, 100, 3));
            Files.delete(snapshotsFolder); // delete only after healthy writer was constructed
            var ticket = writer.submit(place("B2", Side.BUY, 99, 2));
            assertThrows(CompletionException.class, () -> ticket.completion().toCompletableFuture().join());
            assertFalse(writer.accepting());
            assertTrue(writer.fatalFailure().isPresent());
        }
        assertEquals(2, FileCommandJournal.scan(journal(), TailPolicy.STRICT).completedDecisionCount());
        try (RecoveredSequencer resumed = recover(null)) {
            assertEquals(2, state(resumed.sequencer()).restingOrders().size());
        }
    }

    @Test
    void snapshotPathCannotReplaceTheJournal() throws Exception {
        try (FileCommandJournal file = FileCommandJournal.createNew(journal(), DEMO,
                DurabilityPolicy.OS_BUFFERED)) {
            assertThrows(IllegalArgumentException.class, () -> new SingleWriterSequencer(DEMO,
                    32, MAX_ORDERS, MAX_IDS, file, new SnapshotSchedule(journal(), 1)));
        }
        try (RecoveredSequencer resumed = recover(null)) {
            assertEquals(0, resumed.report().recoveredSequence());
        }
    }
}
