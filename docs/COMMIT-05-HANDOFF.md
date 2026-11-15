# Commit 05 — Cancel, lifetime duplicates, amend policy

Baseline: user-confirmed **Commit 04**. Apply **one** artifact, either the
unified patch or the changed-files ZIP. Do not apply both. No earlier code
is intentionally deleted.

## Apply

Store `MATCHLINE_Commit05.patch` in `~/Stuff/Projects`, then:

```bash
cd ~/Stuff/Projects/MATCHLINE
git status --short
git apply --check ../MATCHLINE_Commit05.patch
git apply ../MATCHLINE_Commit05.patch
```

Stop if `git status --short` shows unrelated edits or if `git apply --check`
reports a failure. Share the output rather than forcing the patch.

## Verify in WSL / IntelliJ

```bash
./gradlew --stop
./gradlew clean test --no-daemon --max-workers=1 --console=plain
./gradlew run --args='--self-check' --no-daemon --max-workers=1
./gradlew build --no-daemon --max-workers=1 --console=plain
git diff --check
```

Expected: `BUILD SUCCESSFUL`, the pre-existing Commit 04 test suite still
passes, and new cancellation/reference-model tests pass. Self-check stays:

```text
MATCHLINE BOOTSTRAP OK | Java 21
matching=true http=false journal=false
```

In IntelliJ: run `LimitOrderCancellationTest` and
`LimitOrderCancellationModelTest` under the **WSL JDK 21**. `build/reports/tests/test/index.html`
contains the actual count and failures. You can debug the cancellation test
without any HTTP service. Docker remains an optional CLI smoke test:

```bash
docker compose --profile smoke run --build --rm matchline-smoke
```

If tests fail with `Failed to exec spawn helper`, stop stale Gradle daemons,
use `--no-daemon --max-workers=1`, and restart WSL if needed. This is a
JDK/daemon process-launch issue, not a reason to change matching code.

## Commit and push (user action only)

```bash
git add -A
git diff --cached --check
git commit -m "feat: add ordered cancellation and ID deduplication"
git push
git log -1 --oneline
```

Return the test summary, any failures and the actual commit hash. Stop here;
Commit 06 will introduce the single-writer gateway, accepted command sequence
and backpressure, on this user-confirmed baseline. No actual Git or deployment
action is performed by the code generator.

## Changed paths

**Modified (6):**

- `README.md`
- `docs/roadmap.md`
- `src/main/java/dev/matchline/book/LimitOrderBook.java`
- `src/main/java/dev/matchline/domain/CancelOrder.java`
- `src/main/java/dev/matchline/domain/OrderId.java`
- `src/main/java/dev/matchline/domain/RejectionCode.java`

**Added (5):**

- `docs/COMMIT-05-HANDOFF.md`
- `docs/adr/0006-cancellation-id-history-and-amend-policy.md`
- `src/main/java/dev/matchline/book/CancelResult.java`
- `src/test/java/dev/matchline/book/LimitOrderCancellationTest.java`
- `src/test/java/dev/matchline/book/LimitOrderCancellationModelTest.java`

**Deleted:** none.
