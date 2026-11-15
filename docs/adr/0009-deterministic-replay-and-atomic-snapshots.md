# ADR 0009 — Deterministic journal replay, atomic snapshots, and restart boundary

**Status:** Implemented in Commit 08; Gradle/WSL tests and the commit/push are user gates.

## Problem and required invariants

Commit 07 recorded a validated command and its decision but could not restart a
populated book. Creating a new empty book and continuing at sequence 1 would
lose FIFO, historical IDs and unfilled orders. Worse, a command can be forced
to disk before the process crashes and thus be missing its DECISION; neither a
pending intent nor a client timeout proves that a trade occurred.

Recovery must restore all of the following, not just visible depth:

1. Exact total and remaining quantity for every resting order, including original
   placement quantity for future cancellation evidence.
2. Per-price FIFO insertion order **and** last allocated insertion ordinal, even
   if newer orders were subsequently removed.
3. All *ever accepted* placement IDs for this bounded journal epoch, including
   finished makers, fully executed takers and cancelled orders.
4. Complete decision and rejection ordering; last committed sequence becomes the
   next writer's admission starting value. No duplicate sequence at restart.
5. The distinction between a fully recorded command/decision pair, a physically
   complete but **unacknowledged** final COMMAND, and an incomplete torn frame.

## Recovery procedure

- `FileCommandJournal.openExisting(path, instrument, policy)` acquires an
  exclusive OS advisory lock, performs a **strict** CRC/format/pairing scan of
  every frame and seeks the file's physical end. This does not repair corruption.
- If an optional `.mls` snapshot is present, read and SHA-256-validate it, check
  the instrument and exact capacity settings, then verify its embedded SHA-256
  **journal-prefix hash** and physical byte offset equal the actual prefix ending
  at the recorded completed decision. The prefix digest is computed from actual
  file bytes, not from reserialized domain records. Invalid snapshot -> fail
  startup; never silently reset to zero.
- Restore the `LimitOrderBook`'s price trees, per-level linked FIFO maps, ID index,
  full accepted-ID set and insertion ordinal. Validate monotonic ordinals,
  distinct IDs, no resting crosses, capacity bounds and accepted-ID inclusion.
  `addResting` cannot be used for restore because it assigns new ordinals.
- Reapply the journal suffix from the first command after the checkpoint. For
  each **completed** pair, call the very same `CommandProcessor.apply()` as
  the live sequencer. Compare the complete typed decision (including execution
  prices/order/quantity and rejection messages) to the recorded one. Mismatch ->
  fail startup. A CRC-valid but logically false trade is not accepted as truth.
- If the final scanned record is one unmatched COMMAND, recompute that command
  *once* from the verified state, append its missing DECISION and then start the
  new writer. It did not have a confirmed caller acknowledgement before restart.
  RecoveryReport explicitly marks `resolvedPendingCommand`. A failing append
  aborts recovery; no new commands are admitted.
- Start the restored writer with its last admitted and processed sequence set
  to the recovered journal sequence, so new tickets resume at `N+1`.

**A pending command is not simply discarded**: the original process's volatile
book mutations cannot survive restart, and the reconstructed pre-command state
is canonical. A pending record becomes a completed decision exactly once during
recovery. If a client timed out, it MUST inspect recovered status before retry;
blind retry can be rejected as a duplicate or could be unsafe in a real venue.

## Snapshot design and write ordering

- Optional opt-in `SnapshotSchedule(path, everyDecisions)` captures immutable
  `BookSnapshot` after a fully appended DECISION, synchronously on the writer.
  Snapshot state includes all accepted IDs, active orders, original/remaining
  quantities, insertion ordinals, the last ordinal, capacities and the completed
  sequence. IDs and orders are serialized in deterministic sorted order.
- Snapshot wire format is explicit `MLS1` + version 1 + bounded counts + UTF
  strings + integers, followed by SHA-256 of the **entire binary body**. There
  is a separate `JournalAnchor(sequence, byteOffset, SHA-256(prefixBytes))`.
  The writer reads its own locked journal prefix to compute the anchor. The
  journal is still authoritative: a snapshot is not a substitute for validating
  the journal's frame checksums or for comparing replayed decisions.
- Write to a same-directory temporary file, `FileChannel.force(true)`, then
  `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`. Attempt to force parent directory
  metadata when supported; portability is filesystem-dependent. No silent
  fallback to an unsafe non-atomic copy if atomic rename is unavailable.
- A snapshot error **fail-stops the writer** rather than claiming successful
  acknowledgement. Its decision may already be persisted, so the caller's
  future is ambiguous; journal recovery is authoritative. Snapshot writes and
  full-prefix hashing add latency to the single-writer command path.
- Fresh journal startup refuses an existing snapshot path. Recovery refuses
  snapshot/journal path aliases and requires a preexisting parent directory.

## Crash/failure outcomes

| Point of interruption | Recovery behavior | Caller meaning |
|---|---|---|
| Before journalled command | No recorded intent | No acknowledged result |
| Complete COMMAND only | Replay from last complete decision; append decision once | Old caller's outcome is unknown |
| Torn final COMMAND or DECISION frame | Strict startup refuses; explicit offline `TRUNCATE_INCOMPLETE_TAIL` required | Preserve original file before destructive repair |
| Completed decision, before completion future | Replay or snapshot retains decision | Caller may have missed response |
| Snapshot temp-file write interrupted | Previous completed snapshot remains | Journal remains authority |
| Snapshot journal anchor mismatches | Abort restart, never fall back silently | Operator must investigate mismatch |
| CRC-valid but semantically incorrect decision | Abort restart during comparison | Never infer a trade from tampered data |
| Snapshot write fails after a decision | Current writer fail-stops; journal can recover | Current acknowledgement ambiguous |

`SYNC_EACH_RECORD` calls `force(true)` before book application and after its
recorded decision. `OS_BUFFERED` does not promise persistence on sudden power
loss. This commit's normal-close replay tests and CLI drill are **not** physical
crash/power-loss tests; hardware/filesystem behavior is outside their proof.

## Scope and complexity

- A strict journal scan currently materializes the entire history. Full replay
  costs O(commands × matching cost); snapshots avoid reexecuting decisions *up
  to the checkpoint*, but scanning all CRC frames and recomputing a prefix hash
  still incurs O(journal size) I/O. No constant-time startup claim.
- Snapshots have a bounded 256 MiB file limit and validate the book's configured
  capacity. Journal files remain append-only, not rotated or compacted.
- SHA-256 protects against accidental corruption and wrong-file snapshots, not
  adversarial edits by a party who can rewrite both file and digest. Snapshot
  validity relies on the trusted writer; running full replay independently is
  recommended for strong correctness comparison.
- Journal v1 format is unchanged. The engine's capacity configuration is not
  in its header; callers must retain the same configuration across restarts.
  Restoring with mismatched capacities fails if a snapshot is present; without
  one, changed capacity may surface as a recorded-decision mismatch. Persisting
  a stronger config identity is left for a future format version.
- No HTTP ingress, periodic async snapshot worker, actual process-kill fsync
  drill, streaming replay, journal compaction, migration, disk backup or live
  exchange usage in this increment.

## Verification

`DeterministicRecoveryTest`, `BookSnapshotRestoreTest`, `RecoveryDrillTest`
cover complete and snapshot-suffix equivalence, 350 mixed commands with a
reference full replay, pending command/cancel decisions, CRC-valid false trades,
strict torn-tail handling, forced fail-stop on snapshot IO errors, exclusive
writer locks, wrong journal prefixes and snapshot checksums, ID-history
retention, FIFO ordinals and configuration compatibility.

`./gradlew run --args='--recovery-demo'` creates synthetic temporary data,
normal-closes the writer, restores first from a snapshot+suffix then from the
entire journal, and compares both resulting `BookSnapshot` values. This is an
executable demonstration, **not** a sustained durability benchmark.
