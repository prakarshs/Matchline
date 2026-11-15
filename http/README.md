# MATCHLINE — IntelliJ HTTP Client collection

These checked-in `.http` files are executable API walkthroughs for the **real**
Java 21 MATCHLINE HTTP server. They complement JUnit tests; they are not a
replacement for `./gradlew clean test`.

## Start the server

From the repository root, in WSL (terminal 1):

```bash
./gradlew run --args='--serve' --no-daemon --max-workers=1 --console=plain
```

The default API is `http://127.0.0.1:8080`, bound to loopback.

## Execute in IntelliJ IDEA

1. Open `http/01-matching-flow.http` from the repository project.
2. Select the **local** environment from the **Run with** selector. The
   variables are in `http/http-client.env.json`.
3. Run the first **Health** request using the green gutter arrow.
4. On a fresh volatile server, click **Run All Requests in File**, or execute
   the requests from top to bottom. The first order request creates **unique
   IDs** and stores them in HTTP Client global variables for later requests.
5. Inspect the response and **Tests** tabs. The assertions check maker-price
   fills, partial quantities, cancellation, and lifetime order ID rejection.

The following files are deliberately separate:

| File | Use | Mutates the book? |
| --- | --- | --- |
| `01-matching-flow.http` | Buy → sell match → book → cancel → duplicate rejection | Yes |
| `02-observability-and-errors.http` | Health, OpenAPI, status, recent events, invalid payloads | No accepted placements |
| `03-demo-reset.http` | **Optional destructive** volatile reset | Yes (if authorized) |
| `04-fifo-and-multifill.http` | Multiple makers / same-price FIFO / price priority | Yes |

**Run files 01 and 04 against separate fresh volatile sessions** for their
exact final-book assertions. Since historical IDs are reserved within a book
session, the generated IDs avoid accidental reuse when running a file again.
If another client is placing orders concurrently, sequences or fills may
interleave and the walkthrough's deterministic assertions may fail; that is
expected and not necessarily an engine defect.

## Alternate port

Start the server with `MATCHLINE_HTTP_PORT=18081`, then choose the
**local-alt-port** environment in IntelliJ. No request URLs need editing.

## Optional demo reset (secrets are never committed)

Start a **volatile** server with a locally generated secret:

```bash
export MATCHLINE_RESET_TOKEN="$(openssl rand -hex 32)"
./gradlew run --args='--serve' --no-daemon --max-workers=1 --console=plain
```

Copy `http-client.private.env.example.json` to `http-client.private.env.json`
inside this directory. Replace `resetToken` with the value from your server
shell. The real private file is Git-ignored. **Never commit that file or the
secret.** Run the reset request only when you want to deliberately wipe the
volatile book. A persisted journal-backed session rejects reset even with the
correct secret; the normal server has reset disabled.

## Persistence caveats

A journal-backed server recovers previous orders, so price-time walkthroughs
may yield different results depending on existing state. The trades and events
HTTP endpoints report bounded **process-local** tapes; they are not durable
history. A returned 504 with `OUTCOME_UNKNOWN` is an ambiguous completion,
not permission to blindly retry using a different ID.

For the API definition see `src/main/resources/openapi.yaml`. For endpoint
behavior and server configuration see `docs/COMMIT-09-HANDOFF.md`.


### Commit 10 — metrics inspection

Open [`05-metrics.http`](05-metrics.http) to inspect JSON/Prometheus counters and histogram buckets.
These endpoints have no dynamic order-ID labels. They share the same HTTP request
budget as other diagnostics, so do not hammer them. For controlled HTTP measurements,
use `python3 scripts/bench_http.py` rather than repeatedly clicking IntelliJ requests.

### Commit 11 — browser laboratory

Open [`06-exchange-lab.http`](06-exchange-lab.http) to check both pages and
static assets. The actual **visual interaction** happens at `/lab` in a browser;
`/preview` is an intentionally read-only, embeddable-by-allowlist version.
The browser polls the same API used by this collection. See the root README.
