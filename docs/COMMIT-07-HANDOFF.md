# Commit 07 — Append-only versioned journal

**Baseline:** Verified user-pushed Commit 06. Only apply one of the supplied
artifacts: unified patch (recommended) OR changed-files ZIP. The patch does
not contain Commit 08 recovery. No production trading or HTTP is implemented.

## 1. Apply

Save `MATCHLINE_Commit07.patch` to `~/Stuff/Projects` (beside `MATCHLINE`).

```bash
cd ~/Stuff/Projects/MATCHLINE
git status --short
git apply --check ../MATCHLINE_Commit07.patch
git apply ../MATCHLINE_Commit07.patch
```

If using the changed-files ZIP instead, extract *inside* the MATCHLINE root:

```bash
cd ~/Stuff/Projects/MATCHLINE
unzip -o ../MATCHLINE_Commit07_Changes.zip -d .
```

Do not apply both.

## 2. Verify

```bash
./gradlew --stop
./gradlew clean test --no-daemon --max-workers=1 --console=plain
./gradlew test --tests 'dev.matchline.journal.*' --no-daemon --max-workers=1 --console=plain
./gradlew run --args='--self-check' --no-daemon --max-workers=1 --console=plain
git diff --check
```

Expected bootstrap output:

```text
MATCHLINE BOOTSTRAP OK | Java 21
matching=true http=false journal=true
```

The existing 150 tests should remain passing, with 22 new journal test methods
(12 journal file tests and 10 sequencer/journal tests); the Gradle report is
**the source of truth** for counts and pass/fail. The Gradle/JUnit suite was
not runnable in the artifact-creation sandbox; Java 21 compilation and an
independent journal smoke harness were run there.

New tests cover command + outcome roundtrips, exact trades/cancel/reject data,
concurrent sequence order, forced vs buffered completion policy, command-only
crash window, incomplete tail strict/repair, bad CRC, header/version errors,
no journal overwrite, mismatched instruments and fail-stop when storage closes.

## 3. Inspect journal functionality

`FileCommandJournal.createNew(path, instrument, policy)` creates a fresh
`.mlj` file; it never overwrites a file. The `SingleWriterSequencer` overload
with a journal owns that journal. Commands are recorded before book mutation;
outcomes afterwards. `SYNC_EACH_RECORD` forces both and completes futures only
after the second force. `OS_BUFFERED` is **not durable**. Read the full API
example in README and ADR 0008.

`FileCommandJournal.scan(path, STRICT)` inspects a closed file, without
mutation. `TRUNCATE_INCOMPLETE_TAIL` is **offline destructive repair**; it
requires exclusive lock and never hides a complete bad-CRC record. A pending
command must not be treated as a finished outcome. Restart/replay comes in
Commit 08; **do not open an existing file as a fresh book**.

## 4. Commit manually after successful tests

```bash
git add -A
git diff --cached --check
git commit -m "feat: journal ordered commands and decisions"
git push
git log -1 --oneline
```

**STOP:** send test results and the commit hash, or exact failure output.
Do not proceed to Commit 08 if this commit fails.

If Gradle fails to spawn a JVM after a JDK update, `./gradlew --stop` plus
`--no-daemon --max-workers=1` has previously worked in this WSL environment.
