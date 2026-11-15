# ADR 0006 — Cancellation, bounded lifetime order IDs, and amend policy

**Status:** Adopted in Commit 05; Gradle/JUnit verification must be performed by the user.

## Context and decision

One `LimitOrderBook` remains a **single-thread-owned**, in-memory book for one
`InstrumentId`. The caller's method-call order determines outcomes until the
single-writer gateway in Commit 06 assigns a total command sequence. No HTTP,
journal sequence, durability acknowledgment or concurrent access is implied.

- `cancel(CancelOrder)` cancels **all remaining, unfilled quantity** of the
  currently resting order with that ID in this instrument; it returns an
  immutable `CancelResult` with a detached `RestingOrderView` and the exact
  cancelled remainder. Historical trades are never reversed or deleted.
- A null cancel is `INVALID_COMMAND`; a wrong instrument is
  `INSTRUMENT_MISMATCH`; an unknown, fully filled, or already cancelled ID is
  `ORDER_NOT_FOUND`. All these failures happen before mutation. `ORDER_NOT_FOUND`
  is not a successful no-op, and repeated cancels are not automatically
  idempotent. The API gateway must expose these outcomes accurately.
- Cancellation removes the order from both `byId` and the owner's
  `LinkedHashMap`, subtracts remaining quantity from its price-level aggregate,
  and removes the empty price level from the sorted map. Neighboring orders
  keep FIFO position and local insertion ordinal.
- Every **accepted placement** consumes its `OrderId` for the lifetime of the
  book instance: seeded orders, resting placements, completely matched takers,
  and makers later filled/cancelled. All placement paths reject reused IDs
  with `DUPLICATE_ORDER_ID` **before matching**, even if no active order remains.
  Rejected placements and unsuccessful cancels do not reserve an ID.
- IDs are scoped to one `LimitOrderBook` instance (one instrument/book epoch),
  are case-sensitive, and are never silently normalized or reused in that
  instance. A fresh book instance has a fresh, empty ID set.
- In-memory history is bounded at **1,000,000 accepted IDs/book by default**,
  configurable `1..2,000,000`. When full, a previously unused placement is
  rejected with `ORDER_ID_HISTORY_CAPACITY_EXCEEDED` **before any matching**.
  Previously used IDs still reject as duplicates. Cancelling an active order
  remains possible when the history is full. This is a safety/resource bound;
  not an eviction policy or claimed throughput guarantee.
- There is **no amend command in v1**. To change an order, cancel its unfilled
  remainder and place a NEW order ID. This is not atomic; the replacement is
  subject to normal matching/capacity validation and goes to the tail of the
  same-price FIFO. The old ID cannot be reused. Clients must not mistake it for
  in-place priority-preserving modification.

## Expected operation costs

Let `P` be price levels and `H` accepted ID history size. History membership and
active-ID lookup are expected O(1) with hash collections. Cancel removes an
order from its price-level linked hash map and active ID hash map in expected
O(1); removal of an emptied price level is O(log P). ID history adds one
hash entry per successfully placed ID, even for orders that never rest;
worst-case memory is O(H + active orders + P).

## Failure boundaries and migration to replay

History is **not persistent** in Commit 05. Dropping a book discards this set;
never claim that lifetime deduplication survives a process restart. The journal
and recovery implementations (Commits 07–08) must reconstruct the ID history
by replaying accepted placement commands and must record rejected commands as
rejections without reserving them. Snapshots must include adequate ID history
or reference a persisted deduplication index so restoration cannot reopen IDs.
No ID set eviction is safe within one epoch. Any future demo reset must create
an explicitly new epoch with restricted authorization and a visible boundary.

No synchronisation is added here; calling the book from concurrent threads is
unsupported until the gateway in Commit 06. Race outcomes are presently only
**ordered, deterministic scenarios** (cancel-then-match vs match-then-cancel),
not evidence of thread-safe concurrent cancellation.

## Verification

JUnit cases cover both sides, FIFO after removal, partial fill then cancel,
full fill then late cancel rejection, unknown/wrong-instrument/double cancels,
ID reuse after every terminal state, loss of priority on cancel-and-replace,
capacity/ordinal preflight, bounded history, detached result values and
fixed-seed comparison with an independent list reference under mixed order
flow. Tests also check quantity conservation with cancelled remainders:

`accepted submitted quantity = current resting + 2 × traded + cancelled`

All JUnit results must be verified by the user before considering the commit
complete. No durability, concurrency or performance claims follow from them.
