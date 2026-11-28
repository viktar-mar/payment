import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import test from 'node:test';
import * as acceptance from './stripe-billing-acceptance.mjs';
import {acceptanceBrowserFailure,finishBrowserCleanup} from './stripe-billing-first-payment-browser-diagnostic.mjs';
import {billingCallbackDigest,proveBillingEvidence} from './stripe-billing-evidence.mjs';
import {createBillingPortalFixture} from './stripe-billing-provision.mjs';
import {provisionHarness} from './fixtures/failed-renewal-provision-harness.mjs';
import {id,iso} from './fixtures/failed-renewal-descriptors.mjs';

const configuration={
 env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true',PAYMENT_STRIPE_BILLING_CASE:'initial_portal',
  PAYMENT_STRIPE_BILLING_PORTAL_CLI_PROFILE_RUN_ID:id(50),PAYMENT_STRIPE_BILLING_PORTAL_SUBNET:'10.254.81.0/28'},
 selectedCase:'initial_portal',runId:id(1),expectedSource:'a'.repeat(40),daemon:'unix:///Users/test/docker.sock',
 ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},
 approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},
};
const scope={subscriptionId:id(7),operationId:id(8),attemptId:id(9)};
const keyDigest=createHash('sha256').update(id(13)).digest('hex');
const expectNow=()=>({deadline:Date.now()+3000});
const yes=async()=>true;
const fixedInitialCodes=/^Error: billing_[A-Z0-9_]+$/;

async function harness(mode) {
 const calls=[];
 const periodStart=Math.floor(Date.now()/1000)-60,periodEnd=periodStart+86400,expiresAt=iso(periodStart+300);
 const common={site_id:id(3),customer_id:id(4),subscription_id:id(7),product_id:id(6),occurred_at:iso(periodStart)};
 const payment={...common,event_id:id(30),schema_version:3,event_type:'payment.succeeded',payment_id:id(31),
  billing_record_id:id(32),payment_status:'SUCCEEDED',amount_minor:2000,currency:'EUR',billing_kind:'INITIAL',
  price_id:id(5),price_version:1,period_start:iso(periodStart),period_end:iso(periodEnd)};
 const paid={...common,event_id:id(33),schema_version:4,event_type:'subscription.updated',revision:3,
  billing_record_id:id(32),payment_id:null,state:'ACTIVE',access_allowed:true};
 const callbacks=[payment,paid].map(payload=>({id:payload.event_id,type:payload.event_type,payload,delivered:true}));
 const inspection={count:2,duplicates:0,events:callbacks.map(e=>({event_id:e.id,event_type:e.type,count:1,sha256:billingCallbackDigest(e.payload)}))};
 const row={customer_reference:'cus_owned',subscription_reference:'sub_owned',product_reference:'prod_owned',
  price_reference:'price_fresh',invoice_reference:'in_owned',allocation_reference:'inpay_owned',intent_reference:'pi_owned',
  period_start:periodStart,period_end:periodEnd,payment_id:id(31),billing_record_id:id(32),initial_unscheduled:true,
  subscription_revision:3,cash_journal_ids:[id(30)],paid_journal_ids:[id(33)],
  proof:{cash:mode!=='missing initial cash',journal:true,original:true,webhook:true},callbacks};
 let callbackReads=0,portalReads=0,listenerReads=0;
 const f=await provisionHarness({configuration,
  factory(inputs,dependencies){
   return createBillingPortalFixture(inputs,{...dependencies,fetch(url,settings){
    if(new URL(url).pathname!=='/inspection/digests')return dependencies.fetch(url,settings);
    callbackReads++;calls.push('callbacks');
    return Response.json(mode==='changed callbacks'&&callbackReads===2?{...inspection,duplicates:1}:inspection);
   }});
  },
  async onFetch(path,settings,response){
   if(path.endsWith('/publish'))return Response.json({...await response.json(),site_id:id(3),product_id:id(6)});
   if(!path.endsWith('/subscription-policy'))return response;
   if(settings.method==='GET')return Response.json({site_id:id(3),version:0,enabled:false,grace_days:3,
    allow_immediate_cancellation:false,portal_enabled:false,defer_upgrades:false,portal_return_urls:[]});
   return Response.json({...await response.json(),site_id:id(3),version:1});
  },
  onWrite(name){
   if(name.endsWith('-portal-session-dispatch.json')){
    calls.push('durable Portal intent');
    if(mode==='lost intent')throw Error('private storage details');
   }
   if(name.endsWith('-portal-session-ack.json')){
    calls.push('durable Portal acknowledgement');
    if(mode==='lost ack')throw Error('private storage details');
   }
  },
 });
 const cli={
  async validateBinding(){calls.push('reuse CLI');return true;},
  async startListener(){calls.push('listen');return true;},
  async assertBillingListener(){return ++listenerReads!==3||mode!=='lost listener';},
  async bindOwnedBillingGraph(graph){
   calls.push('bind original graph');assert.equal(graph.runId,configuration.runId);
   assert.equal(graph.subscriptionReference,'sub_owned');return true;
  },
  async observeOwnedBillingGraph(){
   calls.push('prove original provider graph');
   return {providerVerified:true,initialCashVerified:mode!=='missing provider cash',lineCount:1,allocationCount:1,chargeCount:1};
  },
  async stopListener(){calls.push('stop listener');return true;},
 };
 const bootstrap={
  prepare:yes,start:yes,resources:()=>({cliDirectory:'owned-inert'}),
  withDatabaseCredentials:async use=>use({password:'private credentials'}),
  async stop(){calls.push('stop bootstrap');return mode!=='cleanup';},
 };
 const connect=()=>({
  async begin(transaction,execute) {
   assert.equal(transaction,'read only');
   return execute({async unsafe(query,args){
    if(!args){assert.equal(query,"set local statement_timeout='5000'");return [];}
    if(!query.includes('true as portal_verified')){
     calls.push('prove original local cash');assert.equal(args[0],id(2));assert.equal(args.at(-1),false);
     return [row];
    }
    calls.push('prove Portal '+(++portalReads));
    assert.equal(args[3],scope.subscriptionId);
    assert.equal(args[5],id(32));
    assert.equal(args[6],id(31));
    assert.equal(args[8],1);
    assert.equal(args[9],keyDigest);
    assert.equal(args[10],f.fixture.resources().portalReturnUrl);
    assert.equal(args[11],expiresAt);
    return [{portal_verified:!(mode==='before SQL'&&portalReads===1||mode==='after SQL'&&portalReads===2)}];
   }});
  },
  end:async()=>{},
 });
 const owner=await acceptance.createOwnedBillingPortal(configuration,{
  preflight:yes,createBootstrap:()=>bootstrap,createCli:()=>cli,createFixture:()=>f.fixture,
  login:()=>assert.fail('a Portal run must use the saved profile'),
  prove:(inputs,limits)=>proveBillingEvidence(inputs,limits,{connect}),
  portalConnect:connect,
 });
 let returnUrl;
 const tab=Object.freeze({}),hosted=Object.freeze({}),returned=Object.freeze({});
 const browser={
  async openPortal(port){
   assert.equal(this,undefined);
   calls.push('open');
   returnUrl=port.returnUrl;
   if(mode==='without intent')return {opening:{},tab,document:hosted,origin:'https://billing.stripe.com',providerMutationCount:0};
   await port.beforeDispatch(keyDigest);
   calls.push('Portal transport');
   const receipt=mode==='inherited expiry'?Object.assign(Object.create({expiresAt}),{note:true}):{expiresAt};
   const opening=await port.observe201(receipt);
   calls.push('hosted navigation');
   const witness={opening,tab,document:hosted,origin:'https://billing.stripe.com',providerMutationCount:0};
   return mode==='proxied hosted receipt'?new Proxy(witness,{}):witness;
  },
  async observeMerchantReturn(witness){
   assert.equal(this,undefined);
   assert.deepEqual(witness,{tab,document:hosted});
   calls.push('provider return');
   const receipt={tab,sourceDocument:mode==='foreign return'?{}:hosted,document:returned,initiator:'PROVIDER_DOCUMENT',
    method:'GET',url:returnUrl,providerMutationCount:0};
   if(mode==='changing return document'){
    let reads=0;
    Object.defineProperty(receipt,'document',{enumerable:true,get:()=>++reads<=2?returned:hosted});
   }
   return receipt;
  },
  async readReturnedSubscription(witness){
   assert.equal(this,undefined);
   const document=mode==='changing return document'?hosted:returned;
   assert.deepEqual(witness,{tab,document});
   calls.push('read original subscription');
   const access=mode==='returned access accessor'?{get allowed(){return true;},reason:'PAID'}:{allowed:true,reason:'PAID'};
   return {tab,document,siteId:id(3),customerId:id(4),subscriptionId:id(7),method:'GET',status:200,
    billingStatus:'ACTIVE',access,revision:3,paidThrough:iso(periodEnd)};
  },
 };
 async function run(port) {
  assert.deepEqual(Object.keys(port).sort(),['acknowledge','acknowledgeCustomer','beforeDispatch','continuePortal','resources','withOtpCode']);
  if(mode==='early Portal')return port.continuePortal(browser);
  await port.acknowledgeCustomer({customerId:id(4),siteId:id(3)});
  const createAck={subscriptionId:scope.subscriptionId,operationId:scope.operationId};
  if(mode==='early create ack'){
   await Promise.all([port.beforeDispatch('create',id(11)),port.acknowledge('create',createAck)]);
   return true;
  }
  await port.beforeDispatch('create',id(11));
  await port.acknowledge('create',createAck);
  await port.beforeDispatch('confirm',mode==='same dispatch key'?id(11):id(12),scope);
  await port.acknowledge('confirm',{attemptId:scope.attemptId,state:'OBSERVE',nextActionAllowed:false,retryAllowed:false,successorAttemptId:null});
  if(mode==='missing continuation')return true;
  if(mode==='foreign evidence')return port.continuePortal({...browser, verifyInitial:()=>assert.fail('foreign proof used')});
  await port.continuePortal(browser);
  if(mode==='duplicate continuation')await port.continuePortal(browser);
  return true;
 }
 async function journey(port) {
  try{return await run(port);}
  catch(error){
   if(!fixedInitialCodes.test(String(error)))throw error;
   const closed=acceptanceBrowserFailure('BROWSER_OTP_REQUEST');
   finishBrowserCleanup(closed,true);
   throw closed;
  }
 }
 return {owner,run:journey,calls,writes:f.writes};
}

test('Portal owner is inert and admits only its own saved-profile case',async()=>{
 const deps=new Proxy({},{get(){assert.fail('constructor opened a capability');}});
 const owner=await acceptance.createOwnedBillingPortal(configuration,deps);
 assert.equal(owner.resources().scope,'INITIAL_PORTAL');
 const env={...configuration.env};
 delete env.PAYMENT_STRIPE_BILLING_PORTAL_CLI_PROFILE_RUN_ID;
 await assert.rejects(acceptance.createOwnedBillingPortal({...configuration,env},deps),/OPT_IN_REQUIRED/);
 await assert.rejects(acceptance.createOwnedBillingPortal({...configuration,selectedCase:'initial_3ds'},deps),/OPT_IN_REQUIRED/);
});

test('Portal owner joins its original payment, single durable Portal intent and same returned subscription',async()=>{
 const f=await harness('pass'), result=await f.owner.run(f.run,expectNow());
 assert.deepEqual(result,{...f.owner.resources(),scope:'INITIAL_PORTAL',status:'CLOSED',phase:'COMPLETE',
  code:null,initialAccepted:true,portalAccepted:true,full5bAccepted:false,cleanupConfirmed:true});
 assert.deepEqual(f.calls,['reuse CLI','listen','prove original local cash','callbacks','bind original graph','prove original provider graph',
  'open','durable Portal intent','Portal transport','prove Portal 1','durable Portal acknowledgement','hosted navigation','provider return',
  'read original subscription','prove Portal 2','callbacks','stop listener','stop bootstrap']);
 assert.deepEqual(f.writes.filter(e=>e.name.includes('portal-session')).map(e=>e.value.phase),['DISPATCHING','ACKNOWLEDGED']);
 await assert.rejects(f.owner.run(f.run,expectNow()),/SINGLE_USE/);
});

for(const [mode,code,initial,transports,hosted] of [
 ['early Portal','PORTAL_ORDER',0,0,0],['early create ack','ACK',0,0,0],['same dispatch key','DISPATCH',0,0,0],
 ['missing continuation','PORTAL_ORDER',0,0,0],['foreign evidence','PORTAL_ORDER',0,0,0],['missing initial cash','EVIDENCE',1,0,0],
 ['missing provider cash','EVIDENCE',1,0,0],['without intent','PORTAL_OPEN',1,0,0],
 ['lost listener','PORTAL_DISPATCH',1,0,0],['lost intent','PORTAL_DISPATCH',1,0,0],
   ['inherited expiry','PORTAL_201',1,1,0],['proxied hosted receipt','PORTAL_OPEN',1,1,1],
 ['before SQL','PORTAL_PROOF_BEFORE_OPEN',1,1,0],['lost ack','PORTAL_ACK',1,1,0],['foreign return','PORTAL_RETURN',1,1,1],
   ['changing return document','PORTAL_RETURN',1,1,1],['returned access accessor','PORTAL_RETURN_READ',1,1,1],
 ['after SQL','PORTAL_PROOF_AFTER_RETURN',1,1,1],['changed callbacks','PORTAL_PROOF_AFTER_RETURN',1,1,1],
 ['duplicate continuation','PORTAL_ORDER',1,1,1],['cleanup','CLEANUP',1,1,1],
])test('Portal owner stops '+mode+' without repeating a dispatch',async()=>{
 const f=await harness(mode);
 await assert.rejects(f.owner.run(f.run,expectNow()),{message:'billing_'+code});
 const result=f.owner.resources();
 assert.equal(result.code,code);
 assert.equal(result.portalAccepted,false);
 assert.equal(result.initialAccepted,false);
 assert.equal(result.full5bAccepted,false);
 assert.equal(result.cleanupConfirmed,mode!=='cleanup');
 assert.equal(f.calls.filter(x=>x==='prove original local cash').length,initial);
 assert.equal(f.calls.filter(x=>x==='Portal transport').length,transports);
 assert.equal(f.calls.filter(x=>x==='hosted navigation').length,hosted);
 if(mode==='changing return document')assert.equal(f.calls.includes('read original subscription'),false);
 if(mode==='returned access accessor')assert.equal(f.calls.includes('prove Portal 2'),false);
 assert.equal(f.calls.filter(x=>x==='stop bootstrap').length,1);
 assert.doesNotMatch(JSON.stringify(result),/private|customer|portalReturnUrl/);
});
