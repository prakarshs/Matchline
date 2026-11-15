# MATCHLINE — Commit 04 manual handoff

**Scope:** Deterministic in-memory matching, maker-priced trade values, and conservation tests.
**Baseline:** User-verified, pushed Commit 03. All changes apply to the exact
Commit 03 source delivered earlier; the user's local Git history is not present
in the artifact-generation environment.

## Apply

Place `MATCHLINE_Commit04.patch` under `~/Stuff/Projects` (next to `MATCHLINE/`):

```bash
cd ~/Stuff/Projects/MATCHLINE
git status --short
git apply --check ../MATCHLINE_Commit04.patch
git apply ../MATCHLINE_Commit04.patch
```

The alternative `MATCHLINE_Commit04_Changes.zip` contains only updated/new files.
Extract its contents into the repository root with `unzip -o`; **do not apply both**.
No files are deleted, no Gradle dependencies or wrapper files changed.

## Verify

```bash
./gradlew --stop
./gradlew clean test --no-daemon --max-workers=1 --console=plain
./gradlew run --args='--self-check' --no-daemon --max-workers=1
git diff --check
```

Expected: all previous 79 JUnit cases plus 28 new cases = **107** passing
JUnit cases (2 new parameterized methods contribute 4 cases). Test reports:
`build/reports/tests/test/index.html`.

Expected self-check now reads:

```text
MATCHLINE BOOTSTRAP OK | Java 21
matching=true http=false journal=false
```

This is a deliberate, truthful **capability-flag change**: the Java matching
core exists, but there is no interactive CLI order command, HTTP service,
journal, concurrent ingress or exchange readiness.

In IntelliJ, run `LimitOrderMatchingTest` and `LimitOrderMatchingModelTest`.
Optional debugging: breakpoint at `LimitOrderBook.executeLimit()` and step through
`samePriceOrdersFillInStableFifoOrder`.

## Inspect the implementation

- `src/main/java/dev/matchline/book/LimitOrderBook.java` — read-only capacity
  preflight; best-price/FIFO execution; partial quantity updates; fully filled
  maker removal; unmatched remainder insertion.
- `src/main/java/dev/matchline/book/TradeExecution.java` — maker-priced execution.
- `src/main/java/dev/matchline/book/MatchResult.java` — immutable conservation-checked result.
- `src/test/java/dev/matchline/book/LimitOrderMatchingTest.java` — specific
  order flow and rejection cases.
- `src/test/java/dev/matchline/book/LimitOrderMatchingModelTest.java` — independent
  list reference and repeatable in-memory command application.
- `src/main/java/dev/matchline/bootstrap/{BootstrapStatus,MatchlineApplication}.java`
  and their two existing test classes — truthful `matching=true` flag/help text.
- `README.md`, `docs/roadmap.md` — accurate implemented scope.
- `docs/adr/0005-in-memory-matching-and-capacity.md` — decisions and trade-offs.

## Commit and push (only once checks pass)

```bash
git status --short
git diff --stat
git diff --check
git add -A
git diff --cached --check
git commit -m "feat: implement price-time limit order matching"
git push
git log -1 --oneline
```

**STOP.** Send the passing test output and the manually created commit hash.
Commit 05 will introduce cancellation, lifetime duplicate-ID tracking, and
explicit amend/no-amend semantics, without changing the defined matching order.
