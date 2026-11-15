# ADR 0012 — Same-origin browser lab and read-only preview

Status: Accepted for Commit 11.

## Context

The real single-writer, recoverable Java backend and bounded HTTP adapter exist.
A portable demo needs an interactive application, a small responsive portfolio
preview and evidence that neither creates an independent synthetic matching
engine. A separate frontend host would introduce cross-origin configuration,
release coordination and more failure modes. Public deployment and hardened
ingress are not yet complete.

## Decision

- Serve `/lab` (full interactive HTML) and `/preview` (read-only iframe
  HTML), `/ui/app.css`, `/ui/app.mjs`, and `/ui/mark.svg` via a **fixed
  allowlist** of classpath files on the Java HTTP listener.
- Only server API responses may decide book state, trade prices, FIFO outcomes,
  sequence IDs, acceptance or rejection. JavaScript handles visualization,
  input validation for UX, and sends strictly sequenced command requests.
- Use short, non-overlapping polls (full page 4s, preview 5s). On hidden tabs
  pause polling. Do not add SSE/WebSocket until there is real sustained need;
  the existing global HTTP token bucket (4/s refill) also limits polling.
- Scenarios are **deterministic command lists**, not deterministic promises of
  the resulting book: other current/resting orders may affect outcomes. Every
  click issues one HTTP request; there is no implicit reset. Use fresh volatile
  sessions for isolated experiments.
- Display recent events/trades as process-local tapes. Show the actual startup
  recovery report without claiming a browser-triggered journal replay.
- Security: CSP permits scripts/styles/connect only from same origin, forbids
  inline JS and cross-origin resources. Never interpolate remote data as HTML;
  render string fields through `textContent`. Full lab and all API routes are
  non-embeddable. Only the **preview HTML response** may use one configured
  exact `frame-ancestors` origin via `MATCHLINE_EMBED_ORIGIN`.
- Same-origin browser requests don't need CORS. `MATCHLINE_ALLOWED_ORIGIN`
  retains its pre-existing independent meaning for actual cross-origin API
  callers. The preview itself does not expose mutating controls, but direct
  unauthenticated API requests are still possible: this is **not** a public
  ingress security boundary. Commit 12 must add external access protection.
- No frontend npm/build dependency for normal Java build; JavaScript uses
  ES modules and system fonts. Node 20+ optional pure-function tests.

## Consequences

A user can run `./gradlew run --args='--serve'` and open a functioning lab
without installing anything frontend-related. UI is packed inside the JAR,
including in Docker. Same-origin eliminates CORS exposure for the lab. The
preview may be embedded later by the exact verified HTTPS portfolio origin.
The in-memory tape is not guaranteed to include command outcomes recorded
outside this HTTP process, and polls may observe slightly different sequence
boundaries across endpoints. The page marks the book sequence separately.
Browser refresh and actual crash/recovery validation remain manual tasks.
