#!/usr/bin/env python3
"""Bounded localhost-only HTTP read/order benchmark using the Python standard library.

Does not bypass MATCHLINE's rate limiter. Report success ratio with latency;
429/503/504 responses are never silently counted as successes. This is not JMH.
"""

from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import platform
import secrets
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, ProxyHandler


def percentile(values: list[float], percent: float) -> float | None:
    if not values:
        return None
    values = sorted(values)
    return round(values[max(0, math.ceil(percent * len(values)) - 1)], 3)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--mode", choices=("book", "orders"), default="book")
    parser.add_argument("--requests", type=int, default=40)
    parser.add_argument("--warmup", type=int, default=10)
    parser.add_argument("--concurrency", type=int, default=1)
    parser.add_argument("--pace-ms", type=int, default=250,
                        help="Minimum time between scheduling requests; keep 250ms to respect 4/s steady limit")
    parser.add_argument("--output", default="build/benchmarks/http-results.json")
    args = parser.parse_args()

    parts = urlsplit(args.base_url)
    if parts.scheme != "http" or parts.hostname not in ("127.0.0.1", "localhost", "::1") or \
            parts.username or parts.password or parts.path not in ("", "/") or parts.query or parts.fragment:
        parser.error("--base-url must be loopback HTTP, with no path, credentials or query")
    if not (1 <= args.requests <= 500 and 0 <= args.warmup <= 100):
        parser.error("--requests must be 1..500 and --warmup 0..100")
    if not (1 <= args.concurrency <= 16 and 0 <= args.pace_ms <= 5_000):
        parser.error("--concurrency must be 1..16 and --pace-ms 0..5000")
    url = args.base_url.rstrip("/")
    client = build_opener(ProxyHandler({}))
    token = secrets.token_hex(4).upper()
    total = args.warmup + args.requests

    def call(index: int):
        if args.mode == "book":
            request = Request(url + "/api/v1/book?depth=5", method="GET")
        else:
            order_id = f"BH{token}{index}"
            body = json.dumps({"instrument": "DEMO", "orderId": order_id, "side": "BUY",
                               "priceTicks": 101, "quantity": 1}).encode("utf-8")
            request = Request(url + "/api/v1/orders", data=body, method="POST",
                              headers={"Content-Type": "application/json"})
        started = time.perf_counter_ns()
        try:
            with client.open(request, timeout=8) as response:
                response.read()
                status = response.status
        except HTTPError as error:
            status = error.code
            error.close()
        except (URLError, TimeoutError, OSError):
            status = 0  # Transport failure, not an HTTP response
        elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000
        return index, status, elapsed_ms

    # Submit at a fixed interarrival cadence, rather than hammering the API.
    started = time.perf_counter()
    results = []
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = []
        for index in range(total):
            wake = started + (args.pace_ms / 1000.0) * index
            pause = wake - time.perf_counter()
            if pause > 0:
                time.sleep(pause)
            futures.append(pool.submit(call, index))
        for future in as_completed(futures):
            results.append(future.result())
    total_elapsed_s = time.perf_counter() - started
    observed = sorted((r for r in results if r[0] >= args.warmup), key=lambda r: r[0])
    statuses = Counter(str(status) for _, status, _ in observed)
    successful = [latency for _, status, latency in observed if 200 <= status < 300]
    summary = {
        "mode": args.mode,
        "utc": datetime.now(timezone.utc).isoformat(),
        "python": platform.python_version(),
        "platform": platform.platform(),
        "baseUrl": url,
        "requests": args.requests,
        "warmup": args.warmup,
        "concurrency": args.concurrency,
        "paceMs": args.pace_ms,
        "totalRunSecondsIncludesWarmup": round(total_elapsed_s, 3),
        "statuses": dict(statuses),
        "successCount": len(successful),
        "failedCount": len(observed) - len(successful),
        "successLatencyMs": {
            "p50": percentile(successful, .50),
            "p95": percentile(successful, .95),
            "p99": percentile(successful, .99),
            "max": round(max(successful), 3) if successful else None,
        },
        "note": "Client-observed round-trip latency of successful responses only; "
                "show statuses/failures separately. Includes network, server, serialization and possible queuing. "
                "Not comparable directly to single-thread JMH command throughput.",
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        parser.error(f"Output already exists: {output}. Choose a fresh --output.")
    output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))
    print(f"Saved: {output}")
    if summary["failedCount"]:
        raise SystemExit("One or more requests failed; inspect statuses. This run is not a clean baseline.")


if __name__ == "__main__":
    main()
