# MATCHLINE release: Render Free + Cloudflare Pages

**S05 updates an existing user-managed Render service** (`https://matchline-kz8p.onrender.com`)
and portfolio (`https://portfolio.jhethia.workers.dev`). No deployment is performed
by the patch. Consult provider dashboards for free-plan limits before changing a service.

## 0. Local release gate

```bash
./gradlew clean test installDist bootJar --no-daemon --max-workers=1 --console=plain
node --test ui/tests/*.test.mjs
./build/install/matchline/bin/matchline --self-check
./build/install/matchline/bin/matchline --recovery-demo
# If Docker Desktop is available in WSL:
docker build -t matchline:spring .
docker build -f Dockerfile.legacy -t matchline:legacy .
docker run --rm matchline:legacy --self-check
# Start Spring locally using docker compose --profile public-demo up --build
# Then: MATCHLINE_SPRING_BASE=http://127.0.0.1:8081 bash scripts/smoke-spring-release.sh
```

## 1. Verify both adapters locally

```bash
MATCHLINE_EMBED_ORIGIN=https://portfolio.jhethia.workers.dev \
docker compose --profile public-demo up --build
```

In another terminal:

```bash
curl -i http://127.0.0.1:8081/api/v1/health
curl -i http://127.0.0.1:8081/api/v1/book      # expect 401: SESSION_REQUIRED
curl -sS -X POST http://127.0.0.1:8081/api/v1/session
# Copy sessionToken into TOKEN below; in WSL, do not use <> placeholder syntax:
TOKEN='PASTE_RETURNED_TOKEN_HERE'
curl -sS http://127.0.0.1:8081/api/v1/dashboard?depth=5 \
  -H "X-Matchline-Session: $TOKEN"
```

Visit `/lab`, `/preview`, `/embed`, `/monitor` to verify the UI; the browser creates its
own token. For local portfolio testing, use a localhost embed origin and
serve portfolio HTML via an HTTP server, not `file://`. CSP cannot allow the
`file://` origin.

## 2. Render Free Java service

1. **Before pushing**, run IntelliJ tests and the local Spring Docker release smoke.
   If Render auto-deploys from main, pushing will replace the currently healthy
   legacy service: disable auto-deploy until ready, or accept the cutover.
   Then push and check the GitHub Actions workflow
   `Matchline verification` actually passes. CI status cannot be inferred
   from a local JUnit run.
2. **Update the existing Render web service**, do not create another one.
   Keep Dockerfile path `./Dockerfile` and health-check path `/api/v1/health`.
   The default Dockerfile now starts the Spring `bootJar`. Use a **single instance**; sessions are process-local.
3. Configure `MATCHLINE_PUBLIC_DEMO=true`, `MATCHLINE_HTTP_HOST=0.0.0.0`,
   `PORT=8080` (or let Render supply PORT), and
   `MATCHLINE_EMBED_ORIGIN=https://portfolio.jhethia.workers.dev`. Spring
   reads `PORT`, not `MATCHLINE_HTTP_PORT`. Do not enable journal, snapshot,
   reset token or CORS variables.
4. Deploy; inspect Render's build and runtime logs. Confirm the **existing**
   HTTPS URL continues to serve `/lab` and `/api/v1/health`.
5. Run `MATCHLINE_SPRING_BASE=https://matchline-kz8p.onrender.com bash scripts/smoke-spring-release.sh`.
   Verify `/lab`, `/embed`, `/preview` in the browser and in the portfolio iframe.
   `GET /actuator/prometheus` returns 404 in public mode intentionally;
   `/monitor` and token-scoped `/api/v1/metrics` remain available.
   For a rollback, switch Dockerfile path to `./Dockerfile.legacy` and redeploy,
   or use a known-good prior deployment in Render if supported.
6. **Render Free limitations:** sleeps after around 15 idle minutes, can take
   around a minute to wake, has an ephemeral filesystem, and has workspace
   usage limits. The publicly hosted demo is explicitly **not durable**.
   For durable recovery evidence, run the repository's local journal tests
   and recovery drill, not this ephemeral deployment.

## 3. Cloudflare Pages portfolio (separate user-managed repository)

1. Publish the existing single-file `portfolio_v14` as an `index.html` in a
   separate repository through Cloudflare Pages (Git integration or Direct
   Upload). Confirm your actual `https://...pages.dev` site URL; no purchase
   of a custom domain is required.
2. In Render's service environment set **one exact origin**:
   `MATCHLINE_EMBED_ORIGIN=https://portfolio.jhethia.workers.dev` and redeploy.
   Do not include a path, wildcard or trailing slash. For a custom domain,
   use its actual HTTPS origin instead.
3. In the **portfolio's existing** `CONFIG.projects.matching` object set:

   ```javascript
   matching: {
     previewUrl: 'https://matchline-kz8p.onrender.com/preview',
     demoUrl: 'https://matchline-kz8p.onrender.com/embed',
     sourceUrl: 'https://github.com/prakarshs/Matchline',
     docsUrl: 'https://github.com/prakarshs/Matchline#readme'
   }
   ```

4. On portfolio Home and Engineering cards, the iframe loads `/preview`.
   The project case study's **existing** LOAD/DESKTOP/MOBILE container loads
   `/embed`. The full page `/lab` is intentionally opened in a new tab,
   not framed. **No unrelated portfolio HTML edits are required.**
5. Test on desktop and mobile. Inspect browser console and the `Content-
   Security-Policy` response on `/preview` and `/embed`. A blank iframe
   usually means the exact origin is wrong, the backend is asleep, or a
   managed browser extension blocks third-party frames.

## 4. Operate and validate

- After a Render cold start, expect the public session to be reset; the UI
  may show a disconnected message until the service starts. Reload if needed.
- Avoid constant uptime pings: they defeat sleep and consume free hours.
- If user #13 encounters `SESSION_CAPACITY`, the service is correctly
  refusing another active book rather than evicting a visitor.
- `durable=false` is expected on every public command result.
- Run `scripts/run-jmh.sh raw` locally for real benchmark numbers; do not
  label Render HTTP timings as raw matcher throughput.
- Never add a paid disk or upgrade the service without checking billing.

## Verification record (fill after actual deployment)

- Git commit SHA: `NOT YET PROVIDED`
- GitHub Actions: `NOT YET VERIFIED`
- Docker build/smoke: `NOT YET VERIFIED IN USER ENVIRONMENT`
- Render HTTPS URL: `https://matchline-kz8p.onrender.com` (user-provided; S05 not yet verified live)
- Portfolio URL: `https://portfolio.jhethia.workers.dev` (user-provided; S05 not yet verified live)
- Full JMH result: `NOT PROVIDED`
- Cross-site iframe checks: `PENDING`

Current provider docs: https://render.com/docs/free and
https://render.com/docs/blueprint-spec. Provider terms can change.
