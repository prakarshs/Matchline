package dev.matchline.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderQuantityTest {
    @Test
    void boundaryValuesAreAccepted() {
        assertThat(new OrderQuantity(1).value()).isEqualTo(1);
        assertThat(new OrderQuantity(OrderQuantity.MAX).value()).isEqualTo(1_000_000);
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0, 1_000_001, Long.MAX_VALUE})
    void invalidRequestQuantitiesAreRejected(long value) {
        OrderValidationException ex = assertThrows(OrderValidationException.class,
                () -> new OrderQuantity(value));
        assertThat(ex.code()).isEqualTo(RejectionCode.INVALID_QUANTITY);
    }
}
