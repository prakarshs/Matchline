# ADR 0001 — Keep the future matching core independent

**Status:** Accepted as a target design; implementation starts after Commit 01.

## Context

The engine must make strict price-time decisions with reproducible order and quantity semantics, without database I/O influencing the hot path.

## Decision

- Implement domain rules in plain Java 21 classes with integer tick prices and integer quantities.
- Introduce a **single-writer sequencer for each instrument** in a later commit. Ingress may be concurrent, but matching state is mutated only in the order assigned by that sequencer.
- Separate transport, persistence, metrics and visualization from the matching core.
- Use a price-level index and an order-ID lookup, with exact structures and cancellation complexity settled before the order-book implementation.

## Consequences

- The in-memory core can be unit tested without Spring, SQL, a container or a clock.
- Serializing state changes sacrifices same-book parallelism in exchange for deterministic ordering.
- Symbols can be partitioned into independent single writers later; cross-symbol atomicity is outside v1 scope.
- Price-time behavior, bounded ingress, journal acknowledgement and replay are **not implemented** by this ADR.
