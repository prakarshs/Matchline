package dev.matchline.book;

import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.Side;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent sorted-map/list reference for book-only insertion semantics. */
class LimitOrderBookModelTest {
    @Test
    void seededInsertionsAgreeWithIndependentReferenceModel() {
        InstrumentId symbol = new InstrumentId("DEMO");
        LimitOrderBook book = new LimitOrderBook(symbol, 2_000);
        NavigableMap<Long, List<Expected>> bids = new TreeMap<>((a, b) -> Long.compare(b, a));
        NavigableMap<Long, List<Expected>> asks = new TreeMap<>();
        Map<String, Expected> byId = new HashMap<>();
        Random random = new Random(0xC0FFEE);

        for (int i = 0; i < 1_000; i++) {
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            long price = side == Side.BUY ? 50 + random.nextInt(50) : 100 + random.nextInt(50);
            long quantity = 1 + random.nextInt(1_000);
            String id = "ORDER-" + i;
            PlaceLimitOrder command = new PlaceLimitOrder(symbol, new OrderId(id), side,
                    new PriceTicks(price), new OrderQuantity(quantity));
            RestingOrderView inserted = book.addResting(command);
            assertThat(inserted.insertionOrdinal()).isEqualTo(i + 1L);
            NavigableMap<Long, List<Expected>> model = side == Side.BUY ? bids : asks;
            Expected expected = new Expected(id, side, price, quantity, i + 1L);
            model.computeIfAbsent(price, ignored -> new ArrayList<>()).add(expected);
            byId.put(id, expected);
        }

        assertThat(book.restingOrderCount()).isEqualTo(byId.size());
        for (Side side : Side.values()) {
            NavigableMap<Long, List<Expected>> expectedSide = side == Side.BUY ? bids : asks;
            assertThat(book.priceLevelCount(side)).isEqualTo(expectedSide.size());
            List<LevelDepth> actualDepth = book.depth(side, 1_000);
            assertThat(actualDepth).hasSize(expectedSide.size());
            int levelIndex = 0;
            for (Map.Entry<Long, List<Expected>> level : expectedSide.entrySet()) {
                long total = level.getValue().stream().mapToLong(Expected::qty).sum();
                assertThat(actualDepth.get(levelIndex++))
                        .isEqualTo(new LevelDepth(new PriceTicks(level.getKey()), total, level.getValue().size()));
                List<RestingOrderView> actualQueue = book.ordersAt(side, new PriceTicks(level.getKey()));
                assertThat(actualQueue).extracting(order -> order.orderId().value())
                        .containsExactlyElementsOf(level.getValue().stream().map(Expected::id).toList());
                assertThat(actualQueue).extracting(RestingOrderView::insertionOrdinal)
                        .containsExactlyElementsOf(level.getValue().stream().map(Expected::ordinal).toList());
            }
            Expected first = expectedSide.firstEntry().getValue().getFirst();
            assertThat(book.bestOrder(side).orElseThrow().orderId().value()).isEqualTo(first.id());
        }
        for (Expected expected : byId.values()) {
            RestingOrderView found = book.find(new OrderId(expected.id())).orElseThrow();
            assertThat(found.price().value()).isEqualTo(expected.price());
            assertThat(found.side()).isEqualTo(expected.side());
            assertThat(found.remainingQuantity()).isEqualTo(expected.qty());
            assertThat(found.insertionOrdinal()).isEqualTo(expected.ordinal());
        }
    }

    private record Expected(String id, Side side, long price, long qty, long ordinal) { }
}
