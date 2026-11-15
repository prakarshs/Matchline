# ADR 0003 — Bounded command and rejection contracts

**Status:** Accepted for Commit 02 domain contracts; book and gateway behavior remains future work.

## Context

The engine must not accept negative quantities, floating-point prices, overflowed arithmetic,
ambiguous identifiers, or commands with missing fields. Once accepted, order-book operations
must be deterministic under a single writer. The protocol must distinguish invalid inputs
from state-dependent rejection (duplicate ID, missing order), without implying an
acknowledgement or journal append before either exists.

## Decision

- `InstrumentId`: ASCII uppercase, 1–12 characters. First character `A-Z`; subsequent characters
  `A-Z`, `0-9`, `.`, `_`, `-`. Case sensitive, never normalized. The initial single book uses `DEMO`.
- `OrderId`: ASCII, 1–64 characters, begins with a letter or digit; remaining characters may also
  include `.`, `_`, `-`. Case sensitive; never trim or normalize. **Target rule:** cannot reuse an
  ID within one journal epoch, even when the former order is fully filled or cancelled. This
  uniqueness rule will be enforced by the book in Commit 05; it is NOT enforced by a value type.
- `PriceTicks`: integral `long`, inclusive range `[1, 1_000_000_000]`; no decimals, floats or
  implicit currency scaling. `plusTicks` and `minusTicks` use exact long arithmetic and then
  validate the permitted price range. Rejected values raise `OrderValidationException`.
- `OrderQuantity`: integral `long`, inclusive range `[1, 1_000_000]` for **new** orders.
  The future order book must store remaining quantity separately as a `long`, because zero
  is legitimate after a full fill but never valid on an incoming new order.
- Per-order maximum tick-notional is `10^9 * 10^6 = 10^15`, which fits signed 64-bit.
  This is a tick-based number for simulator calculations, **not** money. Aggregated depth
  totals must still be overflow checked; this bound does not cap the number of orders.
- `PlaceLimitOrder` and `CancelOrder` are immutable Java 21 records implementing a sealed
  `OrderCommand`. They have explicit instrument and order IDs. No market orders or amend
  command are accepted in this increment.
- `Side.crosses` is a **pure predicate**, not matching code: BUY price >= resting ask,
  SELL price <= resting bid. It does not assign execution price or execute fills.
- `OrderValidationException.code()` provides machine-readable reasons for constructor
  failures. `CommandRejection` is a small immutable code/message value. State-dependent
  `RejectionCode`s are reserved for future matching/gateway use, not yet emitted.

## Acceptance, ordering and persistence (explicitly NOT implemented)

Constructing a validated command is *not* an order acceptance or sequence assignment.
A future single-writer sequencer will decide order, state mutation, journaling and
acknowledgement. Rejections made before sequencing must not be falsely reported as
journalled/accepted; later behavior needs an explicit protocol and tests.

## Consequences

- Strict validation catches bad values before accessing mutable book state.
- No silent clamping, implicit unit conversion, rounding, Unicode normalisation or
  string case folding.
- The domain compiles without Spring, transport dependencies or a database.
- Tight per-order bounds simplify exact arithmetic but are a deliberate simulator limit.
  This is not an exchange-grade price/quantity model.
- Domain constructors throw errors instead of returning HTTP responses; mapping errors
  to HTTP status and JSON payloads is deferred until Commit 09.

## Future verification gates

- Commit 03: price-level ordering and FIFO index using these identifiers and values.
- Commit 04: exact execution quantities and resting-price fills.
- Commit 05: duplicate IDs, cancellation, amend priority behavior.
- Commit 06+: sequencing, command acceptance boundary, durability, replay.
