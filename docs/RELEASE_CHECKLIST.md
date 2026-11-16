# Release checklist — complete only with observed evidence

## Code and test gate

- [ ] Clean working tree based on latest verified S04
- [ ] JDK 21 `./gradlew clean test installDist bootJar` passes
- [ ] `node --test ui/tests/*.test.mjs` passes
- [ ] One-shot `--self-check` and `--recovery-demo` pass
- [ ] Free WSL curl/Python smoke scripts pass (no IntelliJ HTTP-client dependency)
- [ ] JMH raw benchmark produces a real report, saved under build/ or an
      explicit release evidence path, never a fabricated number
- [ ] Spring Docker build / S05 release smoke succeeds in WSL
- [ ] Legacy Dockerfile builds and --self-check succeeds for rollback
- [ ] `/monitor` follows session metrics; `/actuator/prometheus` is blocked in public mode
- [ ] GitHub Actions verify and docker jobs are green

## Ingress and browser gate

- [ ] Default localhost API behavior unchanged
- [ ] No token: public API returns SESSION_REQUIRED before mutating state
- [ ] Two visitors have distinct order ID and trade histories
- [ ] Same ID can exist in two independent books
- [ ] Session cap, idle expiry and 429 fallback verified
- [ ] Public reset and journal env variables are rejected/unavailable
- [ ] `/preview` has no mutation UI; synthetic sample is clearly labeled
- [ ] `/embed` displays order floor and scenes inside portfolio desktop/mobile viewport
- [ ] `/lab` is not frameable; only `/embed` and `/preview` may use exact portfolio CSP
- [ ] Browser refresh returns real server state, not a UI-generated match
- [ ] Restart loses public sessions (as explicitly documented)

## Deployment and evidence gate

- [ ] Render Free assigned HTTPS URL verified by real GET and POST
- [ ] Cloudflare Pages portfolio HTTPS URL verified
- [ ] Exact `MATCHLINE_EMBED_ORIGIN` configured and deployed
- [ ] Portfolio `CONFIG.projects.matching` uses **actual**, tested URLs
- [ ] README has code/CI evidence and a measured benchmark method/results
- [ ] Public-demo limitations and zero-trading disclaimer visible
- [ ] Screenshots, OG images, short video/GIF are from real captures
- [ ] Interview-defense notes and real bug investigation entries published
- [ ] Git commit SHA and deployment dates recorded, with no invented metrics
