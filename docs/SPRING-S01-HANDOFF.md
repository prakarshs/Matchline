# Spring migration S01 — local handoff

## What changes

Adds Spring Boot 4.1.2 MVC, Actuator and Micrometer Prometheus as an **additional application entrypoint**. No old Java classes or routes are removed. The publicly deployed Render service remains the legacy one until migration parity is established.

## Test

```bash
./gradlew --stop
./gradlew clean test --no-daemon --max-workers=1 --console=plain
./gradlew run --args='--self-check' --no-daemon --max-workers=1 --console=plain
```

## Run Spring shell

Terminal 1:

```bash
./gradlew bootRun --no-daemon --max-workers=1 --console=plain
```

Terminal 2:

```bash
curl -i http://127.0.0.1:8082/api/v1/health
curl -i http://127.0.0.1:8082/actuator/health
curl -sS http://127.0.0.1:8082/actuator/prometheus | head -30
```

The Spring shell currently has no `/api/v1/orders` and does not serve `/lab`. This is intentional.

The existing adapter remains available separately:

```bash
./gradlew run --args='--serve' --no-daemon --max-workers=1 --console=plain
```

The real deployed app must not switch to `bootRun` or the Boot jar at S01. The Spring shell has no trading endpoints until S03-S04.

## Commit

```bash
git diff --check
git add -A
git commit -m "refactor: introduce Spring Boot HTTP foundation"
git push
```
