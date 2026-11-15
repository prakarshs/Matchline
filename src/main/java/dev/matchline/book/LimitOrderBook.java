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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Mutable, single-writer-owned storage for resting limit orders of ONE instrument.
 *
 * <p>{@link #executeLimit(PlaceLimitOrder)} consumes matching resting orders
 * at the maker price, then stores any unmatched remainder. The {@code addResting}
 * methods remain low-level seeding operations: they NEVER match, and they reject
 * crossed insertion. Neither path assigns command sequence numbers, acknowledges
 * durability, or persists data.
 *
 * <p>Prices are indexed in balanced trees; each price level owns a FIFO
 * {@link LinkedHashMap}. The order-ID hash index points to the same mutable
 * order object. Executions and cancellations mutate/remove these objects from
 * the book while preserving index consistency. No concurrent access is safe:
 * the owning single-writer gateway is supplied in Commit 06.
 */
public final class LimitOrderBook {
    public static final int DEFAULT_MAX_RESTING_ORDERS = 50_000;
    public static final int HARD_MAX_RESTING_ORDERS = 100_000;
    public static final int MAX_DEPTH_LEVELS = 1_000;
    // Bounded per-book ID history, not a persisted deduplication guarantee.
    // A fresh book starts a fresh ID epoch; journal replay must reconstruct it.
    public static final int DEFAULT_MAX_ORDER_IDS = 1_000_000;
    public static final int HARD_MAX_ORDER_IDS = 2_000_000;

    private final InstrumentId instrument;
    private final int maxRestingOrders;
    private final int maxOrderIds;
    private final NavigableMap<PriceTicks, PriceLevel> bids =
            new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<PriceTicks, PriceLevel> asks = new TreeMap<>();
    private final Map<OrderId, RestingOrder> byId = new HashMap<>();
    // Includes accepted taker IDs with no remainder, cancelled IDs and filled maker IDs.
    private final Set<OrderId> usedOrderIds = new HashSet<>();
    private long insertionOrdinal;

    public LimitOrderBook(InstrumentId instrument) {
        this(instrument, DEFAULT_MAX_RESTING_ORDERS, DEFAULT_MAX_ORDER_IDS);
    }

    public LimitOrderBook(InstrumentId instrument, int maxRestingOrders) {
        this(instrument, maxRestingOrders, DEFAULT_MAX_ORDER_IDS);
    }

    public LimitOrderBook(InstrumentId instrument, int maxRestingOrders, int maxOrderIds) {
        this.instrument = Objects.requireNonNull(instrument, "instrument");
        if (maxRestingOrders < 1 || maxRestingOrders > HARD_MAX_RESTING_ORDERS) {
            throw new IllegalArgumentException("maxRestingOrders must be 1.." + HARD_MAX_RESTING_ORDERS);
        }
        if (maxOrderIds < 1 || maxOrderIds > HARD_MAX_ORDER_IDS) {
            throw new IllegalArgumentException("maxOrderIds must be 1.." + HARD_MAX_ORDER_IDS);
        }
        this.maxRestingOrders = maxRestingOrders;
        this.maxOrderIds = maxOrderIds;
    }

    public InstrumentId instrument() {
        return instrument;
    }

    public int restingOrderCount() {
        return byId.size();
    }

    /** Count of accepted placement IDs in this book instance's lifetime. */
    public int acceptedOrderIdCount() {
        return usedOrderIds.size();
    }

    /** Detached complete state, safe to capture ONLY on the owning writer. */
    public BookSnapshot snapshot(long completedSequence) {
        if (completedSequence < 0) throw new IllegalArgumentException("negative completed sequence");
        List<OrderId> ids = new ArrayList<>(usedOrderIds);
        ids.sort(Comparator.comparing(OrderId::value));
        List<RestingOrderView> orders = new ArrayList<>(byId.size());
        for (RestingOrder order : byId.values()) orders.add(order.view());
        orders.sort(Comparator.comparingLong(RestingOrderView::insertionOrdinal));
        return new BookSnapshot(instrument, completedSequence, maxRestingOrders, maxOrderIds,
                insertionOrdinal, ids, orders);
    }

    /**
     * Restore every index and historical identifier with original FIFO ordinals.
     * Never call addResting here: it allocates NEW ordinals and changes priority.
     * A failed validation cannot expose a partially initialized book.
     */
    public static LimitOrderBook restore(BookSnapshot state, int maxRestingOrders, int maxOrderIds) {
        Objects.requireNonNull(state, "state");
        if (state.maxRestingOrders() != maxRestingOrders || state.maxOrderIds() != maxOrderIds) {
            throw new IllegalArgumentException("snapshot capacity configuration differs from engine");
        }
        LimitOrderBook restored = new LimitOrderBook(state.instrument(), maxRestingOrders, maxOrderIds);
        String lastId = null;
        for (OrderId id : state.acceptedOrderIds()) {
            if (lastId != null && lastId.compareTo(id.value()) >= 0) {
                throw new IllegalArgumentException("snapshot accepted IDs must be strictly sorted");
            }
            if (!restored.usedOrderIds.add(id)) {
                throw new IllegalArgumentException("duplicate snapshot order ID");
            }
            lastId = id.value();
        }
        long lastOrdinal = 0;
        for (RestingOrderView view : state.restingOrders()) {
            if (view.insertionOrdinal() <= lastOrdinal
                    || view.insertionOrdinal() > state.lastInsertionOrdinal()) {
                throw new IllegalArgumentException("snapshot FIFO ordinals are invalid");
            }
            lastOrdinal = view.insertionOrdinal();
            if (!restored.usedOrderIds.contains(view.orderId())
                    || restored.byId.containsKey(view.orderId())) {
                throw new IllegalArgumentException("resting order must have a distinct accepted ID");
            }
            // Enforce the same domain bounds as new incoming commands.
            PlaceLimitOrder original = new PlaceLimitOrder(state.instrument(), view.orderId(), view.side(),
                    view.price(), new OrderQuantity(view.originalQuantity()));
            NavigableMap<PriceTicks, PriceLevel> levels = restored.levelsFor(view.side());
            PriceLevel level = levels.computeIfAbsent(view.price(), ignored -> new PriceLevel());
            level.totalQuantity = Math.addExact(level.totalQuantity, view.remainingQuantity());
            RestingOrder order = new RestingOrder(original, level, view.remainingQuantity(), view.insertionOrdinal());
            level.orders.put(view.orderId(), order);
            restored.byId.put(view.orderId(), order);
        }
        if (!restored.bids.isEmpty() && !restored.asks.isEmpty()
                && Side.BUY.crosses(restored.bids.firstKey(), restored.asks.firstKey())) {
            throw new IllegalArgumentException("snapshot contains crossed resting sides");
        }
        restored.insertionOrdinal = state.lastInsertionOrdinal();
        return restored;
    }

    public int priceLevelCount(Side side) {
        return levelsFor(side).size();
    }

    /**
     * Execute one validated incoming limit order with strict price/time priority.
     *
     * <p>Only the owner thread may call this method. We first perform a read-only
     * crossing pass so a capacity rejection cannot occur AFTER some makers have
     * been filled. If the order is accepted, we walk best price levels and FIFO
     * makers, reducing their remaining quantities and removing fully filled
     * makers from all indexes. An unexecuted remainder rests with a NEW local
     * insertion ordinal, behind existing orders at the same price.
     *
     * <p>Order IDs are unique for the lifetime of this book instance, including
     * fully executed and cancelled orders. Result order is deterministic for
     * a given single-threaded command sequence.
     */
    public MatchResult executeLimit(PlaceLimitOrder command) {
        if (command == null) {
            throw reject(RejectionCode.INVALID_COMMAND, "place-limit command is required");
        }
        if (!instrument.equals(command.instrument())) {
            throw reject(RejectionCode.INSTRUMENT_MISMATCH,
                    "order instrument " + command.instrument() + " does not match book " + instrument);
        }
        validateNewOrderId(command.orderId());

        // Preflight is intentionally read-only. A partially filled maker still
        // occupies its slot; only makers fully consumed by the incoming order
        // free a slot. This prevents a capacity reject after any book mutation.
        long wouldRemain = command.quantity().value();
        int removedMakers = 0;
        for (Map.Entry<PriceTicks, PriceLevel> levelEntry
                : levelsFor(command.side().opposite()).entrySet()) {
            if (wouldRemain == 0 || !command.side().crosses(command.price(), levelEntry.getKey())) {
                break;
            }
            for (RestingOrder maker : levelEntry.getValue().orders.values()) {
                if (wouldRemain == 0) {
                    break;
                }
                long fill = Math.min(wouldRemain, maker.remainingQuantity);
                wouldRemain -= fill;
                if (fill == maker.remainingQuantity) {
                    removedMakers++;
                }
            }
        }
        if (wouldRemain > 0) {
            if (byId.size() - removedMakers >= maxRestingOrders || insertionOrdinal == Long.MAX_VALUE) {
                throw reject(RejectionCode.BOOK_CAPACITY_EXCEEDED,
                        "unmatched remainder cannot rest within book capacity");
            }
        }

        long unfilled = command.quantity().value();
        List<TradeExecution> trades = new ArrayList<>();
        NavigableMap<PriceTicks, PriceLevel> opposite = levelsFor(command.side().opposite());
        while (unfilled > 0) {
            Map.Entry<PriceTicks, PriceLevel> best = opposite.firstEntry();
            if (best == null || !command.side().crosses(command.price(), best.getKey())) {
                break;
            }
            PriceLevel level = best.getValue();
            Iterator<Map.Entry<OrderId, RestingOrder>> iterator = level.orders.entrySet().iterator();
            RestingOrder maker = iterator.next().getValue(); // Each published price level is nonempty.
            long fill = Math.min(unfilled, maker.remainingQuantity);
            unfilled -= fill;
            maker.remainingQuantity -= fill;
            level.totalQuantity -= fill;
            trades.add(new TradeExecution(instrument, maker.original.orderId(), command.orderId(),
                    command.side(), maker.original.price(), fill));
            if (maker.remainingQuantity == 0) {
                iterator.remove();
                byId.remove(maker.original.orderId());
                if (level.orders.isEmpty()) {
                    opposite.remove(best.getKey());
                }
            }
        }
        if (unfilled > 0) {
            // Preflight proved space exists; insertion records the accepted ID.
            addResting(command, unfilled);
        } else {
            // Fully executed takers must also permanently consume their ID.
            usedOrderIds.add(command.orderId());
        }
        return new MatchResult(command, command.quantity().value() - unfilled, unfilled, trades);
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
     * Store a residual quantity (including the unmatched result of executeLimit). The original command
     * quantity remains available in the immutable inspection view.
     * Every successful insertion consumes its ID for this book instance's
     * lifetime, even if that order is later filled or cancelled.
     */
    public RestingOrderView addResting(PlaceLimitOrder command, long remainingQuantity) {
        if (command == null) {
            throw reject(RejectionCode.INVALID_COMMAND, "place-limit command is required");
        }
        if (!instrument.equals(command.instrument())) {
            throw reject(RejectionCode.INSTRUMENT_MISMATCH,
                    "order instrument " + command.instrument() + " does not match book " + instrument);
        }
        validateNewOrderId(command.orderId());
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
        usedOrderIds.add(command.orderId());
        insertionOrdinal = nextOrdinal;
        return order.view();
    }

    /**
     * Cancel one currently resting order and remove ALL its unexecuted quantity.
     * A missing (including already cancelled or fully filled) order rejects with
     * ORDER_NOT_FOUND; an unsuccessful cancellation does not modify any index.
     * The cancelled order ID remains in the lifetime ID history.
     *
     * <p>Caller ordering is the only ordering at this layer. The Commit 06 sequencer serializes concurrent cancels and placements.
     */
    public CancelResult cancel(CancelOrder command) {
        if (command == null) {
            throw reject(RejectionCode.INVALID_COMMAND, "cancel command is required");
        }
        if (!instrument.equals(command.instrument())) {
            throw reject(RejectionCode.INSTRUMENT_MISMATCH,
                    "cancel instrument " + command.instrument() + " does not match book " + instrument);
        }
        RestingOrder order = byId.get(command.orderId());
        if (order == null) {
            throw reject(RejectionCode.ORDER_NOT_FOUND, "no resting order for ID: " + command.orderId());
        }

        RestingOrderView removed = order.view();
        PriceLevel level = order.ownerLevel;
        // These indexes are kept in sync by all successful insert/fill paths.
        level.orders.remove(command.orderId());
        level.totalQuantity -= order.remainingQuantity;
        byId.remove(command.orderId());
        if (level.orders.isEmpty()) {
            levelsFor(order.original.side()).remove(order.original.price());
        }
        return new CancelResult(command, removed);
    }

    private void validateNewOrderId(OrderId id) {
        if (usedOrderIds.contains(id)) {
            throw reject(RejectionCode.DUPLICATE_ORDER_ID,
                    "order ID was already accepted by this book: " + id);
        }
        if (usedOrderIds.size() >= maxOrderIds) {
            throw reject(RejectionCode.ORDER_ID_HISTORY_CAPACITY_EXCEEDED,
                    "book has reached its maximum accepted-order-ID history of " + maxOrderIds);
        }
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
        private long remainingQuantity;
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
