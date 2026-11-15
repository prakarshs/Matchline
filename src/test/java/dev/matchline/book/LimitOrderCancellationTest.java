package dev.matchline.book;

import dev.matchline.domain.CancelOrder;
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

class LimitOrderCancellationTest {
    private static final InstrumentId DEMO = new InstrumentId("DEMO");

    private static PlaceLimitOrder place(String id, Side side, long price, long quantity) {
        return new PlaceLimitOrder(DEMO, new OrderId(id), side,
                new PriceTicks(price), new OrderQuantity(quantity));
    }

    private static CancelOrder cancel(String id) {
        return new CancelOrder(DEMO, new OrderId(id));
    }

    private static void rejects(RejectionCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(OrderValidationException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void cancellationRemovesOnlyTargetOrderAndUpdatesPriceDepth(Side side) {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(place("OLD", side, 100, 7));
        book.addResting(place("MIDDLE", side, 100, 4));
        book.addResting(place("LAST", side, 100, 2));
        RestingOrderView oldView = book.find(new OrderId("OLD")).orElseThrow();

        CancelResult result = book.cancel(cancel("MIDDLE"));
        assertThat(result.cancelledQuantity()).isEqualTo(4);
        assertThat(result.cancelledOrder().originalQuantity()).isEqualTo(4);
        assertThat(result.command()).isEqualTo(cancel("MIDDLE"));
        assertThat(book.ordersAt(side, new PriceTicks(100)))
                .extracting(x -> x.orderId().value()).containsExactly("OLD", "LAST");
        assertThat(book.depth(side, 1))
                .containsExactly(new LevelDepth(new PriceTicks(100), 9, 2));
        assertThat(book.find(new OrderId("MIDDLE"))).isEmpty();
        assertThat(oldView.remainingQuantity()).isEqualTo(7);
        assertThat(book.acceptedOrderIdCount()).isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(Side.class)
    void cancellingLastOrderRemovesTheEmptyPriceLevel(Side side) {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.executeLimit(place("ONLY", side, 100, 5));
        assertThat(book.cancel(cancel("ONLY")).cancelledQuantity()).isEqualTo(5);
        assertThat(book.restingOrderCount()).isZero();
        assertThat(book.priceLevelCount(side)).isZero();
        assertThat(book.depth(side, 3)).isEmpty();
        assertThat(book.bestOrder(side)).isEmpty();
        assertThat(book.acceptedOrderIdCount()).isEqualTo(1);
    }

    @Test
    void cancellationBeforeAnOpposingOrderPreventsAnyTradeWithCancelledMaker() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.executeLimit(place("B1", Side.BUY, 100, 3));
        book.executeLimit(place("B2", Side.BUY, 100, 2));
        book.cancel(cancel("B1"));
        MatchResult match = book.executeLimit(place("S1", Side.SELL, 100, 4));
        assertThat(match.trades()).extracting(x -> x.makerOrderId().value())
                .containsExactly("B2");
        assertThat(match.executedQuantity()).isEqualTo(2);
        assertThat(match.restingQuantity()).isEqualTo(2);
        assertThat(book.find(new OrderId("B1"))).isEmpty();
        assertThat(book.find(new OrderId("S1")).orElseThrow().remainingQuantity()).isEqualTo(2);
    }

    @Test
    void fillBeforeCancellationRemovesOnlyRemainingQuantity() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.executeLimit(place("B1", Side.BUY, 100, 9));
        MatchResult earlier = book.executeLimit(place("S1", Side.SELL, 100, 4));
        assertThat(earlier.trades()).hasSize(1);
        CancelResult result = book.cancel(cancel("B1"));
        assertThat(result.cancelledOrder().originalQuantity()).isEqualTo(9);
        assertThat(result.cancelledQuantity()).isEqualTo(5);
        assertThat(book.restingOrderCount()).isZero();
        assertThat(earlier.trades().getFirst().quantity()).isEqualTo(4);
        assertThat(book.acceptedOrderIdCount()).isEqualTo(2);
    }

    @Test
    void cancelAfterFullMakerFillRejectsButDoesNotErasePastTrades() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.executeLimit(place("ASK", Side.SELL, 100, 3));
        MatchResult executed = book.executeLimit(place("BUY", Side.BUY, 100, 3));
        rejects(RejectionCode.ORDER_NOT_FOUND, () -> book.cancel(cancel("ASK")));
        rejects(RejectionCode.ORDER_NOT_FOUND, () -> book.cancel(cancel("BUY")));
        assertThat(executed.trades()).hasSize(1);
        assertThat(book.acceptedOrderIdCount()).isEqualTo(2);
        assertThat(book.restingOrderCount()).isZero();
    }

    @Test
    void duplicateCancellationRejectsWithoutChangingState() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.executeLimit(place("B1", Side.BUY, 100, 2));
        book.cancel(cancel("B1"));
        rejects(RejectionCode.ORDER_NOT_FOUND, () -> book.cancel(cancel("B1")));
        assertThat(book.acceptedOrderIdCount()).isEqualTo(1);
        assertThat(book.restingOrderCount()).isZero();
    }

    @Test
    void unknownCancelDoesNotReserveAnId() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        rejects(RejectionCode.ORDER_NOT_FOUND, () -> book.cancel(cancel("UNUSED")));
        assertThat(book.acceptedOrderIdCount()).isZero();
        book.executeLimit(place("UNUSED", Side.BUY, 100, 4));
        assertThat(book.find(new OrderId("UNUSED"))).isPresent();
        assertThat(book.acceptedOrderIdCount()).isEqualTo(1);
    }

    @Test
    void wrongInstrumentCancelCannotRemoveExistingOrder() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.executeLimit(place("B1", Side.BUY, 100, 7));
        CancelOrder wrong = new CancelOrder(new InstrumentId("OTHER"), new OrderId("B1"));
        rejects(RejectionCode.INSTRUMENT_MISMATCH, () -> book.cancel(wrong));
        assertThat(book.find(new OrderId("B1")).orElseThrow().remainingQuantity()).isEqualTo(7);
        assertThat(book.acceptedOrderIdCount()).isEqualTo(1);
    }

    @Test
    void nullCancelIsTypedRejection() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        rejects(RejectionCode.INVALID_COMMAND, () -> book.cancel(null));
        assertThat(book.restingOrderCount()).isZero();
    }

    @Test
    void cancelledIdCannotBeReusedByEitherInsertionPath() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(place("ONCE", Side.BUY, 100, 2));
        book.cancel(cancel("ONCE"));
        rejects(RejectionCode.DUPLICATE_ORDER_ID,
                () -> book.addResting(place("ONCE", Side.BUY, 90, 1)));
        rejects(RejectionCode.DUPLICATE_ORDER_ID,
                () -> book.executeLimit(place("ONCE", Side.SELL, 110, 1)));
        assertThat(book.restingOrderCount()).isZero();
        assertThat(book.acceptedOrderIdCount()).isEqualTo(1);
    }

    @Test
    void fullyFilledMakerAndTakerIdsRemainReserved() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.executeLimit(place("S", Side.SELL, 100, 3));
        book.executeLimit(place("B", Side.BUY, 100, 3));
        for (String id : List.of("S", "B")) {
            rejects(RejectionCode.DUPLICATE_ORDER_ID,
                    () -> book.executeLimit(place(id, Side.BUY, 100, 1)));
            rejects(RejectionCode.DUPLICATE_ORDER_ID,
                    () -> book.addResting(place(id, Side.BUY, 100, 1)));
        }
        assertThat(book.acceptedOrderIdCount()).isEqualTo(2);
    }

    @Test
    void rejectedPlaceDoesNotReserveIdOrChangeOrdinal() {
        LimitOrderBook book = new LimitOrderBook(DEMO, 1);
        book.addResting(place("ASK", Side.SELL, 100, 2));
        rejects(RejectionCode.BOOK_CAPACITY_EXCEEDED,
                () -> book.executeLimit(place("NEW", Side.BUY, 99, 1)));
        assertThat(book.acceptedOrderIdCount()).isEqualTo(1);
        book.cancel(cancel("ASK"));
        RestingOrderView view = book.addResting(place("NEW", Side.BUY, 99, 1));
        assertThat(view.insertionOrdinal()).isEqualTo(2);
        assertThat(book.acceptedOrderIdCount()).isEqualTo(2);
    }

    @Test
    void rejectedCrossedSeedDoesNotReserveId() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.addResting(place("ASK", Side.SELL, 100, 2));
        rejects(RejectionCode.INVALID_COMMAND,
                () -> book.addResting(place("BUY", Side.BUY, 101, 1)));
        assertThat(book.acceptedOrderIdCount()).isEqualTo(1);
        book.executeLimit(place("BUY", Side.BUY, 101, 1));
        assertThat(book.acceptedOrderIdCount()).isEqualTo(2);
    }

    @Test
    void orderIdHistoryCapacityIsCheckedBeforeExecutingAnyTrade() {
        LimitOrderBook book = new LimitOrderBook(DEMO, 4, 2);
        book.executeLimit(place("ASK", Side.SELL, 100, 5));
        book.executeLimit(place("B1", Side.BUY, 100, 2));
        // A third order would cross ASK and fully execute; the engine MUST reject
        // before modifying the remaining maker quantity.
        rejects(RejectionCode.ORDER_ID_HISTORY_CAPACITY_EXCEEDED,
                () -> book.executeLimit(place("B2", Side.BUY, 100, 3)));
        assertThat(book.find(new OrderId("ASK")).orElseThrow().remainingQuantity()).isEqualTo(3);
        assertThat(book.depth(Side.SELL, 1)).containsExactly(new LevelDepth(new PriceTicks(100), 3, 1));
        assertThat(book.acceptedOrderIdCount()).isEqualTo(2);
        rejects(RejectionCode.DUPLICATE_ORDER_ID,
                () -> book.executeLimit(place("B1", Side.BUY, 100, 1)));
    }

    @Test
    void historyCapacityBlocksSeedingEvenWhenBookIsEmpty() {
        LimitOrderBook book = new LimitOrderBook(DEMO, 2, 1);
        book.addResting(place("B1", Side.BUY, 100, 1));
        book.cancel(cancel("B1"));
        rejects(RejectionCode.ORDER_ID_HISTORY_CAPACITY_EXCEEDED,
                () -> book.addResting(place("B2", Side.BUY, 100, 1)));
        assertThat(book.restingOrderCount()).isZero();
    }

    @Test
    void idHistoryConfigurationBoundariesAreValidated() {
        for (int value : new int[]{-1, 0, LimitOrderBook.HARD_MAX_ORDER_IDS + 1}) {
            assertThatThrownBy(() -> new LimitOrderBook(DEMO, 1, value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new LimitOrderBook(DEMO, 1, 1).acceptedOrderIdCount()).isZero();
        assertThat(new LimitOrderBook(DEMO, 1).acceptedOrderIdCount()).isZero();
    }

    @Test
    void identityIsScopedToEachBookInstanceAndCaseSensitive() {
        LimitOrderBook first = new LimitOrderBook(DEMO);
        LimitOrderBook second = new LimitOrderBook(DEMO);
        first.executeLimit(place("abc", Side.BUY, 100, 1));
        first.executeLimit(place("ABC", Side.BUY, 100, 1));
        second.executeLimit(place("abc", Side.BUY, 100, 1));
        assertThat(first.acceptedOrderIdCount()).isEqualTo(2);
        assertThat(second.acceptedOrderIdCount()).isEqualTo(1);
    }

    @Test
    void cancelAndReplaceUsesFreshIdAndLosesOldPriceTimePriority() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.executeLimit(place("FIRST", Side.BUY, 100, 3));
        book.executeLimit(place("SECOND", Side.BUY, 100, 2));
        book.cancel(cancel("FIRST"));
        book.executeLimit(place("REPLACEMENT", Side.BUY, 100, 3));
        assertThat(book.ordersAt(Side.BUY, new PriceTicks(100)))
                .extracting(x -> x.orderId().value()).containsExactly("SECOND", "REPLACEMENT");
        MatchResult result = book.executeLimit(place("SELL", Side.SELL, 100, 4));
        assertThat(result.trades()).extracting(x -> x.makerOrderId().value())
                .containsExactly("SECOND", "REPLACEMENT");
        assertThat(result.trades()).extracting(TradeExecution::quantity).containsExactly(2L, 2L);
        rejects(RejectionCode.DUPLICATE_ORDER_ID,
                () -> book.executeLimit(place("FIRST", Side.BUY, 100, 1)));
    }

    @Test
    void cancelledOrderViewIsDetachedAndQuantityMatchesActiveRemaining() {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        book.executeLimit(place("SELL", Side.SELL, 105, 9));
        book.executeLimit(place("BUY", Side.BUY, 105, 6));
        CancelResult result = book.cancel(cancel("SELL"));
        assertThat(result.cancelledOrder().originalQuantity()).isEqualTo(9);
        assertThat(result.cancelledQuantity()).isEqualTo(3);
        assertThat(book.find(new OrderId("SELL"))).isEmpty();
        assertThat(result.cancelledOrder().remainingQuantity()).isEqualTo(3);
    }

    @Test
    void cancelResultRejectsMismatchedIdAndMissingFields() {
        RestingOrderView view = new RestingOrderView(new OrderId("X"), Side.BUY,
                new PriceTicks(100), 2, 1, 1);
        assertThatThrownBy(() -> new CancelResult(cancel("OTHER"), view))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CancelResult(null, view))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CancelResult(cancel("X"), null))
                .isInstanceOf(NullPointerException.class);
    }
}
