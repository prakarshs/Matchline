package dev.matchline.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SideTest {
    @Test
    void opposingSidesAreMutual() {
        assertThat(Side.BUY.opposite()).isEqualTo(Side.SELL);
        assertThat(Side.SELL.opposite()).isEqualTo(Side.BUY);
    }

    @Test
    void buyCrossesWhenLimitIsAtOrAboveRestingAsk() {
        assertThat(Side.BUY.crosses(new PriceTicks(100), new PriceTicks(99))).isTrue();
        assertThat(Side.BUY.crosses(new PriceTicks(100), new PriceTicks(100))).isTrue();
        assertThat(Side.BUY.crosses(new PriceTicks(100), new PriceTicks(101))).isFalse();
    }

    @Test
    void sellCrossesWhenLimitIsAtOrBelowRestingBid() {
        assertThat(Side.SELL.crosses(new PriceTicks(100), new PriceTicks(101))).isTrue();
        assertThat(Side.SELL.crosses(new PriceTicks(100), new PriceTicks(100))).isTrue();
        assertThat(Side.SELL.crosses(new PriceTicks(100), new PriceTicks(99))).isFalse();
    }

    @Test
    void comparingWithNullPriceFailsRatherThanSilentlyAccepting() {
        assertThrows(NullPointerException.class, () -> Side.BUY.crosses(null, new PriceTicks(1)));
        assertThrows(NullPointerException.class, () -> Side.SELL.crosses(new PriceTicks(1), null));
    }
}
