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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitOrderMatchingTest {
    private static final InstrumentId DEMO = new InstrumentId("DEMO");

    private static PlaceLimitOrder order(String id, Side side, long price, long quantity) {
        return new PlaceLimitOrder(DEMO, new OrderId(id), side,
                new PriceTicks(price), new OrderQuantity(quantity));
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void emptyBookRestsEntireIncomingLimit(Side side) {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        PlaceLimitOrder command = order("A", side, 100, 5);
        MatchResult result = book.executeLimit(command);

        assertThat(result.command()).isEqualTo(command);
        assertThat(result.executedQuantity()).isZero();
        assertThat(result.restingQuantity()).isEqualTo(5);
        assertThat(result.trades()).isEmpty();
        assertThat(result.fullyExecuted()).isFalse();
        assertThat(book.find(new OrderId("A")).orElseThrow().remainingQuantity()).isEqualTo(5);
        assertThat(book.restingOrderCount()).isEqualTo(1);
    }

    @Test
    void buyingCrossesAtMakersAskingPriceNotBuyersLimit() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("ASK", Side.SELL, 99, 3));

        MatchResult result = book.executeLimit(order("BUY", Side.BUY, 105, 2));

        assertThat(result.trades()).containsExactly(new TradeExecution(
                DEMO, new OrderId("ASK"), new OrderId("BUY"), Side.BUY, new PriceTicks(99), 2));
        assertThat(result.fullyExecuted()).isTrue();
        assertThat(book.find(new OrderId("ASK")).orElseThrow().remainingQuantity()).isEqualTo(1);
        assertThat(book.find(new OrderId("BUY"))).isEmpty();
        assertThat(book.depth(Side.SELL, 1)).containsExactly(new LevelDepth(new PriceTicks(99), 1, 1));
    }

    @Test
    void sellingCrossesAtMakersBidNotSellersLimit() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("BID", Side.BUY, 108, 5));
        MatchResult result = book.executeLimit(order("SELL", Side.SELL, 101, 4));

        assertThat(result.trades()).containsExactly(new TradeExecution(
                DEMO, new OrderId("BID"), new OrderId("SELL"), Side.SELL, new PriceTicks(108), 4));
        assertThat(result.trades().getFirst().makerSide()).isEqualTo(Side.BUY);
        assertThat(book.bestOrder(Side.BUY).orElseThrow().remainingQuantity()).isEqualTo(1);
    }

    @Test
    void samePriceOrdersFillInStableFifoOrder() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("FIRST", Side.SELL, 101, 3));
        book.addResting(order("SECOND", Side.SELL, 101, 4));
        book.addResting(order("THIRD", Side.SELL, 101, 5));

        MatchResult result = book.executeLimit(order("BUY", Side.BUY, 101, 9));

        assertThat(result.trades()).extracting(t -> t.makerOrderId().value())
                .containsExactly("FIRST", "SECOND", "THIRD");
        assertThat(result.trades()).extracting(TradeExecution::quantity)
                .containsExactly(3L, 4L, 2L);
        assertThat(book.ordersAt(Side.SELL, new PriceTicks(101)))
                .extracting(o -> o.orderId().value()).containsExactly("THIRD");
        assertThat(book.bestOrder(Side.SELL).orElseThrow().remainingQuantity()).isEqualTo(3);
    }

    @Test
    void buyConsumesCheapestAskLevelsBeforeMoreExpensiveLevels() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("EXPENSIVE", Side.SELL, 103, 2));
        book.addResting(order("CHEAP", Side.SELL, 100, 2));
        book.addResting(order("MID", Side.SELL, 101, 3));
        MatchResult result = book.executeLimit(order("BUY", Side.BUY, 102, 6));

        assertThat(result.trades()).extracting(t -> t.makerOrderId().value())
                .containsExactly("CHEAP", "MID");
        assertThat(result.trades()).extracting(TradeExecution::quantity).containsExactly(2L, 3L);
        assertThat(result.executedQuantity()).isEqualTo(5);
        assertThat(result.restingQuantity()).isEqualTo(1);
        assertThat(book.depth(Side.SELL, 5))
                .containsExactly(new LevelDepth(new PriceTicks(103), 2, 1));
        assertThat(book.depth(Side.BUY, 5))
                .containsExactly(new LevelDepth(new PriceTicks(102), 1, 1));
    }

    @Test
    void sellConsumesHighestBidsBeforeLowerBids() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("LOW", Side.BUY, 98, 4));
        book.addResting(order("HIGH", Side.BUY, 105, 2));
        book.addResting(order("MID", Side.BUY, 101, 3));
        MatchResult result = book.executeLimit(order("SELL", Side.SELL, 100, 6));

        assertThat(result.trades()).extracting(t -> t.makerOrderId().value())
                .containsExactly("HIGH", "MID");
        assertThat(result.executedQuantity()).isEqualTo(5);
        assertThat(result.restingQuantity()).isEqualTo(1);
        assertThat(book.bestOrder(Side.BUY).orElseThrow().orderId()).isEqualTo(new OrderId("LOW"));
        assertThat(book.bestOrder(Side.SELL).orElseThrow().orderId()).isEqualTo(new OrderId("SELL"));
    }

    @Test
    void partialMakerKeepsOriginalFifoPositionAndInsertionOrdinal() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("OLD", Side.SELL, 101, 7));
        book.addResting(order("NEXT", Side.SELL, 101, 2));
        book.executeLimit(order("BUY1", Side.BUY, 101, 3));
        assertThat(book.ordersAt(Side.SELL, new PriceTicks(101)))
                .extracting(RestingOrderView::insertionOrdinal).containsExactly(1L, 2L);
        assertThat(book.ordersAt(Side.SELL, new PriceTicks(101)))
                .extracting(RestingOrderView::remainingQuantity).containsExactly(4L, 2L);

        MatchResult second = book.executeLimit(order("BUY2", Side.BUY, 101, 5));
        assertThat(second.trades()).extracting(t -> t.makerOrderId().value())
                .containsExactly("OLD", "NEXT");
        assertThat(second.trades()).extracting(TradeExecution::quantity).containsExactly(4L, 1L);
        assertThat(book.bestOrder(Side.SELL).orElseThrow().remainingQuantity()).isEqualTo(1);
    }

    @Test
    void remainderJoinsBehindExistingSameSideOrders() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("OLD", Side.BUY, 100, 8));
        book.addResting(order("ASK", Side.SELL, 101, 2));
        MatchResult result = book.executeLimit(order("NEW", Side.BUY, 101, 5));

        assertThat(result.executedQuantity()).isEqualTo(2);
        assertThat(result.restingQuantity()).isEqualTo(3);
        assertThat(book.ordersAt(Side.BUY, new PriceTicks(101)))
                .extracting(o -> o.orderId().value()).containsExactly("NEW");
        assertThat(book.bestOrder(Side.BUY).orElseThrow().insertionOrdinal()).isEqualTo(3);
        assertThat(book.ordersAt(Side.BUY, new PriceTicks(100)))
                .extracting(o -> o.orderId().value()).containsExactly("OLD");
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void nonCrossingOrderRestsWithoutTrade(Side incomingSide) {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        Side makerSide = incomingSide.opposite();
        book.addResting(order("MAKER", makerSide, 100, 4));
        long incomingLimit = incomingSide == Side.BUY ? 99 : 101;
        MatchResult result = book.executeLimit(order("TAKER", incomingSide, incomingLimit, 5));

        assertThat(result.trades()).isEmpty();
        assertThat(result.restingQuantity()).isEqualTo(5);
        assertThat(book.restingOrderCount()).isEqualTo(2);
    }

    @Test
    void exactMatchRemovesMakerAndEmptyPriceLevel() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("ASK", Side.SELL, 100, 3));
        MatchResult result = book.executeLimit(order("BUY", Side.BUY, 100, 3));
        assertThat(result.fullyExecuted()).isTrue();
        assertThat(result.restingQuantity()).isZero();
        assertThat(book.restingOrderCount()).isZero();
        assertThat(book.priceLevelCount(Side.SELL)).isZero();
        assertThat(book.find(new OrderId("ASK"))).isEmpty();
        assertThat(book.depth(Side.SELL, 1)).isEmpty();
    }

    @Test
    void fullBookRejectsUncrossedRemainderWithoutMutating() {
        LimitOrderBook book = new LimitOrderBook(DEMO, 1);
        book.addResting(order("ASK", Side.SELL, 101, 3));
        assertThatThrownBy(() -> book.executeLimit(order("BUY", Side.BUY, 100, 2)))
                .isInstanceOfSatisfying(OrderValidationException.class,
                        e -> assertThat(e.code()).isEqualTo(RejectionCode.BOOK_CAPACITY_EXCEEDED));
        assertThat(book.bestOrder(Side.SELL).orElseThrow().remainingQuantity()).isEqualTo(3);
        assertThat(book.find(new OrderId("BUY"))).isEmpty();
        assertThat(book.restingOrderCount()).isEqualTo(1);
    }

    @Test
    void fullBookCanPartiallyFillMakerWithoutRequiringAnotherSlot() {
        LimitOrderBook book = new LimitOrderBook(DEMO, 1);
        book.addResting(order("ASK", Side.SELL, 100, 6));
        MatchResult result = book.executeLimit(order("BUY", Side.BUY, 100, 3));
        assertThat(result.executedQuantity()).isEqualTo(3);
        assertThat(result.restingQuantity()).isZero();
        assertThat(book.find(new OrderId("ASK")).orElseThrow().remainingQuantity()).isEqualTo(3);
        assertThat(book.find(new OrderId("BUY"))).isEmpty();
    }

    @Test
    void fullBookCanFreeMakerSlotThenRestRemainder() {
        LimitOrderBook book = new LimitOrderBook(DEMO, 1);
        book.addResting(order("ASK", Side.SELL, 100, 2));
        MatchResult result = book.executeLimit(order("BUY", Side.BUY, 100, 5));
        assertThat(result.executedQuantity()).isEqualTo(2);
        assertThat(result.restingQuantity()).isEqualTo(3);
        assertThat(book.find(new OrderId("ASK"))).isEmpty();
        assertThat(book.find(new OrderId("BUY")).orElseThrow().remainingQuantity()).isEqualTo(3);
        assertThat(book.restingOrderCount()).isEqualTo(1);
    }

    @Test
    void fullBookAllowsFullyExecutedIncomingOrderWithoutFreeingASlot() {
        LimitOrderBook book = new LimitOrderBook(DEMO, 1);
        book.addResting(order("ASK", Side.SELL, 100, 10));
        MatchResult result = book.executeLimit(order("BUY", Side.BUY, 100, 3));
        assertThat(result.fullyExecuted()).isTrue();
        assertThat(book.find(new OrderId("ASK")).orElseThrow().remainingQuantity()).isEqualTo(7);
        assertThat(book.restingOrderCount()).isEqualTo(1);
    }

    @Test
    void capacityRejectDoesNotConsumeNextInsertionOrdinal() {
        LimitOrderBook book = new LimitOrderBook(DEMO, 2);
        book.addResting(order("ASK1", Side.SELL, 100, 5));
        book.addResting(order("ASK2", Side.SELL, 101, 5));
        assertThatThrownBy(() -> book.executeLimit(order("BUY", Side.BUY, 99, 6)))
                .isInstanceOfSatisfying(OrderValidationException.class,
                        e -> assertThat(e.code()).isEqualTo(RejectionCode.BOOK_CAPACITY_EXCEEDED));
        // No maker crossed, so the rejection cannot consume any ordinal or quantity.
        assertThat(book.find(new OrderId("ASK1")).orElseThrow().insertionOrdinal()).isEqualTo(1);
        assertThat(book.find(new OrderId("ASK1")).orElseThrow().remainingQuantity()).isEqualTo(5);
        assertThat(book.find(new OrderId("ASK2")).orElseThrow().insertionOrdinal()).isEqualTo(2);
        // A fully consumed first maker frees a slot; the new remainder gets ordinal 3.
        MatchResult accepted = book.executeLimit(order("BUY", Side.BUY, 100, 7));
        assertThat(accepted.restingQuantity()).isEqualTo(2);
        assertThat(book.find(new OrderId("BUY")).orElseThrow().insertionOrdinal()).isEqualTo(3);
    }

    @Test
    void duplicateActiveIdFailsBeforeAnyMatching() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("SAME", Side.BUY, 101, 4));
        book.addResting(order("ASK", Side.SELL, 102, 4));
        assertThatThrownBy(() -> book.executeLimit(order("SAME", Side.BUY, 102, 4)))
                .isInstanceOfSatisfying(OrderValidationException.class,
                        e -> assertThat(e.code()).isEqualTo(RejectionCode.DUPLICATE_ORDER_ID));
        assertThat(book.find(new OrderId("SAME")).orElseThrow().remainingQuantity()).isEqualTo(4);
        assertThat(book.find(new OrderId("ASK")).orElseThrow().remainingQuantity()).isEqualTo(4);
    }

    @Test
    void wrongInstrumentFailsBeforeMutatingOppositeBook() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("ASK", Side.SELL, 100, 5));
        PlaceLimitOrder wrong = new PlaceLimitOrder(new InstrumentId("OTHER"), new OrderId("BUY"),
                Side.BUY, new PriceTicks(100), new OrderQuantity(5));
        assertThatThrownBy(() -> book.executeLimit(wrong))
                .isInstanceOfSatisfying(OrderValidationException.class,
                        e -> assertThat(e.code()).isEqualTo(RejectionCode.INSTRUMENT_MISMATCH));
        assertThat(book.find(new OrderId("ASK")).orElseThrow().remainingQuantity()).isEqualTo(5);
    }

    @Test
    void nullCommandRejectsWithoutMutation() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        assertThatThrownBy(() -> book.executeLimit(null))
                .isInstanceOfSatisfying(OrderValidationException.class,
                        e -> assertThat(e.code()).isEqualTo(RejectionCode.INVALID_COMMAND));
        assertThat(book.restingOrderCount()).isZero();
    }

    @Test
    void maxAllowedOrderQuantitiesStillConserveQuantity() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.executeLimit(order("S1", Side.SELL, PriceTicks.MAX, OrderQuantity.MAX));
        MatchResult result = book.executeLimit(order("B1", Side.BUY, PriceTicks.MAX, OrderQuantity.MAX));
        assertThat(result.executedQuantity()).isEqualTo(OrderQuantity.MAX);
        assertThat(result.restingQuantity()).isZero();
        assertThat(result.trades()).hasSize(1);
        assertThat(result.trades().getFirst().price().value()).isEqualTo(PriceTicks.MAX);
        assertThat(book.restingOrderCount()).isZero();
    }

    @Test
    void detachedViewsDoNotChangeWhenTheUnderlyingMakerIsPartiallyFilled() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("ASK", Side.SELL, 100, 9));
        RestingOrderView before = book.find(new OrderId("ASK")).orElseThrow();
        List<LevelDepth> depthBefore = book.depth(Side.SELL, 1);
        book.executeLimit(order("BUY", Side.BUY, 100, 4));

        assertThat(before.remainingQuantity()).isEqualTo(9);
        assertThat(depthBefore).containsExactly(new LevelDepth(new PriceTicks(100), 9, 1));
        assertThat(book.find(new OrderId("ASK")).orElseThrow().remainingQuantity()).isEqualTo(5);
        assertThat(book.depth(Side.SELL, 1)).containsExactly(new LevelDepth(new PriceTicks(100), 5, 1));
    }

    @Test
    void tradeResultsAndTradeListsAreImmutableAndDetached() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("ASK", Side.SELL, 100, 3));
        MatchResult result = book.executeLimit(order("BUY", Side.BUY, 100, 3));
        assertThatThrownBy(() -> result.trades().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(result.trades()).hasSize(1);
        assertThat(book.restingOrderCount()).isZero();
        List<TradeExecution> mutable = new ArrayList<>(result.trades());
        MatchResult copied = new MatchResult(result.command(), 3, 0, mutable);
        mutable.clear();
        assertThat(copied.trades()).hasSize(1);
    }

    @Test
    void resultRejectsIncorrectTradeConservationAndOwnership() {
        PlaceLimitOrder cmd = order("BUY", Side.BUY, 101, 3);
        TradeExecution valid = new TradeExecution(DEMO, new OrderId("ASK"),
                cmd.orderId(), Side.BUY, new PriceTicks(100), 2);
        assertThatThrownBy(() -> new MatchResult(cmd, 3, 0, List.of(valid)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchResult(cmd, 2, 2, List.of(valid)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchResult(cmd, 2, 1, List.of(new TradeExecution(
                DEMO, new OrderId("ASK"), new OrderId("WRONG"), Side.BUY, new PriceTicks(100), 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tradeCannotBeZeroQuantityOrHaveSameMakerAndTaker() {
        assertThatThrownBy(() -> new TradeExecution(DEMO, new OrderId("A"), new OrderId("B"),
                Side.SELL, new PriceTicks(100), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TradeExecution(DEMO, new OrderId("A"), new OrderId("A"),
                Side.SELL, new PriceTicks(100), 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lowLevelSeedingStillRefusesCrossedOrdersRatherThanTrading() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(order("BID", Side.BUY, 101, 5));
        assertThatThrownBy(() -> book.addResting(order("ASK", Side.SELL, 100, 5)))
                .isInstanceOfSatisfying(OrderValidationException.class,
                        e -> assertThat(e.code()).isEqualTo(RejectionCode.INVALID_COMMAND));
        assertThat(book.restingOrderCount()).isEqualTo(1);
    }
}
