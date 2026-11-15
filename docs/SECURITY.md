# MATCHLINE — security and operational boundaries

This is an **educational exchange simulator**, not a financial/trading system.
It offers no user identities, accounts, money, compliance, or market data.

## Trust boundaries

1. Local `--serve` (default): `127.0.0.1` bind; a single shared book; no API
   authentication. Do not proxy it to the public internet.
2. Local persistence: versioned journal, CRC validation, snapshots, offline
   repair, and explicit fsync policy. Protect local journal files and never
   let multiple processes write the same journal path.
3. Public-demo mode: each browser/tab obtains an opaque unguessable token to a
   **synthetic, bounded, volatile** book. This is isolation, *not login*. No
   journal, snapshots, persistent disk, privileged reset, or CORS allowlist
   permitted in this mode.
4. UI: static classpath assets only; strict CSP and exact `frame-ancestors`
   allow only portfolio `/preview` and `/embed`. The main lab, API and assets
   are non-embeddable. Browser uses same-origin API fetches.
5. Browser security: the session token is exposed to that page's JS and any
   injected script would be able to use it. CSP prevents inline scripts and
   third-party scripts, but cannot substitute for a code/security review.

## Bounded resources

- 12 active isolated sessions, 20-minute idle expiry.
- 500 resting orders, 3,000 accepted order IDs, 64 queue slots per session.
- Global limit: 64 simultaneous HTTP requests, 240 burst, 4 requests/s refill.
- POST JSON body max 4,096 bytes; no unbounded user-generated event tape.
- In public mode, malformed/expired session tokens cannot mutate any book.
- GET dashboard is a writer-ordered read plus separately sampled tapes/metrics.
- The 504 `OUTCOME_UNKNOWN` response is ambiguous and must not be retried
  blindly. Only rejected-before-admission session errors can be safely retried.

## Limitations

No distributed session registry, sticky load balancer routing, account
security, IP-level throttling, WAF, bot defence, anti-abuse verification,
SLOs, TLS termination inside Java, or vulnerability scanning. Free Render
provides reverse proxy HTTPS but may stop/restart the container, wiping all
synthetic sessions. The demo is restricted to one instance.

Never publish a reset bearer token, private `.env`, journal, or any account
credential. In the repository, report issues through a private maintainer
channel when handling a suspected vulnerability rather than posting secrets.
