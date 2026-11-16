# Spring S04 — Public-demo sessions and browser UI

The matching algorithm, single-writer sequencing, binary journaling and deterministic replay **do not change** in S04.

## Why

A public exchange lab must not share one mutable order book across independent visitors. Spring must route each request to a session-specific writer, retain a request lease during processing, and expose a bounded historical view that is not confused with a durable event log.

## Spring routes added

| Route | Function |
|---|---|
| `POST /api/v1/session` | Create an isolated temporary session; optional `?mode=preview` seeds real demo commands |
| `GET /api/v1/dashboard?depth=5` | One writer-ordered book barrier plus bounded trades, events, status and metrics |
| `GET /api/v1/trades?limit=50` | HTTP-confirmed trade tape, not a durable journal |
| `GET /api/v1/events?limit=50` | HTTP-confirmed command tape |
| `GET /api/v1/status`, `/api/v1/recovery`, `/api/v1/metrics` | Volatile writer metadata and diagnostic metrics |
| `/lab`, `/embed`, `/preview` | Same packaged UI from the original Java HTTP adapter |
| `/ui/app.css`, `/ui/app.mjs`, `/ui/mark.svg`, `/openapi.yaml` | Allowlisted resources only |

All original S03 order, cancel, book and lookup routes now resolve through `MatchingRequestScope`. In demo mode they require the 43-character `X-Matchline-Session` capability token. The shared `DemoSessions` implementation enforces 12 sessions, 20-minute idle expiry, 64 queue slots per session, maximum 500 resting orders and 3,000 historic IDs per session; it does not evict active sessions. The servlet guard acquires the lease **before** JSON deserialization; request scope reuses that lease and the filter releases it after the HTTP exchange. Thus an expiring session cannot close its writer midway through a request.

## Run using IntelliJ Community

1. Save an **Application / Spring Boot** configuration for `dev.matchline.spring.MatchlineSpringApplication`.
2. Add **Environment variables**: `MATCHLINE_PUBLIC_DEMO=true;MATCHLINE_EMBED_ORIGIN=https://portfolio.jhethia.workers.dev` (IntelliJ's separator UI may vary). Keep port `8082`.
3. Run `SpringPublicDemoMvcTest`, `SpringLocalUiMvcTest`, `SpringDemoPropertiesTest` using gutter icons; run your saved **All Tests** Gradle configuration.
4. Start Spring Boot in IntelliJ. From WSL: `bash scripts/smoke-spring-demo.sh`.
5. Open `http://127.0.0.1:8082/lab`, `/embed`, `/preview` in a browser. Try a second incognito window; its book must start at sequence 0.
6. Restore `MATCHLINE_PUBLIC_DEMO=false` for normal local one-writer development.

## Security and precise limitations

- Browser assets load exclusively from the service's own origin, with strict CSP. `/lab` is never embeddable; `/preview` is frameable only by an explicitly configured origin; `/embed` also requires public-demo mode. No wildcard origins.
- The servlet filter installs `no-store` and `nosniff`, enforces a global 64-in-flight cap and a coarse 240-token burst / 4 requests-per-second refill; cross-origin `Origin` headers are denied by Host comparison. This is **not** per-IP abuse protection.
- `Content-Length >4096` on order POST is rejected; unbounded chunked uploads need an explicit streaming/body limit at the reverse proxy or servlet boundary before production cutover.
- `GET /actuator/prometheus` returns **text**, not a visual dashboard, in local mode; this exporter is blocked when public-demo mode is enabled to avoid exposing runtime details. Securely exposing engine-specific Micrometer meters is S05.
- Spring S04 is **volatile**: no journal startup, no snapshot replay, no durable response. `GET /api/v1/recovery` clearly reports `journalConfigured=false`.
- The Spring root URL redirects to `/lab`. The original adapter's root behavior is unchanged.
- Public Render **still uses the original Java HTTP server**. Do not modify Dockerfile, Render environment or portfolio iframe URLs until S05 confirms end-to-end equivalence and operational security.

## Why reuse the old session manager?

A second session implementation would risk divergent expiry, capacity and concurrency policies. S04 makes its existing lease and tape APIs visible to the Spring transport without changing their mechanics. Later, those framework-independent types can be moved into a neutral package as a dedicated cleanup.
