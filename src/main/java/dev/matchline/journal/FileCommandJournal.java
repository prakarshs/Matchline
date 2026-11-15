package dev.matchline.journal;

import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderCommand;
import dev.matchline.sequencer.CommandDecision;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * One append-only v1 journal file for one instrument. Binary frame layout:
 * [payloadLength:i32 big endian][type:u8, sequence:i64, fields][CRC32C:i32].
 * Header: [magic:i32][version:u16][symbolLength:u8][symbol:12 ASCII padded][CRC32C:i32].
 * A full-length checksum mismatch is corruption, never an automatically discarded tail.
 *
 * <p>Writer keeps an exclusive OS file lock. The caller transfers ownership to
 * SingleWriterSequencer, which closes it after draining. The offline scanner
 * must run only after the writer has exited (no concurrent repair or reading).
 */
public final class FileCommandJournal implements AutoCloseable {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_FRAME_PAYLOAD = 16 * 1024 * 1024;
    private static final int MAGIC = 0x4D4C4A31; // MLJ1
    private static final int HEADER_BYTES = 4 + 2 + 1 + 12 + 4;
    private static final int MIN_FRAME_PAYLOAD = 9;

    private final FileChannel channel;
    private final FileLock lock;
    private final InstrumentId instrument;
    private final DurabilityPolicy policy;
    private long committedSequence;
    private long pendingSequence;
    private boolean closed;
    private boolean claimed;

    private FileCommandJournal(FileChannel channel, FileLock lock, InstrumentId instrument,
                               DurabilityPolicy policy) {
        this.channel = channel;
        this.lock = lock;
        this.instrument = instrument;
        this.policy = policy;
    }

    /** Exclusively create a new journal; never overwrite an existing file. */
    public static FileCommandJournal createNew(Path path, InstrumentId instrument,
                                                DurabilityPolicy policy) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(policy, "policy");
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("journal is already locked");
            try {
                writeFully(channel, ByteBuffer.wrap(header(instrument)));
                channel.force(true); // Header must survive before a command can be submitted.
                return new FileCommandJournal(channel, lock, instrument, policy);
            } catch (Throwable failure) {
                lock.release();
                throw failure;
            }
        } catch (Throwable failure) {
            channel.close();
            if (failure instanceof IOException io) throw io;
            throw new IOException("cannot create journal", failure);
        }
    }

    public InstrumentId instrument() {
        return instrument;
    }

    public DurabilityPolicy durabilityPolicy() {
        return policy;
    }

    /** Only a fresh journal can be attached before replay exists in Commit 08. */
    public synchronized boolean fresh() {
        return !closed && committedSequence == 0 && pendingSequence == 0;
    }

    /** One journal may be attached to at most one sequencer instance. */
    public synchronized void claimFresh(InstrumentId expectedInstrument) {
        Objects.requireNonNull(expectedInstrument, "expectedInstrument");
        if (claimed || !fresh() || !instrument.equals(expectedInstrument)) {
            throw new IllegalArgumentException("journal must be fresh, unclaimed and match sequencer instrument");
        }
        claimed = true;
    }

    /** Writer only: persist intent before it is allowed to change the order book. */
    public synchronized void appendCommand(long sequence, OrderCommand command) throws IOException {
        ensureOpen();
        if (pendingSequence != 0 || sequence != committedSequence + 1) {
            throw new IOException("command sequence gap or pending decision");
        }
        if (command == null) throw new IOException("command is null");
        append(new JournalEntry.Command(sequence, command));
        pendingSequence = sequence;
    }

    /** Writer only: persist the complete outcome after in-memory application. */
    public synchronized void appendDecision(long sequence, CommandDecision decision) throws IOException {
        ensureOpen();
        if (pendingSequence != sequence || sequence == 0) {
            throw new IOException("decision without matching pending command");
        }
        if (decision == null) throw new IOException("decision is null");
        append(new JournalEntry.Decision(sequence, decision));
        committedSequence = sequence;
        pendingSequence = 0;
    }

    private void append(JournalEntry entry) throws IOException {
        byte[] payload = JournalCodec.encode(entry);
        if (payload.length < MIN_FRAME_PAYLOAD || payload.length > MAX_FRAME_PAYLOAD) {
            throw new IOException("journal frame is outside size bounds");
        }
        ByteBuffer frame = ByteBuffer.allocate(4 + payload.length + 4);
        frame.putInt(payload.length).put(payload).putInt(crc(payload));
        frame.flip();
        writeFully(channel, frame);
        if (policy == DurabilityPolicy.SYNC_EACH_RECORD) {
            channel.force(true);
        }
    }

    /**
     * OFFLINE scan. Strict mode never modifies the file. Repair mode truncates
     * ONLY a short final length/payload/checksum; a complete corrupt record is fatal.
     */
    public static JournalScan scan(Path path, TailPolicy tailPolicy) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(tailPolicy, "tailPolicy");
        boolean repair = tailPolicy == TailPolicy.TRUNCATE_INCOMPLETE_TAIL;
        try (FileChannel channel = repair
                ? FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)
                : FileChannel.open(path, StandardOpenOption.READ)) {
            // Explicit repair must never race an active writer using the same file.
            FileLock repairLock = null;
            if (repair) {
                try {
                    repairLock = channel.tryLock();
                } catch (OverlappingFileLockException locked) {
                    throw new IOException("cannot repair journal while writer holds lock", locked);
                }
                if (repairLock == null) throw new IOException("cannot repair journal while writer holds lock");
            }
            try {
                if (channel.size() < HEADER_BYTES) throw new IOException("truncated journal header");
                ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
                readFully(channel, header);
                InstrumentId instrument = decodeHeader(header.array());
                List<JournalEntry> entries = new ArrayList<>();
                long completed = 0;
                long pending = 0;
                OrderCommand pendingCommand = null;
                long lastValid = HEADER_BYTES;
                long size = channel.size();
                long truncated = 0;
                while (lastValid < size) {
                    long remaining = size - lastValid;
                    if (remaining < 4) {
                        truncated = handleIncomplete(channel, repair, lastValid, size);
                        break;
                    }
                    ByteBuffer lengthBuf = ByteBuffer.allocate(4);
                    readFully(channel, lengthBuf);
                    int length = ByteBuffer.wrap(lengthBuf.array()).getInt();
                    if (length < MIN_FRAME_PAYLOAD || length > MAX_FRAME_PAYLOAD) {
                        throw new IOException("invalid frame length at offset " + lastValid);
                    }
                    if (remaining - 4 < (long) length + 4) {
                        truncated = handleIncomplete(channel, repair, lastValid, size);
                        break;
                    }
                    ByteBuffer dataAndChecksum = ByteBuffer.allocate(length + 4);
                    readFully(channel, dataAndChecksum);
                    ByteBuffer data = ByteBuffer.wrap(dataAndChecksum.array());
                    byte[] payload = new byte[length];
                    data.get(payload);
                    int stored = data.getInt();
                    if (stored != crc(payload)) {
                        throw new IOException("CRC32C mismatch at offset " + lastValid);
                    }
                    JournalEntry entry = JournalCodec.decode(payload, pendingCommand);
                    if (entry instanceof JournalEntry.Command command) {
                        if (command.sequence() != completed + 1) {
                            throw new IOException("command sequence discontinuity at offset " + lastValid);
                        }
                        pending = command.sequence();
                        pendingCommand = command.command();
                    } else if (entry instanceof JournalEntry.Decision decision) {
                        if (pending != decision.sequence()) {
                            throw new IOException("decision sequence mismatch at offset " + lastValid);
                        }
                        // Outcome type and command identity are validated by the codec.
                        completed++;
                        pending = 0;
                        pendingCommand = null;
                    }
                    entries.add(entry);
                    lastValid = channel.position();
                }
                return new JournalScan(instrument, entries, completed, pending, lastValid, truncated);
            } finally {
                if (repairLock != null) repairLock.release();
            }
        }
    }

    private static long handleIncomplete(FileChannel channel, boolean repair,
                                         long valid, long total) throws IOException {
        if (!repair) throw new IOException("incomplete journal tail at offset " + valid);
        channel.truncate(valid);
        channel.force(true);
        return total - valid;
    }

    private static byte[] header(InstrumentId instrument) {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES);
        byte[] symbol = instrument.value().getBytes(StandardCharsets.US_ASCII);
        buffer.putInt(MAGIC).putShort((short) FORMAT_VERSION).put((byte) symbol.length);
        buffer.put(symbol);
        buffer.put(new byte[12 - symbol.length]);
        byte[] first = Arrays.copyOf(buffer.array(), HEADER_BYTES - 4);
        buffer.putInt(crc(first));
        return buffer.array();
    }

    private static InstrumentId decodeHeader(byte[] bytes) throws IOException {
        ByteBuffer header = ByteBuffer.wrap(bytes);
        if (header.getInt() != MAGIC) throw new IOException("wrong journal magic");
        if (Short.toUnsignedInt(header.getShort()) != FORMAT_VERSION) {
            throw new IOException("unsupported journal format version");
        }
        int length = Byte.toUnsignedInt(header.get());
        if (length < 1 || length > 12) throw new IOException("invalid instrument length");
        byte[] symbol = new byte[12];
        header.get(symbol);
        for (int i = length; i < symbol.length; i++) {
            if (symbol[i] != 0) throw new IOException("nonzero journal header padding");
        }
        if (header.getInt() != crc(Arrays.copyOf(bytes, HEADER_BYTES - 4))) {
            throw new IOException("journal header checksum mismatch");
        }
        try {
            return new InstrumentId(new String(symbol, 0, length, StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException bad) {
            throw new IOException("invalid journal instrument", bad);
        }
    }

    private static int crc(byte[] bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) <= 0) throw new IOException("journal write made no progress");
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw new IOException("journal unexpectedly ended");
        }
    }

    private void ensureOpen() throws IOException {
        if (closed || !channel.isOpen()) throw new IOException("journal is closed");
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
