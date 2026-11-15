# Commit 11 — Interactive Exchange Laboratory handoff

**Baseline:** Commit 10 plus the verified Commit 09 IntelliJ HTTP add-on.
**Scope:** Java-served responsive full lab and read-only compact preview; no
server-side matching/journaling/concurrency behavior changed.

## Apply & verify

```bash
cd ~/Stuff/Projects/MATCHLINE
git status --short
git apply --check ../MATCHLINE_Commit11.patch
git apply ../MATCHLINE_Commit11.patch
./gradlew clean test --no-daemon --max-workers=1 --console=plain
node --test ui/tests/*.test.mjs       # optional if Node 20+ installed
./gradlew run --args='--serve' --no-daemon --max-workers=1 --console=plain
```

Open `http://127.0.0.1:8080/lab` in a browser. In a **fresh volatile** server:

1. Confirm `ENGINE ONLINE`; sequence initially zero.
2. Set BUY, price 100, quantity 4; submit and inspect the sequence and bid.
3. Set SELL, price 99, quantity 3; submit. Expect **3 @ 100** against the
   resting BUY; the bid should retain **1**. Observe event and trade tapes.
4. Copy the first BUY ID into the cancel field and cancel its remaining 1.
   Both sides should be empty. Reusing that ID should be rejected by the API.
5. Click the FIFO scenario and execute all three steps. With an empty book,
   the buyer matches A for 2 @ 100, then B for 1 @ 100. B rests with 2.
6. Restart fresh before running Sweep and Cancel scenarios for exactly
   predictable fills. Scripts do **not** perform a reset or overwrite data.
7. Refresh `/preview` at a viewport 320–700px wide: it must display the same
   sequence, price depth and recent process-local executions but **no order
   form**. On a mobile width, the frame should not overflow horizontally.
8. Read the real `/api/v1/metrics` and `/api/v1/recovery` outputs. The lab must
   never show fabricated throughput, exact p99, or replayed trade tape data.
9. Stop the server; the page should report a disconnected state on its next
   polling interval. Restart; the page should recover without a full reload.

The `.http` checks in `http/06-exchange-lab.http` verify real HTTP routes, but
are not a substitute for visual browser checks. A localhost viewer has no
login and shares the live engine with other browser tabs.

## Security / embedding

- Default CSP blocks **all** framing, including for `/preview`.
- To allow only `/preview` in one deployed portfolio, set:
  `MATCHLINE_EMBED_ORIGIN=https://your-real-portfolio-origin.example`.
  Never set `*`, a `file://` URL, or a path. Verify the origin matches exactly.
- `/lab`, `/api/v1/*`, `/ui/*` cannot be framed even with that setting.
- Existing CORS stays disabled by default, and reset still requires an opt-in
  secret and never operates on a journal-backed book.
- **Do not publish this unauthenticated HTTP server on the public internet.**
  Commit 12 will tackle hardened public-demo ingress, deployment and CI.

## Troubleshooting

- `curl: (7)` / page disconnected: start `--serve` in a separate terminal.
- 429: UI and other clients share the 4 requests/s global refill budget;
  stop external polling/benchmarks, then retry later. The UI polls slowly.
- Blank UI but 200 HTML: inspect browser console for blocked asset/CSP.
  The script is a **module** and must be fetched as JavaScript, not opened
  using `file://`.
- UI scenarios fill at unexpected prices: they operate on the *current book*;
  restart a fresh volatile demo for deterministic isolation.
- Book state after restart but empty event/trade panel: expected; only the
  book/journal is recovered. The recent HTTP tape is process-local.

## Git gate

```bash
git diff --check
git add -A
git diff --cached --check
git commit -m "feat: add interactive exchange laboratory UI"
git push
```

Stop and report Java tests, optional Node tests, browser scenario results and
push confirmation before Commit 12.
