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
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent list-based oracle. It does NOT call the book's algorithms. */
class LimitOrderCancellationModelTest {
    private static final InstrumentId DEMO = new InstrumentId("DEMO");

    @ParameterizedTest
    @ValueSource(longs = {7L, 99L, 12_345L})
    void fixedSeedMixedPlacesFillsCancelsAndDuplicatesMatchIndependentModel(long seed) {
        Random rng = new Random(seed);
        Reference oracle = new Reference();
        LimitOrderBook book = new LimitOrderBook(DEMO);
        List<String> placedIds = new ArrayList<>();
        long inputQuantity = 0;
        long tradedQuantity = 0;
        long cancelledQuantity = 0;

        for (int i = 0; i < 1_500; i++) {
            int action = rng.nextInt(100);
            if (action < 64) {
                String id = "NEW-" + i;
                PlaceLimitOrder command = new PlaceLimitOrder(DEMO, new OrderId(id),
                        rng.nextBoolean() ? Side.BUY : Side.SELL,
                        new PriceTicks(96 + rng.nextInt(9)),
                        new OrderQuantity(1 + rng.nextInt(25)));
                List<ModelTrade> expected = oracle.place(command);
                MatchResult result = book.executeLimit(command);
                assertThat(result.trades().stream().map(t -> new ModelTrade(
                        t.makerOrderId().value(), t.takerOrderId().value(),
                        t.price().value(), t.quantity())).toList())
                        .as("fills at step %s, seed %s", i, seed)
                        .containsExactlyElementsOf(expected);
                assertThat(result.executedQuantity())
                        .isEqualTo(expected.stream().mapToLong(ModelTrade::quantity).sum());
                inputQuantity += command.quantity().value();
                tradedQuantity += result.executedQuantity();
                placedIds.add(id);
            } else if (action < 89) {
                String id = !placedIds.isEmpty() && rng.nextInt(10) < 8
                        ? placedIds.get(rng.nextInt(placedIds.size())) : "UNKNOWN-" + i;
                OptionalLong expected = oracle.cancel(id);
                CancelOrder command = new CancelOrder(DEMO, new OrderId(id));
                if (expected.isPresent()) {
                    CancelResult result = book.cancel(command);
                    assertThat(result.cancelledQuantity()).isEqualTo(expected.getAsLong());
                    cancelledQuantity += result.cancelledQuantity();
                } else {
                    assertThatThrownBy(() -> book.cancel(command))
                            .as("unknown/repeated cancellation at step %s", i)
                            .isInstanceOfSatisfying(OrderValidationException.class,
                                    e -> assertThat(e.code()).isEqualTo(RejectionCode.ORDER_NOT_FOUND));
                }
            } else if (!placedIds.isEmpty()) {
                String id = placedIds.get(rng.nextInt(placedIds.size()));
                PlaceLimitOrder duplicate = new PlaceLimitOrder(DEMO, new OrderId(id), Side.BUY,
                        new PriceTicks(100), new OrderQuantity(2));
                assertThat(oracle.seen.contains(id)).isTrue();
                assertThatThrownBy(() -> book.executeLimit(duplicate))
                        .as("lifetime duplicate at step %s", i)
                        .isInstanceOfSatisfying(OrderValidationException.class,
                                e -> assertThat(e.code()).isEqualTo(RejectionCode.DUPLICATE_ORDER_ID));
            }

            assertThat(book.acceptedOrderIdCount()).as("accepted ID count at %s", i)
                    .isEqualTo(oracle.seen.size());
            assertThat(book.restingOrderCount()).as("resting count at %s", i)
                    .isEqualTo(oracle.orders.size());
            long activeQuantity = 0;
            for (Side side : Side.values()) {
                List<ModelOrder> expected = oracle.snapshot(side);
                List<ModelOrder> actual = new ArrayList<>();
                for (LevelDepth level : book.depth(side, 1000)) {
                    List<RestingOrderView> atPrice = book.ordersAt(side, level.price());
                    assertThat(level.totalQuantity()).as("level depth at step %s", i)
                            .isEqualTo(atPrice.stream().mapToLong(RestingOrderView::remainingQuantity).sum());
                    assertThat(level.orderCount()).isEqualTo(atPrice.size());
                    for (RestingOrderView view : atPrice) {
                        assertThat(book.find(view.orderId())).contains(view);
                        activeQuantity += view.remainingQuantity();
                        actual.add(new ModelOrder(view.orderId().value(), view.side(),
                                view.price().value(), view.remainingQuantity(), view.insertionOrdinal()));
                    }
                }
                assertThat(actual).as("active FIFO state at step %s, seed %s", i, seed)
                        .containsExactlyElementsOf(expected);
                assertThat(book.bestOrder(side).map(x -> x.orderId().value()))
                        .isEqualTo(expected.isEmpty()
                                ? java.util.Optional.empty()
                                : java.util.Optional.of(expected.getFirst().id()));
            }
            assertThat(activeQuantity)
                    .as("input = resting + twice-filled + cancelled at step %s", i)
                    .isEqualTo(inputQuantity - 2 * tradedQuantity - cancelledQuantity);
        }
    }

    @Test
    void sameSequenceRepeatedWithCancelsProducesIdenticalLogicalTrace() {
        List<Command> commands = List.of(
                new Command("BUY", Side.BUY, 100, 7, false),
                new Command("ASK1", Side.SELL, 101, 4, false),
                new Command("ASK2", Side.SELL, 100, 2, false),
                new Command("BUY", Side.BUY, 0, 0, true),
                new Command("ASK1", Side.SELL, 0, 0, true),
                new Command("BUY2", Side.BUY, 102, 6, false),
                new Command("BUY2", Side.BUY, 0, 0, true)
        );
        assertThat(run(commands)).isEqualTo(run(commands));
    }

    private static Trace run(List<Command> commands) {
        LimitOrderBook book = new LimitOrderBook(DEMO);
        List<TradeExecution> trades = new ArrayList<>();
        List<CancelResult> cancels = new ArrayList<>();
        for (Command command : commands) {
            if (command.cancel) {
                cancels.add(book.cancel(new CancelOrder(DEMO, new OrderId(command.id))));
            } else {
                trades.addAll(book.executeLimit(new PlaceLimitOrder(DEMO, new OrderId(command.id),
                        command.side, new PriceTicks(command.price),
                        new OrderQuantity(command.quantity))).trades());
            }
        }
        List<RestingOrderView> resting = new ArrayList<>();
        for (Side side : Side.values()) {
            for (LevelDepth level : book.depth(side, 1000)) {
                resting.addAll(book.ordersAt(side, level.price()));
            }
        }
        return new Trace(List.copyOf(trades), List.copyOf(cancels), List.copyOf(resting),
                book.acceptedOrderIdCount());
    }

    private record Command(String id, Side side, long price, long quantity, boolean cancel) { }
    private record Trace(List<TradeExecution> trades, List<CancelResult> cancels,
                         List<RestingOrderView> active, int idCount) { }
    private record ModelTrade(String maker, String taker, long price, long quantity) { }
    private record ModelOrder(String id, Side side, long price, long remaining, long ordinal) { }

    private static final class Reference {
        private final List<MutableOrder> orders = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private long nextOrdinal;

        List<ModelTrade> place(PlaceLimitOrder command) {
            if (!seen.add(command.orderId().value())) {
                throw new AssertionError("test generated a duplicate fresh command");
            }
            long remaining = command.quantity().value();
            List<ModelTrade> trades = new ArrayList<>();
            while (remaining > 0) {
                List<MutableOrder> makers = orders.stream()
                        .filter(x -> x.side != command.side() &&
                                (command.side() == Side.BUY
                                        ? x.price <= command.price().value()
                                        : x.price >= command.price().value()))
                        .sorted(Comparator.comparingLong((MutableOrder x) -> x.price)
                                .thenComparingLong(x -> x.ordinal))
                        .toList();
                if (command.side() == Side.SELL) {
                    makers = orders.stream()
                            .filter(x -> x.side == Side.BUY && x.price >= command.price().value())
                            .sorted(Comparator.comparingLong((MutableOrder x) -> x.price).reversed()
                                    .thenComparingLong(x -> x.ordinal))
                            .toList();
                }
                if (makers.isEmpty()) {
                    break;
                }
                MutableOrder maker = makers.getFirst();
                long quantity = Math.min(remaining, maker.remaining);
                trades.add(new ModelTrade(maker.id, command.orderId().value(), maker.price, quantity));
                remaining -= quantity;
                maker.remaining -= quantity;
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

        OptionalLong cancel(String id) {
            for (int i = 0; i < orders.size(); i++) {
                MutableOrder current = orders.get(i);
                if (current.id.equals(id)) {
                    orders.remove(i);
                    return OptionalLong.of(current.remaining);
                }
            }
            return OptionalLong.empty();
        }

        List<ModelOrder> snapshot(Side side) {
            Comparator<MutableOrder> sorted = Comparator.comparingLong(x -> x.price);
            if (side == Side.BUY) {
                sorted = sorted.reversed();
            }
            return orders.stream().filter(x -> x.side == side)
                    .sorted(sorted.thenComparingLong(x -> x.ordinal))
                    .map(x -> new ModelOrder(x.id, x.side, x.price, x.remaining, x.ordinal))
                    .toList();
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
