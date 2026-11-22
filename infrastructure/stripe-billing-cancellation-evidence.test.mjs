import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {proveBillingEvidence} from './stripe-billing-evidence.mjs';

const api = await import('./stripe-billing-cancellation-evidence.mjs').catch(e => {
  if (e.code === 'ERR_MODULE_NOT_FOUND' && e.url?.endsWith('/stripe-billing-cancellation-evidence.mjs')) return {};
  throw e;
});
const id = n => String(n).padStart(8, '0') + '-0000-4000-8000-000000000000';
const hash = n => String(n).repeat(64);
function receipt(phase) {
  const scope = {runId:id(1), expectedSource:'b'.repeat(40), accountId:id(2), siteId:id(3),
    customerId:id(4), subscriptionId:id(5), operationId:id(6), productId:id(7), priceId:id(8),
    attemptId:id(9), createKeyHash:hash(1), confirmKeyHash:hash(2), priceReference:'price_one',
    siteKey:'s5a-' + id(1).replaceAll('-',''), endpoints:{demo:'http://127.0.0.1:18081'}};
  const graph = Object.fromEntries(['runId','expectedSource','accountId','siteId','customerId','subscriptionId','operationId','priceId'].map(k => [k,scope[k]]));
  Object.assign(graph,{priceVersion:1, periodStart:1700000000,periodEnd:1702500000,customerReference:'cus_one',
    subscriptionReference:'sub_one',productReference:'prod_one',priceReference:'price_one',invoiceReference:'in_one',
    allocationReference:'inpay_one',intentReference:'pi_one'});
  const baseline={graph,cashVerified:true,journalVerified:true,signedCallbacksVerified:true};
  const operations={schedule:{operationId:id(20),keyHash:hash(3)}};
  if(phase==='withdrawn') operations.withdraw={operationId:id(21),keyHash:hash(4)};
  const inputs={phase,operations,baseline,configuration:{...scope,selectedCase:'initial_cancel_withdraw'},fixture:{resources:()=>scope}};
  const row={payment_id:id(10),billing_record_id:id(11),cash:true,journal:true,original:true,closed_inventory:true,
    original_paid_event_id:id(13),period_start:graph.periodStart,period_end:graph.periodEnd,revision:phase==='scheduled'?4:5,
    cancellation_at:phase==='scheduled'?graph.periodEnd:null,callbacks:[],receipts:[]};
  for(const key of ['customer','subscription','product','price','invoice','allocation','intent']) row[key+'_reference']=graph[key+'Reference'];
  const common={site_id:scope.siteId,customer_id:scope.customerId,subscription_id:scope.subscriptionId,product_id:scope.productId};
  const iso=t=>new Date(t*1000).toISOString();
  function add(payload){row.callbacks.push({id:payload.event_id,type:payload.event_type,payload:{...common,...payload},delivered:true});}
  add({event_id:id(12),event_type:'payment.succeeded',schema_version:3,payment_status:'SUCCEEDED',payment_id:id(10),billing_record_id:id(11),
    amount_minor:2000,currency:'EUR',billing_kind:'INITIAL',price_id:scope.priceId,price_version:1,
    occurred_at:iso(graph.periodStart),period_start:iso(graph.periodStart),period_end:iso(graph.periodEnd)});
  add({event_id:id(13),event_type:'subscription.updated',schema_version:4,state:'ACTIVE',revision:3,access_allowed:true,billing_record_id:id(11),payment_id:null,
    occurred_at:iso(graph.periodStart),access_valid_until:iso(graph.periodStart+300)});
  function addTransition(op,revision,event,kind,mode,state,at){
    const eventId=id(event);
    row.receipts.push({operation_id:op.operationId,key_hash:op.keyHash,kind,mode,expected_revision:revision-1,
      event_id:eventId,revision,boundary:graph.periodEnd,acknowledged:true,prior_operation:null,
      prior_event:mode==='WITHDRAW'?id(14):null,prior_event_revision:mode==='WITHDRAW'?4:null});
    add({event_id:eventId,event_type:'subscription.updated',schema_version:4,state,revision,access_allowed:true,payment_id:null,billing_record_id:null,
      occurred_at:iso(at),access_valid_until:iso(at+300)});
  }
  addTransition(operations.schedule,4,14,'CANCEL','PERIOD_END','CANCEL_AT_PERIOD_END',graph.periodStart+2);
  if(phase==='withdrawn') addTransition(operations.withdraw,5,15,'WITHDRAW_CANCEL','WITHDRAW','ACTIVE',graph.periodStart+4);
  const inspection=()=>({count:row.callbacks.length,duplicates:0,events:row.callbacks.map(({id,type,payload})=>({
    event_id:id,event_type:type,count:1,sha256:createHash('sha256').update(JSON.stringify(payload,Object.keys(payload).sort())).digest('hex')
  }))});
  const original={paymentId:id(10),billingRecordId:id(11),cashEventId:id(12),paidEventId:id(13),revision:3,
    callbackDigests:Object.fromEntries(inspection().events.slice(0,2).map(e=>[e.event_id,e.sha256]))};
  const scheduled={operationId:id(20),eventId:id(14),revision:4,callbackDigest:inspection().events[2].sha256};
  return {scope,inputs,row,inspection,witness:{original,scheduled}};
}
async function mintBaseline(f,initialCount=2) {
  const initial={...f.row,subscription_revision:'3',initial_unscheduled:true,callbacks:f.row.callbacks.slice(0,initialCount),cash_journal_ids:[id(12)],paid_journal_ids:[id(13)],
    proof:{cash:true,journal:true,original:true,webhook:true}};
  const events=f.inspection().events.slice(0,initialCount);
  return proveBillingEvidence({...f.inputs,bootstrap:{withDatabaseCredentials:fn=>fn({})},fixture:{resources:()=>f.scope,
    inspectCallbacks:async()=>({count:initialCount,duplicates:0,events})}},{deadline:Date.now()+1000},{connect:()=>({
    begin:async()=>[initial],end:async()=>{}
  })});
}

test('both phases require the original cash, precise transition journal and all signed deliveries',()=>{
  assert.equal(typeof api.validateBillingCancellationEvidence,'function');
  for(const phase of ['scheduled','withdrawn']){
    const {inputs,row,inspection,witness}=receipt(phase);
    assert.equal(api.validateBillingCancellationEvidence(inputs,row,inspection(),witness),true);
    if(phase==='withdrawn'){
      Object.assign(row.receipts[1],{prior_operation:inputs.operations.schedule.operationId,prior_event:null,prior_event_revision:null});
      assert.equal(api.validateBillingCancellationEvidence(inputs,row,inspection(),witness),true);
    }
  }
});
const mutations={
  'ordinary initial-only case':f=>f.inputs.configuration.selectedCase='initial_success',
  'unbound original paid callback':f=>f.row.original_paid_event_id=id(98),
  'withdrawal reusing the cash event':f=>f.row.receipts[1].event_id=f.row.original_paid_event_id,
  'foreign baseline run':f=>f.inputs.baseline.graph.runId=id(98),
  'lost baseline verification':f=>f.inputs.baseline.cashVerified=false,
  'substituted original invoice':f=>f.row.invoice_reference='in_other',
  'different billing period':f=>f.inputs.baseline.graph.periodEnd++,
  'cash mismatch':f=>f.row.cash=false,
  'missing original journal':f=>f.row.journal=false,
  'foreign original attempt':f=>f.row.original=false,
  'additional operation or bill':f=>f.row.closed_inventory=false,
  'still scheduled':f=>f.row.cancellation_at=f.row.period_end,
  'wrong current revision':f=>f.row.revision++,
  'reused customer key':f=>f.inputs.operations.withdraw.keyHash=f.inputs.operations.schedule.keyHash,
  'wrong schedule ID':f=>f.row.receipts[0].operation_id=id(98),
  'unacknowledged provider step':f=>f.row.receipts[0].acknowledged=false,
  'wrong command kind':f=>f.row.receipts[1].kind='CANCEL',
  'wrong command key':f=>f.row.receipts[1].key_hash=hash(9),
  'incorrect captured boundary':f=>f.row.receipts[0].boundary--,
  'extra receipt':f=>f.row.receipts.push(f.row.receipts[0]),
  'withdrawal before next revision':f=>f.row.receipts[1].expected_revision--,
  'foreign prior operation':f=>Object.assign(f.row.receipts[1],{prior_operation:id(98),prior_event:null,prior_event_revision:null}),
  'foreign predecessor event':f=>f.row.receipts[1].prior_event=id(98),
  'stale predecessor revision':f=>f.row.receipts[1].prior_event_revision--,
  'ambiguous predecessor':f=>f.row.receipts[1].prior_operation=f.inputs.operations.schedule.operationId,
  'missing withdrawal callback':f=>f.row.callbacks.pop(),
  'incorrect callback revision':f=>f.row.callbacks.at(-1).payload.revision--,
  'lost paid access':f=>f.row.callbacks.at(-1).payload.access_allowed=false,
  'expired callback access':f=>f.row.callbacks.at(-1).payload.access_valid_until=f.row.callbacks.at(-1).payload.occurred_at,
  'second positive payment':f=>f.row.callbacks.push({...f.row.callbacks[0],id:id(55)}),
};
for(const [label,mutate] of Object.entries(mutations))test('refuses '+label,()=>{
  const f=receipt('withdrawn'); mutate(f);
  assert.throws(()=>api.validateBillingCancellationEvidence(f.inputs,f.row,f.inspection(),f.witness),/billing_EVIDENCE/);
});
test('publisher uses a read-only transaction and closes credentials before receiver inspection',async()=>{
  assert.equal(typeof api.proveBillingCancellationEvidence,'function');
  const f=receipt('scheduled'),calls=[];f.inputs.baseline=await mintBaseline(f);let leased=false,reads=0;
  f.inputs.bootstrap={withDatabaseCredentials:async callback=>{leased=true;try{return await callback({password:'sentinel'});}finally{leased=false;}}};
  f.inputs.fixture.inspectCallbacks=async()=>{assert.equal(leased,false);calls.push('inspection');return f.inspection();};
  const connect=()=>({begin:async(mode,fn)=>{assert.equal(mode,'read only');return fn({unsafe:(query,args)=>{
    if(query.startsWith('set local'))return [];
    assert.deepEqual(args.slice(0,8),['accountId','siteId','customerId','subscriptionId','operationId','attemptId','productId','priceId'].map(k=>f.scope[k]));
    assert.ok(args.includes(f.inputs.operations.schedule.operationId)&&args.includes(f.inputs.operations.schedule.keyHash));
    assert.ok(!args.includes('sentinel'));reads++;
    if(reads===1)return [{...f.row,callbacks:f.row.callbacks.map(e=>({...e,delivered:false}))}];
    return [f.row];
  }});},end:async()=>calls.push('close')});
  assert.equal(await api.proveBillingCancellationEvidence(f.inputs,{deadline:Date.now()+3000},{connect}),true);
  assert.equal(reads,2);assert.deepEqual(calls,['close','inspection']);
});
test('rejected scope never opens credentials; duplicate, missing or changed signed deliveries cannot pass',async()=>{
  assert.equal(typeof api.proveBillingCancellationEvidence,'function');
  const f=receipt('scheduled');f.inputs.baseline.graph.accountId=id(90);let leases=0;
  f.inputs.bootstrap={withDatabaseCredentials:()=>{leases++;}};
  await assert.rejects(api.proveBillingCancellationEvidence(f.inputs,{deadline:Date.now()+1000}),/billing_EVIDENCE/);assert.equal(leases,0);
  const valid=receipt('withdrawn');
  for(const mutate of [i=>i.duplicates=1,i=>i.events.pop(),i=>i.events.at(-1).sha256=hash(9)]){
    const i=valid.inspection();mutate(i);
    assert.throws(()=>api.validateBillingCancellationEvidence(valid.inputs,valid.row,i,valid.witness),/billing_EVIDENCE/);
  }
});

test('aborting an in-flight read closes the original connection; late rows cannot reach the receiver',async()=>{
  const f=receipt('scheduled'),controller=new AbortController();f.inputs.baseline=await mintBaseline(f);let entered,release,closed=0,inspected=0;
  const reading=new Promise(r=>entered=r);
  f.inputs.bootstrap={withDatabaseCredentials:callback=>callback({password:'sentinel'})};
  f.inputs.fixture.inspectCallbacks=()=>{inspected++;return f.inspection();};
  const pending=api.proveBillingCancellationEvidence(f.inputs,{deadline:Date.now()+2000,signal:controller.signal},{
    connect:()=>({begin:()=>new Promise(r=>{release=r;entered();}),end:async()=>{closed++;}})
  });
  await reading;controller.abort();await assert.rejects(pending,/billing_ABORTED/);
  release([f.row]);await new Promise(r=>setImmediate(r));
  assert.equal(closed,1);assert.equal(inspected,0);
});

for(const [name,change] of Object.entries({
  payment:f=>f.witness.original.paymentId=id(66),
  bill:f=>f.witness.original.billingRecordId=id(66),
  paymentJournal:f=>f.witness.original.cashEventId=id(66),
  periodJournal:f=>f.witness.original.paidEventId=id(66),
  scheduledJournal:f=>f.witness.scheduled.eventId=id(66),
  scheduledRevision:f=>f.witness.scheduled.revision++,
}))test('a complete internally consistent withdrawal cannot replace the previously observed '+name,()=>{
  const f=receipt('withdrawn');change(f);
  assert.throws(()=>api.validateBillingCancellationEvidence(f.inputs,f.row,f.inspection(),f.witness),/billing_EVIDENCE/);
});

test('publisher requires the exact private baseline object and the original accepted schedule before withdrawal',async()=>{
  const f=receipt('withdrawn');const actual=await mintBaseline(f);let leases=0;
  f.inputs.bootstrap={withDatabaseCredentials:()=>{leases++;}};
  for(const baseline of [structuredClone(actual),actual]){
    f.inputs.baseline=baseline;
    await assert.rejects(api.proveBillingCancellationEvidence(f.inputs,{deadline:Date.now()+1000},{connect:()=>assert.fail()}),/billing_EVIDENCE/);
  }
  assert.equal(leases,0);
});

for(const substitute of [false,true])test('publisher carries the same immutable local IDs through both phases (substitution '+substitute+')',async()=>{
  const initial=receipt('scheduled'),baseline=await mintBaseline(initial);
  async function publish(f) {
    f.inputs.baseline=baseline;
    f.inputs.bootstrap={withDatabaseCredentials:fn=>fn({})};
    f.inputs.fixture.inspectCallbacks=async()=>f.inspection();
    return api.proveBillingCancellationEvidence(f.inputs,{deadline:Date.now()+1000},{connect:()=>({
      begin:async()=>[f.row],end:async()=>{}
    })});
  }
  assert.equal(await publish(initial),true);
  const last=receipt('withdrawn');
  if(substitute){
    last.row.receipts[0].event_id=id(71);
    last.row.receipts[1].prior_event=id(71);
    last.row.callbacks[2].id=id(71);
    last.row.callbacks[2].payload.event_id=id(71);
    await assert.rejects(publish(last),/billing_EVIDENCE/);
  }else assert.equal(await publish(last),true);
});

function extraLifecycle(f,n) {
  const base=f.row.callbacks.find(e=>e.id===id(13));
  return {id:id(n),type:'subscription.access_changed',delivered:true,payload:{...base.payload,event_id:id(n),event_type:'subscription.access_changed',
    state:'INCOMPLETE',revision:2,access_allowed:false,access_valid_until:null,billing_record_id:null}};
}
async function publishPhase(f,baseline) {
  return api.proveBillingCancellationEvidence({...f.inputs,baseline,bootstrap:{withDatabaseCredentials:fn=>fn({})},
    fixture:{resources:()=>f.scope,inspectCallbacks:async()=>f.inspection()}},{deadline:Date.now()+2000},
    {connect:()=>({begin:async()=>[f.row],end:async()=>{}})});
}
for(const variant of ['legitimate-initial','injected-before-schedule','injected-before-withdrawal','schedule-revision-gap','baseline-digest','schedule-digest']){
  test('published callback inventory across both transitions: '+variant,async()=>{
    const first=receipt('scheduled');
    first.row.callbacks.splice(1,0,extraLifecycle(first,60));
    const baseline=await mintBaseline(first,3);
    if(variant==='injected-before-schedule') first.row.callbacks.push(extraLifecycle(first,61));
    if(variant==='schedule-revision-gap'){
      const schedule=first.row.receipts[0];
      schedule.expected_revision++;schedule.revision++;first.row.revision++;
      first.row.callbacks.find(e=>e.id===schedule.event_id).payload.revision++;
    }
    if(variant==='baseline-digest')first.row.callbacks.find(e=>e.id===id(60)).payload.revision=1;
    if(['injected-before-schedule','schedule-revision-gap','baseline-digest'].includes(variant)){
      await assert.rejects(publishPhase(first,baseline),/billing_EVIDENCE/);
      return;
    }
    assert.equal(await publishPhase(first,baseline),true);
    const last=receipt('withdrawn');
    last.row.callbacks.push(extraLifecycle(last,60)); // Database output order has no bearing on membership.
    if(variant==='injected-before-withdrawal')last.row.callbacks.push(extraLifecycle(last,61));
    if(variant==='schedule-digest'){
      const payload=last.row.callbacks.find(e=>e.id===id(14)).payload;
      payload.occurred_at=new Date(Date.parse(payload.occurred_at)+1000).toISOString();
      payload.access_valid_until=new Date(Date.parse(payload.access_valid_until)+1000).toISOString();
    }
    if(variant==='legitimate-initial')assert.equal(await publishPhase(last,baseline),true);
    else await assert.rejects(publishPhase(last,baseline),/billing_EVIDENCE/);
  });
}

test('all callbacks at the initial proof limit can be retained through both single additions',async()=>{
  const schedule=receipt('scheduled'),withdraw=receipt('withdrawn');
  for(let n=100;n<198;n++){
    schedule.row.callbacks.splice(2,0,extraLifecycle(schedule,n));
    withdraw.row.callbacks.push(extraLifecycle(withdraw,n));
  }
  const baseline=await mintBaseline(schedule,100);
  assert.equal(await publishPhase(schedule,baseline),true);
  assert.equal(await publishPhase(withdraw,baseline),true);
});
