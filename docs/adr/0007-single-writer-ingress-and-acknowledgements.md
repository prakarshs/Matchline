# ADR 0007 — Per-book single-writer sequencing and bounded ingress

**Status:** Implemented in Commit 06; Gradle test results require user verification.

## Context

The Commit 05 book is mutable and explicitly not thread-safe. It already has
price-time matching and cancel methods. Letting REST handlers call these directly
from independent request threads would violate FIFO and corrupt indexes. The
sequencer owns a new book privately and never exposes its mutable reference.

## Decision

- **One instrument = one book = one writer thread.** `SingleWriterSequencer` has
  an internally created `LimitOrderBook`; the initial demo uses `DEMO`. This
  does not yet implement a cross-symbol router, but an eventual router can
  own one sequencer per independent partition.
- **Linearization point:** `submit(command)` acquires `admissionLock`, performs
  one *non-blocking* bounded `ArrayBlockingQueue.offer`, and, iff that succeeds,
  assigns the next contiguous 1-based **volatile admission sequence**.
  Successful submissions in that sequence run on the writer in that order.
  Simultaneously arriving callers are ordered by *lock acquisition*, not by
  thread ID, timestamps, HTTP arrival time or fairness guarantees.
- **No wait-for-space:** Full queue throws `OrderValidationException` with
  `INGRESS_OVERLOADED`, **without consuming a sequence** and without matching.
  After shutdown, new work rejects with `INGRESS_CLOSED`, likewise unsequenced.
  Null commands reject with `INVALID_COMMAND` before admission.
- **Business outcomes remain ordered:** Duplicate IDs, wrong instruments,
  missing cancel targets and book-capacity violations are decided by the writer.
  They consume a sequence and yield a `SequencedResult` with a typed
  `CommandDecision.Rejected`. Successful commands yield `Placed` or `Cancelled`.
  In-memory ingress ordering must be preserved for both successes and failures.
- **Acknowledgement boundary in Commit 06:** The caller gets an immutable
  `CommandTicket` *immediately after enqueue*; this is **only queue admission**.
  Its `completion` future settles **after in-memory application/rejection**.
  Neither admission nor completed decisions are durable; both can disappear
  after process death. The word *accepted* here refers to queue admission,
  not persistent journal acceptance.
- **Reads are sequenced without consuming command IDs:** `inspect(depth)`,
  `find(id)` and `ordersAt(side, price)` are enqueued behind preceding work
  under the same lock and executed by the writer. They produce detached immutable
  outputs. A full queue also rejects reads with `INGRESS_OVERLOADED`; a closed
  sequencer refuses new reads. `BookReadView` is a diagnostic view, **not** a
  recovery snapshot and has no historical deduplication contents.
- **Graceful lifecycle:** `stopAccepting()` blocks further admission. The writer
  drains previously admitted commands and reads, then stops. `close()` waits
  up to 10 seconds and reports failure instead of silently losing queued work.
  A daemon writer is used so a stuck client callback cannot pin the JVM at exit.
  Unexpected writer exceptions cause fail-stop: all admitted unfinished futures
  fail exceptionally, ingress closes, and the potentially corrupt book is not
  reused. An unexpected failure is NOT a normal business rejection.
- **Completion callback caution:** CompletableFuture dependent handlers can
  execute inline on the writer, so consumers must use `*Async` callbacks for
  expensive or blocking work. Never block the writer waiting for itself.

## Observable implications and performance

- Admission expected O(1), one monitor/offer per request; full queues reject
  promptly. No unbounded message backlog or unbounded ingress executor pool.
- Each command still has the Commit 04–05 price-level book cost; only one is
  mutating a given instrument at a time. The writer may be CPU-bound and the
  queue is not an automatic throughput guarantee. Per-book order ID history and
  active-order memory bounds still apply.
- Sequence IDs are **per book instance**, start at 1, have no global ordering
  across instruments, and reset when a fresh instance is created. They are not
  timestamps or recovery offsets. Commit 07 must define journal format and
  reconcile admission sequence vs durable acknowledgement before exposing an
  HTTP API. Crashes before journal fsync can lose even completed outcomes in
  this increment.

## Correctness gates

- Many concurrent producers submit into a single writer, then immutable FIFO
  order is compared with sorted ticket sequence; all tickets are gapless.
- Competing cancel and match commands are launched together; whichever has
  lower admission sequence determines the outcome, regardless of thread names.
- Duplicate IDs have exactly one winning placement; rejected attempts are
  sequenced and leave state intact.
- A deterministic test-only execution gate fills the inbox to prove that
  overload consumes no sequence and does not partially execute any order.
- Linearized reads, graceful shutdown and fail-stop of pending futures are
  tested with bounded latch-based synchronization (no sleep-based race guesses).

## Future: journal integration

Commits 07–08 will require a record format, checksums, durable write policy,
replay of accepted commands **including sequenced rejections**, and recovery
semantics. The current `completion` must never be described as an fsynced
commit until that boundary is implemented and crash-tested. Public APIs will
need separate status for queue overload vs business rejection. No market
connectivity, money movement, or production trading behavior is provided.
