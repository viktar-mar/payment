import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {createBillingInvoiceWebhookEvidence} from './stripe-billing-invoice-webhook-evidence.mjs';

const id=n=>('00000000'+n).slice(-8)+'-1234-4234-8123-000000000000';
const limits=()=>({deadline:Date.now()+5000,signal:new AbortController().signal});
function setup(mutate=()=>{}) {
  const scope={runId:id(1),expectedSource:'a'.repeat(40),accountId:id(2),siteId:id(3),customerId:id(4),subscriptionId:id(5),
    operationId:id(6),attemptId:id(7),productId:id(8),priceId:id(9),createKeyHash:'a'.repeat(64),confirmKeyHash:'b'.repeat(64),
    siteKey:'s5a-'+id(1).replaceAll('-',''),priceReference:'price_owned',endpoints:{demo:'http://127.0.0.1:6041'}};
  const references={invoiceReference:'in_owned',subscriptionReference:'sub_owned',customerReference:'cus_owned'};
  const receipts={finalized:{...references,eventId:'evt_Finalized',created:100},paid:{...references,eventId:'evt_Paid',created:102}};
  const operation={operationId:id(15),keyHash:'c'.repeat(64)};
  const phases=[], captured=[];
  let row, portalOpen=false;
  function dbRow(stage) {
    const settled=['reconciled','paid','finalized'].includes(stage);
    const common={site_id:scope.siteId,customer_id:scope.customerId,subscription_id:scope.subscriptionId,product_id:scope.productId};
    const payloads=[{...common,schema_version:3,event_id:id(12),event_type:'payment.succeeded',payment_status:'SUCCEEDED',
      payment_id:id(10),billing_record_id:id(11),amount_minor:2000,currency:'EUR',billing_kind:'INITIAL',price_id:scope.priceId,
      price_version:1,period_start:new Date(100000).toISOString(),period_end:new Date(200000).toISOString()},
      {...common,schema_version:4,event_id:id(13),event_type:'subscription.updated',state:'ACTIVE',access_allowed:true,
      billing_record_id:id(11),payment_id:null,revision:2}];
    const kinds=stage==='finalized'?['paid','finalized']:stage==='paid'?['paid']:[];
    return {original:true,phase_proof:true,pending:false,invoice_reference:'in_owned',subscription_reference:'sub_owned',customer_reference:'cus_owned',
      intent_reference:'pi_owned',price_reference:'price_owned',product_reference:'prod_owned',allocation_reference:'inpay_owned',
      period_start:settled?100:null,period_end:settled?200:null,payment_id:settled?id(10):null,billing_record_id:settled?id(11):null,
      payment_event_id:settled?id(12):null,period_event_id:settled?id(13):null,subscription_revision:settled?2:0,
      create_operation_id:scope.operationId,attempt_id:scope.attemptId,reconcile_operation_id:settled?operation.operationId:null,
      callbacks:settled?payloads.map((payload,n)=>({id:payload.event_id,type:payload.event_type,payload,delivered:true,deliveryId:id(20+n)})):[],
      inbox:kinds.map(kind=>({id:id(kind==='paid'?31:32),eventId:receipts[kind].eventId,type:'invoice.'+kind,occurredAt:receipts[kind].created,
        normalizedType:'SUBSCRIPTION_INVOICE_CHANGED',receivedAt:kind==='paid'?'2026-11-22T18:02:01Z':'2026-11-22T18:02:02Z',status:'PROCESSED'}))};
  }
  const inspect=()=>{assert.equal(portalOpen,false);return {duplicates:0,count:row.callbacks.length,events:row.callbacks.map(c=>({event_id:c.id,event_type:c.type,
    count:1,sha256:createHash('sha256').update(JSON.stringify(Object.fromEntries(Object.keys(c.payload).sort().map(k=>[k,c.payload[k]])))).digest('hex')}))}};
  const fixture={resources:()=>scope,inspectCallbacks:async()=>inspect()};
  const inputs={configuration:{...scope,selectedCase:'initial_missed_reordered'},fixture,
    bootstrap:{withDatabaseCredentials:async cb=>{portalOpen=true;try{return await cb({});}finally{portalOpen=false;}}},
    cli:{observeOwnedBillingCapturedInvoice:async graph=>{assert.equal(portalOpen,false);assert.equal(graph.intentReference,'pi_owned');captured.push(graph);return true;}}};
  const connect=()=>({begin:async(mode,cb)=>{assert.equal(mode,'read only');return cb({unsafe:async(sql,params)=>{
    if(!params)return [];const stage=params[12];phases.push(stage);row=dbRow(stage);mutate(stage,row);return [row];}});},end:async()=>{}});
  const create=()=>createBillingInvoiceWebhookEvidence(inputs,{connect});
  return {subject:create(),create,inputs,scope,operation,receipts,references,phases,captured};
}
async function toReconcile(f) {
  assert.deepEqual(await f.subject.beforeConfirm(limits()),f.references);
  assert.equal(await f.subject.beforeReconcile(f.receipts,limits()),true);
}
async function all(f) {
  await toReconcile(f);
  assert.equal(await f.subject.reconciled(f.operation,limits()),true);
  assert.equal(await f.subject.delivered('paid',limits()),true);
  assert.equal(await f.subject.delivered('finalized',limits()),true);
}
test('one scoped reconciliation and two original deliveries preserve cash, journal and callback identities',async()=>{
  const f=setup();await all(f);assert.deepEqual(f.phases,['beforeConfirm','beforeReconcile','reconciled','paid','finalized']);
  assert.equal(f.captured.length,2);
  await assert.rejects(f.subject.delivered('finalized',limits()));
});
for(const [name,change] of [
 ['lost source',r=>r.original=false],['foreign intent',r=>r.intent_reference='pi_other'],
 ['cash replaced',r=>r.payment_id=id(88)],['lost bill',r=>r.billing_record_id=null],
 ['period journal replaced',r=>r.period_event_id=id(75)],['cash journal replaced',r=>r.payment_event_id=id(74)],
 ['delivery replaced',r=>r.callbacks[0].deliveryId=id(88)],['canonical payload changed',r=>r.callbacks[1].payload.revision++],
 ['changed revision',r=>r.subscription_revision++],['receipt changed',r=>r.inbox[0].eventId='evt_Other'],
 ['receipt is unprocessed',r=>r.inbox[0].status='RECEIVED'],['receipt time changed',r=>r.inbox[0].occurredAt++],
 ['receipt consumed twice',r=>r.inbox.push({...r.inbox[0]})],['invalid phase',r=>r.phase_proof=false]
])test('late notification cannot replace original evidence: '+name,async()=>{
  const f=setup((stage,row)=>{if(stage==='finalized')change(row);});
  await assert.rejects(all(f));assert.equal(f.captured.length,1);await assert.rejects(f.subject.delivered('finalized',limits()));
});
test('receipt identifiers, original invoice and event order are checked before reconciliation',async()=>{
  for(const mutate of [r=>r.paid.created=r.finalized.created,r=>r.finalized.eventId=r.paid.eventId,r=>r.paid.customerReference='cus_other']){
    const f=setup();await f.subject.beforeConfirm(limits());mutate(f.receipts);
    await assert.rejects(f.subject.beforeReconcile(f.receipts,limits()));assert.deepEqual(f.phases,['beforeConfirm']);
  }
});
test('a previously recorded unpaid bill must be the same invoice and period that receives the payment',async()=>{
  for(const replaced of [false,true]){
    const f=setup((stage,row)=>{
      if(stage==='beforeReconcile'){
        row.billing_record_id=replaced?id(77):id(11);
        row.period_start=100;row.period_end=200;
      }
    });
    if(replaced)await assert.rejects(all(f));else await all(f);
    assert.equal(f.captured[0].periodStart,100);
  }
});
test('an earlier phase cannot be skipped, replayed or bound to another fixture',async()=>{
  const f=setup();
  await assert.rejects(f.subject.reconciled(f.operation,limits()));
  assert.deepEqual(f.phases,[]);
  const g=setup();
  assert.throws(()=>g.create());await g.subject.beforeConfirm(limits());
  g.scope.siteId=id(87);await assert.rejects(g.subject.beforeReconcile(g.receipts,limits()));
  assert.deepEqual(g.phases,['beforeConfirm']);
});
test('only an expected processing receipt can be polled until the committed evidence arrives',async()=>{
  let reads=0;
  const f=setup((stage,row)=>{
    if(stage==='paid' && ++reads===1){
      row.phase_proof=false;
      row.pending=true;
    }
  });
  await all(f);
  assert.equal(reads,2);
  const preCash=setup((stage,row)=>{
    if(stage==='beforeReconcile'){
      row.phase_proof=false;
      row.pending=true;
    }
  });
  await assert.rejects(toReconcile(preCash));
  assert.deepEqual(preCash.phases,['beforeConfirm','beforeReconcile']);
  assert.equal(preCash.captured.length,0);
});
test('a late provider read after cancellation cannot authorize settlement or disclose its failure',async()=>{
  const f=setup(),controller=new AbortController();
  await f.subject.beforeConfirm(limits());
  let announce,release;
  const observed=new Promise(resolve=>announce=resolve);
  f.inputs.cli.observeOwnedBillingCapturedInvoice=async()=>{
    announce();
    return new Promise(resolve=>release=resolve);
  };
  const checking=f.subject.beforeReconcile(f.receipts,{...limits(),signal:controller.signal});
  await observed;
  controller.abort();
  await assert.rejects(checking,/^Error: billing_ABORTED$/);
  release(true);
  await new Promise(resolve=>setImmediate(resolve));
  await assert.rejects(f.subject.reconciled(f.operation,limits()));
  assert.deepEqual(f.phases,['beforeConfirm','beforeReconcile']);
});
test('a failed provider observation publishes only the evidence error and consumes the phase',async()=>{
  const f=setup();
  f.inputs.cli.observeOwnedBillingCapturedInvoice=async()=>{throw new Error('synthetic-private-diagnostic');};
  await assert.rejects(toReconcile(f),/^Error: billing_EVIDENCE$/);
  await assert.rejects(f.subject.beforeReconcile(f.receipts,limits()),/^Error: billing_EVIDENCE$/);
  assert.deepEqual(f.phases,['beforeConfirm','beforeReconcile']);
});
test('a callback observed before reconciliation must belong to the unpaid subscription creation',async()=>{
  const f=setup();
  await f.subject.beforeConfirm(limits());
  f.inputs.fixture.inspectCallbacks=async()=>({count:1,duplicates:0,events:[
    {event_id:id(12),event_type:'payment.succeeded',count:1,sha256:'a'.repeat(64)}
  ]});
  await assert.rejects(f.subject.beforeReconcile(f.receipts,limits()),/^Error: billing_EVIDENCE$/);
  assert.equal(f.captured.length,0);
});
test('the receiver can acknowledge the creation callback before its delivery transaction commits',async()=>{
  const f=setup((stage,row)=>{
    if(stage!=='beforeReconcile')return;
    row.callbacks.push({id:id(18),type:'subscription.updated',deliveryId:null,delivered:false,
      payload:{schema_version:4,event_id:id(18),event_type:'subscription.updated',subscription_id:f.scope.subscriptionId,
        site_id:f.scope.siteId,customer_id:f.scope.customerId,product_id:f.scope.productId,state:'INCOMPLETE',
        access_allowed:false,billing_record_id:null,payment_id:null}});
  });
  await toReconcile(f);
  assert.equal(f.captured.length,1);
});
