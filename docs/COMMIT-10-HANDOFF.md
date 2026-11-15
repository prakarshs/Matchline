# Commit 10 handoff — JMH, measured operational metrics, reproducible scripts

## Scope and files

- `src/main/java/dev/matchline/observability/*`: fixed-bin, bounded thread-safe
  histograms; process counters; JSON and Prometheus encoding.
- `src/main/java/dev/matchline/sequencer/SingleWriterSequencer.java`: command
  queue timing, decision timing and ingress counters. No change to matching,
  journaling, replay, sequence allocation or command result schemas.
- `src/main/java/dev/matchline/http/MatchlineHttpServer.java`: HTTP response
  counts and request duration; two new diagnostic endpoints.
- `src/main/java/dev/matchline/benchmark/DeterministicWorkload.java` and
  `src/jmh/java/...`: isolated JMH input and benchmark classes.
- `build.gradle.kts`: `jmh` source set and opt-in task, JMH 1.37 processor.
  Regular `test` and `build` do NOT run JMH and production remains dependency-light.
- `scripts/run-jmh.sh` captures unmodified JMH JSON, console and environment;
  `scripts/bench_http.py` performs paced localhost HTTP requests and captures
  a success/error breakdown, not an optimistic-only p99.
- `src/test/java/dev/matchline/observability/*` and
  `src/test/java/dev/matchline/http/MetricsHttpTest.java`: focused correctness
  tests for metrics and workloads.
- `src/main/resources/openapi.yaml`, `http/05-metrics.http`, docs and README.

## Apply (from the actual MATCHLINE checkout)

```bash
cd ~/Stuff/Projects/MATCHLINE
git status --short
git apply --check ../MATCHLINE_Commit10.patch
git apply ../MATCHLINE_Commit10.patch
```

Alternative: unzip changed-files ZIP into the repo root; never apply both.
No existing source file is deleted.

## Verify on Java 21 / WSL

```bash
./gradlew --stop
./gradlew clean test --no-daemon --max-workers=1 --console=plain
./gradlew test --tests 'dev.matchline.observability.*' \
  --no-daemon --max-workers=1 --console=plain
./gradlew test --tests 'dev.matchline.http.MetricsHttpTest' \
  --no-daemon --max-workers=1 --console=plain
./gradlew run --args='--self-check' --no-daemon --max-workers=1
```

The expected bootstrap line stays:

```
MATCHLINE BOOTSTRAP OK | Java 21
matching=true http=true journal=true
```

Run the live API in terminal 1:

```bash
./gradlew run --args='--serve' --no-daemon --max-workers=1 --console=plain
```

In terminal 2:

```bash
curl -sS http://127.0.0.1:8080/api/v1/metrics
curl -sS http://127.0.0.1:8080/metrics
curl -sS http://127.0.0.1:8080/openapi.yaml
```

Run `http/05-metrics.http` with IntelliJ HTTP Client. The JSON must contain
`writer` counters, `queueWaitNanos`, `processingNanos`,
`acknowledgementNanos`, and `http`. Prometheus text contains
`matchline_commands_completed_total` and `_bucket/_sum/_count` histograms.
After placing an order, `commandsAdmitted`, `commandsCompleted` and the
histogram counts increase without changing the book/trade outcome.

## Benchmark verification gate (not ordinary tests)

```bash
chmod +x scripts/run-jmh.sh
./scripts/run-jmh.sh raw
./scripts/run-jmh.sh sequencer
# Optional slower/storage-dependent runs:
./scripts/run-jmh.sh buffered
./scripts/run-jmh.sh sync
```

First invocation downloads JMH 1.37 and runs the annotation processor; the
regular tests do not depend on it. The scripts write **actual** JMH reports
in `build/benchmarks/<profile>-<UTCstamp>/results.json`, along with run logs,
`environment.txt`, and `results.sha256`. Check that every JMH score has a
scenario label, a unit and a non-empty measurement. The scripts do not
include any hardcoded performance numbers. Confirm same JDK/CPU/OS/policy
before comparing profiles. Save the raw JSON if you want to publish results.

Run the paced HTTP client only with a **disposable localhost volatile demo**:

```bash
python3 scripts/bench_http.py --mode book --requests 40 --warmup 10 \
  --output build/benchmarks/http-book-1.json
```

If JMH cannot be downloaded in WSL, troubleshoot Gradle internet/repositories
and pinning before treating benchmark verification as done. Do not replace
JMH with an `elapsedMillis()` loop or post invented claims.

## Commit manually after verification

```bash
git diff --check
git add -A
git diff --cached --check
git commit -m "perf: add JMH workloads and bounded metrics"
git push
git log -1 --oneline
```

**STOP / checkpoint:** report `./gradlew clean test`, one JMH raw run's
success/failure, and GET `/api/v1/metrics`. Next planned increment is
Commit 11 — interactive full UI + compact portfolio iframe preview, using
real backend responses, not a disconnected browser mock.
