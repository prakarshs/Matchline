package dev.matchline.book;

import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.OrderValidationException;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.RejectionCode;
import dev.matchline.domain.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitOrderBookTest {
    private static final InstrumentId DEMO = new InstrumentId("DEMO");

    private static PlaceLimitOrder order(String id, Side side, long price, long qty) {
        return new PlaceLimitOrder(DEMO, new OrderId(id), side,
                new PriceTicks(price), new OrderQuantity(qty));
    }

    @Test
    void startsEmpty() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        assertThat(book.instrument()).isEqualTo(DEMO);
        assertThat(book.restingOrderCount()).isZero();
        assertThat(book.priceLevelCount(Side.BUY)).isZero();
        assertThat(book.depth(Side.BUY, 5)).isEmpty();
        assertThat(book.bestOrder(Side.SELL)).isEmpty();
        assertThat(book.find(new OrderId("missing"))).isEmpty();
        assertThat(book.ordersAt(Side.BUY, new PriceTicks(10))).isEmpty();
    }

    @Test
    void bidsAreHighestPriceFirstAndStableWithinPrice() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("B1", Side.BUY, 100, 4));
        book.addResting(order("B2", Side.BUY, 101, 3));
        book.addResting(order("B3", Side.BUY, 100, 2));
        book.addResting(order("B4", Side.BUY, 99, 1));
        book.addResting(order("B5", Side.BUY, 101, 5));

        assertThat(book.depth(Side.BUY, 5)).containsExactly(
                new LevelDepth(new PriceTicks(101), 8, 2),
                new LevelDepth(new PriceTicks(100), 6, 2),
                new LevelDepth(new PriceTicks(99), 1, 1));
        assertThat(book.bestOrder(Side.BUY).orElseThrow().orderId()).isEqualTo(new OrderId("B2"));
        assertThat(book.ordersAt(Side.BUY, new PriceTicks(100)))
                .extracting(o -> o.orderId().value()).containsExactly("B1", "B3");
        assertThat(book.ordersAt(Side.BUY, new PriceTicks(101)))
                .extracting(o -> o.orderId().value()).containsExactly("B2", "B5");
        assertThat(book.restingOrderCount()).isEqualTo(5);
        assertThat(book.priceLevelCount(Side.BUY)).isEqualTo(3);
    }

    @Test
    void asksAreLowestPriceFirstAndStableWithinPrice() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("S1", Side.SELL, 104, 3));
        book.addResting(order("S2", Side.SELL, 103, 2));
        book.addResting(order("S3", Side.SELL, 103, 4));
        book.addResting(order("S4", Side.SELL, 105, 1));

        assertThat(book.depth(Side.SELL, 10)).containsExactly(
                new LevelDepth(new PriceTicks(103), 6, 2),
                new LevelDepth(new PriceTicks(104), 3, 1),
                new LevelDepth(new PriceTicks(105), 1, 1));
        assertThat(book.bestOrder(Side.SELL).orElseThrow().orderId()).isEqualTo(new OrderId("S2"));
        assertThat(book.ordersAt(Side.SELL, new PriceTicks(103)))
                .extracting(o -> o.orderId().value()).containsExactly("S2", "S3");
    }

    @Test
    void preservesOriginalQuantityAndResidualQuantityForFutureMatcher() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        RestingOrderView inserted = book.addResting(order("PART", Side.BUY, 100, 10), 3);
        assertThat(inserted.originalQuantity()).isEqualTo(10);
        assertThat(inserted.remainingQuantity()).isEqualTo(3);
        assertThat(book.depth(Side.BUY, 1)).containsExactly(new LevelDepth(new PriceTicks(100), 3, 1));
        assertThat(book.find(new OrderId("PART"))).contains(inserted);
    }

    @Test
    void insertionOrdinalsAreBookLocalMonotonicAndIndependentOfPrice() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        assertThat(book.addResting(order("ONE", Side.BUY, 1, 1)).insertionOrdinal()).isEqualTo(1);
        assertThat(book.addResting(order("TWO", Side.SELL, 3, 1)).insertionOrdinal()).isEqualTo(2);
        assertThat(book.addResting(order("THREE", Side.BUY, 2, 1)).insertionOrdinal()).isEqualTo(3);
        assertThat(new LimitOrderBook(DEMO).addResting(order("OTHER", Side.BUY, 2, 1)).insertionOrdinal())
                .isEqualTo(1);
    }

    @Test
    void rejectsDuplicateCurrentlyRestingIdWithoutChangingBook() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("B1", Side.BUY, 100, 3));
        assertThatThrownBy(() -> book.addResting(order("B1", Side.SELL, 105, 5)))
                .isInstanceOfSatisfying(OrderValidationException.class,
                        ex -> assertThat(ex.code()).isEqualTo(RejectionCode.DUPLICATE_ORDER_ID));
        assertThat(book.restingOrderCount()).isEqualTo(1);
        assertThat(book.priceLevelCount(Side.SELL)).isZero();
        assertThat(book.depth(Side.BUY, 1)).containsExactly(new LevelDepth(new PriceTicks(100), 3, 1));
    }

    @Test
    void rejectsOtherInstrumentsBeforeMutation() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        PlaceLimitOrder wrong = new PlaceLimitOrder(new InstrumentId("OTHER"), new OrderId("X"),
                Side.BUY, new PriceTicks(100), new OrderQuantity(1));
        assertThatThrownBy(() -> book.addResting(wrong))
                .isInstanceOfSatisfying(OrderValidationException.class,
                        ex -> assertThat(ex.code()).isEqualTo(RejectionCode.INSTRUMENT_MISMATCH));
        assertThat(book.restingOrderCount()).isZero();
    }

    @Test
    void refusesCrossedRestingBookWithoutPretendingToMatch() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("B1", Side.BUY, 100, 3));
        book.addResting(order("S1", Side.SELL, 101, 2));
        assertThatThrownBy(() -> book.addResting(order("S2", Side.SELL, 100, 1)))
                .isInstanceOfSatisfying(OrderValidationException.class,
                        ex -> assertThat(ex.code()).isEqualTo(RejectionCode.INVALID_COMMAND));
        assertThatThrownBy(() -> book.addResting(order("B2", Side.BUY, 101, 1)))
                .isInstanceOfSatisfying(OrderValidationException.class,
                        ex -> assertThat(ex.code()).isEqualTo(RejectionCode.INVALID_COMMAND));
        assertThat(book.restingOrderCount()).isEqualTo(2);
        assertThat(book.bestOrder(Side.BUY).orElseThrow().orderId()).isEqualTo(new OrderId("B1"));
        assertThat(book.bestOrder(Side.SELL).orElseThrow().orderId()).isEqualTo(new OrderId("S1"));
    }

    @Test
    void capacityFailureLeavesIndexDepthAndNextInsertionOrdinalUnchanged() {
        LimitOrderBook book = new LimitOrderBook(DEMO, 2);
        book.addResting(order("B1", Side.BUY, 100, 10));
        book.addResting(order("B2", Side.BUY, 100, 20));
        assertThatThrownBy(() -> book.addResting(order("B3", Side.BUY, 100, 30)))
                .isInstanceOfSatisfying(OrderValidationException.class,
                        ex -> assertThat(ex.code()).isEqualTo(RejectionCode.BOOK_CAPACITY_EXCEEDED));
        assertThat(book.restingOrderCount()).isEqualTo(2);
        assertThat(book.depth(Side.BUY, 1)).containsExactly(new LevelDepth(new PriceTicks(100), 30, 2));
        assertThat(book.find(new OrderId("B3"))).isEmpty();
        assertThat(book.ordersAt(Side.BUY, new PriceTicks(100)))
                .extracting(RestingOrderView::insertionOrdinal).containsExactly(1L, 2L);
    }

    @Test
    void rejectsInvalidRemainingWithoutChangingBook() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        for (long quantity : new long[]{-1L, 0L, 6L, Long.MAX_VALUE}) {
            assertThatThrownBy(() -> book.addResting(order("B1", Side.BUY, 100, 5), quantity))
                    .isInstanceOfSatisfying(OrderValidationException.class,
                            ex -> assertThat(ex.code()).isEqualTo(RejectionCode.INVALID_QUANTITY));
        }
        assertThat(book.restingOrderCount()).isZero();
        assertThat(book.addResting(order("B1", Side.BUY, 100, 5)).insertionOrdinal()).isEqualTo(1);
    }

    @Test
    void returnedViewsAreDetachedAndDepthListsAreImmutable() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("B1", Side.BUY, 100, 4));
        List<LevelDepth> earlier = book.depth(Side.BUY, 1);
        List<RestingOrderView> earlierOrders = book.ordersAt(Side.BUY, new PriceTicks(100));
        book.addResting(order("B2", Side.BUY, 100, 2));
        assertThat(earlier).containsExactly(new LevelDepth(new PriceTicks(100), 4, 1));
        assertThat(earlierOrders).extracting(o -> o.orderId().value()).containsExactly("B1");
        assertThatThrownBy(() -> earlier.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> earlierOrders.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(book.depth(Side.BUY, 1)).containsExactly(new LevelDepth(new PriceTicks(100), 6, 2));
    }

    @Test
    void depthLimitRestrictsNumberOfLevelsNotOrders() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("B1", Side.BUY, 100, 1));
        book.addResting(order("B2", Side.BUY, 100, 1));
        book.addResting(order("B3", Side.BUY, 99, 1));
        assertThat(book.depth(Side.BUY, 1)).containsExactly(new LevelDepth(new PriceTicks(100), 2, 2));
        assertThat(book.depth(Side.BUY, 2)).hasSize(2);
        for (int invalid : new int[]{-1, 0, 1_001}) {
            assertThatThrownBy(() -> book.depth(Side.BUY, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aggregateTotalsUseLongArithmetic() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        for (int i = 0; i < 1_000; i++) {
            book.addResting(order("B" + i, Side.BUY, 100, 1_000_000));
        }
        assertThat(book.depth(Side.BUY, 1))
                .containsExactly(new LevelDepth(new PriceTicks(100), 1_000_000_000L, 1_000));
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void lookupIsIndependentOfPriceLevelTraversal(Side side) {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        for (int i = 0; i < 75; i++) {
            book.addResting(order("O" + i, side, 100 + (i % 5), i + 1));
        }
        for (int i = 74; i >= 0; i--) {
            RestingOrderView found = book.find(new OrderId("O" + i)).orElseThrow();
            assertThat(found.price().value()).isEqualTo(100 + (i % 5));
            assertThat(found.remainingQuantity()).isEqualTo(i + 1);
            assertThat(found.side()).isEqualTo(side);
        }
    }

    @Test
    void separateInstrumentsMayUseTheSameOrderId() {
        LimitOrderBook a = new LimitOrderBook(DEMO);
        LimitOrderBook b = new LimitOrderBook(new InstrumentId("BETA"));
        a.addResting(order("X", Side.BUY, 100, 1));
        b.addResting(new PlaceLimitOrder(new InstrumentId("BETA"), new OrderId("X"),
                Side.SELL, new PriceTicks(100), new OrderQuantity(2)));
        assertThat(a.restingOrderCount()).isEqualTo(1);
        assertThat(b.restingOrderCount()).isEqualTo(1);
    }

    @Test
    void refusesUnreasonableCapacityAndNullCommand() {
        assertThatThrownBy(() -> new LimitOrderBook(DEMO, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LimitOrderBook(DEMO, 100_001)).isInstanceOf(IllegalArgumentException.class);
        LimitOrderBook book = new LimitOrderBook(DEMO);
        assertThatThrownBy(() -> book.addResting(null))
                .isInstanceOfSatisfying(OrderValidationException.class,
                        ex -> assertThat(ex.code()).isEqualTo(RejectionCode.INVALID_COMMAND));
    }
}
