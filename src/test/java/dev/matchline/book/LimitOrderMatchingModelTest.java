package dev.matchline.book;

import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.Side;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Intentionally different reference implementation: two unsorted order lists,
 * resorted on every incoming command. It does not use LimitOrderBook internals.
 */
class LimitOrderMatchingModelTest {
    private static final InstrumentId DEMO = new InstrumentId("DEMO");

    @Test
    void fixedSeedFlowAgreesWithIndependentListBasedReferenceAfterEveryCommand() {
        LimitOrderBook actual = new LimitOrderBook(DEMO);
        Reference model = new Reference();
        Random random = new Random(0x4D_41_54_43_48_4CL);
        long inputQuantity = 0;
        long executedQuantity = 0;

        for (int i = 0; i < 2_500; i++) {
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            int price = 93 + random.nextInt(17);
            int qty = 1 + random.nextInt(30);
            PlaceLimitOrder command = new PlaceLimitOrder(DEMO, new OrderId("CMD-" + i), side,
                    new PriceTicks(price), new OrderQuantity(qty));
            inputQuantity += qty;

            MatchResult result = actual.executeLimit(command);
            List<ExpectedTrade> expectedTrades = model.apply(command);
            executedQuantity += result.executedQuantity();
            assertThat(result.trades().stream().map(t -> new ExpectedTrade(
                    t.makerOrderId().value(), t.takerOrderId().value(), t.price().value(), t.quantity())).toList())
                    .as("trade sequence for command %s", i)
                    .containsExactlyElementsOf(expectedTrades);
            assertThat(result.trades().stream().mapToLong(TradeExecution::quantity).sum())
                    .isEqualTo(result.executedQuantity());
            assertThat(result.executedQuantity() + result.restingQuantity()).isEqualTo(qty);

            for (Side current : Side.values()) {
                List<ExpectedOrder> expected = model.snapshot(current);
                List<RestingOrderView> fromBook = new ArrayList<>();
                for (LevelDepth level : actual.depth(current, 1000)) {
                    List<RestingOrderView> levelOrders = actual.ordersAt(current, level.price());
                    assertThat(level.totalQuantity()).isEqualTo(levelOrders.stream()
                            .mapToLong(RestingOrderView::remainingQuantity).sum());
                    assertThat(level.orderCount()).isEqualTo(levelOrders.size());
                    fromBook.addAll(levelOrders);
                }
                assertThat(fromBook.stream().map(o -> new ExpectedOrder(o.orderId().value(),
                        o.side(), o.price().value(), o.remainingQuantity(), o.insertionOrdinal())).toList())
                        .as("resting %s after command %s", current, i)
                        .containsExactlyElementsOf(expected);
            }
            assertThat(actual.restingOrderCount()).isEqualTo(model.orders.size());
            // Every trade transfers one unit from each side; the total number of
            // resting units equals all submitted units minus 2x traded units.
            long resting = model.orders.stream().mapToLong(o -> o.remaining).sum();
            assertThat(resting).isEqualTo(inputQuantity - 2 * executedQuantity);
            if (actual.bestOrder(Side.BUY).isPresent() && actual.bestOrder(Side.SELL).isPresent()) {
                assertThat(actual.bestOrder(Side.BUY).orElseThrow().price().value())
                        .isLessThan(actual.bestOrder(Side.SELL).orElseThrow().price().value());
            }
        }
    }

    @Test
    void replayingTheSameInMemoryCommandListProducesIdenticalLogicalStateAndTrades() {
        Random random = new Random(0xF1_11L);
        List<PlaceLimitOrder> commands = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            commands.add(new PlaceLimitOrder(DEMO, new OrderId("R-" + i),
                    random.nextBoolean() ? Side.BUY : Side.SELL,
                    new PriceTicks(95 + random.nextInt(11)),
                    new OrderQuantity(1 + random.nextInt(25))));
        }
        EngineTrace first = run(commands);
        EngineTrace second = run(commands);
        assertThat(second).isEqualTo(first);
    }

    private static EngineTrace run(List<PlaceLimitOrder> commands) {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        List<TradeExecution> trades = new ArrayList<>();
        for (PlaceLimitOrder command : commands) {
            trades.addAll(book.executeLimit(command).trades());
        }
        List<RestingOrderView> bids = snapshot(book, Side.BUY);
        List<RestingOrderView> asks = snapshot(book, Side.SELL);
        return new EngineTrace(List.copyOf(bids), List.copyOf(asks), List.copyOf(trades));
    }

    private static List<RestingOrderView> snapshot(LimitOrderBook book, Side side) {
        List<RestingOrderView> all = new ArrayList<>();
        for (LevelDepth level : book.depth(side, 1000)) {
            all.addAll(book.ordersAt(side, level.price()));
        }
        return List.copyOf(all);
    }

    private record EngineTrace(List<RestingOrderView> bids, List<RestingOrderView> asks,
                               List<TradeExecution> trades) { }
    private record ExpectedTrade(String maker, String taker, long price, long qty) { }
    private record ExpectedOrder(String id, Side side, long price, long remaining, long ordinal) { }

    private static final class Reference {
        final List<MutableOrder> orders = new ArrayList<>();
        long nextOrdinal;

        List<ExpectedTrade> apply(PlaceLimitOrder command) {
            long remaining = command.quantity().value();
            List<ExpectedTrade> trades = new ArrayList<>();
            while (remaining > 0) {
                List<MutableOrder> candidates = orders.stream()
                        .filter(o -> o.side != command.side()
                                && command.side().crosses(command.price(), new PriceTicks(o.price)))
                        .sorted(Comparator.comparingLong((MutableOrder o) -> o.price)
                                .thenComparingLong(o -> o.ordinal))
                        .toList();
                // For a SELL taker, price priority is highest BUY first.
                if (command.side() == Side.SELL) {
                    candidates = orders.stream()
                            .filter(o -> o.side == Side.BUY && o.price >= command.price().value())
                            .sorted(Comparator.comparingLong((MutableOrder o) -> o.price).reversed()
                                    .thenComparingLong(o -> o.ordinal))
                            .toList();
                }
                if (candidates.isEmpty()) {
                    break;
                }
                MutableOrder maker = candidates.getFirst();
                long fill = Math.min(remaining, maker.remaining);
                remaining -= fill;
                maker.remaining -= fill;
                trades.add(new ExpectedTrade(maker.id, command.orderId().value(), maker.price, fill));
                if (maker.remaining == 0) {
                    orders.remove(maker);
                }
            }
            if (remaining > 0) {
                orders.add(new MutableOrder(command.orderId().value(), command.side(),
                        command.price().value(), remaining, ++nextOrdinal));
            }
            return trades;
        }

        List<ExpectedOrder> snapshot(Side side) {
            Comparator<MutableOrder> best = Comparator.comparingLong((MutableOrder o) -> o.price);
            if (side == Side.BUY) {
                best = best.reversed();
            }
            return orders.stream().filter(o -> o.side == side)
                    .sorted(best.thenComparingLong(o -> o.ordinal))
                    .map(o -> new ExpectedOrder(o.id, o.side, o.price, o.remaining, o.ordinal)).toList();
        }
    }

    private static final class MutableOrder {
        final String id;
        final Side side;
        final long price;
        long remaining;
        final long ordinal;

        MutableOrder(String id, Side side, long price, long remaining, long ordinal) {
            this.id = id;
            this.side = side;
            this.price = price;
            this.remaining = remaining;
            this.ordinal = ordinal;
        }
    }
}
