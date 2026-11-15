# ADR 0004 — Price-level order book and FIFO index

**Status:** Accepted for Commit 03. The matching/execution and sequencer behaviors below are future work.

## Context

Price-time priority requires best BUY price descending, best SELL price ascending,
first-in-first-out order at a price, and fast lookup by order ID for future cancellation.
Prices and quantities are bounded integers from ADR 0003. We need a correct,
inspectable storage primitive without implementing the matcher or thread control early.

## Decision

- One `LimitOrderBook` instance holds **one** `InstrumentId`. It is mutable and **not thread-safe**.
  A future single writer owns it; external callers must not concurrently mutate/read it.
- Two `TreeMap<PriceTicks, PriceLevel>` indexes: reverse comparator for bids,
  ascending comparator for asks. Price levels are created only on demand.
- Each price level has a `LinkedHashMap<OrderId, RestingOrder>` preserving FIFO insertion
  order and a `long` total remaining quantity.
- A book-wide `HashMap<OrderId, RestingOrder>` points to exactly the same live order
  objects. Future cancel/removal can remove by ID from its level in expected O(1).
- The book holds private mutable orders; reads return immutable `RestingOrderView`
  and `LevelDepth` values, with immutable list copies. No caller can mutate state through views.
- Local positive insertion ordinals describe resting arrival order, **not** concurrent
  ingress ordering or a durable command sequence. Accepted sequence IDs arrive in Commit 06.
- `addResting` seeds a complete order or a validated unfilled remainder. It is **not**
  a command processor and deliberately does **no** matching. To protect the invariant
  that resting bids never cross resting asks, inserting a crossing order is rejected.
  The future matcher must consume opposing liquidity before inserting any remainder.
- A duplicate currently-resting ID, mismatched instrument, invalid remainder,
  capacity exceedance or crossed insertion is rejected **before any state mutation**.
  For now, duplicates are checked only against active orders; no orders can leave the
  book yet. Lifetime deduplication and cancel semantics are Commit 05 responsibilities.
- Default capacity is 50,000 resting orders/book, hard max 100,000; depth requests
  permit at most 1,000 price levels. This is a simulator limit, not an exchange SLA.

## Complexity (P = price levels, N = resting orders, L = returned levels)

| Operation | Cost |
| --- | --- |
| Add at already-present price | Expected O(1) for hash maps |
| Add at new price | O(log P) for TreeMap |
| Find active order ID | Expected O(1) |
| Best order (by price then FIFO) | O(log P) worst-case to locate best price; O(1) FIFO head |
| Depth (L levels) | O(log P + L) tree iteration, plus result allocation |
| Enumerate orders at a price (K orders) | O(log P + K) |
| Memory | O(N + P), including both indexes and per-level maps |

All aggregations use `long` and exact arithmetic. The global order capacity also
bounds per-level totals under the per-order quantity limit.

## What this does NOT prove

- No matching, partial fills, cancellations, amendments or executed trades.
- No concurrent readers/writers, thread-safe collection or accepted-command ordering.
- No journal, crash recovery, idempotency or persistence.
- No latency/throughput numbers; complexity bounds are not benchmarks.

## Verification

Deterministic best-price/FIFO/lookup tests, capacity and rejection no-mutation tests,
immutable snapshot tests, 64-bit aggregate checks, and a fixed-seed independent
reference-model comparison on 1,000 seeded orders.
