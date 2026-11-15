# ADR 0010 — Dependency-free HTTP adapter, command semantics and controlled demo reset

**Status:** Accepted for Commit 09, November 2026.

## Decision

Implement an HTTP boundary using Java 21 `jdk.httpserver` and virtual-thread request handlers, not Spring Boot or an external server framework. JSON commands accept **only flat objects** of strings and signed 64-bit integral values, with booleans/null syntactically accepted but rejected if used for typed command fields. The deliberately small JSON codec supports escaped strings and rejects duplicate keys, malformed input, nonintegral numbers, excess body size and unexpected fields. Nested JSON is emitted for responses; incoming commands do not need nested structures. Avoid adding network serialization to the matching hot loop and pin no new runtime dependencies.

The server owns one `SingleWriterSequencer(DEMO)`. Every placement/cancellation calls `submit`; all mutable book access remains on the writer. `inspect` and `find` are writer-queued reads, preserving the established ordering guarantees. Admission is **not** an execution acknowledgement; the HTTP request waits for its completion stage. A completed placement/cancel returns 200; a sequenced business rejection returns 409 with its original sequence and machine code. Invalid pre-admission JSON/domain values return 4xx **without** a sequence. A full ingress queue returns 429, closed/failing ingress returns 503. If an admitted operation has not completed after 5 seconds, return 504 with `OUTCOME_UNKNOWN` and the original sequence; the client must inspect/reconcile state before deciding to retry. Read timeouts are not command outcomes.

When a journal is configured, create it only if it does not exist; otherwise **strictly recover** the existing file and optional snapshot before calling `HttpServer.start`. No automatic repair, compaction, migration or deletion. Journal configuration is opt-in; `durable=true` in command outcomes requires **sync-each-record** (the journal command and decision were both forced). This is a configured fsync boundary, not a claim about hardware power-loss behavior. A snapshot is scheduled every 100 completed decisions when a snapshot path is configured. `GET /recovery` returns the last verified startup report and current sequence; it does not perform a concurrent scan of the live journal (which could observe an incomplete frame) or a new replay.

Recent HTTP-observed decisions and fills are **non-authoritative, process-local**, separately bounded to 2,048 compact command summaries and 2,048 trade entries. Maps are ordered by command sequence and fill index even if HTTP response threads wake out of order. Restarts clear this tape; full journal history remains in the binary file. An admitted command whose HTTP handler times out may execute without appearing in this best-effort tape. No SSE is introduced in this commit.

## Reset authorization and atomicity

Reset is disabled unless an operator sets `MATCHLINE_RESET_TOKEN` (at least 32 characters). Compare the complete Bearer value using `MessageDigest.isEqual`; never echo the token. The reset endpoint takes a **session write lock** and drains the existing writer before replacing it with a fresh **volatile-only** writer and clearing the tapes. Ordinary HTTP operations hold the read lock through their acknowledgement, so reset cannot split a request across engine generations. Reset reuses an order ID only in the new explicitly reset generation; `generation` increments. Journal-backed sessions **always** reject reset with HTTP 409, even when authorized: deleting a persisted epoch requires an explicit offline administrative workflow, not an HTTP action.

## Resource and network boundary

- Default bind 127.0.0.1:8080, with no CORS origin and no reset token. Remote listening requires an explicit override and is unsuitable for direct public internet exposure.
- Body maximum 4 KiB; JSON content type required; one DEMO instrument; strict domain bounds inherited from Commit 02.
- Maximum 64 simultaneously handled requests; global token bucket capacity 240, refill 4/s; bounded sequencer ingress 1..65,536. This is **not** per-IP control, user authentication or protection against a distributed denial of service.
- Optional single exact CORS origin, no `*`, no credentials. Browser preflight is allowed only for that origin.
- Responses carry `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` and restrictive content security policy. The API is JSON/YAML-only; iframe embedding and a separate browser UI will be addressed in Commit 11.

## Trade-offs and limitations

Standard JDK HTTP reduces boot and dependency footprint but does not replace mature HTTP middleware. The deliberately flat input JSON parser does not accept arbitrary JSON structure, floating-point prices, or duplicate keys; document it explicitly. No client identity, exchange-grade risk controls, TLS listener, SSE, durable HTTP tape, multi-symbol routing, or public deployment in this increment. These endpoints are a synthetic **demo interface**, not a trading service. Benchmarks in Commit 10 must measure the matcher separately from HTTP, serialization and fsync costs.
