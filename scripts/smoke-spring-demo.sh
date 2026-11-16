#!/usr/bin/env bash
# Spring S04: public-demo end-to-end smoke check, no IDE Ultimate/Postman/jq required.
set -euo pipefail
BASE="${MATCHLINE_SPRING_BASE:-http://127.0.0.1:8082}"
MATCHLINE_SPRING_BASE="$BASE" python3 - <<'PY'
import json, os, urllib.error, urllib.request
base=os.environ['MATCHLINE_SPRING_BASE'].rstrip('/')
def req(path, method='GET', token=None, body=None):
    hdrs={'X-Matchline-Session':token} if token else {}
    if body is not None: hdrs['Content-Type']='application/json'
    data=None if body is None else json.dumps(body).encode()
    request=urllib.request.Request(base+path, data=data, method=method,headers=hdrs)
    try:
        r=urllib.request.urlopen(request,timeout=10)
    except urllib.error.HTTPError as e:
        r=e
    raw=r.read()
    try: payload=json.loads(raw) if raw else {}
    except ValueError: payload=raw.decode(errors='replace')
    return r.status, payload
status,health=req('/api/v1/health')
assert status==200,('health',status,health)
status,missing=req('/api/v1/dashboard')
assert status==401 and missing['error']['code']=='SESSION_REQUIRED',('public mode not enabled?',status,missing)
def session(preview=False):
    status,payload=req('/api/v1/session'+('?mode=preview' if preview else ''),'POST')
    assert status==201 and len(payload['sessionToken'])==43,(status,payload)
    return payload['sessionToken']
a,b=session(),session()
assert a!=b
buy={'instrument':'DEMO','orderId':'B1','side':'BUY','priceTicks':100,'quantity':4}
status,placed=req('/api/v1/orders','POST',a,buy)
assert status==200 and placed['sequence']==1,(status,placed)
status,book_a=req('/api/v1/dashboard?depth=5',token=a)
status_b,book_b=req('/api/v1/dashboard?depth=5',token=b)
assert status==status_b==200
assert book_a['book']['asOfSequence']==1 and book_b['book']['asOfSequence']==0
sell={'instrument':'DEMO','orderId':'S1','side':'SELL','priceTicks':99,'quantity':3}
status,traded=req('/api/v1/orders','POST',a,sell)
assert status==200 and traded['trades'][0]['priceTicks']==100,(status,traded)
status,tape=req('/api/v1/trades?limit=10',token=a)
assert status==200 and len(tape['trades'])==1
status,preview=req('/api/v1/dashboard?depth=5',token=session(True))
assert status==200 and preview['book']['asOfSequence']==5
assert preview['trades'][0]['makerOrderId']=='SHOW-S1'
status,reset=req('/api/v1/demo/reset','POST',token=a)
assert status==404
print('MATCHLINE SPRING S04 PUBLIC-DEMO SMOKE PASSED')
print('Checked: token requirement, distinct books, maker-price trade, tape, seeded preview, reset refusal.')
PY
