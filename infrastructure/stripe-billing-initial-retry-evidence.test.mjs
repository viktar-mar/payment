import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {proveBillingInitialRetryEvidence} from './stripe-billing-initial-retry-evidence.mjs';

const id=n=>'00000000-1111-4444-8000-'+String(n).padStart(12,'0');
const scope={runId:id(1),expectedSource:'e'.repeat(40),accountId:id(2),siteId:id(3),customerId:id(4),subscriptionId:id(5),
  operationId:id(6),attemptId:id(7),productId:id(8),priceId:id(9),priceReference:'price_owned',createKeyHash:'a'.repeat(64),
  confirmKeyHash:'b'.repeat(64),siteKey:'s5a-'+id(1).replaceAll('-',''),endpoints:{demo:'http://127.0.0.1:18081'}};
const first={attemptId:scope.attemptId,confirmKeyHash:scope.confirmKeyHash};
const retry={attemptId:id(15),keyHash:'c'.repeat(64)},second={attemptId:retry.attemptId,confirmKeyHash:'d'.repeat(64)};
function setup(){
  const fields={customer_reference:'cus_owned',subscription_reference:'sub_owned',product_reference:'prod_owned',
    price_reference:'price_owned',invoice_reference:'in_owned',allocation_reference:'inpay_owned',intent_reference:'pi_owned',
    charge_reference:'ch_declined',request_id:'req_original',decline_code:'generic_decline',original:true,lineage:true};
  const declined={...fields,phase_proof:true,period_start:null,period_end:null};
  const common={site_id:scope.siteId,customer_id:scope.customerId,subscription_id:scope.subscriptionId,product_id:scope.productId};
  const payment={...common,schema_version:3,event_id:id(12),event_type:'payment.succeeded',payment_id:id(10),billing_record_id:id(11),
    payment_status:'SUCCEEDED',amount_minor:2000,currency:'EUR',billing_kind:'INITIAL',price_id:scope.priceId,price_version:1,
    period_start:'2026-11-01T00:00:00Z',period_end:'2026-12-01T00:00:00Z'};
  const lifecycle={...common,schema_version:4,event_id:id(13),event_type:'subscription.updated',state:'ACTIVE',access_allowed:true,
    billing_record_id:id(11),payment_id:null};
  const callbacks=[payment,lifecycle].map(payload=>({id:payload.event_id,type:payload.event_type,payload,delivered:true}));
  const recovered={...fields,phase_proof:true,journal:true,webhook:true,payment_id:id(10),billing_record_id:id(11),
    period_start:Date.parse(payment.period_start)/1000,period_end:Date.parse(payment.period_end)/1000,callbacks};
  const inspection={count:2,duplicates:0,events:callbacks.map(({id,type,payload})=>({event_id:id,event_type:type,count:1,
    sha256:createHash('sha256').update(JSON.stringify(Object.fromEntries(Object.keys(payload).sort().map(k=>[k,payload[k]])))).digest('hex')}))};
  let phase='declined',connections=0;
  const observed=[];
  const configuration={runId:scope.runId,expectedSource:scope.expectedSource,selectedCase:'initial_decline_retry'};
  const fixture={resources:()=>scope,inspectCallbacks:async()=>inspection};
  const bootstrap={withDatabaseCredentials:async body=>body({})};
  const cli={observeOwnedBillingInitialRetry:async(p,graph,receipt)=>{observed.push(p);return graph.intentReference==='pi_owned'&&receipt.chargeReference==='ch_declined';}};
  const connect=()=>{connections++;return {begin:async(mode,body)=>{assert.equal(mode,'read only');return body({
    unsafe:async(query,args)=>args?[phase==='declined'?declined:recovered]:[]});},end:async()=>{}};};
  const prove=(p,attempts)=>{phase=p;return proveBillingInitialRetryEvidence({configuration,fixture,bootstrap,cli,phase,attempts},
    {deadline:Date.now()+1200},{connect});};
  return {declined,recovered,inspection,observed,prove,connections:()=>connections};
}
test('only a verified first decline permits the verified same-intent successor',async()=>{
  const f=setup();assert.equal(await f.prove('declined',{first}),true);
  assert.equal(await f.prove('recovered',{first,retry,second}),true);assert.deepEqual(f.observed,['declined','recovered']);
});
test('missing prior decline and mismatched successor attempt stop before SQL or provider access',async()=>{
  const f=setup();await assert.rejects(f.prove('recovered',{first,retry,second}),/EVIDENCE/);
  assert.equal(f.connections(),0);
  const g=setup();await g.prove('declined',{first});
  await assert.rejects(g.prove('recovered',{first,retry,second:{...second,attemptId:id(42)}}),/EVIDENCE/);
  assert.deepEqual(g.observed,['declined']);assert.equal(g.connections(),1);
});
for(const [name,change] of [
  ['cash before retry',f=>f.declined.phase_proof=false],
  ['missing durable predecessor',f=>f.declined.lineage=false],
  ['missing initial request attribution',f=>f.declined.request_id=null],
])test('declined evidence denies '+name,async()=>{
  const f=setup();change(f);await assert.rejects(f.prove('declined',{first}),/EVIDENCE/);assert.deepEqual(f.observed,[]);
});
for(const [name,change] of [
  ['changed original decline charge',f=>f.recovered.charge_reference='ch_other'],
  ['changed original request',f=>f.recovered.request_id='req_other'],
  ['changed invoice',f=>f.recovered.invoice_reference='in_other'],
  ['missing journal',f=>f.recovered.journal=false],
  ['changed delivery digest',f=>f.inspection.events[0].sha256='0'.repeat(64)],
])test('settlement evidence denies '+name,async()=>{
  const f=setup();await f.prove('declined',{first});change(f);
  await assert.rejects(f.prove('recovered',{first,retry,second}),/EVIDENCE/);assert.deepEqual(f.observed,['declined']);
});
