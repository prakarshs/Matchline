package dev.matchline.benchmark;

import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderCommand;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderQuantity;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Prebuilt synthetic commands: same validated inputs across raw, sequencer and
 * journal benchmarks. Not a statistical market-data model or production traffic.
 */
public final class DeterministicWorkload {
    public static final InstrumentId DEMO = new InstrumentId("DEMO");
    public static final int BATCH_SIZE = 500;

    public enum Scenario { REST_ONLY, CROSSING, MULTI_FILL }

    private DeterministicWorkload() { }

    public static List<OrderCommand> commands(Scenario scenario, int count) {
        Objects.requireNonNull(scenario, "scenario");
        if (count < 5 || count > 2_000 || (scenario == Scenario.MULTI_FILL && count % 5 != 0)) {
            throw new IllegalArgumentException("count must be 5..2000; MULTI_FILL requires a multiple of five");
        }
        List<OrderCommand> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Side side;
            long price;
            long quantity;
            switch (scenario) {
                case REST_ONLY -> {
                    side = Side.BUY;
                    price = 100 + i % 10;
                    quantity = 2;
                }
                case CROSSING -> {
                    side = i % 2 == 0 ? Side.BUY : Side.SELL;
                    price = side == Side.BUY ? 100 : 99;
                    quantity = 2;
                }
                case MULTI_FILL -> {
                    int step = i % 5;
                    side = step == 4 ? Side.SELL : Side.BUY;
                    price = step == 4 ? 100 : 100 + step;
                    quantity = step == 4 ? 8 : 2;
                }
                default -> throw new IllegalStateException("unknown scenario");
            }
            result.add(new PlaceLimitOrder(DEMO, new OrderId("BENCH" + i), side,
                    new PriceTicks(price), new OrderQuantity(quantity)));
        }
        return List.copyOf(result);
    }
}
