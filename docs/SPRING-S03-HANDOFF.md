# S03 — Spring MVC order endpoints, not a matching-engine rewrite

> The rules are in plain Java. Spring is the transport, configuration and lifecycle boundary.

## Why this migration matters

A matching engine receives BUY/SELL limit orders. It must pick the best price and, within that price, the earliest order. Spring MVC should not know how a book works: it validates the HTTP shape, builds a domain command, submits that command to the one writer, and serializes the immutable result.

## Added Spring routes (`http://127.0.0.1:8082`)

| Method | Path | Key behavior | Status |
|---|---|---|---|
| POST | `/api/v1/orders` | Place a limit order | 200 or sequenced rejection 409 |
| POST | `/api/v1/orders/{id}/cancel` | Cancel unfilled quantity | 200 or sequenced rejection 409 |
| GET | `/api/v1/orders/{id}` | Find currently resting order | 200 / 404 |
| GET | `/api/v1/book?depth=10` | Writer-ordered depth and best levels | 200 / 400 |
| GET | `/api/v1/health` | S02 health controller | 200 |
| GET | `/actuator/prometheus` | Micrometer exposition | 200 |

A request with malformed fields or invalid domain values is rejected **before admission** and has no sequence. A duplicate ID/missing cancellation enters the writer, consumes a sequence and returns a **business rejection**. `durable:false` is correct because S03's Spring writer is volatile.

The original Java HTTP server (`./gradlew run --args='--serve'`, port 8080) and Render deployment **do not change**. S03 does not yet provide `/api/v1/session`, `/api/v1/trades`, `/api/v1/events`, `/api/v1/dashboard`, static lab pages, CORS rules or persistent journal attachment. Do not reconfigure Render to run `bootJar` yet.

### Known differences still to reconcile before cutover

The legacy HTTP adapter has a 4096-byte body cap and a custom duplicate-field-rejecting JSON parser. Spring MVC's normal Jackson object binding is not yet configured to enforce identical low-level JSON parsing or body size. The local Spring API is **not** approved for public exposure in S03; S04 will close these gaps along with rate limiting and sessions. Comparing the *core valid order wire contracts* is the scope of S03.

## Apply

Run `git apply --check ../MATCHLINE_Spring_S03.patch` followed by `git apply ../MATCHLINE_Spring_S03.patch` from your MATCHLINE root. Alternatively extract the changed-files ZIP, never both.

## IntelliJ verification

1. Reload Gradle; the new `spring-boot-starter-webmvc-test` dependency is necessary on Boot 4.
2. Use the gutter icon on `SpringOrdersMvcTest` (11 fresh-context MockMvc tests).
3. Run your saved **All Tests** configuration.
4. Run `MatchlineSpringApplication` from its green `main()` icon; keep it open.
5. WSL terminal: `bash scripts/smoke-spring-orders.sh`; expected `MATCHLINE SPRING S03 HTTP SMOKE PASSED`.
6. `curl -sS http://127.0.0.1:8082/actuator/prometheus | head -20` should emit Micrometer text.
7. Stop Spring Boot from IntelliJ when done.

## Example

```bash
curl -i -X POST http://127.0.0.1:8082/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"instrument":"DEMO","orderId":"B1","side":"BUY","priceTicks":100,"quantity":4}'

curl -i -X POST http://127.0.0.1:8082/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{"instrument":"DEMO","orderId":"S1","side":"SELL","priceTicks":99,"quantity":3}'

curl -sS 'http://127.0.0.1:8082/api/v1/book?depth=10'
```

The SELL should trade 3 at **maker price 100**, leaving B1 with 1 unit. An order ID cannot be reused after cancellation or fill.

## Separation of concerns

```text
HTTP / JSON (SpringOrdersController)
       ↓
Transport DTO (PlaceOrderRequest → typed command)
       ↓
MatchingApplicationService (S02 facade)
       ↓
SingleWriterSequencer (bounded queue and ordered decisions)
       ↓
Plain Java order book + matching algorithm (unchanged)
       ↓
SpringApiResponses / SpringApiErrorAdvice → HTTP JSON
```

## Next: S04

Add isolated public sessions, API event tape, browser assets, strict request bounds, complete HTTP parity tests and security headers. No Spring AI or WebFlux is justified in the matching path.
