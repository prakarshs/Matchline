/** MATCHLINE browser client. UI never computes or persists matching decisions. */
const LIMITS = Object.freeze({priceMin:1,priceMax:1_000_000_000,quantityMin:1,quantityMax:1_000_000});
const POLL_MS = 4000;
const PREVIEW_POLL_MS = 5000;

export function validateOrder(input) {
  const orderId = String(input.orderId ?? '');
  const side = input.side;
  const priceTicks = Number(input.priceTicks);
  const quantity = Number(input.quantity);
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(orderId))
    throw new Error('Order ID must begin with an ASCII letter/digit and contain 1–64 permitted characters.');
  if (side !== 'BUY' && side !== 'SELL') throw new Error('Side must be BUY or SELL.');
  if (!Number.isSafeInteger(priceTicks) || priceTicks < LIMITS.priceMin || priceTicks > LIMITS.priceMax)
    throw new Error('Price must be a whole number of ticks between 1 and 1,000,000,000.');
  if (!Number.isSafeInteger(quantity) || quantity < LIMITS.quantityMin || quantity > LIMITS.quantityMax)
    throw new Error('Quantity must be a whole number between 1 and 1,000,000.');
  return { instrument: 'DEMO', orderId, side, priceTicks, quantity };
}

export function marketSummary(book) {
  const bid = Array.isArray(book?.bids) && book.bids.length ? book.bids[0].priceTicks : null;
  const ask = Array.isArray(book?.asks) && book.asks.length ? book.asks[0].priceTicks : null;
  return {bid,ask,spread:bid === null || ask === null ? null : ask - bid,
    mid:bid === null || ask === null ? null : (bid + ask) / 2};
}

export function depthRows(levels, maxRows = 5) {
  const list = Array.isArray(levels) ? levels.slice(0,maxRows) : [];
  const maxQty = Math.max(1,...list.map(level => Number(level.quantity) || 0));
  return list.map(level => ({priceTicks:level.priceTicks, quantity:level.quantity,
    orders:level.orders, width:Math.min(100,Math.max(0,100 * (Number(level.quantity) || 0) / maxQty))}));
}

export function formatAckP99(upperNanos) {
  if (!Number.isFinite(upperNanos) || upperNanos < 0) return '—';
  if (upperNanos < 1_000_000) return `${Math.ceil(upperNanos / 1000)} µs`;
  return `${(upperNanos / 1_000_000).toLocaleString('en-US',{maximumFractionDigits:2})} ms`;
}

export function createScenario(name, prefix) {
  if (!/^[A-Za-z][A-Za-z0-9-]{0,40}$/.test(prefix)) throw new Error('Invalid scenario prefix');
  const buy = (tag,price,quantity) => ({kind:'place',label:`BUY ${quantity} @ ${price}`,order:{orderId:`${prefix}-${tag}`,side:'BUY',priceTicks:price,quantity}});
  const sell = (tag,price,quantity) => ({kind:'place',label:`SELL ${quantity} @ ${price}`,order:{orderId:`${prefix}-${tag}`,side:'SELL',priceTicks:price,quantity}});
  const cancel = tag => ({kind:'cancel',label:`CANCEL ${tag}`,orderId:`${prefix}-${tag}`});
  const scenarios = {
    fifo:{title:'First in. First out.',description:'Sellers A and B arrive at the same tick. A buyer then consumes 3 units; the matching engine determines the fill order.',steps:[sell('A',100,2),sell('B',100,3),buy('C',101,3)]},
    sweep:{title:'Across the spread.',description:'Two sell prices rest before an aggressive buy. Each executed fill uses the resting maker price.',steps:[sell('A',110,2),sell('B',112,4),buy('C',113,5)]},
    cancel:{title:'Cancel. Then cross.',description:'A bid is placed and cancelled before an opposing sell arrives. The cancelled bid cannot trade.',steps:[buy('A',90,4),cancel('A'),sell('B',90,2)]}
  };
  if (!Object.hasOwn(scenarios,name)) throw new Error('Unknown scenario');
  return {name,...scenarios[name]};
}

function byId(id){return document.getElementById(id)}
function text(id,value){const el=byId(id);if(el)el.textContent=value==null?'—':String(value)}
function pretty(value){return typeof value==='number'?value.toLocaleString('en-US'):value==null?'—':String(value)}
function element(tag,cls,textValue){const el=document.createElement(tag);if(cls)el.className=cls;if(textValue!=null)el.textContent=String(textValue);return el}
function empty(parent,message,cls='empty-feed'){parent.replaceChildren(element(parent.tagName==='OL'?'li':'div',cls,message))}
function uniqueId(prefix='O'){
  const random=globalThis.crypto?.getRandomValues ? Array.from(globalThis.crypto.getRandomValues(new Uint32Array(1)))[0].toString(36) : Math.floor(Math.random()*2**32).toString(36);
  return `${prefix}-${Date.now().toString(36)}-${random}`.slice(0,64);
}
function setConnected(ok){const el=byId('connection');if(!el)return;el.classList.toggle('connected',ok);el.classList.toggle('disconnected',!ok);text('connection-text',ok?'ENGINE ONLINE':'DISCONNECTED')}
function showNotice(message,kind='info'){
  const el=byId('notice');if(!el)return;
  el.hidden=false;el.className='notice '+kind;el.textContent=message;
}
async function request(path,options={}) {
  const controller=new AbortController();
  const timer=setTimeout(()=>controller.abort(),6000);
  try {
    const res=await fetch(path,{...options,cache:'no-store',credentials:'same-origin',signal:controller.signal});
    let data;
    try{data=await res.json()}catch{throw new Error(`Non-JSON response (HTTP ${res.status})`)}
    if(!res.ok && data?.decision!=='REJECTED'){
      const code=data?.error?.code||`HTTP_${res.status}`;
      const detail=data?.error?.message||'Request rejected';
      throw new Error(`${code}: ${detail}${data.sequence!=null?` (sequence ${data.sequence})`:''}`);
    }
    return data;
  } catch(error) {
    if(error?.name==='AbortError')throw new Error('Request timed out; command outcome may be unknown. Check engine status before retrying.');
    throw error;
  }finally{clearTimeout(timer)}
}
function renderDepth(id,levels,side,limit){const root=byId(id);if(!root)return;const rows=depthRows(levels,limit);if(!rows.length){empty(root,`No ${side==='bid'?'bids':'asks'} resting`,'empty-levels');return}const fragment=document.createDocumentFragment();for(const row of rows){const item=element('div',`depth-row ${side}`);const bar=element('span','bar');bar.style.setProperty('--width',`${row.width.toFixed(2)}%`);bar.setAttribute('aria-hidden','true');item.append(bar,element('span','price-cell',pretty(row.priceTicks)),element('span','',pretty(row.quantity)),element('span','order-count',pretty(row.orders)));fragment.append(item)}root.replaceChildren(fragment)}
function renderBook(book,preview){const summary=marketSummary(book);text('hero-sequence',pretty(book.asOfSequence));text('book-asof',`SEQ ${pretty(book.asOfSequence)}`);text('best-bid',pretty(summary.bid));text('best-ask',pretty(summary.ask));text('spread',pretty(summary.spread));text('midpoint',summary.mid===null?'MID —':`MID ${pretty(summary.mid)}`);text('resting-count',pretty(book.restingOrders));renderDepth('bids',book.bids,'bid',preview?3:5);renderDepth('asks',book.asks,'ask',preview?3:5)}
function renderTrades(trades,preview){const root=byId('trades');if(!root)return;const list=(Array.isArray(trades)?trades:[]).slice(-(preview?2:12)).reverse();if(!list.length){empty(root,'No trades observed yet.');return}const f=document.createDocumentFragment();for(const trade of list){const li=element('li');const t=element('div','trade-text');t.append(element('span','trade-seq',`#${pretty(trade.sequence)}.${trade.fillIndex}`));const pair=element('span','trade-pair');pair.append(document.createTextNode(`${trade.makerOrderId} `),element('span','', '→'),document.createTextNode(` ${trade.takerOrderId}`));t.append(pair);const right=element('div','trade-qty',pretty(trade.quantity));right.append(element('small','',`@ ${pretty(trade.priceTicks)}`));li.append(t,right);f.append(li)}root.replaceChildren(f)}
function renderEvents(events){const root=byId('events');if(!root)return;const list=(Array.isArray(events)?events:[]).slice(-12).reverse();if(!list.length){empty(root,'No commands observed yet.');return}const f=document.createDocumentFragment();for(const ev of list){const li=element('li');li.append(element('span','event-seq',`#${pretty(ev.sequence)}`));const main=element('div','event-main',`${ev.decision||'UNKNOWN'} / ${ev.orderId||'—'}`);let detail=ev.decision==='REJECTED'?ev.rejection?.code:ev.decision==='CANCELLED'?`Cancelled ${pretty(ev.cancelledQuantity)} units`:`Executed ${pretty(ev.executedQuantity)} · Resting ${pretty(ev.restingQuantity)}`;main.append(element('small','',detail||'—'));li.append(main);f.append(li)}root.replaceChildren(f)}
function renderStatus(status){text('hero-inbox',`${pretty(status.queuedWork)} / ${pretty(status.queueCapacity)}`);text('hero-durability',status.journalConfigured?status.durabilityPolicy:'VOLATILE')}
function renderRecovery(r){text('policy',r.durabilityPolicy||'NONE');text('recovered',r.recoveredExistingJournal?'YES':'NO');text('snapshot-seq',r.lastRecovery?pretty(r.lastRecovery.snapshotSequence):'NOT USED');text('replayed',r.lastRecovery?pretty(r.lastRecovery.replayedDecisions):'NOT USED');text('pending',r.lastRecovery?String(Boolean(r.lastRecovery.resolvedPendingCommand)).toUpperCase():'NOT USED')}
function renderMetrics(metrics){text('metric-completed',pretty(metrics.writer?.completed));text('metric-rejected',pretty(metrics.writer?.businessRejected));text('metric-p99',formatAckP99(metrics.acknowledgementNanos?.p99UpperBoundNanos));text('metric-http',pretty(metrics.http?.responses2xx))}

function init(){const preview=document.documentElement.dataset.view==='preview';let refreshing=false;let submitting=false;let scenario=null;let stepIndex=0;let side='BUY';
  async function refresh(){if(refreshing||submitting||document.hidden)return;refreshing=true;try{
    const routes=preview?['/api/v1/book?depth=5','/api/v1/trades?limit=10','/api/v1/status']:
      ['/api/v1/book?depth=5','/api/v1/trades?limit=12','/api/v1/events?limit=12','/api/v1/status','/api/v1/metrics','/api/v1/recovery'];
    // Same-origin requests. A failed endpoint leaves other successful panels useful.
    const settled=await Promise.allSettled(routes.map(path=>request(path)));
    if(settled[0].status==='rejected')throw settled[0].reason;
    renderBook(settled[0].value,preview);
    if(settled[1].status==='fulfilled')renderTrades(settled[1].value.trades,preview);
    if(!preview){if(settled[2].status==='fulfilled')renderEvents(settled[2].value.events);
      if(settled[3].status==='fulfilled')renderStatus(settled[3].value);
      if(settled[4].status==='fulfilled')renderMetrics(settled[4].value);
      if(settled[5].status==='fulfilled')renderRecovery(settled[5].value);
      text('refresh-label',`AS OF SEQ ${pretty(settled[0].value.asOfSequence)}`);
    }
    setConnected(true);
  }catch(error){setConnected(false);if(!preview)showNotice(`Cannot refresh: ${error.message}`,'error')}
  finally{refreshing=false}}
  async function sendCommand(path,payload){const options={method:'POST',headers:{'Content-Type':'application/json'}};if(payload)options.body=JSON.stringify(payload);return request(path,options)}
  async function execute(step){if(step.kind==='cancel')return sendCommand(`/api/v1/orders/${encodeURIComponent(step.orderId)}/cancel`);return sendCommand('/api/v1/orders',validateOrder(step.order))}
  function describeDecision(r){if(r.decision==='REJECTED')return `#${r.sequence} · REJECTED: ${r.rejection?.code||'Unknown'} — ${r.rejection?.message||''}`;
    if(r.decision==='CANCELLED')return `#${r.sequence} · CANCELLED ${r.orderId}: ${pretty(r.cancelledQuantity)} units removed.`;
    const fills=(r.trades||[]).map(t=>`${t.quantity} @ ${t.priceTicks} (maker ${t.makerOrderId})`).join(', ');
    return `#${r.sequence} · PLACED ${r.orderId}. ${pretty(r.executedQuantity)} executed, ${pretty(r.restingQuantity)} resting.${fills?` Fills: ${fills}.`:''}`}
  async function withSubmitting(action){if(submitting)return;submitting=true;setDisabled(true);try{const result=await action();showNotice(describeDecision(result),result.decision==='REJECTED'?'error':'success');return result}catch(e){showNotice(e.message,'error');return null}finally{submitting=false;setDisabled(false);await refresh()}}
  function setDisabled(busy){for(const id of ['submit-order','cancel-button','scenario-step']){const b=byId(id);if(b)b.disabled=busy||(id==='scenario-step'&&(!scenario||stepIndex>=scenario.steps.length))}}
  function chooseSide(next){side=next;for(const value of ['BUY','SELL']){const button=byId(`side-${value.toLowerCase()}`);button?.setAttribute('aria-pressed',String(value===side));button?.classList.toggle('is-active',value===side);button?.classList.toggle('buy-mode',value==='BUY');button?.classList.toggle('sell-mode',value==='SELL')}const b=byId('submit-order');if(b){b.firstChild.textContent=`Submit ${side.toLowerCase()} order `;b.classList.toggle('sell-action',side==='SELL')}}
  function repaintScenario(){text('scenario-name',scenario?.title||'Choose an experiment above');text('scenario-description',scenario?.description||'Scenarios add to the current book.');text('scenario-progress',scenario?`${stepIndex} / ${scenario.steps.length}`:'0 / 0');const steps=byId('scenario-steps');if(steps){const f=document.createDocumentFragment();for(let i=0;i<(scenario?.steps.length||0);i++){const li=element('li',i<stepIndex?'done':i===stepIndex?'current':'',`${i<stepIndex?'✓ ':''}${scenario.steps[i].label}`);f.append(li)}steps.replaceChildren(f)}setDisabled(submitting)}
  if(!preview){
    byId('order-id').value=uniqueId('O');
    byId('side-buy')?.addEventListener('click',()=>chooseSide('BUY'));
    byId('side-sell')?.addEventListener('click',()=>chooseSide('SELL'));
    byId('new-id')?.addEventListener('click',()=>{byId('order-id').value=uniqueId('O')});
    byId('order-form')?.addEventListener('submit',event=>{event.preventDefault();let order;try{order=validateOrder({orderId:byId('order-id').value,side,priceTicks:byId('price').value,quantity:byId('quantity').value})}catch(error){showNotice(error.message,'error');return}void withSubmitting(()=>sendCommand('/api/v1/orders',order)).then(result=>{if(result?.decision==='PLACED')byId('order-id').value=uniqueId('O')})});
    byId('cancel-form')?.addEventListener('submit',event=>{event.preventDefault();const id=byId('cancel-id').value;if(!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(id)){showNotice('Enter a valid resting order ID to cancel.','error');return}void withSubmitting(()=>sendCommand(`/api/v1/orders/${encodeURIComponent(id)}/cancel`)).then(result=>{if(result?.decision==='CANCELLED')byId('cancel-id').value=''})});
    document.querySelectorAll('[data-scenario]').forEach(button=>button.addEventListener('click',()=>{if(submitting)return;const prefix=uniqueId('S').replace(/[^A-Za-z0-9-]/g,'');scenario=createScenario(button.dataset.scenario,prefix);stepIndex=0;document.querySelectorAll('[data-scenario]').forEach(node=>node.setAttribute('aria-pressed',String(node===button)));repaintScenario();showNotice('Scenario staged; each next step sends an actual command to this book. No reset occurs.')}));
    byId('scenario-step')?.addEventListener('click',()=>{if(!scenario||stepIndex>=scenario.steps.length)return;const step=scenario.steps[stepIndex];void withSubmitting(()=>execute(step)).then(result=>{if(result){stepIndex++;repaintScenario()}})});
  }
  void refresh();setInterval(()=>void refresh(),preview?PREVIEW_POLL_MS:POLL_MS);
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)void refresh()});
}
if(typeof document!=='undefined')init();
