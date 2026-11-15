# ADR 0011 — Separate performance boundaries; bounded operational histograms

**Status:** Accepted for Commit 10 (Java 21, JMH 1.37)

## Context

MATCHLINE has a deterministic raw book, a one-thread sequencer, a versioned
append-only journal and an HTTP adapter. A single throughput number would
mislead users by conflating several very different costs. Production traffic
patterns and storage/host performance are not known, so never invent scale.

## Decision

1. **Three isolated JMH classes, source set `src/jmh`:**
   - `RawMatchingBenchmark`: book creation + 500 prebuilt validated commands
     per invocation; no queue/HTTP/disk.
   - `SequencerBenchmark`: new single-writer thread + 500 sequential submit/
     acknowledgement cycles + graceful close per invocation; no HTTP/disk.
   - `JournalBenchmark`: file creation, header fsync, 100 submit/ack cycles,
     binary frames, selected journal policy, close and filesystem cleanup.
     Explicit `OS_BUFFERED` vs `SYNC_EACH_RECORD` parameters; never compare
     them without identifying policy and physical storage medium.
   `@OperationsPerInvocation` normalizes output to one command, including
   per-batch setup/teardown amortization; it is not steady-state pure loop cost.
   Pre-created workloads use 500/100 distinct IDs, so no order-ID exhaustion.
2. All three share `DeterministicWorkload`: fixed REST_ONLY, CROSSING, and
   MULTI_FILL patterns as appropriate. This is an artificial, fixed input
   distribution, not exchange traffic. Benchmark tasks are excluded from
   ordinary `test` and `build`. Exactly one benchmark thread per fork, without
   concurrent writers within one book; source code defines warmup/measurement.
3. **HTTP measurements are separate** (`scripts/bench_http.py`) and restricted
   to the loopback listener, with explicit pacing and bounded request count.
   They measure client round-trip including network, JSON, HTTP server and
   writer queue; the server's 4 req/s steady request budget remains active.
   Report errors including HTTP 429, 503 and 504 alongside latency; failure
   responses are excluded from success latency statistics. No direct
   apples-to-apples comparison with raw-core JMH.
4. The active engine uses **fixed-memory, thread-safe histograms** for queue
   wait, command processing (journal and snapshot work included), and full
   admission-to-acknowledgement. The HTTP server counts 2xx/4xx/5xx responses
   and records whole-request duration. Histogram buckets are predefined and
   **the p50/p95/p99 values are bucket upper bounds, not exact latencies**.
   Metrics are observational and never determine book behavior.
5. `GET /api/v1/metrics` provides JSON and `GET /metrics` exposes a
   Prometheus-compatible text view, using only fixed metric names. No
   per-order, per-client, or raw-path labels, tokens or filesystem paths.
   Both remain under existing demo HTTP rate limiting, no open CORS by default.
   The HTTP counters persist across a volatile-demo reset; writer counters
   reset when that writer is replaced. No long-term metrics persistence.

## Consequences and limits

- Every result must be saved as raw JMH JSON/console + environment metadata,
  git commit and the precise command/policy. `scripts/run-jmh.sh` captures these.
- JMH does not prove request tail latency, real multi-producer throughput,
  exchange behavior, disk power-loss guarantees or application scalability.
- JMH measurements can change with CPU, memory, CPU frequency scaling, JVM,
  environment variables, GC, filesystem, and background load. WSL/Docker/
  network filesystems may distort fsync. For comparative runs, pin the same
  machine, JDK, thread setting, command scenario and policy.
- Histograms are intentionally a small but nonzero recording overhead; they
  are approximately consistent under live concurrency, not transactional.
- Prometheus endpoints are **not safe internet-facing telemetry endpoints**
  without edge protection. Keep the demo bound to loopback until a later
  secured deployment increment.
