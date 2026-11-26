import assert from 'node:assert/strict';
import test from 'node:test';
import {createOwnedBillingOptionalTrial} from './stripe-billing-optional-trial-run.mjs';
import {OPTIONAL_TRIAL_CLI_PROFILE_RUN_ID, OPTIONAL_TRIAL_SUBNET} from './stripe-billing-optional-trial-options.mjs';

const id = n => '81000000-1111-4222-8333-' + String(n).padStart(12, '0');
const options = {
  env: {PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL: 'true', PAYMENT_STRIPE_SANDBOX_ENABLED: 'true',
    [OPTIONAL_TRIAL_CLI_PROFILE_RUN_ID]: id(99), [OPTIONAL_TRIAL_SUBNET]:'10.241.1.0/28'},
  selectedCase: 'optional_trial_pause_resume', runId: id(1), expectedSource: 'a'.repeat(40),
  daemon: 'unix:///var/run/docker.sock', controlPort: 18511,
  ports: {backend:18510,demo:18512,receiver:18513,postgres:18514,mailpitHttp:18515,mailpitSmtp:18516},
  approvedBinding: {externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},
};

function fixture(fault) {
  const calls = [];
  let ownerSignal, ownerDeadline;
  const binding = {runId:options.runId, expectedSource:options.expectedSource, siteId:id(2), accountId:id(3),
    priceId:id(4), productId:id(5), siteKey:'s5a-'+options.runId.replaceAll('-',''), email:'fixture@example.test',
    endpoints:{backend:'http://127.0.0.1:18510',demo:'http://127.0.0.1:18512',receiver:'http://127.0.0.1:18513'}};
  const permit = {setupIntentId:'seti_owned',publishableKey:'pk_test_owned',
    clientSecret:'seti_owned_secret_synthetic',expiresAt:new Date(Date.now()+60000).toISOString()};
  const stages = {open:'TRIALING',advance:'PAUSED','setup-permit':'WAITING_FOR_SETUP',
    'setup-install':'SETUP_INSTALLED',resume:'OPEN',pay:'PAID'};
  const order = Object.keys(stages);
  const identity = {runId:options.runId,clockIntentId:id(6),subscriptionId:id(7),createOperationId:id(8),
    frozenTime:1800000000,trialEnd:1800604800};
  const cleanup = label => async () => {calls.push(label);return fault!==label;};
  const fixture = {resources:()=>binding,provision:cleanup('provision'),close:cleanup('fixture-close'),
    withOtpCode:async action=>action('123456'),
    acknowledgeCustomer:async v=>v.siteId===binding.siteId&&v.customerId===id(9)};
  const response = (object,status=200)=>new Response(JSON.stringify(object),{status,headers:{'content-type':'application/json'}});
  const fetch = async (value,request) => {
    const url = new URL(value), body = JSON.parse(request.body);
    if(url.pathname.startsWith('/api/')) {
      assert.equal(url.origin,binding.endpoints.backend);
      assert.equal(request.headers.Origin,binding.endpoints.demo);
      if(url.pathname.endsWith('/challenges')){
        assert.deepEqual(body,{site_key:binding.siteKey,email:binding.email,intent:'AUTHENTICATE'});
        return response({challenge_id:id(10)},202);
      }
      assert.ok(url.pathname.endsWith('/'+id(10)+'/verify'));
      assert.deepEqual(body,{site_key:binding.siteKey,code:'123456'});
      return response({site_id:binding.siteId,customer:{id:id(9),status:'ACTIVE'},session_token:'x'.repeat(43),
        scopes:['SUBSCRIPTION_READ','SUBSCRIPTION_MANAGE'],expires_at:new Date(Date.now()+600000).toISOString()});
    }
    const name = url.pathname.split('/').at(-1);
    calls.push(name);
    assert.equal(url.origin,'http://127.0.0.1:18511');
    assert.equal(request.headers.Authorization,'Bearer '+'s'.repeat(43));
    if(name==='abort')return response({stage:'QUIESCENT'},fault==='abort'?500:200);
    if(name==='open')assert.deepEqual(body,{accountId:binding.accountId,siteId:binding.siteId,priceId:binding.priceId,
      customerId:id(9),origin:binding.endpoints.demo,customerToken:'x'.repeat(43),recurringConsent:true});
    else assert.deepEqual(body,{});
    if(fault===name)return response({error:'privateStripeDetail'},400);
    return response({stage:stages[name],...identity,...(fault==='foreign-scope'&&name==='advance'?{subscriptionId:id(91)}:{}),
      ...(order.indexOf(name)>=2?{setupOperationId:id(11)}:{}),...(order.indexOf(name)>=4?{resumeOperationId:id(12)}:{}),
      ...(name==='setup-permit'?permit:{})});
  };
  const dependencies = {
    fetch,preflight:cleanup('preflight'),
    createBootstrap:()=>({prepare:async limits=>{ownerSignal=limits.signal;ownerDeadline=limits.deadline;return cleanup('prepare')();},
      verify:async()=>true,start:async limits=>{assert.equal(limits.signal,ownerSignal);return cleanup('start')();},
      resources:()=>({cliDirectory:'/tmp/synthetic'}),stop:cleanup('bootstrap-stop'),
      withOptionalTrialRuntime:async cb=>{
        assert.equal(ownerSignal.aborted,false);
        return cb({endpoint:'http://127.0.0.1:18511',capability:(fault==='new-capability'&&calls.includes('open')?'x':'s').repeat(43)});
      }}),
    createCli:()=>({checkSavedProfile:async ()=>{
      calls.push('profile');
      if(fault==='profile-error')throw Error('synthetic credential data');
      return fault==='profile-expired'?'EXPIRED':'READY';
    },validateBinding:async limits=>{
      assert.equal(limits.signal,ownerSignal);
      assert.equal(limits.deadline,ownerDeadline);
      return cleanup('binding')();
    },startListener:async limits=>{assert.equal(limits.signal,ownerSignal);return cleanup('listen')();},
      assertBillingListener:async()=>true,stopListener:cleanup('listener-stop')}),
    createFixture:()=>fixture,
    createEvidence:async () => ({
      pause:async () => {calls.push('pause-proof');return fault!=='pause-proof';},
      pending:async () => {calls.push('open-proof');return fault!=='open-proof';},
      paid:async () => {calls.push('paid-proof');return fault!=='paid-proof';},
    }),
  };
  const browser = async value => {calls.push('browser');assert.deepEqual(value,permit);return fault!=='browser';};
  const limits = {deadline:Date.now()+10000};
  return {calls,dependencies,browser,limits,create:()=>createOwnedBillingOptionalTrial(options,dependencies)};
}

test('runs only the original scope, with evidence before pay and cleanup before acceptance', async () => {
  const f=fixture(), run=f.create();
  const result=await run.run(f.browser,f.limits);
  assert.equal(result.optionalTrialAccepted,true);
  assert.equal(result.cleanupConfirmed,true);
  assert.deepEqual(f.calls,['preflight','profile','prepare','binding','listen','start','provision','open','advance','pause-proof',
    'setup-permit','browser','setup-install','resume','open-proof','pay','paid-proof','abort','fixture-close','listener-stop','bootstrap-stop']);
  assert.doesNotMatch(JSON.stringify(result),/synthetic|secret_|Bearer/);
  await assert.rejects(run.run(f.browser,f.limits),/SINGLE_USE/);
});

for(const [fault,code] of [['profile-expired','CLI_PROFILE_EXPIRED'],['profile-error','CLI_PROFILE']]) {
  test('refuses '+fault+' before preparing or stopping any Docker resources',async ()=>{
    const f=fixture(fault),run=f.create();
    await assert.rejects(run.run(f.browser,f.limits),{message:'billing_'+code});
    assert.deepEqual(f.calls,['preflight','profile','listener-stop']);
    assert.equal(run.resources().phase,'CLI_PROFILE');
    assert.equal(run.resources().code,code);
    assert.equal(run.resources().cleanupConfirmed,true);
  });
}

for (const [fault,stop] of [['foreign-scope','advance'],['new-capability','open'],['pause-proof','pause-proof'],['browser','browser'],
  ['setup-install','setup-install'],['open-proof','open-proof'],['pay','pay'],['paid-proof','paid-proof'],['abort','paid-proof']]) {
  test('fails closed without repeating writes after '+fault, async () => {
    const f=fixture(fault),run=f.create();
    await assert.rejects(run.run(f.browser,f.limits),e=>!e.message.includes('privateStripeDetail'));
    const abort=f.calls.indexOf('abort');
    assert.equal(f.calls[abort-1],stop);
    assert.deepEqual(f.calls.slice(abort),['abort','fixture-close','listener-stop','bootstrap-stop']);
    assert.equal(run.resources().optionalTrialAccepted,false);
    assert.equal(run.resources().cleanupConfirmed,fault!=='abort');
  });
}
