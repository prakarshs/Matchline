package dev.matchline.recovery;

import dev.matchline.book.BookSnapshot;
import dev.matchline.book.LimitOrderBook;
import dev.matchline.book.RestingOrderView;
import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BookSnapshotRestoreTest {
    private static final InstrumentId DEMO = new InstrumentId("DEMO");
    private static final int CAP = 30;
    private static final int IDS = 100;
    private static final PriceTicks PRICE = new PriceTicks(100);
    private static OrderId id(String value) { return new OrderId(value); }
    private static RestingOrderView order(String id, Side side, long ordinal) {
        return new RestingOrderView(id(id), side, PRICE, 5, 3, ordinal);
    }

    @Test
    void preservesHistoricalInsertionOrdinalWhenOnlyHigherWasCancelled() {
        LimitOrderBook original = new LimitOrderBook(DEMO, CAP, IDS);
        original.addResting(new PlaceLimitOrder(DEMO, id("A"), Side.BUY, PRICE, new OrderQuantity(5)));
        original.addResting(new PlaceLimitOrder(DEMO, id("B"), Side.BUY, PRICE, new OrderQuantity(5)));
        original.cancel(new dev.matchline.domain.CancelOrder(DEMO, id("B")));
        BookSnapshot captured = original.snapshot(3);
        LimitOrderBook restored = LimitOrderBook.restore(captured, CAP, IDS);
        assertEquals(captured, restored.snapshot(3));
        restored.addResting(new PlaceLimitOrder(DEMO, id("C"), Side.BUY, PRICE, new OrderQuantity(2)));
        assertEquals(3, restored.find(id("C")).orElseThrow().insertionOrdinal());
    }

    @Test
    void rejectsDuplicateActiveIds() {
        BookSnapshot bad = new BookSnapshot(DEMO, 2, CAP, IDS, 2,
                List.of(id("A")), List.of(order("A", Side.BUY, 1), order("A", Side.BUY, 2)));
        assertThrows(IllegalArgumentException.class, () -> LimitOrderBook.restore(bad, CAP, IDS));
    }

    @Test
    void rejectsNonIncreasingOrdinals() {
        BookSnapshot bad = new BookSnapshot(DEMO, 2, CAP, IDS, 4,
                List.of(id("A"), id("B")), List.of(order("A", Side.BUY, 3), order("B", Side.BUY, 2)));
        assertThrows(IllegalArgumentException.class, () -> LimitOrderBook.restore(bad, CAP, IDS));
    }

    @Test
    void rejectsUnacceptedRestingId() {
        BookSnapshot bad = new BookSnapshot(DEMO, 1, CAP, IDS, 1,
                List.of(), List.of(order("A", Side.BUY, 1)));
        assertThrows(IllegalArgumentException.class, () -> LimitOrderBook.restore(bad, CAP, IDS));
    }

    @Test
    void rejectsCrossedRestingSides() {
        BookSnapshot bad = new BookSnapshot(DEMO, 2, CAP, IDS, 2,
                List.of(id("A"), id("B")), List.of(order("A", Side.BUY, 1), order("B", Side.SELL, 2)));
        assertThrows(IllegalArgumentException.class, () -> LimitOrderBook.restore(bad, CAP, IDS));
    }

    @Test
    void rejectsUnsortedAcceptedIdHistory() {
        BookSnapshot bad = new BookSnapshot(DEMO, 2, CAP, IDS, 0,
                List.of(id("B"), id("A")), List.of());
        assertThrows(IllegalArgumentException.class, () -> LimitOrderBook.restore(bad, CAP, IDS));
    }

    @Test
    void rejectsDifferentConfiguredCapacity() {
        BookSnapshot good = new BookSnapshot(DEMO, 0, CAP, IDS, 0, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> LimitOrderBook.restore(good, CAP + 1, IDS));
    }
}
