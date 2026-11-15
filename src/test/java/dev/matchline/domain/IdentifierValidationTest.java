package dev.matchline.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdentifierValidationTest {

    @Test
    void orderIdsAreCaseSensitiveAndValueBased() {
        assertThat(new OrderId("a1_2.z-9")).isEqualTo(new OrderId("a1_2.z-9"));
        assertThat(new OrderId("a1")).isNotEqualTo(new OrderId("A1"));
        assertThat(new OrderId("X".repeat(64)).value()).hasSize(64);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " leading", "trailing ", "_first", "-first", ".first", "has/slash", "has:colon", "é", "A中", "x\n", "x\t"})
    void invalidOrderIdsAreRejected(String value) {
        assertRejection(RejectionCode.INVALID_ORDER_ID, () -> new OrderId(value));
    }

    @Test
    void nullAndTooLongOrderIdsAreRejected() {
        assertRejection(RejectionCode.INVALID_ORDER_ID, () -> new OrderId(null));
        assertRejection(RejectionCode.INVALID_ORDER_ID, () -> new OrderId("X".repeat(65)));
    }

    @Test
    void instrumentIsAnExplicitUppercasePartitionKey() {
        assertThat(new InstrumentId("DEMO")).isEqualTo(new InstrumentId("DEMO"));
        assertThat(new InstrumentId("BTC-USD.1").toString()).isEqualTo("BTC-USD.1");
        assertThat(new InstrumentId("A".repeat(12)).value()).hasSize(12);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "demo", "2TEST", "_DEMO", "DEMO/USD", "DEMO USD", "Xé", " X", "X\n", "X\t"})
    void invalidInstrumentsAreRejected(String value) {
        assertRejection(RejectionCode.INVALID_INSTRUMENT, () -> new InstrumentId(value));
    }

    @Test
    void nullAndTooLongInstrumentIdsAreRejected() {
        assertRejection(RejectionCode.INVALID_INSTRUMENT, () -> new InstrumentId(null));
        assertRejection(RejectionCode.INVALID_INSTRUMENT, () -> new InstrumentId("Z".repeat(13)));
    }

    private static void assertRejection(RejectionCode expected, Runnable attempt) {
        OrderValidationException ex = assertThrows(OrderValidationException.class, attempt::run);
        assertThat(ex.code()).isEqualTo(expected);
    }
}
