import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import {proveOwnedBillingRequiredTrialEvidence} from './stripe-billing-required-trial-evidence.mjs';

const seedSql=await readFile(new URL('./stripe-billing-required-trial-seed.sql',import.meta.url),'utf8');
const proofSql=await readFile(new URL('./stripe-billing-required-trial-proof.sql',import.meta.url),'utf8');
const id=n=>'00000000-0000-4000-8000-'+String(n).padStart(12,'0');
const error=/^Error: billing_REQUIRED_TRIAL_EVIDENCE$/;
const approvedBinding={externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'};
const customer='/v1/customers/cus_fixture',setup='/v1/setup_intents/seti_fixture';
const method='/v1/payment_methods/pm_fixture',subscription='/v1/subscriptions/sub_fixture',invoice='/v1/invoices/in_fixture';
const list=(...data)=>({object:'list',has_more:false,data});
const object=(id,type,fields)=>({id,object:type,livemode:false,...fields});

function fixture(){
 const scope={runId:id(1),expectedSource:'a'.repeat(40),siteKey:'s5a-'+id(1).replaceAll('-',''),
  accountId:id(2),siteId:id(3),customerId:id(4),productId:id(5),priceId:id(6),subscriptionId:id(7),
  operationId:id(8),createKeyHash:'b'.repeat(64),priceReference:'price_fixture'};
 const configuration={selectedCase:'initial_required_trial',runId:scope.runId,expectedSource:scope.expectedSource,approvedBinding};
 const inputs={configuration,fixture:{resources:()=>scope}};
 const seedArgs=[id(1),id(2),id(3),id(4),id(5),id(6),id(7),id(8),scope.createKeyHash];
 const start=Math.floor(Date.now()/1000)-30,end=start+7*86400;
 const seed={customer_reference:'cus_fixture',setup_reference:'seti_fixture',method_reference:'pm_fixture',
  subscription_reference:'sub_fixture',item_reference:'si_fixture',invoice_reference:'in_fixture',
  trial_start:new Date(start*1000),trial_end:new Date(end*1000),provider_price_id:'price_fixture',provider_product_id:'prod_fixture'};
 const ownership={account_id:id(2),site_id:id(3),customer_id:id(4)};
 const metadata={...ownership,subscription_id:id(7),operation_id:id(8)};
 const remoteInvoice=object('in_fixture','invoice',{status:'paid',currency:'eur',billing_reason:'subscription_create',
  customer:'cus_fixture',test_clock:null,parent:{type:'subscription_details',subscription_details:{subscription:'sub_fixture'}},
  collection_method:'charge_automatically',amount_due:0,amount_paid:0,amount_remaining:0,total:0,subtotal:0,
  amount_paid_off_stripe:0,amount_overpaid:0,starting_balance:0,ending_balance:0,
  pre_payment_credit_notes_amount:0,post_payment_credit_notes_amount:0,
  discounts:[],total_discount_amounts:[],total_pretax_credit_amounts:[],
  lines:list(object('il_fixture','line_item',{invoice:'in_fixture',amount:0,quantity:1,currency:'eur',
   parent:{type:'subscription_item_details',subscription_item_details:{subscription:'sub_fixture',subscription_item:'si_fixture',proration:false}},
   pricing:{type:'price_details',price_details:{price:'price_fixture',product:'prod_fixture'}},
   period:{start,end},discount_amounts:[],pretax_credit_amounts:[]}))});
 const bodies=new Map([
  [customer,object('cus_fixture','customer',{test_clock:null,metadata:ownership,deleted:false})],
  [setup,object('seti_fixture','setup_intent',{customer:'cus_fixture',payment_method:'pm_fixture',status:'succeeded',
   usage:'off_session',payment_method_types:['card'],metadata})],
  [method,object('pm_fixture','payment_method',{customer:'cus_fixture',type:'card',card:{}})],
  [subscription,object('sub_fixture','subscription',{customer:'cus_fixture',status:'trialing',currency:'eur',
   billing_mode:{type:'classic'},collection_method:'charge_automatically',test_clock:null,latest_invoice:'in_fixture',metadata,
   pause_collection:null,cancel_at:null,cancel_at_period_end:false,schedule:null,pending_update:null,ended_at:null,
   default_payment_method:'pm_fixture',trial_start:start,trial_end:end,trial_settings:{end_behavior:{missing_payment_method:'pause'}},
   items:list({id:'si_fixture',object:'subscription_item',subscription:'sub_fixture',quantity:1,
    current_period_start:start,current_period_end:end,price:object('price_fixture','price',{product:'prod_fixture',active:true,
     metadata:{local_price_id:id(6),local_price_version:'1'},unit_amount:2000,currency:'eur',type:'recurring',
     tax_behavior:'inclusive',recurring:{interval:'month',interval_count:1,usage_type:'licensed'}})})})],
  [invoice,remoteInvoice],
  ['/v1/invoices',list(remoteInvoice)],
  ['/v1/invoice_payments',list()],
  ['/v1/payment_intents',list()],
  ['/v1/charges',list()],
 ]);
 const calls=[],controller=new AbortController();
 const dependencies={
  async readOnly(request,limits){
   calls.push(request.query===seedSql?'seed':'proof');
   assert.equal(request.mode,'read only');
   assert.ok(Object.isFrozen(request)&&Object.isFrozen(request.args));
   assert.equal(limits.signal.aborted,false);
   if(request.query===seedSql){assert.deepEqual(request.args,seedArgs);return [seed];}
   assert.equal(request.query,proofSql);
   assert.deepEqual(request.args,[...seedArgs,...Object.values(seed).slice(0,6),seed.trial_start.toISOString(),seed.trial_end.toISOString()]);
   const access=new Date();
   return [{run_id:id(1),billing_record_id:id(9),trial_started_event_id:id(10),revision:1,
    trial_start:seed.trial_start,trial_end:seed.trial_end,access_evaluated_at:access,access_valid_until:new Date(access.getTime()+300000)}];
  },
  async readProvider(request,limits){
   const {path}=request;
   calls.push(path);
   assert.equal(request.method,'GET');
   assert.equal(request.runId,scope.runId);
   assert.equal(request.expectedSource,scope.expectedSource);
   assert.ok(Object.isFrozen(request)&&Object.isFrozen(request.parameters));
   assert.equal(limits.signal.aborted,false);
   const expected={
    [invoice]:{'expand[]':'amount_paid_off_stripe'},'/v1/invoices':{subscription:'sub_fixture',limit:2},
    '/v1/invoice_payments':{invoice:'in_fixture',limit:1},'/v1/payment_intents':{customer:'cus_fixture',limit:1},
    '/v1/charges':{customer:'cus_fixture',limit:1},
   };
   assert.deepEqual(request.parameters,expected[path]??{});
   assert.ok(bodies.has(path));
   return {...approvedBinding,livemode:false,body:bodies.get(path)};
  },
 };
 const run=()=>proveOwnedBillingRequiredTrialEvidence(inputs,{deadline:Date.now()+3000,signal:controller.signal},dependencies);
 return {run,inputs,scope,seed,bodies,calls,controller,dependencies};
}

const reads=['seed',customer,setup,method,subscription,invoice,'/v1/invoices','/v1/invoice_payments','/v1/payment_intents','/v1/charges'];

test('reads only the seeded Stripe graph and proves the same trial without cash',async()=>{
 const f=fixture();
 const result=await f.run();
 assert.deepEqual(f.calls,[...reads,'proof']);
 assert.equal(result.trialVerified,true);
 assert.equal(result.noCash,true);
 assert.equal(result.billingRecordId,id(9));
 assert.doesNotMatch(JSON.stringify(result),/cus_fixture|seti_fixture|pm_fixture|sub_fixture|in_fixture/);
});

for(const [name,change,last] of [
 ['noncanonical seed',f=>{f.seed.setup_reference='seti_fixture/confirm';},'seed'],
 ['seed Price differs from the provisioned Price',f=>{f.seed.provider_price_id='price_other';},'seed'],
 ['live Customer',f=>{f.bodies.get(customer).livemode=true;},customer],
 ['foreign Customer site',f=>{f.bodies.get(customer).metadata.site_id=id(99);},customer],
 ['foreign setup operation',f=>{f.bodies.get(setup).metadata.operation_id=id(99);},setup],
 ['foreign setup Customer',f=>{f.bodies.get(setup).customer='cus_other';},setup],
 ['failed setup',f=>{f.bodies.get(setup).status='requires_action';},setup],
 ['foreign card',f=>{f.bodies.get(method).customer='cus_other';},method],
 ['Customer default substituted for subscription method',f=>{f.bodies.get(subscription).default_payment_method=null;},subscription],
 ['different Stripe item',f=>{f.bodies.get(subscription).items.data[0].id='si_other';},subscription],
 ['deleted Stripe item',f=>{f.bodies.get(subscription).items.data[0].deleted=true;},subscription],
 ['collection paused while status is trialing',f=>{f.bodies.get(subscription).pause_collection={behavior:'void'};},subscription],
 ['cancellation scheduled while status is trialing',f=>{f.bodies.get(subscription).cancel_at_period_end=true;},subscription],
 ['a pending subscription update',f=>{f.bodies.get(subscription).pending_update={};},subscription],
 ['coherent foreign Stripe product',f=>{
  f.bodies.get(subscription).items.data[0].price.product='prod_other';
  f.bodies.get(invoice).lines.data[0].pricing.price_details.product='prod_other';
 },subscription],
 ['another trial end',f=>{f.bodies.get(subscription).trial_end++;},subscription],
 ['invoice belongs to a sibling',f=>{f.bodies.get(invoice).parent.subscription_details.subscription='sub_other';},invoice],
 ['missing out-of-band amount',f=>{delete f.bodies.get(invoice).amount_paid_off_stripe;},invoice],
 ['pretax credit',f=>{f.bodies.get(invoice).total_pretax_credit_amounts=[{}];},invoice],
 ['foreign invoice line item',f=>{f.bodies.get(invoice).lines.data[0].parent.subscription_item_details.subscription_item='si_other';},invoice],
 ['another invoice',f=>{f.bodies.get('/v1/invoices').data.push(object('in_other','invoice',{}));},'/v1/invoices'],
 ['incomplete invoice inventory',f=>{f.bodies.get('/v1/invoices').has_more=true;},'/v1/invoices'],
 ['invoice allocation',f=>{f.bodies.get('/v1/invoice_payments').data.push({id:'inpay_other'});},'/v1/invoice_payments'],
 ['PaymentIntent on the original Customer',f=>{f.bodies.get('/v1/payment_intents').data.push({id:'pi_other'});},'/v1/payment_intents'],
 ['charge on the original Customer',f=>{f.bodies.get('/v1/charges').data.push({id:'ch_other'});},'/v1/charges'],
])test('refuses '+name+' without starting any later read',async()=>{
 const f=fixture();change(f);
 await assert.rejects(f.run(),error);
 assert.deepEqual(f.calls,reads.slice(0,reads.indexOf(last)+1));
});

for(const fault of ['wrong account','wrong API','retired owner','aborted owner'])test('stops after one GET for '+fault,async()=>{
 const f=fixture(),read=f.dependencies.readProvider;
 f.dependencies.readProvider=async(...args)=>{
  const response=await read(...args);
  if(fault==='wrong account')response.externalAccountId='acct_other';
  if(fault==='wrong API')response.apiVersion='wrong';
  if(fault==='retired owner')f.scope.operationId=id(99);
  if(fault==='aborted owner')f.controller.abort();
  return response;
 };
 await assert.rejects(f.run(),error);
 assert.deepEqual(f.calls,['seed',customer]);
});

test('cannot switch to a replacement fixture that reports the original IDs',async()=>{
 const f=fixture(),read=f.dependencies.readProvider,original=f.inputs.fixture;
 f.dependencies.readProvider=async(...args)=>{
  const response=await read(...args);
  original.resources=()=>undefined;
  f.inputs.fixture={resources:()=>f.scope};
  return response;
 };
 await assert.rejects(f.run(),error);
 assert.deepEqual(f.calls,['seed',customer]);
});

test('an absent transport returns only the fixed failure',async()=>{
 const f=fixture();
 await assert.rejects(proveOwnedBillingRequiredTrialEvidence(f.inputs,{deadline:Date.now()+3000},null),error);
 assert.deepEqual(f.calls,[]);
});
