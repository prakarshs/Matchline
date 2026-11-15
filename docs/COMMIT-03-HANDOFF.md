# MATCHLINE — Commit 03 manual handoff

**Scope:** Resting limit-order book storage only. No matcher or command-processing service.

## Apply (from the root of the manually managed repository)

Place `MATCHLINE_Commit03.patch` in `~/Stuff/Projects`, not inside your Git repository.

```bash
cd ~/Stuff/Projects/MATCHLINE
git status --short
git apply --check ../MATCHLINE_Commit03.patch
git apply ../MATCHLINE_Commit03.patch
```

Alternatively, extract the **changed-files-only** ZIP with `unzip -o` in the repository root;
never apply both artifacts. No files are deleted.

## Verify

```bash
./gradlew --stop
./gradlew clean test --no-daemon --max-workers=1 --console=plain
./gradlew run --args='--self-check' --no-daemon --max-workers=1
git diff --check
```

Expected: all Commit 01/02 tests plus the new Commit 03 cases pass. The application
continues to print `MATCHLINE BOOTSTRAP OK | Java 21` and
`matching=false http=false journal=false` because there is still no matcher.
The HTML test report is `build/reports/tests/test/index.html`.

In IntelliJ, right-click `src/test/java/dev/matchline/book` -> Run tests. Confirm
that both book test classes run and pass. Optional: set a breakpoint in
`LimitOrderBook.addResting` and debug `bidsAreHighestPriceFirstAndStableWithinPrice`.

## Review manual Git diff

```bash
git status --short
git diff --stat
git diff --check
git add -A
git diff --cached --check
git commit -m "feat: add FIFO price-level order book storage"
git push
git log -1 --oneline
```

**STOP:** Send the Gradle test result and commit hash. If a test fails, do not commit;
send the exact failure and we will repair Commit 03 before proceeding to matching
execution in Commit 04.

## New files

- `src/main/java/dev/matchline/book/LimitOrderBook.java`
- `src/main/java/dev/matchline/book/RestingOrderView.java`
- `src/main/java/dev/matchline/book/LevelDepth.java`
- `src/test/java/dev/matchline/book/LimitOrderBookTest.java`
- `src/test/java/dev/matchline/book/LimitOrderBookModelTest.java`
- `docs/adr/0004-price-level-order-book.md`
- `docs/COMMIT-03-HANDOFF.md`

## Modified files

- `README.md` — current status, explicit cost model, new book usage and limitations.

## Deleted files

None. Existing bootstrap, domain classes/tests, Gradle, Docker, license and wrapper remain intact.
