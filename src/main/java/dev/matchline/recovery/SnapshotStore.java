package dev.matchline.recovery;

import dev.matchline.book.BookSnapshot;
import dev.matchline.book.LimitOrderBook;
import dev.matchline.book.RestingOrderView;
import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.Side;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Atomic replaceable binary snapshots, version 1, SHA-256 protected.
 * A 32-byte trailer hashes the exact binary body. An independent SHA-256
 * anchor binds a snapshot to a completed prefix of its matching journal.
 * Snapshot corruption is fatal on recovery; never silently reset to empty.
 */
public final class SnapshotStore {
    private static final int MAGIC = 0x4D4C5331; // MLS1
    private static final int VERSION = 1;
    private static final int DIGEST_BYTES = 32;
    private static final long MAX_FILE_BYTES = 256L * 1024L * 1024L;

    private SnapshotStore() { }

    public record Stored(BookSnapshot state, JournalAnchor anchor) { }

    public static void writeAtomically(Path path, BookSnapshot state, JournalAnchor anchor) throws IOException {
        if (state.lastSequence() != anchor.sequence()) {
            throw new IOException("snapshot and journal anchor sequences differ");
        }
        byte[] body = encode(state, anchor);
        if ((long) body.length + DIGEST_BYTES > MAX_FILE_BYTES) {
            throw new IOException("snapshot exceeds maximum file size");
        }
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("snapshot parent directory must already exist");
        }
        Path temporary = Files.createTempFile(parent, ".matchline-snapshot-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writeFully(channel, ByteBuffer.wrap(body));
                writeFully(channel, ByteBuffer.wrap(sha256(body)));
                channel.force(true);
            }
            // Never fall back to copy/nonatomic overwrite if the filesystem does not support rename.
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            // Best effort directory metadata force; not universally supported on Windows.
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
                directory.force(true);
            } catch (IOException | UnsupportedOperationException ignored) {
                // Atomic rename has succeeded; report no stronger durability claim.
            }
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("atomic snapshot rename unsupported by target filesystem", unsupported);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static Stored read(Path path) throws IOException {
        long length = Files.size(path);
        if (length < 100 || length > MAX_FILE_BYTES) throw new IOException("invalid snapshot size");
        byte[] bytes = Files.readAllBytes(path);
        byte[] body = Arrays.copyOf(bytes, bytes.length - DIGEST_BYTES);
        byte[] storedDigest = Arrays.copyOfRange(bytes, body.length, bytes.length);
        if (!MessageDigest.isEqual(storedDigest, sha256(body))) {
            throw new IOException("snapshot SHA-256 mismatch");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            if (in.readInt() != MAGIC) throw new IOException("invalid snapshot magic");
            if (in.readUnsignedShort() != VERSION) throw new IOException("unsupported snapshot version");
            InstrumentId instrument = new InstrumentId(in.readUTF());
            long sequence = in.readLong();
            JournalAnchor anchor = new JournalAnchor(sequence, in.readLong(), in.readUTF());
            int maxResting = in.readInt();
            int maxIds = in.readInt();
            long ordinal = in.readLong();
            int idsCount = in.readInt();
            if (idsCount < 0 || idsCount > LimitOrderBook.HARD_MAX_ORDER_IDS) {
                throw new IOException("invalid snapshot accepted-ID count");
            }
            List<OrderId> ids = new ArrayList<>(idsCount);
            for (int i = 0; i < idsCount; i++) ids.add(new OrderId(in.readUTF()));
            int restingCount = in.readInt();
            if (restingCount < 0 || restingCount > LimitOrderBook.HARD_MAX_RESTING_ORDERS) {
                throw new IOException("invalid snapshot resting-order count");
            }
            List<RestingOrderView> orders = new ArrayList<>(restingCount);
            for (int i = 0; i < restingCount; i++) {
                OrderId id = new OrderId(in.readUTF());
                int sideByte = in.readUnsignedByte();
                Side side = switch (sideByte) {
                    case 1 -> Side.BUY;
                    case 2 -> Side.SELL;
                    default -> throw new IOException("invalid snapshot order side");
                };
                orders.add(new RestingOrderView(id, side, new PriceTicks(in.readLong()),
                        in.readLong(), in.readLong(), in.readLong()));
            }
            if (in.available() != 0) throw new IOException("trailing data in snapshot");
            BookSnapshot state = new BookSnapshot(instrument, sequence, maxResting, maxIds, ordinal, ids, orders);
            // Validate indexes and FIFO before allowing recovery to accept snapshot.
            LimitOrderBook.restore(state, maxResting, maxIds);
            return new Stored(state, anchor);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new IOException("invalid snapshot state", invalid);
        }
    }

    private static byte[] encode(BookSnapshot state, JournalAnchor anchor) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeShort(VERSION);
        out.writeUTF(state.instrument().value());
        out.writeLong(state.lastSequence());
        out.writeLong(anchor.offset());
        out.writeUTF(anchor.prefixSha256());
        out.writeInt(state.maxRestingOrders());
        out.writeInt(state.maxOrderIds());
        out.writeLong(state.lastInsertionOrdinal());
        out.writeInt(state.acceptedOrderIds().size());
        for (OrderId id : state.acceptedOrderIds()) out.writeUTF(id.value());
        out.writeInt(state.restingOrders().size());
        for (RestingOrderView order : state.restingOrders()) {
            out.writeUTF(order.orderId().value());
            out.writeByte(order.side() == Side.BUY ? 1 : 2);
            out.writeLong(order.price().value());
            out.writeLong(order.originalQuantity());
            out.writeLong(order.remainingQuantity());
            out.writeLong(order.insertionOrdinal());
        }
        out.flush();
        return bytes.toByteArray();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 required by Java 21", impossible);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer data) throws IOException {
        while (data.hasRemaining()) {
            if (channel.write(data) <= 0) throw new IOException("snapshot file write made no progress");
        }
    }
}
