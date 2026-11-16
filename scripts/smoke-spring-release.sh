#!/usr/bin/env bash
# S05 release gate for the new Spring Boot public container, local or Render.
# Uses only Python 3; does not depend on IntelliJ Ultimate or third-party CLI tools.
set -euo pipefail
BASE="${MATCHLINE_SPRING_BASE:-http://127.0.0.1:8081}"
MATCHLINE_SPRING_BASE="$BASE" python3 - <<'PY'
import json, os, urllib.error, urllib.request
base=os.environ['MATCHLINE_SPRING_BASE'].rstrip('/')
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        return None
opener=urllib.request.build_opener(NoRedirect)
def req(path, method='GET', token=None, body=None, headers=None):
    h=dict(headers or {})
    if token: h['X-Matchline-Session']=token
    if body is not None: h['Content-Type']='application/json'
    r=urllib.request.Request(base+path, data=body, headers=h, method=method)
    try: response=opener.open(r, timeout=18)
    except urllib.error.HTTPError as error: response=error
    payload=response.read()
    try: data=json.loads(payload) if payload else {}
    except ValueError: data=payload.decode('utf-8',errors='replace')
    return response.status, data, dict(response.headers)
status,health,_=req('/api/v1/health')
assert status==200 and health.get('status')=='UP', ('health',status,health)
status,actuator,_=req('/actuator/health')
assert status==200 and actuator.get('status')=='UP', ('actuator',status,actuator)
status,_,_=req('/actuator/prometheus')
assert status==404, ('public process metrics must stay private',status)
status,_,headers=req('/')
assert status==302 and headers.get('Location')=='/lab', ('redirect',status,headers)
for route in ('/lab','/embed','/preview','/monitor'):
    status,page,headers=req(route)
    assert status==200 and isinstance(page,str) and 'MATCHLINE' in page, (route,status)
    csp=headers.get('Content-Security-Policy','')
    assert "frame-ancestors 'none'" in csp if route in ('/lab','/monitor') else 'frame-ancestors ' in csp
status,_,_=req('/api/v1/dashboard')
assert status==401,('unauthorized visitor must be rejected',status)
status,issued,_=req('/api/v1/session','POST')
assert status==201 and issued.get('durable') is False, ('new session',status,issued)
token=issued['sessionToken']
assert len(token)==43
status,metrics,_=req('/api/v1/metrics',token=token)
assert status==200 and metrics['writer']['completed']==0,('metrics',status,metrics)
order=json.dumps({'instrument':'DEMO','orderId':'RELEASE-B1','side':'BUY',
                  'priceTicks':100,'quantity':3}).encode()
status,placed,_=req('/api/v1/orders','POST',token,order)
assert status==200 and placed['sequence']==1,('placement',status,placed)
status,metrics,_=req('/api/v1/metrics',token=token)
assert status==200 and metrics['writer']['completed']==1,('updated metrics',status,metrics)
status,_,_=req('/api/v1/demo/reset','POST',token=token)
assert status==404,('reset must stay disabled',status)
status,_,_=req('/api/v1/orders','POST',token=token,body=b'x'*4097)
assert status==413,('chunked oversized upload must be rejected',status)
status,_,_=req('/api/v1/metrics',token='invalid-token')
assert status==401,('invalid session must not reveal telemetry',status)
print('MATCHLINE SPRING S05 RELEASE SMOKE PASSED')
print('health, Spring Actuator, private exporter, UI, session, matching, metrics, reset, upload cap')
PY
