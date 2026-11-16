# Spring migration — behavior first, framework second

We are not replacing the matching algorithm with Spring. Spring will own transport, configuration, lifecycle and observability. The immutable Java commands, `LimitOrderBook`, `SingleWriterSequencer`, journal, recovery and snapshots remain framework independent.

## Milestones (one Git commit, one verification gate)

1. **S01 — Boot shell / Actuator / README (verified).** Start Spring MVC on local `:8082`; prove `/api/v1/health`, `/actuator/health`, `/actuator/prometheus`. Keep the existing `./gradlew run --args='--serve'` and deployed Docker entrypoint untouched. The Spring shell **does not yet serve order endpoints**.
2. **S02 — Configuration and lifecycle (this commit).** Spring-owned configuration of instruments/session policy, engine lifecycle bean(s), shutdown tests, and delegation. Ensure no writer bypass and no duplicate engine instances.
3. **S03 — REST contract parity.** Controllers, DTOs, request validation, consistent error mapping, session token boundary and existing `/api/v1/*` requests. Run every existing HTTP `.http` flow against both adapters.
4. **S04 — Static UI, security and public-demo parity.** `/lab`, `/embed`, `/preview`, API polling, iframe CSP, explicit public safety checks. Test isolation and mobile/desktop views.
5. **S05 — Observability and cutover.** Micrometer meters for engine semantics, Actuator plus legacy metrics consistency, `bootJar`/Docker/Render migration, CI, JMH comparison. Switch container entrypoint **only after full parity**.
6. **S06 (optional) — UI refinement and docs.** Portfolio colour alignment, accessible order-ID aliases, concise interview-focused architecture documentation.

## What stays out

- No JPA or database added merely because Boot is present; journal/snapshot durability already has a different contract.
- No WebFlux unless a measured load problem justifies reactive request handling.
- No Spring AI in order decisions, journals, or the deterministic replay path.

## Metrics clarification

- Existing `GET /metrics` returns Prometheus text. `GET /api/v1/metrics` returns JSON. Neither endpoint renders a dashboard.
- The new Spring shell provides `GET /actuator/prometheus` (Prometheus scrape format), and `GET /actuator/health`.
- A *visual* dashboard needs Prometheus plus Grafana, or a custom page. We will not deploy a paid service just to show graphs.
- Spring's initial Prometheus endpoint holds JVM/HTTP metrics, **not yet the custom matching counters**. Bridging those meters is S05.
