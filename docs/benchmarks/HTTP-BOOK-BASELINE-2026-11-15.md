# Verified user-supplied HTTP read baseline — 2026-11-15

This is **not** a JMH throughput result and not a benchmark of a public
Render instance. These figures come from the user's pasted output of
`scripts/bench_http.py`, running locally on WSL2 before Commit 12.

| Field | User-reported value |
| --- | --- |
| Run mode | `book` (GET read requests) |
| Requests measured | 40 |
| Warmup | 10 |
| Success / failure | 40 / 0 |
| Statuses | 40 × HTTP 200 |
| Concurrency | 1 |
| Pace | 250 ms between requests |
| Runtime including warmup | 12.253 s |
| Client p50 | 1.971 ms |
| Client p95 | 2.508 ms |
| Client p99 | 3.192 ms |
| Client max | 3.192 ms |
| Python | 3.12.3 |
| Host | Linux 6.18.58.1-microsoft-standard-WSL2-x86_64 (glibc 2.39) |

The numbers reflect client-observed localhost HTTP round trips, including
network stack, serialization, and potential queuing. With only 40 measured
requests and one concurrent client, the p99 cannot support production SLA
claims or throughput extrapolations. Neither the JMH raw matcher benchmark
nor fsync latency measurements have been provided. Do not compare these
figures directly against the separate JMH benchmark boundaries.
