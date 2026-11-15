package dev.matchline.book;

import dev.matchline.domain.InstrumentId;
import dev.matchline.domain.OrderId;
import dev.matchline.domain.OrderValidationException;
import dev.matchline.domain.PlaceLimitOrder;
import dev.matchline.domain.PriceTicks;
import dev.matchline.domain.RejectionCode;
import dev.matchline.domain.Side;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Mutable, single-writer-owned storage for resting limit orders of ONE instrument.
 *
 * <p>This is a storage primitive, NOT a matching engine or command processor. The
 * {@code addResting} methods are intended for controlled book seeding and, later,
 * for the unmatched remainder after execution. They do not execute trades,
 * assign command sequence numbers, acknowledge requests or persist anything.
 * An insertion that would cross the best price on the other side is rejected.
 *
 * <p>Prices are indexed in balanced trees; each price level owns a FIFO
 * {@link LinkedHashMap}. The order-ID hash index points to the same mutable
 * order object. Future fill/cancel code will mutate/remove these objects from
 * the book while preserving index consistency. No concurrent access is safe:
 * the single-writer gateway comes in Commit 06.
 */
public final class LimitOrderBook {
    public static final int DEFAULT_MAX_RESTING_ORDERS = 50_000;
    public static final int HARD_MAX_RESTING_ORDERS = 100_000;
    public static final int MAX_DEPTH_LEVELS = 1_000;

    private final InstrumentId instrument;
    private final int maxRestingOrders;
    private final NavigableMap<PriceTicks, PriceLevel> bids =
            new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<PriceTicks, PriceLevel> asks = new TreeMap<>();
    private final Map<OrderId, RestingOrder> byId = new HashMap<>();
    private long insertionOrdinal;

    public LimitOrderBook(InstrumentId instrument) {
        this(instrument, DEFAULT_MAX_RESTING_ORDERS);
    }

    public LimitOrderBook(InstrumentId instrument, int maxRestingOrders) {
        this.instrument = Objects.requireNonNull(instrument, "instrument");
        if (maxRestingOrders < 1 || maxRestingOrders > HARD_MAX_RESTING_ORDERS) {
            throw new IllegalArgumentException("maxRestingOrders must be 1.." + HARD_MAX_RESTING_ORDERS);
        }
        this.maxRestingOrders = maxRestingOrders;
    }

    public InstrumentId instrument() {
        return instrument;
    }

    public int restingOrderCount() {
        return byId.size();
    }

    public int priceLevelCount(Side side) {
        return levelsFor(side).size();
    }

    /**
     * Add a fully unexecuted order as resting book storage. No trading occurs.
     * All validation and aggregate checks complete before indexes are mutated.
     */
    public RestingOrderView addResting(PlaceLimitOrder command) {
        if (command == null) {
            throw reject(RejectionCode.INVALID_COMMAND, "place-limit command is required");
        }
        return addResting(command, command.quantity().value());
    }

    /**
     * Store a residual quantity (for a future matcher). The original command
     * quantity remains available in the immutable inspection view.
     * An ID is currently unique among RESTING orders only; lifetime reuse
     * prohibition, cancels and fills are added in Commit 05.
     */
    public RestingOrderView addResting(PlaceLimitOrder command, long remainingQuantity) {
        if (command == null) {
            throw reject(RejectionCode.INVALID_COMMAND, "place-limit command is required");
        }
        if (!instrument.equals(command.instrument())) {
            throw reject(RejectionCode.INSTRUMENT_MISMATCH,
                    "order instrument " + command.instrument() + " does not match book " + instrument);
        }
        if (byId.containsKey(command.orderId())) {
            throw reject(RejectionCode.DUPLICATE_ORDER_ID,
                    "order ID already rests in this book: " + command.orderId());
        }
        if (remainingQuantity < 1 || remainingQuantity > command.quantity().value()) {
            throw reject(RejectionCode.INVALID_QUANTITY,
                    "resting remainder must be between 1 and the original order quantity");
        }
        if (byId.size() >= maxRestingOrders) {
            throw reject(RejectionCode.BOOK_CAPACITY_EXCEEDED,
                    "book is at its resting-order capacity of " + maxRestingOrders);
        }
        Map.Entry<PriceTicks, PriceLevel> oppositeBest = levelsFor(command.side().opposite()).firstEntry();
        if (oppositeBest != null && command.side().crosses(command.price(), oppositeBest.getKey())) {
            throw reject(RejectionCode.INVALID_COMMAND,
                    "resting insertion would cross the opposite book; matching must occur first");
        }

        NavigableMap<PriceTicks, PriceLevel> levels = levelsFor(command.side());
        PriceLevel existing = levels.get(command.price());
        long newTotal;
        long nextOrdinal;
        try {
            newTotal = Math.addExact(existing == null ? 0 : existing.totalQuantity, remainingQuantity);
            nextOrdinal = Math.addExact(insertionOrdinal, 1L);
        } catch (ArithmeticException ex) {
            throw reject(RejectionCode.BOOK_CAPACITY_EXCEEDED, "price-level total or insertion ordinal overflow");
        }

        // Validation is complete: update all in-memory indexes together on the owner thread.
        PriceLevel level = existing == null ? new PriceLevel() : existing;
        RestingOrder order = new RestingOrder(command, level, remainingQuantity, nextOrdinal);
        level.orders.put(command.orderId(), order);
        level.totalQuantity = newTotal;
        if (existing == null) {
            levels.put(command.price(), level);
        }
        byId.put(command.orderId(), order);
        insertionOrdinal = nextOrdinal;
        return order.view();
    }

    /** Average-case O(1) active order lookup, returning a detached immutable view. */
    public Optional<RestingOrderView> find(OrderId id) {
        RestingOrder order = byId.get(Objects.requireNonNull(id, "id"));
        return order == null ? Optional.empty() : Optional.of(order.view());
    }

    /** Best price first; at a tied price the earliest inserted resting order wins. */
    public Optional<RestingOrderView> bestOrder(Side side) {
        Map.Entry<PriceTicks, PriceLevel> entry = levelsFor(side).firstEntry();
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(entry.getValue().orders.values().iterator().next().view());
    }

    /** Immutable price-level aggregates in best-price-first order. */
    public List<LevelDepth> depth(Side side, int maxLevels) {
        if (maxLevels < 1 || maxLevels > MAX_DEPTH_LEVELS) {
            throw new IllegalArgumentException("maxLevels must be 1.." + MAX_DEPTH_LEVELS);
        }
        List<LevelDepth> result = new ArrayList<>();
        for (Map.Entry<PriceTicks, PriceLevel> entry : levelsFor(side).entrySet()) {
            if (result.size() == maxLevels) {
                break;
            }
            PriceLevel level = entry.getValue();
            result.add(new LevelDepth(entry.getKey(), level.totalQuantity, level.orders.size()));
        }
        return List.copyOf(result);
    }

    /** Immutable FIFO order views at one price; empty if the price has no level. */
    public List<RestingOrderView> ordersAt(Side side, PriceTicks price) {
        PriceLevel level = levelsFor(side).get(Objects.requireNonNull(price, "price"));
        if (level == null) {
            return List.of();
        }
        List<RestingOrderView> result = new ArrayList<>(level.orders.size());
        for (RestingOrder order : level.orders.values()) {
            result.add(order.view());
        }
        return List.copyOf(result);
    }

    private NavigableMap<PriceTicks, PriceLevel> levelsFor(Side side) {
        Objects.requireNonNull(side, "side");
        return side == Side.BUY ? bids : asks;
    }

    private static OrderValidationException reject(RejectionCode code, String message) {
        return new OrderValidationException(code, message);
    }

    private static final class PriceLevel {
        private final LinkedHashMap<OrderId, RestingOrder> orders = new LinkedHashMap<>();
        private long totalQuantity;
    }

    private static final class RestingOrder {
        private final PlaceLimitOrder original;
        // Direct owner-level link for future O(1)-expected cancel/unlink by order ID.
        private final PriceLevel ownerLevel;
        private final long remainingQuantity;
        private final long insertionOrdinal;

        private RestingOrder(PlaceLimitOrder original, PriceLevel ownerLevel,
                             long remainingQuantity, long insertionOrdinal) {
            this.original = original;
            this.ownerLevel = ownerLevel;
            this.remainingQuantity = remainingQuantity;
            this.insertionOrdinal = insertionOrdinal;
        }

        private RestingOrderView view() {
            return new RestingOrderView(original.orderId(), original.side(), original.price(),
                    original.quantity().value(), remainingQuantity, insertionOrdinal);
        }
    }
}
