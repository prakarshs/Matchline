package dev.matchline.observability;

import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.Side;
import dev.matchline.sequencer.SingleWriterSequencer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EngineMetricsTest {
    private static PlaceLimitOrder command(String id) {
        return new PlaceLimitOrder(new InstrumentId("DEMO"), new OrderId(id),
                Side.BUY, new PriceTicks(100), new OrderQuantity(2));
    }

    @Test void successfulAndRejectedCommandsAreCountedWithoutChangingSequencing() {
        try (var engine = new SingleWriterSequencer(new InstrumentId("DEMO"))) {
            var first = engine.submit(command("B1")).completion().toCompletableFuture().join();
            var duplicate = engine.submit(command("B1")).completion().toCompletableFuture().join();
            assertEquals(1, first.sequence());
            assertEquals(2, duplicate.sequence());
            assertTrue(first.successful());
            assertFalse(duplicate.successful());
            var m = engine.metricsSnapshot();
            assertEquals(2, m.commandsAdmitted());
            assertEquals(2, m.commandsCompleted());
            assertEquals(1, m.businessRejected());
            assertEquals(2, m.queueWaitNanos().count());
            assertEquals(2, m.processingNanos().count());
            assertEquals(2, m.acknowledgementNanos().count());
            engine.inspect(1).join();
            assertEquals(1, engine.metricsSnapshot().readsAdmitted());
            assertEquals(2, engine.lastProcessedSequence());
        }
    }
}
