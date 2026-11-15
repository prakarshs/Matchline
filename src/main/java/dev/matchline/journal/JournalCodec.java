package dev.matchline.journal;

import dev.matchline.book.CancelResult;
import dev.matchline.book.MatchResult;
import dev.matchline.book.RestingOrderView;
import dev.matchline.book.TradeExecution;
import dev.matchline.domain.CancelOrder;
import dev.matchline.domain.CommandRejection;
import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderCommand;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.RejectionCode;
import dev.matchline.domain.Side;
import dev.matchline.sequencer.CommandDecision;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Stable explicit v1 wire format, independent of Java serialization and enum ordinals. */
final class JournalCodec {
    static final byte COMMAND = 1;
    static final byte DECISION = 2;
    private static final byte PLACE = 1;
    private static final byte CANCEL = 2;
    private static final byte PLACED = 1;
    private static final byte CANCELLED = 2;
    private static final byte REJECTED = 3;
    private static final int MAX_TRADE_COUNT = 100_000;

    private JournalCodec() { }

    static byte[] encode(JournalEntry entry) throws IOException {
        var buffer = new ByteArrayOutputStream();
        var out = new DataOutputStream(buffer);
        if (entry instanceof JournalEntry.Command command) {
            out.writeByte(COMMAND);
            out.writeLong(command.sequence());
            writeCommand(out, command.command());
        } else if (entry instanceof JournalEntry.Decision decision) {
            out.writeByte(DECISION);
            out.writeLong(decision.sequence());
            writeDecision(out, decision.decision());
        } else {
            throw new IOException("unsupported journal entry type");
        }
        out.flush();
        return buffer.toByteArray();
    }

    static JournalEntry decode(byte[] bytes, OrderCommand pending) throws IOException {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            byte type = in.readByte();
            long sequence = in.readLong();
            if (sequence < 1) throw new IOException("non-positive journal sequence");
            JournalEntry entry;
            if (type == COMMAND && pending == null) {
                entry = new JournalEntry.Command(sequence, readCommand(in));
            } else if (type == DECISION && pending != null) {
                entry = new JournalEntry.Decision(sequence, readDecision(in, pending));
            } else {
                throw new IOException("unexpected journal entry type or command/decision order");
            }
            if (in.available() != 0) throw new IOException("trailing bytes in journal payload");
            return entry;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid decoded journal payload", invalid);
        }
    }

    private static void writeCommand(DataOutputStream out, OrderCommand command) throws IOException {
        if (command instanceof PlaceLimitOrder place) {
            out.writeByte(PLACE);
            out.writeUTF(place.instrument().value());
            out.writeUTF(place.orderId().value());
            writeSide(out, place.side());
            out.writeLong(place.price().value());
            out.writeLong(place.quantity().value());
        } else if (command instanceof CancelOrder cancel) {
            out.writeByte(CANCEL);
            out.writeUTF(cancel.instrument().value());
            out.writeUTF(cancel.orderId().value());
        } else {
            throw new IOException("unsupported command");
        }
    }

    private static OrderCommand readCommand(DataInputStream in) throws IOException {
        byte kind = in.readByte();
        InstrumentId instrument = new InstrumentId(in.readUTF());
        OrderId id = new OrderId(in.readUTF());
        return switch (kind) {
            case PLACE -> new PlaceLimitOrder(instrument, id, readSide(in),
                    new PriceTicks(in.readLong()), new OrderQuantity(in.readLong()));
            case CANCEL -> new CancelOrder(instrument, id);
            default -> throw new IOException("unsupported command kind " + kind);
        };
    }

    private static void writeDecision(DataOutputStream out, CommandDecision decision) throws IOException {
        if (decision instanceof CommandDecision.Placed placed) {
            MatchResult result = placed.result();
            out.writeByte(PLACED);
            out.writeLong(result.executedQuantity());
            out.writeLong(result.restingQuantity());
            out.writeInt(result.trades().size());
            for (TradeExecution trade : result.trades()) {
                out.writeUTF(trade.makerOrderId().value());
                out.writeLong(trade.price().value());
                out.writeLong(trade.quantity());
            }
        } else if (decision instanceof CommandDecision.Cancelled cancelled) {
            RestingOrderView order = cancelled.result().cancelledOrder();
            out.writeByte(CANCELLED);
            out.writeUTF(order.orderId().value());
            writeSide(out, order.side());
            out.writeLong(order.price().value());
            out.writeLong(order.originalQuantity());
            out.writeLong(order.remainingQuantity());
            out.writeLong(order.insertionOrdinal());
        } else if (decision instanceof CommandDecision.Rejected rejected) {
            out.writeByte(REJECTED);
            out.writeUTF(rejected.rejection().code().name());
            out.writeUTF(rejected.rejection().message());
        } else {
            throw new IOException("unsupported decision");
        }
    }

    private static CommandDecision readDecision(DataInputStream in, OrderCommand pending) throws IOException {
        byte kind = in.readByte();
        if (kind == PLACED && pending instanceof PlaceLimitOrder place) {
            long executed = in.readLong();
            long resting = in.readLong();
            int count = in.readInt();
            if (count < 0 || count > MAX_TRADE_COUNT) throw new IOException("invalid trade count " + count);
            List<TradeExecution> trades = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                trades.add(new TradeExecution(place.instrument(), new OrderId(in.readUTF()),
                        place.orderId(), place.side(), new PriceTicks(in.readLong()), in.readLong()));
            }
            return new CommandDecision.Placed(new MatchResult(place, executed, resting, trades));
        }
        if (kind == CANCELLED && pending instanceof CancelOrder cancel) {
            var resting = new RestingOrderView(new OrderId(in.readUTF()), readSide(in),
                    new PriceTicks(in.readLong()), in.readLong(), in.readLong(), in.readLong());
            return new CommandDecision.Cancelled(new CancelResult(cancel, resting));
        }
        if (kind == REJECTED) {
            RejectionCode code;
            try {
                code = RejectionCode.valueOf(in.readUTF());
            } catch (IllegalArgumentException invalid) {
                throw new IOException("unknown rejection code", invalid);
            }
            return new CommandDecision.Rejected(new CommandRejection(code, in.readUTF()));
        }
        throw new IOException("decision kind does not match pending command");
    }

    private static void writeSide(DataOutputStream out, Side side) throws IOException {
        out.writeByte(side == Side.BUY ? 1 : 2);
    }

    private static Side readSide(DataInputStream in) throws IOException {
        return switch (in.readByte()) {
            case 1 -> Side.BUY;
            case 2 -> Side.SELL;
            default -> throw new IOException("invalid side in journal");
        };
    }
}
