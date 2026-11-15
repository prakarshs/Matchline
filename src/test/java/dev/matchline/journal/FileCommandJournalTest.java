package dev.matchline.journal;

import dev.matchline.book.CancelResult;
import dev.matchline.book.MatchResult;
import dev.matchline.domain.CancelOrder;
import dev.matchline.domain.CommandRejection;
import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.RejectionCode;
import dev.matchline.domain.Side;
import dev.matchline.sequencer.CommandDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileCommandJournalTest {
    private static final InstrumentId DEMO = new InstrumentId("DEMO");
    @TempDir
    Path temp;

    private static PlaceLimitOrder buy(String id, int qty) {
        return new PlaceLimitOrder(DEMO, new OrderId(id), Side.BUY, new PriceTicks(100), new OrderQuantity(qty));
    }

    private Path file() {
        return temp.resolve("orders.mlj");
    }

    @Test
    void emptyFileContainsVersionedChecksummedHeader() throws Exception {
        try (var journal = FileCommandJournal.createNew(file(), DEMO, DurabilityPolicy.SYNC_EACH_RECORD)) {
            assertThat(journal.fresh()).isTrue();
        }
        JournalScan scan = FileCommandJournal.scan(file(), TailPolicy.STRICT);
        assertThat(scan.instrument()).isEqualTo(DEMO);
        assertThat(scan.entries()).isEmpty();
        assertThat(scan.completedDecisionCount()).isZero();
        assertThat(scan.truncatedBytes()).isZero();
        assertThat(scan.hasUnacknowledgedCommand()).isFalse();
    }

    @Test
    void commandAndOutcomeSurviveClosedJournalWithExactData() throws Exception {
        var command = buy("B1", 2);
        var decision = new CommandDecision.Placed(new MatchResult(command, 0, 2, List.of()));
        try (var journal = FileCommandJournal.createNew(file(), DEMO, DurabilityPolicy.SYNC_EACH_RECORD)) {
            journal.appendCommand(1, command);
            journal.appendDecision(1, decision);
        }
        JournalScan scan = FileCommandJournal.scan(file(), TailPolicy.STRICT);
        assertThat(scan.entries()).containsExactly(new JournalEntry.Command(1, command), new JournalEntry.Decision(1, decision));
        assertThat(scan.completedDecisionCount()).isEqualTo(1);
        assertThat(scan.pendingCommandSequence()).isZero();
    }

    @Test
    void rejectedBusinessOutcomeIsRecordedWithStableCodeAndMessage() throws Exception {
        var command = new CancelOrder(DEMO, new OrderId("MISSING"));
        var decision = new CommandDecision.Rejected(new CommandRejection(RejectionCode.ORDER_NOT_FOUND, "no resting order"));
        try (var journal = FileCommandJournal.createNew(file(), DEMO, DurabilityPolicy.SYNC_EACH_RECORD)) {
            journal.appendCommand(1, command);
            journal.appendDecision(1, decision);
        }
        assertThat(FileCommandJournal.scan(file(), TailPolicy.STRICT).entries()).containsExactly(new JournalEntry.Command(1, command), new JournalEntry.Decision(1, decision));
    }

    @Test
    void incompleteCommandIsVisibleWithoutInventingAnOutcome() throws Exception {
        try (var journal = FileCommandJournal.createNew(file(), DEMO, DurabilityPolicy.SYNC_EACH_RECORD)) {
            journal.appendCommand(1, buy("B1", 4));
        }
        JournalScan scan = FileCommandJournal.scan(file(), TailPolicy.STRICT);
        assertThat(scan.entries()).hasSize(1);
        assertThat(scan.pendingCommandSequence()).isEqualTo(1);
        assertThat(scan.completedDecisionCount()).isZero();
        assertThat(scan.hasUnacknowledgedCommand()).isTrue();
    }

    @Test
    void rejectsOutOfOrderWritesAndDoesNotAdvanceSequence() throws Exception {
        try (var journal = FileCommandJournal.createNew(file(), DEMO, DurabilityPolicy.OS_BUFFERED)) {
            assertThatThrownBy(() -> journal.appendCommand(2, buy("B2", 1))).isInstanceOf(IOException.class);
            journal.appendCommand(1, buy("B1", 1));
            assertThatThrownBy(() -> journal.appendCommand(2, buy("B2", 1))).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> journal.appendDecision(2, new CommandDecision.Rejected(new CommandRejection(RejectionCode.ORDER_NOT_FOUND, "x")))).isInstanceOf(IOException.class);
            journal.appendDecision(1, new CommandDecision.Placed(new MatchResult(buy("B1", 1), 0, 1, List.of())));
            assertThat(journal.fresh()).isFalse();
        }
    }

    @Test
    void refusesOverwriteOfExistingFile() throws Exception {
        try (var ignored = FileCommandJournal.createNew(file(), DEMO, DurabilityPolicy.OS_BUFFERED)) {
            assertThatThrownBy(() -> FileCommandJournal.createNew(file(), DEMO, DurabilityPolicy.OS_BUFFERED)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void shortFinalFrameRequiresExplicitOfflineRepair() throws Exception {
        createOneCompleteCommand();

        long fullSize = Files.size(file());

        try (FileChannel channel = FileChannel.open(
            file(), StandardOpenOption.WRITE)) {
            channel.position(fullSize);
            channel.write(ByteBuffer.wrap(new byte[]{0x01, 0x02}));
        }

        long corruptedSize = Files.size(file());

        // The test fixture must actually contain the torn tail.
        assertThat(corruptedSize).isEqualTo(fullSize + 2);

        // STRICT must reject corruption without changing the file.
        assertThatThrownBy(() ->
            FileCommandJournal.scan(file(), TailPolicy.STRICT))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("incomplete journal tail");

        assertThat(Files.size(file())).isEqualTo(corruptedSize);

        // Explicit offline repair may remove only the incomplete tail.
        JournalScan repaired = FileCommandJournal.scan(
            file(), TailPolicy.TRUNCATE_INCOMPLETE_TAIL);

        assertThat(repaired.truncatedBytes()).isEqualTo(2);
        assertThat(Files.size(file())).isEqualTo(fullSize);
        assertThat(repaired.entries()).hasSize(2);

        // The repaired journal must now pass strict validation.
        JournalScan verified = FileCommandJournal.scan(
            file(), TailPolicy.STRICT);

        assertThat(verified.truncatedBytes()).isZero();
        assertThat(verified.entries()).isEqualTo(repaired.entries());
    }

    @Test
    void truncatedFinalDecisionIsRemovedWhileCommandRemainsPending() throws Exception {
        var place = buy("B1", 3);
        try (var journal = FileCommandJournal.createNew(file(), DEMO, DurabilityPolicy.SYNC_EACH_RECORD)) {
            journal.appendCommand(1, place);
            journal.appendDecision(1, new CommandDecision.Placed(new MatchResult(place, 0, 3, List.of())));
        }
        long size = Files.size(file());
        try (FileChannel channel = FileChannel.open(file(), StandardOpenOption.WRITE)) {
            channel.truncate(size - 1);
        }
        assertThatThrownBy(() -> FileCommandJournal.scan(file(), TailPolicy.STRICT)).isInstanceOf(IOException.class);
        JournalScan repaired = FileCommandJournal.scan(file(), TailPolicy.TRUNCATE_INCOMPLETE_TAIL);
        assertThat(repaired.truncatedBytes()).isGreaterThan(1);
        assertThat(repaired.pendingCommandSequence()).isEqualTo(1);
        assertThat(repaired.entries()).containsExactly(new JournalEntry.Command(1, place));
        assertThat(FileCommandJournal.scan(file(), TailPolicy.STRICT).entries()).hasSize(1);
    }

    @Test
    void completeFrameWithWrongCrcIsNeverAutoDiscarded() throws Exception {
        createOneCompleteCommand();
        long size = Files.size(file());
        try (FileChannel channel = FileChannel.open(file(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer byteToCorrupt = ByteBuffer.allocate(1);
            channel.read(byteToCorrupt, size - 1); // last byte of CRC
            byteToCorrupt.flip();
            byteToCorrupt.put(0, (byte) (byteToCorrupt.get(0) ^ 0x55));
            channel.write(byteToCorrupt, size - 1);
        }
        assertThatThrownBy(() -> FileCommandJournal.scan(file(), TailPolicy.STRICT)).isInstanceOf(IOException.class).hasMessageContaining("CRC32C");
        assertThatThrownBy(() -> FileCommandJournal.scan(file(), TailPolicy.TRUNCATE_INCOMPLETE_TAIL)).isInstanceOf(IOException.class).hasMessageContaining("CRC32C");
        assertThat(Files.size(file())).isEqualTo(size);
    }

    @Test
    void unknownVersionCannotBeRepairedAsTail() throws Exception {
        try (var ignored = FileCommandJournal.createNew(file(), DEMO, DurabilityPolicy.OS_BUFFERED)) {
        }
        try (FileChannel channel = FileChannel.open(file(), StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[]{0x00, 0x02}), 4); // version 2
        }
        assertThatThrownBy(() -> FileCommandJournal.scan(file(), TailPolicy.TRUNCATE_INCOMPLETE_TAIL)).isInstanceOf(IOException.class).hasMessageContaining("unsupported journal format version");
    }

    @Test
    void headerChecksumDetectsBitFlips() throws Exception {
        try (var ignored = FileCommandJournal.createNew(file(), DEMO, DurabilityPolicy.OS_BUFFERED)) {
        }
        try (FileChannel channel = FileChannel.open(file(), StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[]{'X'}), 7);
        }
        assertThatThrownBy(() -> FileCommandJournal.scan(file(), TailPolicy.STRICT)).isInstanceOf(IOException.class).hasMessageContaining("checksum");
    }

    @Test
    void oversizedFrameLengthIsHardCorruption() throws Exception {
        createOneCompleteCommand();
        try (FileChannel channel = FileChannel.open(file(), StandardOpenOption.WRITE)) {
            channel.position(channel.size());
            ByteBuffer illegalLength = ByteBuffer.allocate(4).putInt(Integer.MAX_VALUE);
            illegalLength.flip();
            channel.write(illegalLength);
        }
        assertThatThrownBy(() -> FileCommandJournal.scan(file(), TailPolicy.TRUNCATE_INCOMPLETE_TAIL)).isInstanceOf(IOException.class).hasMessageContaining("invalid frame length");
    }

    private void createOneCompleteCommand() throws Exception {
        var place = buy("B1", 2);
        try (var journal = FileCommandJournal.createNew(file(), DEMO, DurabilityPolicy.SYNC_EACH_RECORD)) {
            journal.appendCommand(1, place);
            journal.appendDecision(1, new CommandDecision.Placed(new MatchResult(place, 0, 2, List.of())));
        }
    }
}
