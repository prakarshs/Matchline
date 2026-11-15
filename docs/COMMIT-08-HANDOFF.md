# Commit 08 — Deterministic replay, snapshots and restart recovery

**Baseline:** User-confirmed, pushed Commit 07 **including the corrected
`shortFinalFrameRequiresExplicitOfflineRepair` assertion**. The patch is
relative to that corrected baseline and does **not** replace the user's
`FileCommandJournalTest.java` with an older failing copy.

## 1. Apply ONE artifact

Place `MATCHLINE_Commit08.patch` alongside your repository in
`~/Stuff/Projects`. Use a clean working tree.

```bash
cd ~/Stuff/Projects/MATCHLINE
git status --short
git apply --check ../MATCHLINE_Commit08.patch
git apply ../MATCHLINE_Commit08.patch
```

If the patch cannot be applied because you independently changed one of its
files, stop and share the exact `git apply --check` output rather than forcing
it. Alternative changed-files ZIP (do **not** apply both):

```bash
unzip -o ../MATCHLINE_Commit08_Changes.zip -d .
```

The ZIP contains only new and changed files, with paths relative to the
repository root. The manifest lists them. No deletions are requested.

## 2. Test and run

```bash
./gradlew --stop
./gradlew clean test --no-daemon --max-workers=1 --console=plain
./gradlew test --tests 'dev.matchline.recovery.*' \
  --no-daemon --max-workers=1 --console=plain
./gradlew run --args='--recovery-demo' \
  --no-daemon --max-workers=1 --console=plain
./gradlew run --args='--self-check' \
  --no-daemon --max-workers=1 --console=plain
git diff --check
```

The recovery drill should print:

```text
MATCHLINE RECOVERY OK | commands=6 snapshot=4 suffix=2 full=6 state_equal=true
This is a normal-close replay demonstration; no abrupt crash or fsync guarantee was tested.
```

Self-check remains `matching=true http=false journal=true`. The 28 new JUnit
methods supplement the previously working suite (approximately 200 total test
invocations); your Gradle report is the source of truth. Java 21 compilation,
28 test invocations through a local Java assertion harness, and the actual
recovery drill were executed in the artifact-generation sandbox. The complete
Gradle/JUnit suite still requires your WSL run.

## 3. Inspect correctness, not just compilation

- `SnapshotStore.read` and `SnapshotSchedule` define the persisted snapshot
  format and periodic interval; `LimitOrderBook.restore` reconstructs FIFO and
  the lifetime ID set, not just aggregate depth.
- `SingleWriterSequencer.recover` strictly validates the original journal,
  checks snapshot SHA-256 against its **journal decision-boundary prefix**,
  applies the verified command suffix, checks every persisted decision, and
  resolves an unmatched final command before accepting fresh work.
- An incomplete last record does **not** automatically disappear. To repair
  only a short final frame, shut down the writer, copy the file for analysis,
  call `FileCommandJournal.scan(file, TRUNCATE_INCOMPLETE_TAIL)` offline, then
  retry startup. Wrong CRC on a complete frame remains fatal.
- `OS_BUFFERED` is not crash-durable, and the recovery demo is only normal-close
  simulation. The journal/snapshot implementation is not a financial exchange.

For a copyable first-start/restart usage example and complexity limitations,
see the README `Deterministic restart` section and ADR 0009.

## 4. Stage, commit and push manually AFTER your verification

```bash
git add -A
git diff --cached --check
git commit -m "feat: restore books with replay and snapshots"
git push
git log -1 --oneline
```

**STOP:** Send the Gradle test result, recovery demo output, and confirmation
that Commit 08 was pushed. If a test fails, send its stack trace and we'll
repair the current increment before Commit 09 (HTTP API).
