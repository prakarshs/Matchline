/** Bounded, read-only telemetry client. Never writes orders or logs a session token. */
const KEY = 'matchline-demo-lab-v1'; // Same tab's laboratory session, when available.
const $ = id => document.getElementById(id);
let token;
try { token = sessionStorage.getItem(KEY); } catch { token = null; }
let refreshing = false;
let lastUpdate = null;
function set(id,value){ $(id).textContent = value == null ? '—' : String(value); }
function formatCount(n){return Number.isFinite(n) ? n.toLocaleString('en-US') : '—';}
function ms(n){
  if(!Number.isFinite(n) || n < 0) return '—';
  return n < 1_000_000 ? `${Math.ceil(n/1_000)} µs` : `${(n/1_000_000).toFixed(2)} ms`;
}
function state(ok,message){$('dot').classList.toggle('live',ok);set('status',message);}
async function call(path, options={}){
  const controller = new AbortController();
  const timer = setTimeout(()=>controller.abort(),8_000);
  try {
    const response = await fetch(path,{...options,cache:'no-store',credentials:'same-origin',
      headers:{...(token ? {'X-Matchline-Session':token}:{}),...(options.headers||{})},
      signal:controller.signal});
    let data;
    try{data=await response.json();}catch{data={};}
    return {response,data};
  } finally {clearTimeout(timer);}
}
async function getMetrics(){
  let {response,data}=await call('/api/v1/metrics');
  if(response.status===401 && ['SESSION_REQUIRED','SESSION_EXPIRED'].includes(data?.error?.code)){
    token=null;
    try{sessionStorage.removeItem(KEY);}catch{}
    const created=await call('/api/v1/session',{method:'POST'});
    if(!created.response.ok || !/^[A-Za-z0-9_-]{43}$/.test(created.data?.sessionToken||''))
      throw new Error(created.data?.error?.message || 'Demo session unavailable');
    token=created.data.sessionToken;
    try{sessionStorage.setItem(KEY,token);}catch{}
    ({response,data}=await call('/api/v1/metrics'));
  }
  if(!response.ok) throw new Error(data?.error?.code || `HTTP ${response.status}`);
  return data;
}
async function refresh(){
  if(refreshing) return;
  refreshing=true;
  try{
    const metrics=await getMetrics();
    set('admitted',formatCount(metrics.writer?.admitted));
    set('completed',formatCount(metrics.writer?.completed));
    set('queued',formatCount(metrics.queuedWork));
    set('queue-limit',`Queue slots: ${formatCount(metrics.queueCapacity)} / waiting: ${formatCount(metrics.queuedWork)}`);
    set('rejected',formatCount((metrics.writer?.ingressRejectedFull||0)+(metrics.writer?.ingressRejectedClosed||0)));
    set('processing',ms(metrics.processingNanos?.p95UpperBoundNanos));
    set('ack',ms(metrics.acknowledgementNanos?.p99UpperBoundNanos));
    lastUpdate=new Date();
    set('age',`Updated ${lastUpdate.toLocaleTimeString()}`);
    state(true,'SESSION METRICS LIVE');
  }catch(err){state(false,`UNAVAILABLE: ${String(err?.message||err).slice(0,130)}`);}
  finally{refreshing=false;}
}
if(typeof document!=='undefined'){
  refresh();
  setInterval(()=>{if(document.visibilityState==='visible')refresh();},4_000);
}
