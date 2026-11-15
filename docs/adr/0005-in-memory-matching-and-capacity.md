# ADR 0005 — In-memory limit matching and capacity preflight

**Status:** Accepted for Commit 04 (subject to user's Gradle/JUnit verification).

## Decision

`LimitOrderBook.executeLimit(PlaceLimitOrder)` is the only new matching entry point.
The existing `addResting` operations remain low-level storage/seeding primitives:
they cannot cross the opposite side, execute trades, or allocate command sequence IDs.
Only one thread may access a book at a time; single-writer ingress is deferred to Commit 06.

For an incoming BUY, match the lowest resting SELL price first while that price is
at or below the incoming limit. For an incoming SELL, match the highest resting
BUY price first while that price is at or above the incoming limit. Within a
price level, consume orders from the head of the existing `LinkedHashMap` FIFO.
The price on every `TradeExecution` is **the maker's resting price**, never the
incoming limit unless the two happen to coincide.

Each maker is either partially decremented without moving its FIFO position,
or fully removed from the price level and by-ID hash map. Remove empty price
levels from the sorted tree. If the incoming order is not fully executed,
insert its leftover quantity as a new resting order at its limit, with a
new **local** insertion ordinal. Each `MatchResult` reports executed quantity,
resting remainder, and ordered immutable trade values. No durable trade/event
identifier, journal acknowledgement, timestamp, or HTTP response exists yet.

## Validation before mutation

1. Check that the command is not null, uses this book's instrument, and does
   not collide with an **active/resting** order ID.
2. Perform a read-only walk over crossed maker orders to determine the final
   remainder and how many resting orders will be fully consumed.
3. If the remainder needs a new slot, reject with `BOOK_CAPACITY_EXCEEDED`
   **before any trades** if `currentCount - fullyRemoved >= maxRestingOrders`.
   Also reject if the next insertion ordinal would overflow.
4. Only then execute fills and insert any remainder. Input domain types are
   already validated by Commit 02.

For a standard limit order, if an incoming remainder exists after executing
some positive volume, every crossed maker was completely removed; therefore
at least one slot has been freed. The preflight makes the rule explicit,
prevents mutations for rejects, and is intentionally conservative. It does
**not** promise rollback on JVM failures such as out-of-memory, abrupt process
death or arbitrary asynchronous exceptions; durable atomicity requires the
journal and recovery design in Commits 07–08.

ID reuse after a fully filled order is **currently allowed**, because the book
only tracks resting IDs. Commit 05 adds lifetime duplicate-ID semantics and
cancellation and must preserve this matching behavior. There is no amend API.

## Costs and bounds

- Let `F` be the number of maker orders examined/filled, `P` the number of price
  levels, and `N` the number of active orders.
- Preflight scans at most the crossed makers: O(F), before a second O(F) fill pass.
  Worst-case price-tree operations to remove exhausted levels: O(F log P).
- A partial maker fill is O(1); a fully filled maker is removed from its
  FIFO level and by-ID hash index in expected O(1), plus O(log P) to delete
  an emptied level. Insertion complexity remains that of ADR 0004.
- Each trade and each `MatchResult` is an immutable Java record; the result
  allocates O(F) trade values and an immutable list. This is **not** a
  measured performance claim or a zero-allocation design.
- Quantity arithmetic uses `long`; per-order quantity is at most 1,000,000,
  book capacity at most 100,000 orders, so aggregated level quantities remain
  within a signed `long`.

## Verification gate

The added unit tests cover price priority in both directions, FIFO within a
price level, maker-price execution, partial/full/multiple fills, boundary
quantities, capacity and duplicate rejects without mutation, immutable views,
and a seeded independent list-based reference model over 2,500 commands.
The reference model recomputes priorities independently, checks conservation,
book depth and indexes after each submission, and compares a repeat run's
logical state and trade sequence. This is **in-memory deterministic re-application**,
not journal replay or restart recovery.

All new tests must be executed in the user's environment before the commit is
considered verified. No benchmark throughput, latency or production suitability
is claimed.
