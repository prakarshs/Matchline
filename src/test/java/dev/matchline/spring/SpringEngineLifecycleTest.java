package dev.matchline.spring;

import dev.matchline.domain.CancelOrder;
import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.RejectionCode;
import dev.matchline.domain.Side;
import dev.matchline.sequencer.CommandDecision;
import dev.matchline.sequencer.CommandTicket;
import dev.matchline.sequencer.SequencedResult;
import dev.matchline.sequencer.SingleWriterSequencer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spring wiring and shutdown; existing domain tests remain the source of matcher correctness. */
final class SpringEngineLifecycleTest {
    @Test
    void oneSingletonWriterIsInjectedIntoTheFacadeAndShutDownWithItsContext() throws Exception {
        MatchingApplicationService retained;
        SingleWriterSequencer writer;
        try (var context = open()) {
            retained = context.getBean(MatchingApplicationService.class);
            writer = context.getBean(SingleWriterSequencer.class);
            assertThat(context.getBean(MatchingApplicationService.class)).isSameAs(retained);
            assertThat(context.getBean(SingleWriterSequencer.class)).isSameAs(writer);
            assertThat(context.getBeansOfType(SingleWriterSequencer.class)).hasSize(1);
            assertThat(retained.accepting()).isTrue();
            assertThat(retained.instrument()).isEqualTo(new InstrumentId("DEMO"));
            assertThat(retained.queueCapacity()).isEqualTo(1024);
        }
        assertThat(retained.accepting()).isFalse();
        assertThat(writer.awaitTermination(java.time.Duration.ofSeconds(1))).isTrue();
        assertThatThrownBy(() -> retained.submit(order("LATE", Side.BUY, 100, 1)))
                .hasMessageContaining("closed");
    }

    @Test
    void applicationFacadeOrdersReadsAndCancelsThroughSingleWriter() throws Exception {
        try (var context = open()) {
            var service = context.getBean(MatchingApplicationService.class);
            SequencedResult buy = await(service.submit(order("B1", Side.BUY, 100, 5)));
            assertThat(buy.sequence()).isEqualTo(1);
            var sell = await(service.submit(order("S1", Side.SELL, 99, 3)));
            assertThat(sell.sequence()).isEqualTo(2);
            assertThat(sell.decision()).isInstanceOf(CommandDecision.Placed.class);
            var trade = ((CommandDecision.Placed) sell.decision()).result();
            assertThat(trade.executedQuantity()).isEqualTo(3);
            assertThat(trade.trades()).hasSize(1);
            assertThat(trade.trades().getFirst().price().value()).isEqualTo(100);
            var state = service.inspect(10).get(5, TimeUnit.SECONDS);
            assertThat(state.lastProcessedSequence()).isEqualTo(2);
            assertThat(state.bestBid()).isPresent();
            assertThat(state.bestBid().orElseThrow().remainingQuantity()).isEqualTo(2);
            var cancelled = await(service.submit(new CancelOrder(new InstrumentId("DEMO"), new OrderId("B1"))));
            assertThat(cancelled.sequence()).isEqualTo(3);
            assertThat(cancelled.decision()).isInstanceOf(CommandDecision.Cancelled.class);
            assertThat(service.inspect(10).get(5, TimeUnit.SECONDS).bestBid()).isEmpty();
        }
    }

    @Test
    void businessRejectionHasSequenceAndDoesNotCreateSecondBook() throws Exception {
        try (var context = open()) {
            var service = context.getBean(MatchingApplicationService.class);
            await(service.submit(order("DUP", Side.BUY, 100, 1)));
            var duplicate = await(service.submit(order("DUP", Side.BUY, 101, 1)));
            assertThat(duplicate.sequence()).isEqualTo(2);
            assertThat(duplicate.decision()).isInstanceOf(CommandDecision.Rejected.class);
            assertThat(((CommandDecision.Rejected) duplicate.decision()).rejection().code())
                    .isEqualTo(RejectionCode.DUPLICATE_ORDER_ID);
            assertThat(service.inspect(10).get(5, TimeUnit.SECONDS).restingOrders()).isEqualTo(1);
        }
    }

    @Test
    void closeDrainsAlreadyAdmittedCommandsRatherThanDroppingThem() throws Exception {
        List<CommandTicket> tickets = new ArrayList<>();
        var context = open("--matchline.engine.queue-capacity=512");
        var service = context.getBean(MatchingApplicationService.class);
        try {
            for (int i = 0; i < 80; i++) {
                tickets.add(service.submit(order("D" + i, Side.BUY, 100, 1)));
            }
        } finally {
            context.close();
        }
        for (int i = 0; i < tickets.size(); i++) {
            assertThat(await(tickets.get(i)).sequence()).isEqualTo(i + 1L);
        }
        assertThat(service.lastProcessedSequence()).isEqualTo(80);
        assertThat(service.accepting()).isFalse();
    }

    private static SequencedResult await(CommandTicket ticket) throws Exception {
        return ticket.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static PlaceLimitOrder order(String id, Side side, long price, long quantity) {
        return new PlaceLimitOrder(new InstrumentId("DEMO"), new OrderId(id), side,
                new PriceTicks(price), new OrderQuantity(quantity));
    }

    private static ConfigurableApplicationContext open(String... overrides) {
        SpringApplication app = new SpringApplication(MatchlineSpringApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app.run(overrides);
    }
}
