# Commit 06 — Single-writer sequencer and bounded ingress

Apply exactly the supplied `MATCHLINE_Commit06.patch` to the **verified and
pushed Commit 05** checkout. Do not apply the alternative changed-files ZIP on
top of the patch. This handoff is not evidence that tests ran on your machine.

```bash
cd ~/Stuff/Projects/MATCHLINE
git status --short
git apply --check ../MATCHLINE_Commit06.patch
git apply ../MATCHLINE_Commit06.patch

./gradlew --stop
./gradlew clean test --no-daemon --max-workers=1 --console=plain
./gradlew run --args='--self-check' --no-daemon --max-workers=1
git diff --check
```

The Gradle tests should complete without failures. Check the actual number of
cases in `build/reports/tests/test/index.html`; no test count is claimed until
Gradle runs successfully. The CLI remains `matching=true http=false journal=false`;
the sequencer is a Java API, not an HTTP server or persistence layer.

To run only the new tests:

```bash
./gradlew test --tests 'dev.matchline.sequencer.*' \
    --no-daemon --max-workers=1 --console=plain
```

Inspect `SingleWriterSequencerTest` and
`SingleWriterSequencerConcurrencyTest` for reproducible demonstrations of
queue saturation, admission order, FIFO, cancel-vs-fill, shutdown and fault
containment. No UI or production load-generator is added in this commit.

If green, perform the manual Git steps:

```bash
git add -A
git diff --cached --check
git commit -m "feat: serialize commands with bounded single writer"
git push
git log -1 --oneline
```

**STOP:** Send test output and final commit hash before requesting Commit 07.
Commit 07 will introduce a journal format and define a *new* durability-aware
acknowledgement boundary. It must not pretend the current futures are durable.

Troubleshooting: if Gradle reports `Failed to exec spawn helper` after a JDK
update, run `./gradlew --stop` and retry with `--no-daemon --max-workers=1`.
If compilation or assertions fail, send the exact output and do NOT commit;
we will repair this increment before advancing.
