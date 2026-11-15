package dev.matchline.recovery;

import dev.matchline.journal.JournalEntry;
import dev.matchline.journal.JournalScan;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Computes prefix hashes from actual bytes, never from a reserialized journal entry. */
public final class JournalAnchors {
    private static final int HEADER_BYTES = 23;

    private JournalAnchors() { }

    /** Caller must hold the writer lock or otherwise ensure no concurrent append/truncate. */
    public static JournalAnchor current(FileChannel channel, long completedSequence) throws IOException {
        if (completedSequence < 0 || channel.size() < HEADER_BYTES) {
            throw new IOException("invalid current journal checkpoint");
        }
        long offset = channel.size();
        return new JournalAnchor(completedSequence, offset, digest(channel, offset));
    }

    /** The scanned journal must be complete/validated and unchanged while this method runs. */
    public static JournalAnchor atCompletedSequence(Path path, JournalScan scan, long completedSequence)
            throws IOException {
        if (completedSequence < 0 || completedSequence > scan.completedDecisionCount()) {
            throw new IOException("snapshot sequence exceeds completed journal decisions");
        }
        int frames = Math.toIntExact(Math.multiplyExact(completedSequence, 2));
        if (frames > scan.entries().size()) throw new IOException("journal contains too few frames");
        if (frames > 0 && !(scan.entries().get(frames - 1) instanceof JournalEntry.Decision decision
                && decision.sequence() == completedSequence)) {
            throw new IOException("snapshot checkpoint is not a decision boundary");
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long offset = HEADER_BYTES;
            ByteBuffer length = ByteBuffer.allocate(4);
            for (int i = 0; i < frames; i++) {
                length.clear();
                readFully(channel, length, offset);
                length.flip();
                int payloadSize = length.getInt();
                if (payloadSize < 9 || payloadSize > 16 * 1024 * 1024) {
                    throw new IOException("invalid frame while locating journal checkpoint");
                }
                offset = Math.addExact(offset, 4L + payloadSize + 4L);
            }
            if (offset > scan.validBytes() || offset > channel.size()) {
                throw new IOException("snapshot offset exceeds scanned journal");
            }
            return new JournalAnchor(completedSequence, offset, digest(channel, offset));
        } catch (ArithmeticException overflow) {
            throw new IOException("journal checkpoint offset overflow", overflow);
        }
    }

    public static String digest(FileChannel channel, long byteCount) throws IOException {
        if (byteCount < HEADER_BYTES || byteCount > channel.size()) {
            throw new IOException("invalid journal prefix length");
        }
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java 21 requires SHA-256", impossible);
        }
        ByteBuffer chunk = ByteBuffer.allocate(64 * 1024);
        long position = 0;
        while (position < byteCount) {
            chunk.clear();
            chunk.limit((int) Math.min(chunk.capacity(), byteCount - position));
            int count = channel.read(chunk, position);
            if (count <= 0) throw new IOException("cannot read complete journal prefix");
            sha.update(chunk.array(), 0, count);
            position += count;
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static void readFully(FileChannel channel, ByteBuffer target, long offset) throws IOException {
        while (target.hasRemaining()) {
            int count = channel.read(target, offset);
            if (count <= 0) throw new IOException("journal shortened while seeking snapshot boundary");
            offset += count;
        }
    }
}
