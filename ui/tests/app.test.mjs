import test from 'node:test';
import assert from 'node:assert/strict';
import {validateOrder, marketSummary, depthRows, formatAckP99, createScenario} from '../../src/main/resources/ui/app.mjs';

test('validates and serializes integral domain commands',()=>{
  assert.deepEqual(validateOrder({orderId:'B1',side:'BUY',priceTicks:'100',quantity:'3'}),
    {instrument:'DEMO',orderId:'B1',side:'BUY',priceTicks:100,quantity:3});
  for(const input of [
    {orderId:'',side:'BUY',priceTicks:1,quantity:1},
    {orderId:'a b',side:'BUY',priceTicks:1,quantity:1},
    {orderId:'x',side:'BOTH',priceTicks:1,quantity:1},
    {orderId:'x',side:'BUY',priceTicks:1.1,quantity:1},
    {orderId:'x',side:'BUY',priceTicks:0,quantity:1},
    {orderId:'x',side:'BUY',priceTicks:1,quantity:-1},
    {orderId:'x',side:'BUY',priceTicks:1,quantity:1.5},
    {orderId:'x',side:'BUY',priceTicks:1_000_000_001,quantity:1},
    {orderId:'x',side:'BUY',priceTicks:1,quantity:1_000_001},
  ])assert.throws(()=>validateOrder(input));
});

test('best bid, ask, spread and midpoint are derived only from API levels',()=>{
  assert.deepEqual(marketSummary({bids:[{priceTicks:100}],asks:[{priceTicks:104}]}),{bid:100,ask:104,spread:4,mid:102});
  assert.deepEqual(marketSummary({bids:[],asks:[{priceTicks:104}]}),{bid:null,ask:104,spread:null,mid:null});
  assert.deepEqual(marketSummary({}),{bid:null,ask:null,spread:null,mid:null});
});

test('depth scaling and caps handle empty/large/zero quantities',()=>{
  assert.deepEqual(depthRows([]),[]);
  const rows=depthRows([{priceTicks:100,quantity:10,orders:1},{priceTicks:99,quantity:5,orders:2},{priceTicks:98,quantity:0,orders:0}],2);
  assert.equal(rows.length,2);assert.equal(rows[0].width,100);assert.equal(rows[1].width,50);
  assert.equal(depthRows([{priceTicks:1,quantity:0,orders:1}])[0].width,0);
});

test('histogram p99 displays bucket bounds without inventing precision',()=>{
  assert.equal(formatAckP99(-1),'—');assert.equal(formatAckP99(null),'—');
  assert.equal(formatAckP99(250000),'250 µs');assert.equal(formatAckP99(25000000),'25 ms');
});

test('FIFO scenario uses the same price and correct sequential maker quantities',()=>{
  const scenario=createScenario('fifo','TEST');
  assert.deepEqual(scenario.steps.map(step=>step.label),['SELL 2 @ 100','SELL 3 @ 100','BUY 3 @ 101']);
  assert.deepEqual(scenario.steps.map(step=>step.order.orderId),['TEST-A','TEST-B','TEST-C']);
  assert.equal(scenario.steps[2].order.side,'BUY');
});

test('sweep scenario crosses multiple maker prices',()=>{
  const steps=createScenario('sweep','X').steps;
  assert.deepEqual(steps.map(s=>s.order.priceTicks),[110,112,113]);
  assert.equal(steps[2].order.quantity,5);
});

test('cancel scenario references an existing placed ID and then crosses',()=>{
  const steps=createScenario('cancel','TEST').steps;
  assert.equal(steps[0].order.orderId,steps[1].orderId);
  assert.equal(steps[1].kind,'cancel');assert.equal(steps[2].order.side,'SELL');
});

test('scenario prefix and name are checked',()=>{
  assert.throws(()=>createScenario('wrong','X'));
  assert.throws(()=>createScenario('fifo','../bad'));
});
