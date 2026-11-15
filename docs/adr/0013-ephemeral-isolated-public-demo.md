# ADR 0013 — Bounded, isolated public-demo books

Status: Proposed for user verification in Commit 12.

## Context

The local engine is a **single shared order book**, so publicly hosting the
Commit 11 HTTP adapter unchanged would let strangers change each other's test
state. Free Render web services have ephemeral filesystems and may spin down,
so they must not be advertised as durable journal hosts. Browsers in cross-site
portfolio iframes may block third-party cookies. The UI previously issued
up to six polling requests every four seconds per visitor.

## Decision

- Default local mode is unchanged: loopback listener, one shared engine,
  optional sync journal/snapshot/recovery and secret-gated volatile reset.
- `MATCHLINE_PUBLIC_DEMO=true` is an explicit alternative mode. It **rejects**
  journal and snapshot paths, reset secrets and cross-origin API allowlists.
  It runs independent single-writer engines with a hard cap of 12 active
  sessions; each book allows at most 500 resting orders, 3,000 historical IDs,
  and 64 queued work items. Tokens are generated from 32 random bytes and
  exchanged via `X-Matchline-Session`; tokens are not included in URLs/cookies.
- `POST /api/v1/session` creates a temporary session. All other diagnostic and
  mutation endpoints require a valid session token in public mode; no token
  is needed for UI assets, `/api/v1/health`, or OpenAPI. Missing/expired
  tokens are rejected *before* entering a writer. Sessions expire after
  20 minutes without API activity and are closed only after any in-flight
  request has released its lease. Session creation is denied at capacity,
  rather than silently evicting an active user's book.
- An opt-in preview seed (`?mode=preview`) submits synthetic orders through the
  actual Java sequencer, producing real book depth and one real trade. This
  sample is **synthetic** and process-local, not live exchange market data.
- The JS client may keep the capability in tab-scoped `sessionStorage`, with
  an in-memory fallback; no third-party cookie dependency. Session-expired
  rejections happen before execution and may initiate a new empty session;
  ambiguous 504 command outcomes are never automatically retried.
- The browser polls `GET /api/v1/dashboard` once every 4s (5s preview),
  instead of issuing 3–6 parallel reads. It returns a writer-consistent
  **book** plus a process-local observation of status, tape and metrics.
  Those supplemental fields are not an atomic multi-source snapshot.
- `/lab` is deliberately not embeddable. Only `/preview` and compact `/embed`
  may be framed by one exact HTTPS portfolio origin through CSP; interactive
  `/embed` requires public-demo mode even if the embed origin is configured. Requests
  from the iframe go to its own Java origin; CORS remains disabled.
- Free-tier deployment must run only in volatile public mode. No client
  identities, private accounts, financial assets, persistent session data,
  or promises of 24/7 availability. This does not provide DDoS protection,
  per-user identity verification, bot detection, or internet-grade security.

## Consequences

Two visitors cannot impact each other's order IDs or price-time priority.
Session tokens are temporary *capabilities to a synthetic book*, so somebody
holding another person's token could manipulate that book; do not treat tokens
as authentication or include secret user data. When a process restarts, all
public sessions disappear. A still-open browser can transparently start a new
session after receiving `SESSION_EXPIRED`; the old state is **not recovered**.

Bounded sessions prevent unbounded writer accumulation, but legitimate users
will receive 429 when all sessions are active. Shared global rate/concurrency
limits and provider-layer networking still apply. Benchmarks on Render's
free CPU/RAM would not be comparable to workstation JMH results.
