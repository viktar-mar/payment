import assert from 'node:assert/strict';
import test from 'node:test';
import {readFile} from 'node:fs/promises';
import {proveBillingRequiredTrialEvidence} from './stripe-billing-required-trial-evidence.mjs';

const id=n=>'00000000-0000-4000-8000-'+String(n).padStart(12,'0');
const failure=/^Error: billing_REQUIRED_TRIAL_EVIDENCE$/;
const expectedSql=await readFile(new URL('./stripe-billing-required-trial-proof.sql',import.meta.url),'utf8');

function fixture() {
 const configuration={selectedCase:'initial_required_trial',runId:id(1),expectedSource:'a'.repeat(40),
  approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'}};
 const scope={runId:id(1),expectedSource:'a'.repeat(40),siteKey:'s5a-'+id(1).replaceAll('-',''),accountId:id(2),
  siteId:id(3),customerId:id(4),productId:id(5),priceId:id(6),subscriptionId:id(7),operationId:id(8),createKeyHash:'b'.repeat(64),
  priceReference:'price_fixture'};
 const now=Math.floor(Date.now()/1000);
 const provider={...scope,mode:'test',externalAccountId:configuration.approvedBinding.externalAccountId,
  apiVersion:configuration.approvedBinding.apiVersion,customerReference:'cus_fixture',setupReference:'seti_fixture',
  methodReference:'pm_fixture',subscriptionReference:'sub_fixture',itemReference:'si_fixture',invoiceReference:'in_fixture',
  setupType:'setup_intent',setupUsage:'off_session',setupStatus:'succeeded',setupMethod:'pm_fixture',setupCustomerReference:'cus_fixture',
  setupSubscriptionId:scope.subscriptionId,setupOperationId:scope.operationId,
  methodObject:'payment_method',methodType:'card',methodCustomerReference:'cus_fixture',
  defaultPaymentMethod:'pm_fixture',subscriptionType:'subscription',subscriptionCustomerReference:'cus_fixture',
  subscriptionStatus:'trialing',testClock:null,itemType:'subscription_item',itemCount:1,
  itemSubscriptionReference:'sub_fixture',itemPriceReference:'price_fixture',
  invoiceType:'invoice',invoiceStatus:'paid',invoiceReason:'subscription_create',invoiceCustomerReference:'cus_fixture',
  invoiceSubscriptionReference:'sub_fixture',latestInvoiceReference:'in_fixture',amountDue:0,amountPaid:0,amountRemaining:0,invoiceCount:1,allocationCount:0,paymentIntentCount:0,chargeCount:0,
  trialStart:now-30,trialEnd:now+7*86400-30};
 const trialStart=new Date(provider.trialStart*1000),trialEnd=new Date(provider.trialEnd*1000);
 const row={run_id:scope.runId,billing_record_id:id(9),trial_started_event_id:id(10),revision:'2',
  trial_start:trialStart,trial_end:trialEnd};
 const stampRow=()=>{
  row.access_evaluated_at??=new Date();
  row.access_valid_until??=new Date(Math.min(row.trial_end.getTime(),row.access_evaluated_at.getTime()+300000));
  return row;
 };
 const expectedArgs=[scope.runId,scope.accountId,scope.siteId,scope.customerId,scope.productId,scope.priceId,scope.subscriptionId,
  scope.operationId,scope.createKeyHash,provider.customerReference,provider.setupReference,provider.methodReference,
  provider.subscriptionReference,provider.itemReference,provider.invoiceReference,trialStart.toISOString(),trialEnd.toISOString()];
 const calls=[],controller=new AbortController();
 const dependencies={
  async observeProvider(owned,active) {
   calls.push('provider');
   assert.deepEqual(owned,scope);
   assert.ok(Object.isFrozen(owned)&&Object.isFrozen(active));
   assert.equal(active.signal.aborted,false);
   provider.observedAt??=Date.now();
   return provider;
  },
  async readOnly(request,active) {
   calls.push('sql');
   assert.ok(Object.isFrozen(request)&&Object.isFrozen(request.args)&&Object.isFrozen(active));
   assert.equal(active.signal.aborted,false);
   assert.deepEqual(request,{mode:'read only',query:expectedSql,args:expectedArgs});
   return [stampRow()];
  },
 };
 const inputs={configuration,fixture:{resources:()=>scope}};
 const run=(limits={deadline:Date.now()+3000,signal:controller.signal})=>proveBillingRequiredTrialEvidence(inputs,limits,dependencies);
 return {configuration,scope,provider,row,calls,controller,dependencies,inputs,run,stampRow};
}

test('an independently observed original trial binds all 17 SQL arguments and returns only local proof',async()=>{
 const f=fixture();
 assert.deepEqual(await f.run(),{trialVerified:true,noCash:true,runId:f.scope.runId,billingRecordId:f.row.billing_record_id,
  trialStartedEventId:f.row.trial_started_event_id,revision:2,trialStart:f.row.trial_start.toISOString(),
  trialEnd:f.row.trial_end.toISOString(),accessEvaluatedAt:f.row.access_evaluated_at.toISOString(),
  accessValidUntil:f.row.access_valid_until.toISOString()});
 assert.deepEqual(f.calls,['provider','sql']);
});

for(const [name,patch] of [
 ['paid acceptance case',f=>{f.configuration.selectedCase='initial_success';}],
 ['wrong source',f=>{f.scope.expectedSource='c'.repeat(40);}],
 ['foreign Site key',f=>{f.scope.siteKey='s5a-foreign';}],
 ['absent CREATE digest',f=>{delete f.scope.createKeyHash;}],
])test('rejects '+name+' before either observer',async()=>{
 const f=fixture();
 patch(f);
 await assert.rejects(f.run(),failure);
 assert.deepEqual(f.calls,[]);
});

for(const [name,patch] of [
 ['different CREATE',{operationId:id(99)}],
 ['different Customer',{customerId:id(99)}],
 ['different Product',{productId:id(99)}],
 ['different price reference',{priceReference:'price_foreign'}],
 ['different API version',{apiVersion:'unowned'}],
 ['different provider account',{externalAccountId:'acct_foreign'}],
 ['live provider',{mode:'live'}],
 ['test clock',{testClock:'clock_fixture'}],
 ['incomplete setup',{setupStatus:'requires_payment_method'}],
 ['non-SetupIntent object',{setupType:'payment_intent'}],
 ['non-off-session setup',{setupUsage:'on_session'}],
 ['different PaymentMethod object',{methodObject:'source'}],
 ['non-card PaymentMethod',{methodType:'sepa_debit'}],
 ['different Subscription object',{subscriptionType:'subscription_schedule'}],
 ['different Subscription item object',{itemType:'invoice_item'}],
 ['different Invoice object',{invoiceType:'invoiceitem'}],
 ['setup belonging to another provider Customer',{setupCustomerReference:'cus_foreign'}],
 ['setup metadata on another local Subscription',{setupSubscriptionId:id(99)}],
 ['setup metadata on another original CREATE',{setupOperationId:id(99)}],
 ['wrong SetupIntent lane',{setupReference:'pi_fixture'}],
 ['sibling scoped method',{defaultPaymentMethod:'pm_foreign'}],
 ['foreign method Customer',{methodCustomerReference:'cus_foreign'}],
 ['foreign subscription Customer',{subscriptionCustomerReference:'cus_foreign'}],
 ['foreign item Subscription',{itemSubscriptionReference:'sub_foreign'}],
 ['different item Price',{itemPriceReference:'price_foreign'}],
 ['second subscription item',{itemCount:2}],
 ['foreign invoice Customer',{invoiceCustomerReference:'cus_foreign'}],
 ['foreign invoice Subscription',{invoiceSubscriptionReference:'sub_foreign'}],
 ['different latest invoice',{latestInvoiceReference:'in_foreign'}],
 ['invoice due',{amountDue:1}],
 ['invoice paid cash',{amountPaid:1}],
 ['second invoice',{invoiceCount:2}],
 ['invoice allocation',{allocationCount:1}],
 ['PaymentIntent',{paymentIntentCount:1}],
 ['charge',{chargeCount:1}],
 ['ended trial',{trialEnd:1}],
 ['old provider snapshot',{observedAt:1}],
 ['future provider snapshot',{observedAt:Date.now()+3600000}],
])test('rejects '+name+' before querying SQL',async()=>{
 const f=fixture();
 Object.assign(f.provider,patch);
 await assert.rejects(f.run(),failure);
 assert.deepEqual(f.calls,['provider']);
});

test('reports a fixed failure and releases no proof for missing or multiple SQL rows',async()=>{
 for(const count of [0,2]){
  const f=fixture();
  const read=f.dependencies.readOnly;
  f.dependencies.readOnly=async(...args)=>{const [row]=await read(...args);return Array(count).fill(row);};
  await assert.rejects(f.run(),failure);
  assert.deepEqual(f.calls,['provider','sql']);
 }
});

test('requires the original bounds, local event identity, revision and five-minute access ceiling',async()=>{
 for(const patch of [{trial_end:new Date()}, {trial_started_event_id:'foreign'}, {revision:'2.5'}, {access_valid_until:new Date()}]){
  const f=fixture();
  Object.assign(f.row,patch);
  await assert.rejects(f.run(),failure);
 }
});

test('the access lease is clamped to the trial boundary',async()=>{
 const f=fixture();
 const now=Math.floor(Date.now()/1000);
 f.provider.trialEnd=now+30;
 f.row.trial_end=new Date(f.provider.trialEnd*1000);
 f.row.access_valid_until=f.row.trial_end;
 const result=await proveBillingRequiredTrialEvidence(f.inputs,{deadline:Date.now()+2000},{
  observeProvider:f.dependencies.observeProvider,
  async readOnly({args}){assert.equal(args.at(-1),f.row.trial_end.toISOString());return [f.stampRow()];},
 });
 assert.equal(result.accessValidUntil,result.trialEnd);
});

for(const stage of ['observeProvider','readOnly'])test('abort at '+stage+' cannot release proof or start a later read',async()=>{
 const f=fixture();
 const started=Promise.withResolvers(),resume=Promise.withResolvers();
 const original=f.dependencies[stage];
 f.dependencies[stage]=async(...args)=>{
  const result=await original(...args);
  started.resolve();
  await resume.promise;
  return result;
 };
 const result=f.run();
 const refused=assert.rejects(result,failure);
 await started.promise;
 f.controller.abort();
 await refused;
 resume.resolve();
 await new Promise(resolve=>setImmediate(resolve));
 assert.deepEqual(f.calls,stage==='observeProvider'?['provider']:['provider','sql']);
});

for(const seconds of [-10,10])test('rejects SQL access sampled outside its own read interval ('+seconds+')',async()=>{
 const f=fixture();
 f.row.access_evaluated_at=new Date(Date.now()+seconds*1000);
 await assert.rejects(f.run(),failure);
 assert.deepEqual(f.calls,['provider','sql']);
});

test('a replaced original Subscription after provider observation cannot start SQL',async()=>{
 const f=fixture();
 const observe=f.dependencies.observeProvider;
 f.dependencies.observeProvider=async(...args)=>{
  const witness=await observe(...args);
  f.scope.subscriptionId=id(99);
  return witness;
 };
 await assert.rejects(f.run(),failure);
 assert.deepEqual(f.calls,['provider']);
});

test('a retired fixture after SQL cannot release a receipt',async()=>{
 const f=fixture();
 const read=f.dependencies.readOnly;
 f.dependencies.readOnly=async(...args)=>{
  const rows=await read(...args);
  f.inputs.fixture.resources=()=>undefined;
  return rows;
 };
 await assert.rejects(f.run(),failure);
 assert.deepEqual(f.calls,['provider','sql']);
});

test('replacing the owner while loading the query prevents the provider read',async()=>{
 const f=fixture();
 const resources=f.inputs.fixture.resources;
 f.inputs.fixture.resources=()=>{
  queueMicrotask(()=>{f.scope.operationId=id(99);});
  return resources();
 };
 await assert.rejects(f.run(),failure);
 assert.equal(f.scope.operationId,id(99));
 assert.deepEqual(f.calls,[]);
});

test('an old SQL witness cannot release an expired access lease',async()=>{
 const f=fixture();
 f.provider.trialStart-=3600;
 f.row.trial_start=new Date(f.provider.trialStart*1000);
 f.row.access_evaluated_at=new Date(Date.now()-301000);
 f.row.access_valid_until=new Date(f.row.access_evaluated_at.getTime()+300000);
 f.dependencies.readOnly=async({args})=>{
  f.calls.push('sql');
  assert.equal(args[15],f.row.trial_start.toISOString());
  return [f.row];
 };
 await assert.rejects(f.run(),failure);
 assert.deepEqual(f.calls,['provider','sql']);
});

test('a lease that was current in SQL cannot expire before proof returns',async context=>{
 const f=fixture();
 let tick=Date.now();
 context.mock.method(Date,'now',()=>tick);
 f.provider.trialEnd=Math.floor(tick/1000)+1;
 f.row.trial_end=new Date(1000*f.provider.trialEnd);
 f.dependencies.readOnly=async({args})=>{
  f.calls.push('sql');
  assert.equal(args[16],f.row.trial_end.toISOString());
  f.row.access_evaluated_at=new Date(tick);
  f.row.access_valid_until=f.row.trial_end;
  tick=f.row.trial_end.getTime();
  return [f.row];
 };
 await assert.rejects(f.run(),failure);
 assert.deepEqual(f.calls,['provider','sql']);
});
