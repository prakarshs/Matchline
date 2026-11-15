package dev.matchline.observability;

import dev.matchline.benchmark.DeterministicWorkload;
import dev.matchline.book.LimitOrderBook;
import dev.matchline.book.MatchResult;
import dev.matchline.domain.OrderCommand;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.Side;
import dev.matchline.sequencer.SingleWriterSequencer;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class DeterministicWorkloadTest {
    @Test void eachScenarioIsValidAndDeterministicOnRawAndSequencedPaths() {
        for (var scenario : DeterministicWorkload.Scenario.values()) {
            var commands = DeterministicWorkload.commands(scenario, 500);
            assertEquals(commands, DeterministicWorkload.commands(scenario, 500));
            var ids = new HashSet<>();
            var rawBook = new LimitOrderBook(DeterministicWorkload.DEMO, 2_000, 5_000);
            long rawTradeCount = 0;
            long rawExecuted = 0;
            for (OrderCommand command : commands) {
                var place = (PlaceLimitOrder) command;
                assertTrue(ids.add(place.orderId()));
                MatchResult match = rawBook.executeLimit(place);
                assertEquals(place.quantity().value(), match.executedQuantity() + match.restingQuantity());
                rawTradeCount += match.trades().size();
                rawExecuted += match.executedQuantity();
            }
            try (var engine = new SingleWriterSequencer(DeterministicWorkload.DEMO, 512, 2_000, 5_000)) {
                long sequenceTradeCount = 0;
                long sequenceExecuted = 0;
                for (var command : commands) {
                    var result = engine.submit(command).completion().toCompletableFuture().join();
                    assertTrue(result.successful());
                    var match = ((dev.matchline.sequencer.CommandDecision.Placed) result.decision()).result();
                    sequenceTradeCount += match.trades().size();
                    sequenceExecuted += match.executedQuantity();
                }
                assertEquals(500, engine.lastProcessedSequence());
                assertEquals(rawTradeCount, sequenceTradeCount);
                assertEquals(rawExecuted, sequenceExecuted);
                assertEquals(rawBook.snapshot(500), engine.snapshotState().join());
            }
            if (scenario == DeterministicWorkload.Scenario.REST_ONLY) {
                assertEquals(0, rawExecuted);
                assertEquals(500, rawBook.restingOrderCount());
            } else {
                assertTrue(rawTradeCount > 0);
                assertEquals(0, rawBook.restingOrderCount());
            }
        }
    }

    @Test void scenarioConfigurationIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> DeterministicWorkload.commands(
                DeterministicWorkload.Scenario.MULTI_FILL, 501));
        assertThrows(IllegalArgumentException.class, () -> DeterministicWorkload.commands(
                DeterministicWorkload.Scenario.CROSSING, 0));
    }
}
