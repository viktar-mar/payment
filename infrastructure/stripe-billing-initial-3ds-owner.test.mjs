import assert from 'node:assert/strict';
import test from 'node:test';
import * as owners from './stripe-billing-acceptance.mjs';
import {validateBillingAcceptanceOptions} from './stripe-billing-acceptance-options.mjs';
import {createBillingFixture} from './stripe-billing-provision.mjs';
import {provisionHarness} from './fixtures/failed-renewal-provision-harness.mjs';
import {id} from './fixtures/failed-renewal-descriptors.mjs';
import {acceptanceBrowserFailure,finishBrowserCleanup,rememberBrowserProviderOrigin} from './stripe-billing-first-payment-browser-diagnostic.mjs';

const scenario='initial_3ds';
const configuration={
  env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true',PAYMENT_STRIPE_BILLING_CASE:scenario},
  runId:id(1),expectedSource:'a'.repeat(40),daemon:'unix:///Users/test/docker.sock',
  ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},
  approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:scenario,
};
const attempt={subscriptionId:id(7),operationId:id(8),attemptId:id(9)};
const winning={attemptId:id(9),state:'REQUIRES_ACTION',nextActionAllowed:true,retryAllowed:false,successorAttemptId:null};
test('3DS admission requires its own case and exact single-action budget',async()=>{
  const admitted=validateBillingAcceptanceOptions(configuration);
  assert.deepEqual(admitted?.budget,{create:1,confirm:1,nextAction:1});
  for(const factory of [owners.createOwnedBillingAcceptance,owners.createOwnedBillingCancellation,owners.createOwnedBillingDeclineRetry])
    await assert.rejects(factory(configuration),/OPT_IN_REQUIRED/);
  for(const budget of [{create:1,confirm:1},{create:1,confirm:2,nextAction:1},{create:1,confirm:1,nextAction:2}])
    assert.equal(validateBillingAcceptanceOptions({...configuration,budget}),null);
});
for(const fault of ['none','wrong confirm','second create','foreign challenge','missing completion','duplicate challenge','uncertain customer','unproved','browser close']){
 test('initial 3DS owner and real fixture with '+fault,async()=>{
  const f=await provisionHarness({configuration,factory:createBillingFixture,onWrite:name=>{
    if(fault==='uncertain customer'&&name.endsWith('-customer.json'))throw Error('unconfirmed');
  }});
  const yes=async()=>true,cli={validateBinding:yes,startListener:yes,assertBillingListener:yes,stopListener:yes};
  const bootstrap={prepare:yes,start:yes,resources:()=>({cliDirectory:'inert'}),stop:yes};
  let proofs=0;
  const owner=await owners.createOwnedBillingInitial3ds(configuration,{preflight:yes,createBootstrap:()=>bootstrap,
    createCli:()=>cli,login:yes,createFixture:()=>f.fixture,proveInitial3ds:async args=>{
      assert.equal(args.configuration.selectedCase,scenario);assert.equal(args.fixture,f.fixture);assert.equal(args.cli,cli);assert.equal(args.bootstrap,bootstrap);
      proofs++;return fault!=='unproved';
    }});
  const run=owner.run(async p=>{
    try{await p.acknowledgeCustomer({customerId:id(4),siteId:id(3)});}catch{}
    await p.beforeDispatch('create',id(11));
    if(fault==='second create')await p.beforeDispatch('create',id(12));
    await p.acknowledge('create',{subscriptionId:id(7),operationId:id(8)});
    await p.beforeDispatch('confirm',id(12),attempt);
    await p.acknowledge('confirm',fault==='wrong confirm'?{...winning,nextActionAllowed:false}:winning);
    await p.beforeChallenge(fault==='foreign challenge'?{...attempt,attemptId:id(8)}:attempt);
    if(fault==='duplicate challenge')await p.beforeChallenge(attempt);
    if(fault!=='missing completion')await p.acknowledgeChallenge(attempt);
    if(fault==='browser close')throw Error('private');
    return true;
  },{deadline:Date.now()+5000});
  if(fault==='none'){
    const result=await run;assert.equal(result.scope,'INITIAL_3DS');assert.equal(result.initial3dsAccepted,true);assert.equal(result.full5bAccepted,false);
    assert.deepEqual(f.writes.slice(-4).map(row=>row.name.slice(12)),['confirm-dispatch.json','confirm-ack.json','challenge-dispatch.json','challenge-ack.json']);
    assert.equal(f.writes.at(-3).value.state,'REQUIRES_ACTION');
  }else{
    await assert.rejects(run);assert.equal(owner.resources().initial3dsAccepted,false);
    if(fault==='browser close')assert.equal(owner.resources().cleanupConfirmed,false);
  }
  assert.equal(proofs,['none','unproved'].includes(fault)?1:0);
  await assert.rejects(owner.run(async()=>true,{deadline:Date.now()+1000}),/SINGLE_USE/);
 });
}

for(const fault of ['exact','foreign-run','sink-reject','forged-error','before-dispatch'])test('3DS owner keeps private refusal independent of its terminal outcome: '+fault,async()=>{
 const checkpoint='BROWSER_PROVIDER_ORIGIN_STRIPE_COM',origin='https://evidence-only.stripe.com';
 const f=await provisionHarness({configuration,factory:createBillingFixture});
 const yes=async()=>true,calls=[];
 const cli={validateBinding:yes,startListener:yes,assertBillingListener:yes,stopListener:yes};
 const bootstrap={prepare:yes,start:yes,resources:()=>({cliDirectory:'inert'}),stop:async()=>{calls.push('stop');return true;},
  persistRefusedStripeOrigin:async(witness,limits)=>{
   calls.push(witness);assert.ok(limits.deadline>Date.now()&&limits.deadline<=Date.now()+2000);assert.ok(limits.signal instanceof AbortSignal);
   if(fault==='sink-reject')throw Error('private sink details');return true;
  }};
 const owner=await owners.createOwnedBillingInitial3ds(configuration,{preflight:yes,createBootstrap:()=>bootstrap,
  createCli:()=>cli,login:yes,createFixture:()=>f.fixture,proveInitial3ds:async()=>{assert.fail('must not verify');}});
 const error=acceptanceBrowserFailure(checkpoint);
 finishBrowserCleanup(error,true);
 rememberBrowserProviderOrigin(error,fault==='foreign-run'?id(99):configuration.runId,configuration.expectedSource,origin);
 await assert.rejects(owner.run(async p=>{
  await p.acknowledgeCustomer({customerId:id(4),siteId:id(3)});
  await p.beforeDispatch('create',id(11));
  await p.acknowledge('create',{subscriptionId:id(7),operationId:id(8)});
  await p.beforeDispatch('confirm',id(12),attempt);
  await p.acknowledge('confirm',winning);
  if(fault!=='before-dispatch')await p.beforeChallenge(attempt);
  throw fault==='forged-error'?new Error(checkpoint):error;
 },{deadline:Date.now()+5000}),{message:'billing_'+(fault==='forged-error'?'BROWSER':checkpoint)});
 assert.deepEqual(calls,['exact','sink-reject'].includes(fault)?
  [{runId:configuration.runId,expectedSource:configuration.expectedSource,checkpoint,origin},'stop']:['stop']);
 assert.equal(owner.resources().cleanupConfirmed,fault!=='forged-error');
 assert.equal(owner.resources().initial3dsAccepted,false);
 assert.equal(JSON.stringify(owner.resources()).includes(origin),false);
 assert.match(f.writes.at(-1).name,fault==='before-dispatch'?/-confirm-ack\.json$/:/-challenge-dispatch\.json$/);
});
