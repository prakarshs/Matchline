# MATCHLINE — Spring S02 handoff: one managed engine

## What changed and why

S01 proved Spring MVC and Actuator can start. S02 makes Spring actually own one instance of the matching engine **without migrating the HTTP order endpoints yet**. The application service is the API boundary for future controllers. The existing `LimitOrderBook`, `CommandProcessor`, `SingleWriterSequencer`, journal, snapshots and original HTTP adapter are unchanged.

```text
Spring ApplicationContext (local :8082)
  MatchlineEngineProperties  (bound from matchline.engine.*)
       ↓ validate before creating writer
  SpringEngineConfiguration
       ↓ singleton bean; closes on context shutdown
  SingleWriterSequencer  (one writer + one private LimitOrderBook)
       ↑ never share raw book
  MatchingApplicationService  (submit/inspect/find/snapshot)
       ↑ planned Spring MVC controllers in S03
  MatchingEngineHealthIndicator → /actuator/health
```

### The choice being made

**One Spring singleton is not one writer per request.** Every Spring request will share the same application service and writer in the *local Spring process*. An admitted command receives a sequence, then the worker makes the decision. The ticket itself is not a completed or durable decision.

- S02 is **local + volatile only**. It does **not** read old `MATCHLINE_PUBLIC_DEMO` settings, create public sessions, open a journal, or reproduce restart recovery. Keep Render and Docker pointing at the existing CLI until S03/S04/S05 parity checks are green.
- The object model still separates HTTP/configuration (Spring) from execution (plain Java). Spring never executes the matching loop or mutates the book directly.
- Closing the Spring context stops admission, drains admitted work in FIFO order and terminates the writer. A hung completion callback may still prevent clean drain; this is an operational limitation of the sequencer, not hidden by Spring.
- `matchlineEngine` Actuator health is a cheap check of admission/failure state. It is **not** proof that commands can be durably persisted.

## IntelliJ-first verification

1. Import/reload the Gradle project after applying the patch.
2. Run **all JUnit tests** using your saved Gradle `test` run configuration.
3. Run `SpringEngineLifecycleTest`, `SpringEngineConfigurationTest`, and `SpringBoundaryTest` with gutter icons.
4. Run `MatchlineSpringApplication` from its green gutter icon, with port **8082**.
5. Open `http/06-spring-foundation.http` using IntelliJ HTTP Client. Expect 200 for `/api/v1/health`, `/actuator/health`, `/actuator/prometheus`.
6. Stop the Spring run configuration. The context closes the writer. No second `matchline-writer-DEMO` thread should remain attached to that JVM process.

Optional CLI gate (same source; not required on each edit):

```bash
./gradlew clean test --no-daemon --max-workers=1 --console=plain
./gradlew run --args='--self-check' --no-daemon --max-workers=1 --console=plain
```

## Try a configuration override from IntelliJ

In **Run → Edit Configurations → MatchlineSpringApplication**, add to **Program arguments**:

```text
--matchline.engine.instrument=TEST --matchline.engine.queue-capacity=64 --matchline.engine.max-resting-orders=500 --matchline.engine.max-order-ids=3000
```

Then open `/api/v1/health`; `instrument` must be `TEST`. Stop and remove the overrides afterward.

To see a fail-fast startup, temporarily use `--matchline.engine.queue-capacity=0`. Startup must fail with a clear configuration error; restore the valid value.

## The next migration step (S03)

Spring MVC controllers will consume `MatchingApplicationService` and implement the existing order/API contract. Controller tests must prove they pass *through* the writer, preserve status codes, and do not mutate state directly. Public-demo session parity and UI/CSP cutover come after that.

## Commit

```bash
git diff --check
git add -A
git diff --cached --check
git commit -m "refactor: let Spring own matching engine lifecycle"
git push
```
