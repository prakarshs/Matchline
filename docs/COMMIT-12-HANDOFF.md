# Commit 12 — Release engineering handoff

**Patch baseline:** The Commit 11 application and HTTP collection, without
any intervening private changes. Do not apply the patch and ZIP together.

## Implementation scope

- Bounded, expiring **per-browser synthetic public-demo sessions**. Each owns
  a Java single-writer matching engine; local shared + journal-backed modes
  retain their established behavior. A 43-character opaque session capability
  is placed in `X-Matchline-Session`, not an iframe cookie or URL.
- Real seeded preview sequence and match; combined dashboard poll; embedded
  interactive lab (`/embed`), with exact `frame-ancestors` allowlist.
- Docker defaults to `--serve`, nonroot runtime, Java 21, multi-stage build;
  Render Free Blueprint, GitHub Actions CI (Java/Node/Docker), OpenAPI and
  `.http` public demo verification, release/security/deployment documentation.

## 1. Apply

```bash
cd ~/Stuff/Projects/MATCHLINE
git status --short
# Need a clean Commit 11 working tree before applying:
git apply --check ../MATCHLINE_Commit12.patch
git apply ../MATCHLINE_Commit12.patch
```

## 2. Verify Java, JS, CLI

```bash
./gradlew --stop
./gradlew clean test installDist \
  --no-daemon --max-workers=1 --console=plain
node --test ui/tests/*.test.mjs
./build/install/matchline/bin/matchline --self-check
./build/install/matchline/bin/matchline --recovery-demo
git diff --check
```

**Do not assume a JUnit count** from the Gradle console's default summary:
new tests include 5 `DemoSessionsTest` cases and 10 `PublicDemoHttpTest`
cases; the existing `UiHttpTest` frame assertion is updated. CI and local
Gradle results are not verified until the human runs them.

## 3. Old local shared mode regression (Terminal 1)

```bash
./gradlew run --args='--serve' --no-daemon --max-workers=1 --console=plain
```

Terminal 2: use `http/01-matching-flow.http`, inspect `/lab`, verify a
trade at the resting maker price, stop the server. Local mode does **not**
require any `X-Matchline-Session` header and may still use a journal.

## 4. New isolated public mode (Terminal 1)

```bash
MATCHLINE_PUBLIC_DEMO=true MATCHLINE_HTTP_PORT=8081 \
MATCHLINE_EMBED_ORIGIN=https://your-actual-portfolio.pages.dev \
./gradlew run --args='--serve' --no-daemon --max-workers=1 --console=plain
```

Terminal 2: use `http/07-public-demo.http` with the `public-demo` environment.
Test `/lab`, `/preview`, `/embed`. Then open two separate private browser
contexts (not two tabs sharing sessionStorage), submit `B1` to the first and
ensure the second still shows sequence zero. The synthetic preview starts at
sequence five and includes an actual trade. `POST /api/v1/demo/reset` must
return 404 and `GET /api/v1/book` without a session must return 401.

Also test:

- a malformed token receives `SESSION_EXPIRED`, not someone else's book;
- full capacity (12 active sessions) receives 429, no silent book eviction;
- an idle session expires after 20 minutes (covered deterministically by
  unit test; time-based browser checking optional);
- `/preview` + `/embed` have the exact portfolio CSP origin; `/lab` has
  `frame-ancestors 'none'`;
- JS console/network has no exceptions, mobile iframe doesn't overflow;
- the optional standalone journal-backed local server still rebuilds book;
- free deployment uses **only** public volatile mode, not the journal.

## 5. Docker gate (requires Docker Desktop + WSL integration)

```bash
docker build -t matchline:local .
docker run --rm matchline:local --self-check
docker compose --profile public-demo up --build
```

The last command runs until stopped (`Ctrl+C`). In another WSL shell, open
`http://127.0.0.1:8081/lab` and `http://127.0.0.1:8081/api/v1/health`.
The container exposes only a loopback-mapped port in this example.

If Docker isn't available, record that gate as **not verified** instead of
substituting `javac` compilation. The artifact sandbox had no working Docker
engine and couldn't run the network-dependent full Gradle build.

## 6. Commit, push and manually deploy

```bash
git add -A
git diff --cached --check
git commit -m "chore: harden public demo and release pipeline"
git push -u origin main   # only if main still lacks an upstream
# Otherwise: git push
```

Confirm GitHub Actions is green. Follow
`docs/DEPLOY_RENDER_CLOUDFLARE.md` exactly. Do not publish the portfolio
CONFIG update until you supply the actual HTTPS service and site URLs.

## Stop condition

If ANY Gradle, Node, Docker, iframe or CI check fails, send the output and
request a **surgical Commit 12 repair package**. A released app requires real
user-confirmed URLs; this handoff does not claim that cloud deployment happened.
