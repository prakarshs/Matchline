package dev.matchline.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PriceTicksTest {
    @Test
    void exactBoundsAndComparison() {
        assertThat(new PriceTicks(PriceTicks.MIN).value()).isEqualTo(1L);
        assertThat(new PriceTicks(PriceTicks.MAX).value()).isEqualTo(1_000_000_000L);
        assertThat(new PriceTicks(12).compareTo(new PriceTicks(20))).isNegative();
        assertThat(new PriceTicks(20).compareTo(new PriceTicks(12))).isPositive();
        assertThat(new PriceTicks(12).compareTo(new PriceTicks(12))).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1L, 0L, 1_000_000_001L, Long.MAX_VALUE})
    void outOfBoundsPricesRejected(long ticks) {
        OrderValidationException ex = assertThrows(OrderValidationException.class, () -> new PriceTicks(ticks));
        assertThat(ex.code()).isEqualTo(RejectionCode.INVALID_PRICE);
    }

    @Test
    void additionAndSubtractionInTicksAreExact() {
        assertThat(new PriceTicks(100).plusTicks(50)).isEqualTo(new PriceTicks(150));
        assertThat(new PriceTicks(100).plusTicks(-25)).isEqualTo(new PriceTicks(75));
        assertThat(new PriceTicks(100).minusTicks(20)).isEqualTo(new PriceTicks(80));
        assertThat(new PriceTicks(100).minusTicks(-20)).isEqualTo(new PriceTicks(120));
        assertThat(new PriceTicks(120).differenceFrom(new PriceTicks(150))).isEqualTo(-30L);
    }

    @Test
    void boundaryCrossingAndMachineIntegerOverflowAreRejected() {
        assertPriceReject(() -> new PriceTicks(1).minusTicks(1));
        assertPriceReject(() -> new PriceTicks(PriceTicks.MAX).plusTicks(1));
        assertPriceReject(() -> new PriceTicks(1).plusTicks(Long.MAX_VALUE));
        assertPriceReject(() -> new PriceTicks(1).plusTicks(Long.MIN_VALUE));
        assertPriceReject(() -> new PriceTicks(1).minusTicks(Long.MIN_VALUE));
        assertPriceReject(() -> new PriceTicks(1).minusTicks(Long.MAX_VALUE));
    }

    @Test
    void maximalSingleOrderNotionalFitsExactlyWithinSignedLong() {
        PriceTicks maxPrice = new PriceTicks(PriceTicks.MAX);
        OrderQuantity maxQuantity = new OrderQuantity(OrderQuantity.MAX);
        assertThat(maxPrice.notionalTicks(maxQuantity)).isEqualTo(1_000_000_000_000_000L);
        assertThat(new PriceTicks(101).notionalTicks(new OrderQuantity(5))).isEqualTo(505L);
    }

    private static void assertPriceReject(Runnable attempt) {
        OrderValidationException ex = assertThrows(OrderValidationException.class, attempt::run);
        assertThat(ex.code()).isEqualTo(RejectionCode.INVALID_PRICE);
    }
}
