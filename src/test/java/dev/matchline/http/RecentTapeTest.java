package dev.matchline.http;

import dev.matchline.book.MatchResult;
import dev.matchline.book.TradeExecution;
import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.Side;
import dev.matchline.sequencer.CommandDecision;
import dev.matchline.sequencer.SequencedResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecentTapeTest {
    private static final InstrumentId DEMO = new InstrumentId("DEMO");

    private static SequencedResult placement(long sequence, boolean withTrade) {
        PlaceLimitOrder command = new PlaceLimitOrder(DEMO, new OrderId("O" + sequence),
                Side.BUY, new PriceTicks(100), new OrderQuantity(2));
        List<TradeExecution> trades = withTrade
                ? List.of(new TradeExecution(DEMO, new OrderId("M" + sequence), command.orderId(),
                        Side.BUY, new PriceTicks(100), 1)) : List.of();
        return new SequencedResult(sequence, command,
                new CommandDecision.Placed(new MatchResult(command,
                        withTrade ? 1 : 0, withTrade ? 1 : 2, trades)));
    }

    @Test void remembersAtMost2048CommandSummaries() {
        var tape = new RecentTape(false);
        for (long sequence = 1; sequence <= 2500; sequence++) tape.record(placement(sequence, false));
        assertEquals(RecentTape.MAX_COMMANDS, tape.size());
        List<Object> events = tape.events(3000, false);
        assertEquals(2048, events.size());
        assertEquals(453L, ((Map<?, ?>) events.getFirst()).get("sequence"));
        assertEquals(2500L, ((Map<?, ?>) events.getLast()).get("sequence"));
    }

    @Test void sortsOutOfOrderHttpCompletionAndKeepsTradesSeparateAndBounded() {
        var tape = new RecentTape(true);
        tape.record(placement(3, true));
        tape.record(placement(1, true));
        tape.record(placement(2, true));
        List<Object> trades = tape.events(10, true);
        assertEquals(3, trades.size());
        assertEquals(1L, ((Map<?, ?>) trades.getFirst()).get("sequence"));
        assertEquals(3L, ((Map<?, ?>) trades.getLast()).get("sequence"));
        List<Object> commands = tape.events(10, false);
        assertEquals(true, ((Map<?, ?>) commands.getFirst()).get("durable"));
        assertEquals(1, ((Map<?, ?>) commands.getFirst()).get("tradeCount"));
        assertFalse(((Map<?, ?>) commands.getFirst()).containsKey("trades"));
        for (long sequence = 4; sequence <= 2300; sequence++) tape.record(placement(sequence, true));
        assertEquals(RecentTape.MAX_TRADES, tape.events(9999, true).size());
        tape.clear();
        assertEquals(0, tape.size());
        assertTrue(tape.events(100, true).isEmpty());
    }
}
