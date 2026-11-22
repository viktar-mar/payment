import assert from 'node:assert/strict';
import test from 'node:test';
import * as owners from './stripe-billing-acceptance.mjs';
import {validateBillingAcceptanceOptions} from './stripe-billing-acceptance-options.mjs';
import {createBillingFixture} from './stripe-billing-provision.mjs';
import {provisionHarness} from './fixtures/failed-renewal-provision-harness.mjs';
import {id} from './fixtures/failed-renewal-descriptors.mjs';
import {createHash} from 'node:crypto';

const scenario='initial_missed_reordered',configuration={
 env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true',PAYMENT_STRIPE_BILLING_CASE:scenario},
 runId:id(1),expectedSource:'a'.repeat(40),daemon:'unix:///Users/test/docker.sock',
 ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},
 approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:scenario,
};
test('withheld invoices require their own admission and exact dispatch budget',async()=>{
 assert.deepEqual(validateBillingAcceptanceOptions(configuration)?.budget,{create:1,confirm:1,reconcile:1,deliver:2});
 assert.equal(validateBillingAcceptanceOptions({...configuration,budget:{create:1,confirm:1,reconcile:1,deliver:3}}),null);
 await assert.rejects(owners.createOwnedBillingAcceptance(configuration),/OPT_IN_REQUIRED/);
});
for(const fault of ['none','uncertain customer','repeated create','foreign confirm','premature completion','missing grant','missing paid baseline','uncertain reconcile','wrong recovery','paid uncertain','unprocessed paid','unprocessed finalized','tainted before confirm','tainted before reconcile','late ingress','browser closure']){
 test('withheld originals through real fixture and owner: '+fault,async()=>{
  const track=[],yes=async()=>true;
  let sentKey,revoke;
  const h=await provisionHarness({configuration,onWrite:name=>{if(fault==='uncertain customer'&&name.endsWith('-customer.json'))throw Error();},
   factory:(args,deps)=>createBillingFixture(args,{...deps,fetch:async(url,settings)=>{
    if(new URL(url).pathname.endsWith('/reconcile')){
     track.push('admin');assert.equal(url,'http://127.0.0.1:18080/api/v1/admin/subscriptions/'+id(7)+'/reconcile?site_id='+id(3));
     assert.equal(settings.body,'{}');assert.equal(settings.method,'POST');assert.ok(settings.headers.Cookie);assert.ok(settings.headers['X-XSRF-TOKEN']);
     sentKey=settings.headers['Idempotency-Key'];if(fault==='uncertain reconcile')throw Error();
     return Response.json({subscription_id:id(7),operation_id:id(10),status:'SUCCEEDED',next_action:null,action_available:false,browser_confirm_allowed:false},{status:202});
    }return deps.fetch(url,settings);
   }})});
  const paid={eventId:'evt_paid'},finalized={eventId:'evt_finalized'},pair={paid,finalized};
  const ingress={open:yes,arm(local){assert.equal(local.customerId,id(4));track.push('arm');return true;},
   beforeConfirm(local,refs){assert.equal(local.attemptId,id(9));assert.equal(refs.invoiceReference,'in_orig');track.push('bound');return finalized;},
   releaseConfirm(){return true;},requirePair(){return true;},pair(){track.push('pair');return pair;},deliver(kind){track.push('deliver '+kind);return !(fault==='paid uncertain'&&kind==='paid');},
   finish(){track.push('finish');return fault!=='late ingress';},close:yes};
  const evidence={beforeConfirm(){track.push('grant');assert.equal(h.fixture.resources().attemptId,id(9));return fault==='missing grant'?null:{invoiceReference:'in_orig'};},
   beforeReconcile(got){assert.equal(got,pair);track.push('baseline');if(fault==='tainted before reconcile')revoke();return fault!=='missing paid baseline';},
   reconciled(r){track.push('reconciled');assert.equal(r.operationId,id(10));assert.equal(r.keyHash,createHash('sha256').update(sentKey).digest('hex'));return fault!=='wrong recovery';},
   delivered(kind){track.push('proved '+kind);return fault!=='unprocessed '+kind;}};
  const owner=await owners.createOwnedBillingInvoiceWebhooks(configuration,{preflight:yes,createBootstrap:()=>({prepare:yes,start:yes,resources:()=>({cliDirectory:'inert'}),stop:yes}),
   createInvoiceIngress:(_c,_b,{onFailure})=>{revoke=onFailure;return ingress;},createCli:args=>{assert.equal(args.invoiceIngress,ingress);return {validateBinding:yes,startListener:yes,assertBillingListener:()=>true,stopListener:yes};},
   login:yes,createFixture:()=>h.fixture,createInvoiceEvidence:()=>evidence});
  const run=owner.run(async p=>{
   try{await p.acknowledgeCustomer({customerId:id(4),siteId:id(3)});}catch{}
   await p.beforeDispatch('create',id(11));if(fault==='repeated create')await p.beforeDispatch('create',id(12));
   await p.acknowledge('create',{subscriptionId:id(7),operationId:id(8)});
   if(fault==='premature completion')return true;
   await p.beforeDispatch('confirm',id(12),{subscriptionId:id(7),operationId:fault==='foreign confirm'?id(17):id(8),attemptId:id(9)});if(fault==='tainted before confirm')revoke();p.releaseOriginalConfirm();track.push('confirm');
   await p.acknowledge('confirm',{attemptId:id(9),state:'OBSERVE',nextActionAllowed:false});
   if(fault==='browser closure')throw Error();return true;
  },{deadline:Date.now()+5000});
  if(fault==='none'){
   const result=await run;assert.equal(result.scope,'INITIAL_MISSED_REORDERED');assert.equal(result.invoiceWebhooksAccepted,true);
   assert.deepEqual(track,['arm','grant','bound','confirm','pair','baseline','admin','reconciled','deliver paid','proved paid','deliver finalized','proved finalized','finish']);
   assert.equal(h.writes.some(r=>JSON.stringify(r.value).includes(sentKey)),false);
  }else{
   await assert.rejects(run);assert.equal(owner.resources().invoiceWebhooksAccepted,false);
   if(fault==='browser closure')assert.equal(owner.resources().cleanupConfirmed,false);
   if(fault==='tainted before confirm')assert.ok(!track.includes('confirm'));
   if(['tainted before confirm','tainted before reconcile','missing grant','missing paid baseline','foreign confirm','repeated create','uncertain customer','premature completion'].includes(fault))assert.ok(!track.includes('admin'));
   if(['uncertain reconcile','wrong recovery'].includes(fault))assert.ok(!track.includes('deliver paid'));
   if(['unprocessed paid','paid uncertain'].includes(fault))assert.ok(!track.includes('deliver finalized'));
  }
  assert.ok(track.filter(k=>k==='admin').length<=1);await assert.rejects(owner.run(async()=>true,{deadline:Date.now()+1000}),/SINGLE_USE/);
 });
}
import {syntheticInvoiceIngress,owner as original,refs,secret} from './fixtures/invoice-ingress-harness.mjs';
for(const fault of ['none','after grant','inside baseline','after reconcile intent','listener before confirm','listener before reconcile','expired before reconcile','listener after final read']){
 test('real signed ingress and owner through terminal cleanup: '+fault,async()=>{
  const yes=async()=>true,delivered=[];let h,admin=0,forwarded=0,intent=false,listening=true;
  const invalid=async()=>{assert.equal((await h.post(h.event('paid'),{invalid:true})).status,400);};
  const provision=await provisionHarness({configuration,
   onWrite:name=>{if(name.endsWith('-reconcile-dispatch.json'))intent=true;},
   factory:(args,deps)=>createBillingFixture({...args,bootstrap:{...args.bootstrap,verify:async()=>{
    if(intent&&fault==='listener before reconcile')listening=false;
    if(intent&&fault==='expired before reconcile')h.advance(301000);
    if(intent&&fault==='after reconcile intent'){intent=false;await invalid();}return true;
   }}},{...deps,fetch:async(url,s)=>{
    if(!new URL(url).pathname.endsWith('/reconcile'))return deps.fetch(url,s);
    admin++;return Response.json({subscription_id:original.subscriptionId,operation_id:id(10),status:'SUCCEEDED',next_action:null,action_available:false,browser_confirm_allowed:false},{status:202});
   }})});
  const owner=await owners.createOwnedBillingInvoiceWebhooks(configuration,{preflight:yes,createBootstrap:()=>({prepare:yes,start:yes,resources:()=>({cliDirectory:'inert'}),stop:yes}),
   createInvoiceIngress:(c,_b,{onFailure})=>{h=syntheticInvoiceIngress(c,onFailure);return h.ingress;},
   createCli:()=>({validateBinding:yes,startListener:()=>h.ingress.signingKey(secret),assertBillingListener:()=>listening,stopListener:yes}),
   login:yes,createFixture:()=>provision.fixture,createInvoiceEvidence:()=>({beforeConfirm:()=>refs,
    beforeReconcile:async pair=>{assert.ok(pair.paid.created>pair.finalized.created);assert.equal(h.sent.length,0);if(fault==='inside baseline')await invalid();return true;},
    reconciled:()=>admin===1&&h.sent.length===0,delivered:kind=>{delivered.push(kind);return h.sent.length===delivered.length;}})});
  const run=owner.run(async(p,l)=>{
   await p.acknowledgeCustomer({customerId:id(4),siteId:id(3)});await p.beforeDispatch('create',id(11));
   assert.equal((await h.post(h.event('finalized'))).status,204);h.advance(1000);
   await p.acknowledge('create',{subscriptionId:original.subscriptionId,operationId:original.operationId});
   await p.beforeDispatch('confirm',id(12),{subscriptionId:original.subscriptionId,operationId:original.operationId,attemptId:id(9)});
   if(fault==='listener before confirm')listening=false;
   if(fault==='after grant'){await invalid();assert.equal(l.signal.aborted,true);}
   p.releaseOriginalConfirm();forwarded++;
   assert.equal((await h.post(h.event('paid'))).status,204);await p.acknowledge('confirm',{attemptId:id(9),state:'OBSERVE',nextActionAllowed:false});if(fault==='listener after final read'){h.ingress.listenerLost();assert.equal(l.signal.aborted,true);}return true;
  },{deadline:Date.now()+5000});
  if(fault==='none'){assert.equal((await run).invoiceWebhooksAccepted,true);assert.deepEqual(delivered,['paid','finalized']);}
  else{await assert.rejects(run);assert.equal(owner.resources().invoiceWebhooksAccepted,false);assert.equal(admin,fault==='listener after final read'?1:0);assert.equal(h.sent.length,fault==='listener after final read'?2:0);}
  assert.equal(forwarded,['after grant','listener before confirm'].includes(fault)?0:1);
 });
}
