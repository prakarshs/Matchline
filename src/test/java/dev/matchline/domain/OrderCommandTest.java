package dev.matchline.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderCommandTest {
    private static final InstrumentId DEMO = new InstrumentId("DEMO");
    private static final OrderId A1 = new OrderId("A1");

    @Test
    void placeLimitOrderIsAnImmutableValueAndHasExplicitKind() {
        PlaceLimitOrder first = new PlaceLimitOrder(DEMO, A1, Side.BUY,
                new PriceTicks(101), new OrderQuantity(5));
        PlaceLimitOrder same = new PlaceLimitOrder(new InstrumentId("DEMO"),
                new OrderId("A1"), Side.BUY, new PriceTicks(101), new OrderQuantity(5));

        assertThat(first).isEqualTo(same);
        assertThat(first.instrument()).isEqualTo(DEMO);
        assertThat(first.orderId()).isEqualTo(A1);
        assertThat(first.side()).isEqualTo(Side.BUY);
        assertThat(first.price()).isEqualTo(new PriceTicks(101));
        assertThat(first.quantity()).isEqualTo(new OrderQuantity(5));
        assertThat(first.kind()).isEqualTo(OrderCommand.Kind.PLACE_LIMIT);
    }

    @Test
    void cancelTargetsBothOrderAndInstrument() {
        CancelOrder cancel = new CancelOrder(DEMO, A1);
        assertThat(cancel.kind()).isEqualTo(OrderCommand.Kind.CANCEL);
        assertThat(cancel.instrument()).isEqualTo(DEMO);
        assertThat(cancel.orderId()).isEqualTo(A1);
        assertThat(cancel).isNotEqualTo(new CancelOrder(new InstrumentId("OTHER"), A1));
    }

    @Test
    void sealedCommandsCanBeHandledByKindWithoutExecution() {
        List<OrderCommand> commands = List.of(
                new PlaceLimitOrder(DEMO, A1, Side.BUY, new PriceTicks(100), new OrderQuantity(2)),
                new CancelOrder(DEMO, A1)
        );
        assertThat(commands).extracting(OrderCommand::kind)
                .containsExactly(OrderCommand.Kind.PLACE_LIMIT, OrderCommand.Kind.CANCEL);
    }

    @Test
    void nullPlaceFieldsAreRejectedWithStableCodes() {
        assertReject(RejectionCode.INVALID_COMMAND,
                () -> new PlaceLimitOrder(null, A1, Side.BUY, new PriceTicks(1), new OrderQuantity(1)));
        assertReject(RejectionCode.INVALID_COMMAND,
                () -> new PlaceLimitOrder(DEMO, null, Side.BUY, new PriceTicks(1), new OrderQuantity(1)));
        assertReject(RejectionCode.INVALID_SIDE,
                () -> new PlaceLimitOrder(DEMO, A1, null, new PriceTicks(1), new OrderQuantity(1)));
        assertReject(RejectionCode.INVALID_COMMAND,
                () -> new PlaceLimitOrder(DEMO, A1, Side.BUY, null, new OrderQuantity(1)));
        assertReject(RejectionCode.INVALID_COMMAND,
                () -> new PlaceLimitOrder(DEMO, A1, Side.BUY, new PriceTicks(1), null));
    }

    @Test
    void nullCancelFieldsAreRejected() {
        assertReject(RejectionCode.INVALID_COMMAND, () -> new CancelOrder(null, A1));
        assertReject(RejectionCode.INVALID_COMMAND, () -> new CancelOrder(DEMO, null));
    }

    private static void assertReject(RejectionCode expected, Runnable attempt) {
        OrderValidationException ex = assertThrows(OrderValidationException.class, attempt::run);
        assertThat(ex.code()).isEqualTo(expected);
    }
}
