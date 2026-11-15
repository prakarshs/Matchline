package dev.matchline.bootstrap;

import dev.matchline.book.BookSnapshot;
import dev.matchline.domain.CancelOrder;
import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.Side;
import dev.matchline.journal.DurabilityPolicy;
import dev.matchline.journal.FileCommandJournal;
import dev.matchline.recovery.SnapshotSchedule;
import dev.matchline.sequencer.RecoveredSequencer;
import dev.matchline.sequencer.SingleWriterSequencer;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;

/** A deterministic one-shot recovery exercise, NOT a crash/durability benchmark. */
final class RecoveryDrill {
    private RecoveryDrill() { }

    static int run(PrintStream out, PrintStream err) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("matchline-recovery-drill-");
            Path journalFile = directory.resolve("demo.mlj");
            var schedule = new SnapshotSchedule(directory.resolve("demo.mls"), 4);
            var symbol = new InstrumentId("DEMO");
            BookSnapshot original;
            try (var journal = FileCommandJournal.createNew(journalFile, symbol, DurabilityPolicy.OS_BUFFERED);
                 var writer = new SingleWriterSequencer(symbol, 32, 50, 100, journal, schedule)) {
                submit(writer, order(symbol, "S1", Side.SELL, 100, 6));
                submit(writer, order(symbol, "S2", Side.SELL, 100, 4));
                submit(writer, order(symbol, "B1", Side.BUY, 101, 8));
                submit(writer, new CancelOrder(symbol, new OrderId("S2")));
                submit(writer, order(symbol, "B2", Side.BUY, 99, 3));
                submit(writer, order(symbol, "S3", Side.SELL, 99, 2));
                original = writer.snapshotState().join();
            }
            try (RecoveredSequencer resumed = SingleWriterSequencer.recover(journalFile, symbol,
                    32, 50, 100, DurabilityPolicy.OS_BUFFERED, schedule)) {
                if (!Objects.equals(original, resumed.sequencer().snapshotState().join())
                        || resumed.report().snapshotSequence() != 4
                        || resumed.report().replayedDecisions() != 2) {
                    throw new IllegalStateException("snapshot-plus-suffix replay differs from expected state");
                }
            }
            try (RecoveredSequencer full = SingleWriterSequencer.recover(journalFile, symbol,
                    32, 50, 100, DurabilityPolicy.OS_BUFFERED, null)) {
                if (!original.equals(full.sequencer().snapshotState().join())
                        || full.report().replayedDecisions() != 6) {
                    throw new IllegalStateException("full replay differs from expected state");
                }
            }
            out.println("MATCHLINE RECOVERY OK | commands=6 snapshot=4 suffix=2 full=6 state_equal=true");
            out.println("This is a normal-close replay demonstration; no abrupt crash or fsync guarantee was tested.");
            return 0;
        } catch (Exception failure) {
            err.println("MATCHLINE RECOVERY FAILED: " + failure.getMessage());
            return 1;
        } finally {
            if (directory != null) {
                try (var contents = Files.walk(directory)) {
                    for (Path file : contents.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(file);
                    }
                } catch (Exception cleanupFailure) {
                    err.println("Recovery demo temporary-file cleanup failed: " + cleanupFailure.getMessage());
                }
            }
        }
    }

    private static PlaceLimitOrder order(InstrumentId instrument, String id, Side side,
                                          long price, long quantity) {
        return new PlaceLimitOrder(instrument, new OrderId(id), side,
                new PriceTicks(price), new OrderQuantity(quantity));
    }

    private static void submit(SingleWriterSequencer writer, dev.matchline.domain.OrderCommand command) {
        writer.submit(command).completion().toCompletableFuture().join();
    }
}
