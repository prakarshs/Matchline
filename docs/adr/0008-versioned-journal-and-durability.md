# ADR 0008 — Versioned command/outcome journal and acknowledgement boundary

**Status:** Implemented in Commit 07; Gradle/WSL verification is a user gate.

## Context

The Commit 06 sequencer admitted commands into a volatile bounded queue. Its completion
future confirmed an in-memory decision, not a durable one. A restart could lose the
book, accepted order IDs, rejects and fills. Commit 07 adds storage WITHOUT claiming
replay, snapshots or restart of a populated book (those are Commit 08).

## Decision

- One **exclusive append-only file** per instrument, created once with `CREATE_NEW`.
  `createNew` fails if the file already exists. A writer holds an OS file lock.
  A sequencer can claim only a *fresh, unclaimed, symbol-matching* journal; attaching
  a populated journal to an empty book is forbidden until state replay exists.
- Header: `MLJ1` magic (32-bit), version 1 (16-bit), ASCII symbol length (8-bit),
  12-byte padded ASCII symbol, CRC32C over the preceding header bytes. Binary
  integers are big-endian. Unsupported versions reject; no implicit migrations.
- Two record kinds: COMMAND and DECISION, each framed as
  `length:i32 | record bytes | CRC32C:i32`. Record starts with `type:u8` and
  `sequence:i64`, followed by explicit command/outcome fields. COMMAND stores
  the validated place/cancel request. DECISION stores the result: all matched
  maker IDs, prices, quantities and execution order; cancellation details;
  or a machine-readable rejection code and message. `writeUTF` is the v1 string
  encoding. Enum wire values for command/side/outcome use explicit numbers;
  rejection codes use stable textual names. The maximum payload is 16 MiB.
- Legal record order is COMMAND(1), DECISION(1), COMMAND(2), DECISION(2), etc.
  **The final COMMAND may be unmatched** after interruption; it is not an
  acknowledgement or a completed transaction. All scanned frames are CRC-checked,
  structurally decoded and checked for contiguous sequence pairing.
- Admission sequence remains allocated at successful bounded queue offer.
  Queue overload/closed ingress consumes no sequence and has no journal record.
  A business rejection DOES consume a sequence and has a COMMAND + rejected
  DECISION record, even though the book did not mutate.
- **WAL order:** in the writer, append COMMAND before calling `LimitOrderBook`.
  Then apply/reject; append DECISION; only then advance `lastProcessedSequence`
  and complete the public future. A disk failure fail-stops the writer, failing
  uncompleted futures. If the book already mutated but the result append failed,
  the outcome is **ambiguous** until deterministic replay. Do not retry blindly.
- `SYNC_EACH_RECORD` calls `FileChannel.force(true)` after both frames. A
  successfully completed future therefore follows two fsync-style forces
  (subject to underlying filesystem/storage guarantees). A sudden crash may
  leave a forced COMMAND without a forced DECISION. `OS_BUFFERED` makes no
  durability guarantee upon completion; a normal close and readable file are
  NOT a crash-recovery proof. No batching is implemented in this increment.
- OFFLINE `scan(STRICT)` never changes a file. `TRUNCATE_INCOMPLETE_TAIL`
  requires the exclusive writer lock and removes only an incomplete final
  length/payload/checksum; it reports the bytes removed and any remaining
  command with no decision. **Complete** checksum errors, impossible sizes,
  broken header/version and invalid records reject in either mode. No
  guessing over the corrupted area, no skip-ahead resynchronization.

## Failure boundaries

| Crash / failure point | Journal interpretation | Caller guarantee |
|---|---|---|
| Before COMMAND append/force | No guaranteed record | No acknowledgement |
| After forced COMMAND, before book mutation | Command may be pending | No acknowledgement |
| After book mutation, before forced DECISION | Command may be pending, outcome unknown | No acknowledgement; replay needed |
| After forced DECISION, before completion dispatch | Both frames persisted, caller may not have seen reply | Client uncertainty; replay will determine outcome |
| After completed future with SYNC policy | Both frames were forced before response | Disk-backed acknowledgement, still hardware dependent |
| After completed future with OS_BUFFERED or no journal | Frames (or whole book) may disappear on power loss | No durable guarantee |

## Limits / follow-ups

Reading a file materializes entries into memory for inspection; do not use this
as a high-volume streaming replay reader. No snapshot, full journal replay,
startup recovery, outcome comparison, disk-full drill with actual abrupt process
termination, journal rotation or public API are provided here. Commit 08 must
implement deterministic rebuild (including lifetime IDs and rejections), resolve
pending commands, compare recorded outcomes to replay outcomes, introduce atomic
snapshots, and test deliberate crash boundaries. A journal record is **not**
a financial transaction or exchange execution guarantee.
