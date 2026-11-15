# MATCHLINE benchmark methodology (Commit 10)

These are **instructions for obtaining real numbers**, not benchmark results.
No fabricated throughput, p99 latency, 3x speedup, or SDE-level scale claim.
The JMH dependency is pinned to `org.openjdk.jmh:*:1.37` and isolated from
production and `test` dependencies.

## Before measuring

1. Use the WSL Linux filesystem (`~/Stuff/Projects/MATCHLINE`), Java 21,
   and Gradle 8.14.3 wrapper. Check `java -version`.
2. Close memory-heavy background jobs and record CPU details. Avoid running
   profiling tools and the HTTP load generator at the same time as JMH.
3. Make sure the source is at a known commit (`git log -1 --oneline`). Keep
   benchmark artifacts locally under ignored `build/benchmarks/`.
4. On first JMH run, Gradle must resolve JMH 1.37 + annotation processor from
   Maven Central. The ordinary `./gradlew test` does not need JMH.

## Raw core and sequencer

```bash
chmod +x scripts/run-jmh.sh
./scripts/run-jmh.sh raw
./scripts/run-jmh.sh sequencer
```

Each workload **builds a fresh book** so the one-epoch accepted-ID limit never
pollutes later invocations. REST_ONLY inserts 500 bids across ten levels;
CROSSING repeatedly places one bid and crosses it with a sell; MULTI_FILL
inserts four bids at distinct prices and crosses all four with a sell. All
trades execute at maker price, and all order inputs are prebuilt outside
measurement. Each timed invocation processes 500 commands and normalizes
results with JMH `@OperationsPerInvocation(500)`.

**Raw benchmark** includes fresh book creation. **Sequencer benchmark**
includes writer thread startup, sequential producer acknowledgements and
clean shutdown per 500-command batch. They are deliberately full-path batch
throughputs, not a pure nanosecond operation isolation. JMH prints normalized
commands/second for these cases.

## Journal cost and fsync

```bash
./scripts/run-jmh.sh buffered
./scripts/run-jmh.sh sync
```

Each journal invocation creates a **new real file** in the OS temp directory,
forces the journal header, processes 100 commands with 200 frames, closes
and cleans up. `SYNC_EACH_RECORD` forces every command and decision frame;
`OS_BUFFERED` does not guarantee persisted data at acknowledgement. JMH
reports normalized microseconds/command. Filesystem/cache/background load
can dominate. Do not describe results as pure fsync latency; no real crash
or power-loss drill is performed by the harness.

Runner output is saved in `build/benchmarks/<profile>-<UTCstamp>/`:
`results.json` (JMH source data), `jmh-console.log`, `environment.txt`
(OS, JDK, CPU, commit, dirty flag), `results.sha256`. Repeat runs and keep
raw evidence; not single-point claims. If results fluctuate, report ranges
and the environment rather than cherry-picking the best run.

## HTTP overhead (not JMH)

Start the server in a separate terminal on the same machine (loopback by default):

```bash
./gradlew run --args='--serve' --no-daemon --max-workers=1
```

In another WSL terminal, with **a fresh volatile simulator session**:

```bash
python3 scripts/bench_http.py --mode book --requests 40 --warmup 10 \
  --pace-ms 250 --output build/benchmarks/http-book-1.json
python3 scripts/bench_http.py --mode orders --requests 40 --warmup 10 \
  --pace-ms 250 --output build/benchmarks/http-orders-1.json
```

The runner allows only loopback HTTP, no auth header, at most 500 measured
requests + 100 warmup requests, and at most 16 client workers. With default
250ms pacing it stays near the demo server's **4 requests/sec** steady
budget. Burst settings may produce rate-limit responses, which are recorded
as errors rather than falsely claimed successes. Posting orders consumes
unique IDs and changes book state; use a disposable non-persistent session.
The script writes a JSON status breakdown and nearest-rank p50/p95/p99 for
**successful client-observed round trips only**. These are not the server's
histogram p99 bucket estimates, and neither is exchange-grade production
latency evidence. `requests=40` is a functional baseline, not adequate for
confident p99 inference; more samples need longer controlled test periods.

## Live operational metrics

```bash
curl -sS http://127.0.0.1:8080/api/v1/metrics
curl -sS http://127.0.0.1:8080/metrics
```

The JSON view contains admission, rejection and completion counts, queue
capacity/occupancy, histogram samples and approximate percentile **upper
bounds in nanoseconds**. Prometheus format emits `_bucket`, `_sum` and
`_count` for time-based histograms in **seconds**. These measurements are
in-memory, weakly consistent during concurrent commands and not proof of
fsync/durable acknowledgement. A `journal=true` session changes command
processing latency because disk IO is included in the writer stage.

## Profiling hooks (opt-in)

For JVM profiling, use Java Flight Recorder on the **real application**.
Use the Gradle application distribution rather than a non-executable plain JAR:

```bash
mkdir -p build/benchmarks
./gradlew installDist
JAVA_OPTS='-XX:StartFlightRecording=filename=build/benchmarks/matchline.jfr,settings=profile,dumponexit=true' \
  ./build/install/matchline/bin/matchline --serve
```

JFR introduces overhead; do not compare performance with and without a
recorder as if they were equivalent. Stop the running HTTP process cleanly to
flush the recording, inspect in JDK Mission Control/IntelliJ Profiler if
available. A profiler is used to locate a bottleneck, not claim performance.
