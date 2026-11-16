# S05 — Spring Boot observability and public cutover

**Scope:** one incremental release candidate atop S04. The matching algorithm,
sequencing, journal, snapshots and legacy CLI are unchanged. Do not call this a
distributed or durable public exchange: each visitor gets a temporary in-process book.

## What changes

- Spring `bootJar` is now the default Docker image (`java -jar`); `Dockerfile.legacy`
  is the previous CLI image, retained for rollback and recovery drills.
- The Spring single-writer application receives fixed-name Micrometer meters.
  `matchline.local.*` refers **only to Spring's local singleton**, not the
  independent public visitor books. `matchline.demo.active.sessions` counts
  current demo sessions; `matchline.http.active.requests` shows concurrency.
- `GET /actuator/prometheus` returns text **only in non-public local mode**;
  in the public-demo mode it deliberately returns 404 to avoid leaking
  process-wide JVM/process metrics to every visitor.
- `GET /api/v1/metrics` returns per-session JSON with the visitor capability.
  New `/monitor` is a small browser UI for that data, styled like `/lab`.
  The laboratory links to `/monitor`; both reuse the same sessionStorage token
  when opened in the same browser tab. No token is ever put in a URL.
- The servlet guard now rejects POST bodies over **4096 bytes**, including
  unknown-length/chunked requests, reading at most 4097 bytes before rejecting.
  It is not a substitute for reverse-proxy body/time limits or abusive-traffic
  protection; keep Render's upstream controls and one process.
- CI verifies Spring Boot and legacy CLI artifacts; Docker smoke starts Spring.

## In IntelliJ Community

1. Apply the S05 patch **after** S04 and its successful tests. Reload Gradle.
2. Run `SpringObservabilityMvcTest`, `SpringReleaseMvcTest`,
   `BoundedRequestBodyTest`, and the S04 public-session tests using gutter icons.
3. Run your saved complete Gradle `test` configuration.
4. Run `MatchlineSpringApplication` with `MATCHLINE_PUBLIC_DEMO=true` and
   `MATCHLINE_EMBED_ORIGIN=https://portfolio.jhethia.workers.dev`.
5. In WSL: `MATCHLINE_SPRING_BASE=http://127.0.0.1:8082 bash scripts/smoke-spring-release.sh`.
6. Open `http://127.0.0.1:8082/lab`; submit an order; follow Telemetry to `/monitor`
   in the **same tab**. The completed count should be >0.
7. Temporarily disable `MATCHLINE_PUBLIC_DEMO` in IntelliJ, restart, and
   inspect `http://127.0.0.1:8082/actuator/prometheus`. Search for
   `matchline_local_writer_queue_depth` and `matchline_demo_active_sessions`.

## Before pushing / triggering Render auto-deploy

From WSL with Docker Desktop running:

```bash
cd ~/Stuff/Projects/MATCHLINE
./gradlew clean test bootJar installDist --no-daemon --max-workers=1 --console=plain
node --test ui/tests/*.test.mjs

docker build -t matchline:spring-s05 .
docker run -d --rm --name matchline-s05 --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  -p 127.0.0.1:18081:8080 \
  -e PORT=8080 -e MATCHLINE_PUBLIC_DEMO=true \
  -e MATCHLINE_EMBED_ORIGIN=https://portfolio.jhethia.workers.dev \
  matchline:spring-s05
MATCHLINE_SPRING_BASE=http://127.0.0.1:18081 bash scripts/smoke-spring-release.sh
docker rm -f matchline-s05

docker build -f Dockerfile.legacy -t matchline:legacy-s05 .
docker run --rm matchline:legacy-s05 --self-check
```

**Important:** pushing to a Render-linked auto-deploy branch may replace the
working public service immediately. Run the gates **before pushing**, and if
needed disable automatic deployments in the Render dashboard while validating.

## Existing Render service cutover

Service: `https://matchline-kz8p.onrender.com` (user-managed).
Keep `Dockerfile` path `./Dockerfile` and `/api/v1/health` health-check path.
Use env variables:

```text
MATCHLINE_PUBLIC_DEMO=true
MATCHLINE_HTTP_HOST=0.0.0.0
MATCHLINE_EMBED_ORIGIN=https://portfolio.jhethia.workers.dev
PORT=8080
```

The Dockerfile defaults to PORT=8080, but Spring honors a value supplied by
Render. `MATCHLINE_HTTP_PORT` belongs to the legacy adapter; Spring ignores it.
Keep a single process: visitor sessions are volatile and not shared between
instances. Do not add a paid disk or claim journal durability in demo mode.

After Render redeploys, run:

```bash
MATCHLINE_SPRING_BASE=https://matchline-kz8p.onrender.com bash scripts/smoke-spring-release.sh
```

Verify `/lab`, `/preview`, and `/embed` in the existing portfolio. Do not
change the portfolio URLs; the public paths are unchanged.

**Rollback:** Set Render Dockerfile path to `./Dockerfile.legacy`, redeploy,
and validate `/api/v1/health` plus the original `/lab`. Reverting to a prior
successful deployment is another option if available in your Render account.
Spring `bootJar` and legacy `installDist` both remain buildable.

## Known boundaries

- S05 does **not** migrate journal-backed recovery into Spring's startup mode;
  use original CLI/journal tests for durable recovery demonstrations.
- Metric values are approximate snapshots; p95/p99 in `/monitor` are histogram
  bucket bounds, not individually observed request percentiles.
- S05 does not add Grafana or Prometheus storage. `/monitor` is a small
  first-party session view, while the local Actuator exporter remains scrape
  text. No paid monitoring service is required.
- A health 200 proves the server is responsive, not that a trade is durable.
- Do not claim production failover, replication or exchange-grade security.

**Evidence gate:** JUnit, Node, Docker runtime and actual Render results must
be supplied by the human running IntelliJ/WSL. The author-side code/source
checks alone cannot prove deployment parity.
