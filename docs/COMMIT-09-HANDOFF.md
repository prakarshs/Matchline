# Commit 09 — HTTP interface, OpenAPI and safe demo reset

## Scope

Adds a real Java 21 `HttpServer` adapter on top of the **unchanged matching and sequencer contracts**; it does **not** bypass the existing single-writer or introduce production trading behavior. Adds 27 JUnit tests (8 JSON, 3 configuration, 2 tape, 14 end-to-end real HTTP) plus updated bootstrap assertions, OpenAPI, explicit CORS, bounds, journaling/restart support, and token-controlled volatile reset.

## Apply one artifact only

Place `MATCHLINE_Commit09.patch` next to the project (e.g. in `~/Stuff/Projects`):

```bash
cd ~/Stuff/Projects/MATCHLINE
git status --short
git apply --check ../MATCHLINE_Commit09.patch
git apply ../MATCHLINE_Commit09.patch
```

Use `MATCHLINE_Commit09_Changes.zip` only as an alternative; it contains complete versions of changed/new files. There are **no deletions**. Do not apply both. The source base is the user's confirmed/pushed Commit 08 state.

## Build and verify

```bash
./gradlew --stop
./gradlew clean test --no-daemon --max-workers=1 --console=plain
./gradlew test --tests 'dev.matchline.http.*' --no-daemon --max-workers=1 --console=plain
./gradlew run --args='--self-check' --no-daemon --max-workers=1 --console=plain
./gradlew run --args='--recovery-demo' --no-daemon --max-workers=1 --console=plain
./gradlew installDist --no-daemon --max-workers=1 --console=plain
```

Expected self-check reports `matching=true http=true journal=true`; the recovery demo from Commit 08 remains unchanged. Expect **27 additional JUnit test methods** compared with Commit 08. Tests that start HTTP use loopback **ephemeral ports** and don't assume 8080 is available. Integration tests cover end-to-end matching/FIFO, cancellation, domain errors and 4xx codes, CORS, token reset, concurrent requests, journal recovery, snapshot checkpoint and explicit torn-tail repair. JUnit and Gradle integration in your WSL are the official pass gate; Java 21 compilation and an independent local JUnit assertion harness passed in the artifact-generation environment, but the real Gradle test runtime was not available there.

## Manual HTTP walkthrough (two WSL terminals)

Terminal 1:

```bash
./gradlew run --args='--serve' --no-daemon --max-workers=1 --console=plain
```

The process stays up until you stop it with **Ctrl+C**. Default address is `http://127.0.0.1:8080`, **volatile** and **loopback only**. If 8080 is in use, run with `MATCHLINE_HTTP_PORT=18081` and adjust URLs accordingly.

Terminal 2 (use a fresh volatile process for deterministic results):

```bash
curl -i http://127.0.0.1:8080/api/v1/health
curl -i http://127.0.0.1:8080/openapi.yaml
curl -i -X POST http://127.0.0.1:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"instrument":"DEMO","orderId":"B1","side":"BUY","priceTicks":100,"quantity":4}'
curl -i -X POST http://127.0.0.1:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"instrument":"DEMO","orderId":"S1","side":"SELL","priceTicks":99,"quantity":3}'
curl -i 'http://127.0.0.1:8080/api/v1/book?depth=5'
curl -i http://127.0.0.1:8080/api/v1/trades
curl -i http://127.0.0.1:8080/api/v1/events
curl -i http://127.0.0.1:8080/api/v1/orders/B1
curl -i -X POST http://127.0.0.1:8080/api/v1/orders/B1/cancel
curl -i http://127.0.0.1:8080/api/v1/status
curl -i http://127.0.0.1:8080/api/v1/recovery
```

Observe the second order's trade `makerOrderId=B1`, `takerOrderId=S1`, `priceTicks=100`, `quantity=3`; the bid book has **one** B1 remaining before cancellation. Both submissions respond with monotonic `sequence` values (1,2), and the cancelled result has sequence 3. On a plain session, `durable=false`. Subsequent `POST` reusing B1 returns sequenced 409 `DUPLICATE_ORDER_ID`, even though B1 is no longer resting.

## Verify journal-backed HTTP restart (optional)

```bash
mkdir -p local-data
MATCHLINE_JOURNAL_PATH=local-data/http-demo.mlj \
MATCHLINE_SNAPSHOT_PATH=local-data/http-demo.mls \
./gradlew run --args='--serve' --no-daemon --max-workers=1 --console=plain
```

Use new journal/snapshot names for each fresh walkthrough. After submitting and stopping normally, rerun the same command. The file is **not overwritten**: strict recovery must precede listening, and `/api/v1/recovery` should report `recoveredExistingJournal=true`, continuing the sequence. After 100 completed commands a snapshot is written; snapshot recovery is also checked by the new JUnit tests. **Do not run two processes against the same journal**, and do not invoke torn-tail repair with an active writer. A truncated journal must fail startup and be repaired explicitly offline using the established Commit 08 procedure; there is no HTTP repair endpoint.

## Verify reset safeguards (optional)

```bash
export MATCHLINE_RESET_TOKEN='replace-with-a-random-secret-of-at-least-32-characters'
./gradlew run --args='--serve' --no-daemon --max-workers=1 --console=plain
# Other terminal:
curl -i -X POST http://127.0.0.1:8080/api/v1/demo/reset \
  -H "Authorization: Bearer $MATCHLINE_RESET_TOKEN"
```

Reset is disabled without a token (404), rejected for a bad token (403) and **never available** with a journal configured (409). Reset drains the old writer and creates a new volatile generation and sequence space. Never put real secrets in Git, logs, or URL query strings.

## Troubleshooting and limitations

- `Address already in use`: choose `MATCHLINE_HTTP_PORT=18081`, or stop the other listener.
- `INSTRUMENT_MISMATCH`: only `DEMO` is implemented. Use whole-number `priceTicks`, not floating-point currency.
- HTTP 400 `INVALID_JSON`/`INVALID_FIELDS`: send flat JSON with exactly five placement fields; no extra keys or floats.
- HTTP 429: global request budget or bounded sequencer queue full; command rejected **before** admission unless a sequence is included.
- HTTP 504 `OUTCOME_UNKNOWN`: a command was **admitted** but its outcome was not confirmed during the 5-second request window. Do not automatically retry using a new ID; first reconcile through book/order lookup, engine status and recovery when appropriate.
- Trades/events are **process memory**, not the full binary journal and not guaranteed to include a timed-out HTTP command. The HTTP adapter intentionally does not expose live file scanning or destructive journal repair.
- In-memory mode loses its book on process restart; optional sync journal restores the book. `SYNC_EACH_RECORD` forces records before completing a future, but physical hardware crash durability is not proven here.
- No login/authentication for placing orders, TLS endpoint, per-IP rate limits, SSE, browser interface, production readiness or real trading. Do **not** expose directly to the public Internet. The bound global budget is an educational safeguard only.

## Git (user executes after tests)

```bash
git diff --check
git add -A
git diff --cached --check
git commit -m "feat: expose single-writer demo HTTP API"
git push
git log -1 --oneline
```

**STOP:** Share test results, manual HTTP observations and push confirmation before Commit 10 (JMH/metrics). No repository, Git or deployment action has been executed on the user's machine.
