# MATCHLINE release: Render Free + Cloudflare Pages

**Manual deployment runbook; no service or DNS record has been created by the
artifact author.** Consult provider dashboards and current pricing/limits
before clicking Create. Free does not mean an unconditional cost guarantee.

## 0. Local release gate

```bash
./gradlew clean test installDist --no-daemon --max-workers=1 --console=plain
node --test ui/tests/*.test.mjs
./build/install/matchline/bin/matchline --self-check
./build/install/matchline/bin/matchline --recovery-demo
# If Docker Desktop is available in WSL:
docker build -t matchline:local .
docker run --rm matchline:local --self-check
```

## 1. Smoke public-demo mode locally

```bash
MATCHLINE_PUBLIC_DEMO=true MATCHLINE_HTTP_PORT=8081 \
MATCHLINE_EMBED_ORIGIN=https://YOUR-REAL-PORTFOLIO.pages.dev \
./gradlew run --args='--serve' --no-daemon --max-workers=1 --console=plain
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

Visit `/lab`, `/preview`, `/embed` to verify the UI; the browser creates its
own token. For local portfolio testing, use a localhost embed origin and
serve portfolio HTML via an HTTP server, not `file://`. CSP cannot allow the
`file://` origin.

## 2. Render Free Java service

1. Push this **verified** commit to GitHub; check the GitHub Actions workflow
   `Matchline verification` actually passes. CI status cannot be inferred
   from a local JUnit run.
2. In Render, choose **New Web Service** from the Git repository (or use the
   optional root `render.yaml` Blueprint). Set **Docker** runtime, **Free**
   instance, Dockerfile at the repository root, and health-check path
   `/api/v1/health`. Use a **single instance**; sessions are process-local.
3. Configure `MATCHLINE_PUBLIC_DEMO=true` and
   `MATCHLINE_HTTP_HOST=0.0.0.0`. The Java app reads the Render-supplied `PORT`
   if `MATCHLINE_HTTP_PORT` is unset. Do not enable journal, snapshot, reset
   token or CORS variables. Use the JRE heap limit in the Dockerfile.
4. Deploy; inspect Render's build and runtime logs. In the Render UI copy
   your **actual assigned** HTTPS URL, rather than guessing one.
5. Test `/api/v1/health`, `/lab`, `/preview`, `/embed`, POST `/api/v1/session`,
   actual order placement, cancellation, session isolation, HTTP 429 at
   capacity, and CSP response headers.
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
   `MATCHLINE_EMBED_ORIGIN=https://YOUR-REAL-SITE.pages.dev` and redeploy.
   Do not include a path, wildcard or trailing slash. For a custom domain,
   use its actual HTTPS origin instead.
3. In the **portfolio's existing** `CONFIG.projects.matching` object set:

   ```javascript
   matching: {
     previewUrl: 'https://YOUR-ACTUAL-SERVICE.onrender.com/preview',
     demoUrl: 'https://YOUR-ACTUAL-SERVICE.onrender.com/embed',
     sourceUrl: 'https://github.com/YOUR-ACCOUNT/MATCHLINE',
     docsUrl: 'https://github.com/YOUR-ACCOUNT/MATCHLINE#readme'
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
- Render HTTPS URL: `NOT DEPLOYED`
- Cloudflare Pages URL: `NOT DEPLOYED`
- Full JMH result: `NOT PROVIDED`
- Cross-site iframe checks: `PENDING`

Current provider docs: https://render.com/docs/free and
https://render.com/docs/blueprint-spec. Provider terms can change.
