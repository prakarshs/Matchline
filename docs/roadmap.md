# Sequential commit plan

Each item represents a sequential **manual** Git commit. The user confirmed commits 01–09; Commit 10 was committed locally with verified HTTP benchmark output, but its push reported no upstream branch. Actual JMH execution has not yet been reported. Commit 11's browser screenshot was shown by the user; its full test/push gate is not independently visible. Commit 12 code is now supplied as a release candidate, pending the user's Gradle, Docker, CI and deployment verification. Do not claim remote pushes or visual checks without user evidence.

| Commit | Implementation target | Verification gate |
|---|---|---|
| 01 | Java/Gradle/IDE bootstrap, unit tests, Docker smoke | `./gradlew test`; debug one test |
| 02 | Bounded types, tick arithmetic, command/reject contracts | Invalid input and overflow tests |
| 03 | Price levels, FIFO order ID index | Stable priority and lookup tests |
| 04 | Trades, partial/multi fills | Quantity and resting-price assertions |
| 05 | Cancellation, duplicate IDs, explicit amend rules | Cancel-vs-match event ordering |
| 06 | Single writer, sequencing and backpressure | Concurrent ingress linearization |
| 07 | Append-only versioned command/event journal | Tail corruption/torn-write tests |
| 08 | Deterministic replay, snapshots and recovery | Full vs restored state equivalence |
| 09 | HTTP API, OpenAPI, controlled reset | API and integration tests |
| 10 | JMH, metrics, reproducible result scripts | Unmodified raw benchmark capture |
| 11 | Live interactive app + compact iframe preview | Browser actions correspond to backend |
| 12 | CI, security, public deployment, documentation, portfolio | Clean clone, live checks, reviewed claims |

The authoritative brief specifies the full mission and release bar. This file is an implementation-order map, not evidence of completion.
