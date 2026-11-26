import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import {createOptionalTrialEvidence} from './stripe-billing-optional-trial-evidence.mjs';

const id=n=>`00000000-0000-0000-0000-${String(n).padStart(12,'0')}`;
const account='acct_1UPiMpELswAd8tpR',apiVersion='2026-09-30.endive';
const trialStart=1770000000,trialEnd=trialStart+604800;
const resumeStart=trialEnd+1,resumeEnd=1773024001;
const original=Object.freeze({
 runId:id(1),expectedSource:'a'.repeat(40),siteId:id(2),siteKey:'s5a-'+id(1).replaceAll('-',''),
 customerId:id(3),accountId:id(4),productId:id(5),priceId:id(6),subscriptionId:id(7),
 createId:id(8),trialBillId:id(9),startedEventId:id(10),
 customerReference:'cus_trial',subscriptionReference:'sub_trial',itemReference:'si_trial',
 invoiceReference:'in_trial',productReference:'prod_trial',priceReference:'price_trial',
 clockReference:'clock_trial',trialStart,trialEnd,
});
const resume=Object.freeze({
 setupId:id(12),resumeId:id(13),resumeBillId:id(14),resumeInvoiceReference:'in_resume',
 setupReference:'seti_trial',methodReference:'pm_trial',resumeStart,resumeEnd,
});
const configuration={
 selectedCase:'optional_trial_pause_resume',runId:original.runId,expectedSource:original.expectedSource,
 approvedBinding:{externalAccountId:account,apiVersion},
};
const object=(type,id,data)=>({object:type,id,livemode:false,...data});
const list=(...data)=>({object:'list',has_more:false,data});
const instant=s=>new Date(s*1000);
const sql={};
for(const stage of ['pause','resume']){
 sql[stage]=await readFile(new URL(`./stripe-billing-optional-trial-${stage}-proof.sql`,import.meta.url),'utf8');
}

function fixture(options={}) {
 const calls=[];
 let phase='PAUSED',report;
 let owner={...original};
 const ownerMetadata={account_id:original.accountId,site_id:original.siteId,customer_id:original.customerId};
 const operationMetadata={...ownerMetadata,subscription_id:original.subscriptionId};
 const price=object('price','price_trial',{
  product:'prod_trial',active:true,unit_amount:2000,currency:'eur',type:'recurring',tax_behavior:'inclusive',
  recurring:{interval:'month',interval_count:1,usage_type:'licensed'},
  metadata:{local_price_id:original.priceId,local_price_version:'1'},
 });
 function stripe() {
  const paused=phase==='PAUSED',paid=phase==='PAID';
  const cash=paid?2000:0;
  const invoice=(id,initial)=>object('invoice',id,{
   status:initial||paid?'paid':'open',billing_reason:initial?'subscription_create':'subscription_cycle',
   customer:'cus_trial',test_clock:'clock_trial',currency:'eur',collection_method:'charge_automatically',
   parent:{type:'subscription_details',subscription_details:{subscription:'sub_trial'}},
   amount_due:initial?0:2000,amount_paid:initial?0:cash,amount_remaining:initial?0:2000-cash,
   total:initial?0:2000,subtotal:initial?0:2000,amount_paid_off_stripe:0,amount_overpaid:0,
   starting_balance:0,ending_balance:0,pre_payment_credit_notes_amount:0,post_payment_credit_notes_amount:0,
   discounts:[],total_discount_amounts:[],total_pretax_credit_amounts:[],total_taxes:[],
  });
  const line=(invoice,start,end,amount)=>object('line_item','il_'+invoice.replaceAll('_',''),{
   invoice,currency:'eur',quantity:1,amount,
   parent:{type:'subscription_item_details',subscription_item_details:{subscription:'sub_trial',subscription_item:'si_trial',proration:false}},
   pricing:{type:'price_details',price_details:{price:'price_trial',product:'prod_trial'}},
   period:{start,end},discounts:[],discount_amounts:[],pretax_credit_amounts:[],taxes:[],
  });
  const initial=invoice('in_trial',true),resumed=invoice('in_resume',false);
  const method=object('payment_method','pm_trial',{customer:'cus_trial',type:'card',card:{}});
  const allocation=object('invoice_payment','inpay_trial',{
   invoice:'in_resume',currency:'eur',is_default:true,amount_requested:2000,amount_paid:paid?2000:null,
   payment:{type:'payment_intent',payment_intent:'pi_trial'},status:paid?'paid':'open',
   status_transitions:{paid_at:paid?resumeStart:null,canceled_at:null},
  });
  const intent=object('payment_intent','pi_trial',{
   customer:'cus_trial',status:paid?'succeeded':'requires_confirmation',amount:2000,amount_received:cash,
   currency:'eur',capture_method:'automatic',payment_method_types:['card'],payment_method:paid?'pm_trial':null,latest_charge:paid?'ch_trial':null,
  });
  const charge=object('charge','ch_trial',{
   customer:'cus_trial',payment_intent:'pi_trial',payment_method:'pm_trial',
   amount:2000,amount_captured:2000,amount_refunded:0,currency:'eur',status:'succeeded',
   captured:true,paid:true,refunded:false,disputed:false,payment_method_details:{type:'card'},
  });
  return {
   '/v1/test_helpers/test_clocks/clock_trial':object('test_helpers.test_clock','clock_trial',{
    status:'ready',frozen_time:resumeStart,
   }),
   '/v1/customers/cus_trial':object('customer','cus_trial',{
    test_clock:'clock_trial',default_source:null,invoice_settings:{default_payment_method:null},metadata:ownerMetadata,
   }),
   '/v1/subscriptions/sub_trial':object('subscription','sub_trial',{
    status:paid?'active':'paused',customer:'cus_trial',currency:'eur',test_clock:'clock_trial',
    latest_invoice:paused?'in_trial':'in_resume',collection_method:'charge_automatically',billing_mode:{type:'classic'},
    pause_collection:null,default_source:null,default_payment_method:paused?null:'pm_trial',
    cancel_at:null,cancel_at_period_end:false,canceled_at:null,ended_at:null,schedule:null,pending_update:null,
    payment_settings:{save_default_payment_method:'on_subscription',payment_method_types:['card']},
    trial_start:trialStart,trial_end:trialEnd,trial_settings:{end_behavior:{missing_payment_method:'pause'}},
    metadata:{...operationMetadata,operation_id:original.createId},
    items:list({id:'si_trial',object:'subscription_item',subscription:'sub_trial',quantity:1,price,
     current_period_start:paid?resumeStart:trialStart,current_period_end:paid?resumeEnd:trialEnd}),
   }),
   '/v1/subscriptions':list(object('subscription','sub_trial',{customer:'cus_trial',test_clock:'clock_trial',status:paid?'active':'paused'})),
   '/v1/invoices':paused?list(initial):list(initial,resumed),
   '/v1/invoices/in_trial':initial,
   '/v1/invoices/in_trial/lines':list(line('in_trial',trialStart,trialEnd,0)),
   '/v1/invoices/in_resume':resumed,
   '/v1/invoices/in_resume/lines':list(line('in_resume',resumeStart,resumeEnd,2000)),
   '/v1/invoice_payments':list(allocation),
   '/v1/setup_intents/seti_trial':object('setup_intent','seti_trial',{
    customer:'cus_trial',status:'succeeded',usage:'off_session',payment_method:'pm_trial',payment_method_types:['card'],
    metadata:{...operationMetadata,operation_id:resume.setupId},
   }),
   '/v1/payment_methods':paused?list():list(method),
   '/v1/payment_intents':paused?list():list(intent),
   '/v1/charges':paid?list(charge):list(),
   '/v1/refunds':list(),
  };
 }
 const readProvider=async request=>{
  calls.push(request);
  assert.equal(Object.isFrozen(request),true);
  assert.equal(Object.isFrozen(request.parameters),true);
  assert.deepEqual([request.method,request.runId,request.expectedSource],['GET',original.runId,original.expectedSource]);
  let response=stripe()[request.path];
  assert.ok(response,request.path);
  if(request.path==='/v1/invoice_payments'&&request.parameters.invoice==='in_trial')response=list();
  const envelope={externalAccountId:account,apiVersion,livemode:false,body:structuredClone(response)};
  if(!options.phase||options.phase===phase)options.provider?.(request,envelope);
  if(options.replace===calls.length)owner={...owner,runId:id(999)};
  return envelope;
 };
 const readOnly=async request=>{
  calls.push(request);
  assert.equal(Object.isFrozen(request.args),true);
  assert.equal(request.mode,'read only');
  const initial=[original.siteId,original.siteKey,original.customerId,original.accountId,
   original.productId,original.priceId,original.subscriptionId,original.createId,original.trialBillId,
   original.startedEventId,original.subscriptionReference,original.invoiceReference,
   instant(trialStart).toISOString(),instant(trialEnd).toISOString()];
  if(phase==='PAUSED'){
   assert.equal(request.query,sql.pause);
   assert.deepEqual(request.args,initial);
   report={external_account_id:account,item_reference:'si_trial',subscription_id:original.subscriptionId,billing_record_id:original.trialBillId,
    trial_started_event_id:original.startedEventId,paused_event_id:id(11),revision:'3',trial_end:instant(trialEnd),paused_at:instant(trialEnd)};
  }else{
   assert.equal(request.query,sql.resume);
   assert.deepEqual(request.args,[...initial,id(11),resume.setupId,resume.resumeId,resume.resumeInvoiceReference,
    instant(resumeStart).toISOString(),instant(resumeEnd).toISOString(),resume.resumeBillId,
    phase==='PAID'?'pi_trial':null,phase]);
   const paid=phase==='PAID';
   report={external_account_id:account,item_reference:'si_trial',subscription_id:original.subscriptionId,trial_bill_id:original.trialBillId,
    resume_bill_id:resume.resumeBillId,setup_reference:resume.setupReference,method_reference:resume.methodReference,
    payment_id:paid?id(15):null,cash_event_id:paid?id(16):null,period_event_id:paid?id(17):null,cash_occurred_at:paid?instant(resumeStart):null};
  }
  return options.database?.(phase,report)??[report];
 };
 const proof=createOptionalTrialEvidence(configuration,{resources:()=>owner},{readOnly,readProvider});
 const limits=()=>({deadline:Date.now()+3000});
 return {
  calls,
  async pause(extra={}) {phase='PAUSED';return proof.verifyPause({frozenTime:resumeStart,...extra},limits());},
  async pending(info=resume) {phase='PENDING';return proof.verifyPendingResume(info,limits());},
  async paid() {phase='PAID';return proof.verifyPaidResume(limits());},
 };
}

const failure=/^Error: billing_OPTIONAL_TRIAL_EVIDENCE$/;
test('proves a single paused trial and the exact same eventual paid resumption using only bounded reads',async()=>{
 const f=fixture();
 const paused=await f.pause();
 assert.equal(paused.pausedEventId,id(11));
 assert.equal(paused.trialBillId,original.trialBillId);
 const pending=await f.pending();
 assert.equal(pending.resumeBillId,resume.resumeBillId);
 assert.equal(pending.resumeInvoiceReference,'in_resume');
 assert.equal(pending.paymentId,null);
 const paid=await f.paid();
 assert.equal(paid.resumeBillId,pending.resumeBillId);
 assert.equal(paid.invoiceReference,paused.invoiceReference);
 assert.equal(paid.paymentId,id(15));
 assert.equal(paid.cashEventId,id(16));
 assert.equal(paid.periodEventId,id(17));
 assert.deepEqual(f.calls.filter(c=>c.query).map(c=>c.args.length),[14,23,23]);
 const allocations=f.calls.filter(c=>c.path==='/v1/invoice_payments');
 assert.ok(allocations.some(c=>c.parameters['payment[payment_intent]']==='pi_trial'));
 for(const path of ['/v1/invoices','/v1/subscriptions','/v1/payment_methods','/v1/payment_intents','/v1/charges']){
  const reads=f.calls.filter(c=>c.path===path);
  assert.equal(reads.length,3,path);
  assert.ok(reads.every(c=>c.parameters.customer===original.customerReference),path);
 }
 assert.ok(f.calls.every(c=>c.query||c.method==='GET'));
 assert.ok(Object.isFrozen(paid));
 assert.doesNotMatch(JSON.stringify(paid),/card|client_secret|account_id/);
});

for(const [name,path,mutate,phase='PAUSED'] of [
 ['live scope','/v1/subscriptions/sub_trial',e=>e.livemode=true],
 ['foreign account','/v1/subscriptions/sub_trial',e=>e.externalAccountId='acct_other'],
 ['wrong version','/v1/subscriptions/sub_trial',e=>e.apiVersion='other'],
 ['changing clock','/v1/test_helpers/test_clocks/clock_trial',e=>e.body.status='advancing'],
 ['foreign clock','/v1/customers/cus_trial',e=>e.body.test_clock='clock_other'],
 ['customer default','/v1/customers/cus_trial',e=>e.body.invoice_settings.default_payment_method='pm_other'],
 ['collection pause masquerading as actual pause','/v1/subscriptions/sub_trial',e=>e.body.pause_collection={behavior:'void'}],
 ['method attached before pause','/v1/subscriptions/sub_trial',e=>e.body.default_payment_method='pm_trial'],
 ['foreign price','/v1/subscriptions/sub_trial',e=>e.body.items.data[0].price.id='price_other'],
 ['non-default attached card','/v1/payment_methods',e=>e.body.data=[{}]],
 ['sibling subscription','/v1/subscriptions',e=>e.body.data.push({})],
 ['invoice includes shipping','/v1/invoices/in_trial',e=>e.body.shipping_details={}],
 ['discount attached to the line','/v1/invoices/in_trial/lines',e=>e.body.data[0].discounts=['di_foreign']],
 ['another invoice','/v1/invoices',e=>e.body.has_more=true],
 ['old invoice paid out of band','/v1/invoices/in_trial',e=>e.body.amount_paid_off_stripe=2000],
 ['foreign original item','/v1/invoices/in_trial/lines',e=>e.body.data[0].parent.subscription_item_details.subscription_item='si_other'],
 ['original trial cash','/v1/charges',e=>e.body.data=[{}]],
 ['method setup operation mismatch','/v1/setup_intents/seti_trial',e=>e.body.metadata.operation_id=id(999),'PENDING'],
 ['premature resumed access','/v1/subscriptions/sub_trial',e=>e.body.status='active','PENDING'],
 ['wrong payment after resume','/v1/invoice_payments',e=>{if(e.body.data.length)e.body.data[0].payment.payment_intent='pi_other';},'PAID'],
 ['manual-capture intent','/v1/payment_intents',e=>e.body.data[0].capture_method='manual','PAID'],
 ['paid item period differs from the bill','/v1/subscriptions/sub_trial',e=>e.body.items.data[0].current_period_end=resumeEnd+1,'PAID'],
 ['disputed capture','/v1/charges',e=>e.body.data[0].disputed=true,'PAID'],
 ['refunded charge','/v1/charges',e=>e.body.data[0].amount_refunded=1,'PAID'],
 ['foreign charge','/v1/charges',e=>e.body.data[0].payment_intent='pi_other','PAID'],
 ['refund found','/v1/refunds',e=>e.body.data=[{}],'PAID'],
]){
 test(`rejects ${name} before the corresponding SQL proof`,async()=>{
  const f=fixture({phase,provider:(request,envelope)=>{if(request.path===path)mutate(envelope);}});
  if(phase!=='PAUSED')await f.pause();
  if(phase==='PAID')await f.pending();
  const before=f.calls.filter(c=>c.query).length;
  await assert.rejects(phase==='PAUSED'?f.pause():phase==='PENDING'?f.pending():f.paid(),failure);
  assert.equal(f.calls.filter(c=>c.query).length,before);
  const stopped=f.calls.length;
  await assert.rejects(f.pause(),failure);
  assert.equal(f.calls.length,stopped);
 });
}

test('rejects omitted pause, replaced owner, and missing or mismatched local proof',async()=>{
 const unordered=fixture();
 await assert.rejects(unordered.pending(),failure);
 assert.equal(unordered.calls.length,0);
 const replaced=fixture({replace:2});
 await assert.rejects(replaced.pause(),failure);
 assert.equal(replaced.calls.length,2);
 const wrongSetup=fixture({database:(phase,row)=>[{...row,method_reference:phase==='PENDING'?'pm_other':row.method_reference}]});
 await wrongSetup.pause();
 await assert.rejects(wrongSetup.pending(),failure);
 const missing=fixture({database:()=>[]});
 await assert.rejects(missing.pause(),failure);
});

test('rejects a second concurrent observation without additional reads',async()=>{
 const f=fixture();
 const pause=f.pause();
 await assert.rejects(f.pause(),failure);
 await assert.rejects(pause,failure);
 assert.ok(f.calls.length<=1);
});

test('rejects a foreign reverse allocation even when the original invoice allocation matches',async()=>{
 const f=fixture({
  phase:'PAID',
  provider:(request,envelope)=>{
   if(request.parameters['payment[payment_intent]']){
    envelope.body.data[0].invoice='in_foreign';
   }
  },
 });
 await f.pause();
 await f.pending();
 await assert.rejects(f.paid(),failure);
 assert.equal(f.calls.filter(c=>c.query).length,2);
});

test('rejects an unrelated local account with the original subscription and events',async()=>{
 const f=fixture({database:(phase,row)=>[{...row,external_account_id:'acct_foreign'}]});
 await assert.rejects(f.pause(),failure);
 assert.equal(f.calls.filter(c=>c.query).length,1);
});

test('an abort stops later reads and transport failures expose only the safe evidence code',async()=>{
 const controller=new AbortController();
 const observed=[];
 const dependencies={
  readProvider:async(request,limits)=>{
   observed.push(request);
   assert.equal(limits.signal.aborted,false);
   controller.abort();
   throw new Error('credential-shaped private response');
  },
  readOnly:()=>assert.fail('SQL attempted after abort'),
 };
 const proof=createOptionalTrialEvidence(configuration,{resources:()=>original},dependencies);
 await assert.rejects(proof.verifyPause({frozenTime:resumeStart},{
  deadline:Date.now()+3000,signal:controller.signal,
 }),failure);
 assert.equal(observed.length,1);
 await assert.rejects(proof.verifyPause({frozenTime:resumeStart},{deadline:Date.now()+3000}),failure);
 assert.equal(observed.length,1);
});

test('matches original item, pause time and cash occurrence across both evidence sources',async()=>{
 const cases=[
  ['PAUSED',{item_reference:'si_foreign'}],
  ['PAUSED',{paused_at:instant(resumeStart+1)}],
  ['PENDING',{item_reference:'si_foreign'}],
  ['PAID',{cash_occurred_at:instant(resumeStart+1)}],
 ];
 for(const [stage,replacement] of cases){
  const f=fixture({database:(phase,row)=>[{...row,...(phase===stage?replacement:{})}]});
  if(stage==='PAUSED')await assert.rejects(f.pause(),failure);
  else{
   await f.pause();
   if(stage==='PENDING')await assert.rejects(f.pending(),failure);
   else{
    await f.pending();
    await assert.rejects(f.paid(),failure);
   }
  }
 }
});

test('a clock that moves mid-proof prevents a checkpoint from being issued',async()=>{
 let clockReads=0;
 const f=fixture({
  provider:(request,envelope)=>{
   if(request.path.startsWith('/v1/test_helpers/test_clocks/')&&++clockReads===2){
    envelope.body.frozen_time+=1;
   }
  },
 });
 await assert.rejects(f.pause(),failure);
 assert.equal(clockReads,2);
});

test('rejected status in a customer list cannot be hidden by a nominal direct response',async()=>{
 for(const path of ['/v1/subscriptions','/v1/invoices']){
  const f=fixture({
   provider:(request,envelope)=>{
    if(request.path===path)envelope.body.data[0].status='unpaid';
   },
  });
  await assert.rejects(f.pause(),failure);
  assert.ok(f.calls.every(c=>!c.query));
 }
});
