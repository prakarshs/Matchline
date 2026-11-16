#!/usr/bin/env bash
# No IntelliJ Ultimate or Postman needed. Start MatchlineSpringApplication in IDEA first.
set -euo pipefail
BASE="${MATCHLINE_SPRING_URL:-http://127.0.0.1:8082}"
uid="s03-$(date +%s)-$$"
pass() { printf '[PASS] %s\n' "$1"; }
require() { command -v "$1" >/dev/null || { echo "Missing dependency: $1" >&2; exit 1; }; }
require curl
require python3
check() {
  local expected="$1" path="$2" json="$3" body status
  body="$(mktemp)"
  if [[ "$json" == '-' ]]; then
    status="$(curl --fail-with-body -sS -o "$body" -w '%{http_code}' "$BASE$path")" || { cat "$body"; rm -f "$body"; exit 1; }
  else
    status="$(curl -sS -o "$body" -w '%{http_code}' -X POST "$BASE$path" -H 'Content-Type: application/json' -d "$json")"
  fi
  if [[ "$status" != "$expected" ]]; then
    echo "FAIL $path: expected HTTP $expected, got $status" >&2
    cat "$body" >&2; rm -f "$body"; exit 1
  fi
  cat "$body"; rm -f "$body"
}

check 200 /api/v1/health - >/dev/null
pass 'Spring health'
check 200 /actuator/health - >/dev/null
pass 'Actuator health'

buy="$(check 200 /api/v1/orders "{\"instrument\":\"DEMO\",\"orderId\":\"${uid}-B\",\"side\":\"BUY\",\"priceTicks\":100,\"quantity\":4}")"
python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["decision"]=="PLACED" and r["restingQuantity"]==4 and not r["durable"]' <<<"$buy"
pass 'BUY rests'

sell="$(check 200 /api/v1/orders "{\"instrument\":\"DEMO\",\"orderId\":\"${uid}-S\",\"side\":\"SELL\",\"priceTicks\":99,\"quantity\":3}")"
python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["executedQuantity"]==3 and r["trades"][0]["priceTicks"]==100' <<<"$sell"
pass 'SELL crosses at maker price'

lookup="$(check 200 "/api/v1/orders/${uid}-B" -)"
python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["order"]["remainingQuantity"]==1' <<<"$lookup"
pass 'Remaining quantity is 1'

cancel="$(check 200 "/api/v1/orders/${uid}-B/cancel" '{}')"
python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["decision"]=="CANCELLED" and r["cancelledQuantity"]==1' <<<"$cancel"
pass 'Cancel remainder'

dup="$(check 409 /api/v1/orders "{\"instrument\":\"DEMO\",\"orderId\":\"${uid}-B\",\"side\":\"BUY\",\"priceTicks\":100,\"quantity\":1}")"
python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["rejection"]["code"]=="DUPLICATE_ORDER_ID"' <<<"$dup"
pass 'Duplicate rejection retains sequence'

book="$(check 200 /api/v1/book?depth=5 -)"
python3 -c 'import json,sys; r=json.load(sys.stdin); assert r["instrument"]=="DEMO" and "bids" in r and "asks" in r' <<<"$book"
pass 'Ordered book read'

echo 'MATCHLINE SPRING S03 HTTP SMOKE PASSED'
