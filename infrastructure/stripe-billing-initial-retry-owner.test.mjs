import assert from 'node:assert/strict';
import test from 'node:test';
import {createHash} from 'node:crypto';
import * as owners from './stripe-billing-acceptance.mjs';
import {validateBillingAcceptanceOptions, snapshotBillingAcceptanceEnvironment} from './stripe-billing-acceptance-options.mjs';
import {createBillingFixture} from './stripe-billing-provision.mjs';
import {provisionHarness} from './fixtures/failed-renewal-provision-harness.mjs';
import {id} from './fixtures/failed-renewal-descriptors.mjs';
import {acceptanceBrowserFailure,finishBrowserCleanup} from './stripe-billing-first-payment-browser-diagnostic.mjs';

const scenario = 'initial_decline_retry';
const profileFlag = 'PAYMENT_STRIPE_BILLING_RETRY_CLI_PROFILE_RUN_ID';
const input = () => ({
  env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true',PAYMENT_STRIPE_BILLING_CASE:scenario},
  runId:id(1),expectedSource:'a'.repeat(40),daemon:'unix:///Users/test/docker.sock',
  ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},
  approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'}, selectedCase:scenario,
});
const budget = {create:1,confirm:2,retry:1};
const identity = {subscriptionId:id(7),operationId:id(8),attemptId:id(9)};
const successor = {...identity,attemptId:id(10)};
const declined = {attemptId:id(9),state:'DECLINED',retryAllowed:true,successorAttemptId:null,nextActionAllowed:false};
const recovered = {attemptId:id(10),state:'OBSERVE',nextActionAllowed:false};
const digest = value => createHash('sha256').update(value).digest('hex');
const limits = () => ({deadline:Date.now()+10000});

test('initial retry requires matching explicit case and exact budget; legacy factories stay isolated', async () => {
  const configuration = input();
  const admitted = validateBillingAcceptanceOptions(configuration);
  assert.ok(admitted);
  assert.deepEqual(admitted.budget,budget);
  assert.deepEqual(validateBillingAcceptanceOptions(admitted),admitted);
  assert.deepEqual(snapshotBillingAcceptanceEnvironment(configuration.env),configuration.env);
  const baseEnv = {...configuration.env};
  delete baseEnv.PAYMENT_STRIPE_BILLING_CASE;
  for (const changed of [{env:baseEnv},{selectedCase:'initial_success'},{selectedCase:'initial_cancel_withdraw'},
    {budget:{...budget,confirm:1}},{budget:{...budget,retry:2}},{budget:{...budget,advance:1}}])
    assert.equal(validateBillingAcceptanceOptions({...configuration,...changed}),null);
  for (const factory of [owners.createOwnedBillingAcceptance,owners.createOwnedBillingCancellation])
    await assert.rejects(factory(configuration),/OPT_IN_REQUIRED/);
  await assert.rejects(owners.createOwnedBillingDeclineRetry({...configuration,env:baseEnv,selectedCase:'initial_success'}),/OPT_IN_REQUIRED/);
});

test('only initial retry retains a canonical prior Sandbox profile selection', () => {
  const configuration = input();
  configuration.env[profileFlag]=id(99);
  const admitted = validateBillingAcceptanceOptions(configuration);
  assert.equal(admitted?.env[profileFlag],id(99));
  assert.deepEqual(validateBillingAcceptanceOptions(admitted),admitted);
  assert.equal(snapshotBillingAcceptanceEnvironment(configuration.env)?.[profileFlag],id(99));
  for (const invalid of ['',null,undefined,'../'+id(99),id(99)+'\n',configuration.runId,'031b5ae7-6603-4339-b1b3-77231291ff34']) {
    const changed={...configuration,env:{...configuration.env,[profileFlag]:invalid}};
    assert.equal(validateBillingAcceptanceOptions(changed),null);
  }
  for (const selectedCase of ['initial_cancel_withdraw','initial_3ds','initial_missed_reordered']) {
    const changed={...configuration,selectedCase,env:{...configuration.env,PAYMENT_STRIPE_BILLING_CASE:selectedCase}};
    assert.equal(snapshotBillingAcceptanceEnvironment(changed.env),null);
    assert.equal(validateBillingAcceptanceOptions(changed),null);
  }
  const inherited=Object.assign(Object.create({[profileFlag]:id(99)}),input().env);
  assert.equal(snapshotBillingAcceptanceEnvironment(inherited),null);
  const accessor=input().env;
  Object.defineProperty(accessor,profileFlag,{enumerable:true,get(){assert.fail('profile selector accessor invoked');}});
  assert.equal(snapshotBillingAcceptanceEnvironment(accessor),null);
});

async function harness({prove,write,clean=true,profile,binding=true}={}) {
  const configuration = input(), calls = [];
  if(profile)configuration.env[profileFlag]=id(99);
  const f = await provisionHarness({configuration,factory:createBillingFixture,onWrite:write});
  const yes = async () => true;
  const authorization=[];
  const cli = {validateBinding:async()=>{authorization.push('binding');return binding;},
    startListener:async()=>{authorization.push('listener');return true;},assertBillingListener:yes,stopListener:yes};
  const bootstrap = {prepare:yes,start:async()=>{authorization.push('runtime');return true;},resources:()=>({cliDirectory:'inert'}),stop:async()=>clean};
  const owner = await owners.createOwnedBillingDeclineRetry(configuration,{
    preflight:yes,createBootstrap:async()=>bootstrap,createCli:async()=>cli,
    login:async()=>{authorization.push('device login');return true;},createFixture:async()=>f.fixture,
    proveInitialRetry:async value => {
      calls.push(value.phase);
      assert.equal(value.cli,cli);assert.equal(value.bootstrap,bootstrap);assert.equal(value.fixture,f.fixture);
      assert.equal(value.configuration.selectedCase,scenario);
      const first = {attemptId:id(9),confirmKeyHash:digest(id(12))};
      const attempts = value.phase==='declined' ? {first}
        : {first,retry:{keyHash:digest(id(13)),attemptId:id(10)},second:{attemptId:id(10),confirmKeyHash:digest(id(14))}};
      assert.deepEqual(value.attempts,attempts);
      return prove ? prove(value.phase) : true;
    },
  });
  return {owner,...f,calls,authorization};
}
async function start(port) {
  await port.acknowledgeCustomer({customerId:id(4),siteId:id(3)});
  await port.beforeDispatch('create',id(11));
  await port.acknowledge('create',{subscriptionId:id(7),operationId:id(8)});
  await port.beforeDispatch('confirm',id(12),identity);
}
async function finish(port) {
  await port.beforeRetry(id(13),identity);
  await port.acknowledgeRetry({attemptId:id(9),successorAttemptId:id(10)});
  await port.beforeSuccessorConfirm(id(14),successor);
  await port.acknowledgeSuccessor(recovered);
  return true;
}
async function journey(port) {
  await start(port);
  await port.acknowledge('confirm',declined);
  return finish(port);
}
test('owner proves the original decline before Retry and one recovered charge before acceptance', async () => {
  const f = await harness();
  const result = await f.owner.run(journey,limits());
  assert.equal(result.scope,'INITIAL_DECLINE_RETRY');
  assert.equal(result.declineRetryAccepted,true);
  assert.equal(result.cleanupConfirmed,true);assert.equal(result.full5bAccepted,false);
  assert.deepEqual(f.calls,['declined','recovered']);
  assert.deepEqual(f.authorization,['device login','binding','listener','runtime']);
  const ledger = f.writes.slice(f.writes.findIndex(r=>r.name.endsWith('-create-dispatch.json')));
  assert.deepEqual(ledger.map(r=>r.name.slice(12)),['create-dispatch.json','create-ack.json','confirm-dispatch.json','confirm-ack.json',
    'decline-proof.json','retry-dispatch.json','retry-ack.json','successor-confirm-dispatch.json','successor-confirm-ack.json','recovered-proof.json']);
  assert.equal(ledger[3].value.state,'DECLINED');
  assert.equal(ledger[5].value.attemptId,id(9));
  assert.equal(ledger[6].value.successorAttemptId,id(10));
  assert.equal(ledger[7].value.attemptId,id(10));
  assert.equal(f.fixture.resources().attemptId,id(9));
});
test('confirmed retry profile skips device login but verifies binding before listener and runtime',async()=>{
 const f=await harness({profile:true});
 const result=await f.owner.run(journey,limits());
 assert.equal(result.declineRetryAccepted,true);
 assert.deepEqual(f.authorization,['binding','listener','runtime']);
});
test('rejected retry profile ends before login, listener, runtime or subscription dispatch',async()=>{
 const f=await harness({profile:true,binding:false});
 await assert.rejects(f.owner.run(()=>assert.fail('browser used after binding refusal'),limits()),/billing_BINDING/);
 assert.deepEqual(f.authorization,['binding']);
 assert.equal(f.writes.some(r=>r.name.endsWith('-create-dispatch.json')),false);
 assert.equal((await f.owner.close()).cleanupConfirmed,true);
});
for(const [kind,browserClosed,bootstrapStopped,expected,cleanup] of [
 ['trusted',true,true,'BROWSER_INITIAL_SESSION_HTTP',true],
 ['trusted',true,false,'BROWSER_INITIAL_SESSION_HTTP',false],
 ['trusted',false,true,'BROWSER_INITIAL_SESSION_HTTP',false],
 ['foreign',true,true,'BROWSER',false],
])test('the owner retains only the trusted browser refusal and checked cleanup '+[kind,browserClosed,bootstrapStopped].join('/'),async()=>{
  const f=await harness({clean:bootstrapStopped});
  const error=kind==='trusted'?acceptanceBrowserFailure('BROWSER_INITIAL_SESSION_HTTP'):Error('BROWSER_INITIAL_SESSION_HTTP');
  finishBrowserCleanup(error,browserClosed);
  await assert.rejects(f.owner.run(async port=>{
    await port.acknowledgeCustomer({customerId:id(4),siteId:id(3)});
    await port.beforeDispatch('create',id(11));
    await port.acknowledge('create',{subscriptionId:id(7),operationId:id(8)});
    throw error;
  },limits()));
  const result=await f.owner.close();
  assert.equal(result.phase,'RETRY_ACK');
  assert.equal(result.code,expected);
  assert.equal(result.cleanupConfirmed,cleanup);
  assert.ok(!f.writes.some(row=>row.name.endsWith('-confirm-dispatch.json')));
});
for(const mode of ['unproved decline','wrong response','orphan retry','foreign scope','reused key','same successor','subscription as successor','stale successor','second confirm','unproved recovery','cleanup']) {
  test('retry owner stops on '+mode,async()=>{
    const f = await harness({prove:phase=>!(mode==='unproved decline'&&phase==='declined'||mode==='unproved recovery'&&phase==='recovered'),clean:mode!=='cleanup'});
    await assert.rejects(f.owner.run(async p=>{
      await start(p);
      if(mode==='orphan retry')await p.beforeRetry(id(13),identity);
      await p.acknowledge('confirm',mode==='wrong response'?{...declined,state:'UNKNOWN'}:declined);
      await p.beforeRetry(mode==='reused key'?id(12):id(13),mode==='foreign scope'?{...identity,operationId:id(99)}:identity);
      await p.acknowledgeRetry({attemptId:id(9),successorAttemptId:mode==='same successor'?id(9):mode==='subscription as successor'?id(7):id(10)});
      await p.beforeSuccessorConfirm(id(14),mode==='stale successor'?identity:successor);
      if(mode==='second confirm')await p.beforeSuccessorConfirm(id(15),successor);
      await p.acknowledgeSuccessor(recovered);
      return true;
    },limits()));
    assert.equal(f.owner.resources().initialAccepted,false);
    await assert.rejects(f.owner.run(journey,limits()),/SINGLE_USE/);
    if(['unproved decline','wrong response','orphan retry','foreign scope','reused key'].includes(mode))
      assert.equal(f.writes.some(r=>r.name.endsWith('-retry-dispatch.json')),false);
    if(mode==='subscription as successor')assert.equal(f.writes.some(r=>r.name.endsWith('-retry-ack.json')),false);
    assert.ok(f.writes.filter(r=>r.name.endsWith('-successor-confirm-dispatch.json')).length<=1);
  });
}
for(const receipt of ['confirm-ack','decline-proof','retry-dispatch','retry-ack','successor-confirm-dispatch','successor-confirm-ack','recovered-proof']) {
  test('uncertain '+receipt+' write cannot be replayed',async()=>{
    const f=await harness({write:name=>{if(name.endsWith('-'+receipt+'.json'))throw Error('private canary');}});
    await assert.rejects(f.owner.run(async p=>{
      try {await journey(p);}catch{}
      await p.beforeRetry(id(13),identity);
      return true;
    },limits()),/^Error: billing_/);
    assert.equal(f.owner.resources().declineRetryAccepted,false);
    assert.equal(f.writes.filter(r=>r.name.endsWith('-'+receipt+'.json')).length,1);
  });
}

test('a caught customer receipt storage failure cannot authorize CREATE',async()=>{
  const f=await harness({write:name=>{if(name.endsWith('-customer.json'))throw Error('unconfirmed');}});
  await assert.rejects(f.owner.run(async port=>{
    try { await port.acknowledgeCustomer({customerId:id(4),siteId:id(3)}); } catch {}
    await port.beforeDispatch('create',id(11));
    return true;
  },limits()));
  assert.equal(f.writes.some(r=>r.name.endsWith('-create-dispatch.json')),false);
});
test('Retry cannot race the independent decline proof',async()=>{
  let entered,release;
  const checking=new Promise(resolve=>{entered=resolve;});
  const pending=new Promise(resolve=>{release=resolve;});
  const f=await harness({prove:async phase=>{assert.equal(phase,'declined');entered();await pending;return true;}});
  await assert.rejects(f.owner.run(async port=>{
    await start(port);
    const ack=port.acknowledge('confirm',declined);
    ack.catch(()=>{});
    await checking;
    try { await port.beforeRetry(id(13),identity); } finally {release();}
    return true;
  },limits()));
  assert.equal(f.writes.some(r=>r.name.endsWith('-retry-dispatch.json')),false);
  assert.equal(f.owner.resources().declineRetryAccepted,false);
});
test('case and budget derive from the same admitted environment snapshot',()=>{
  const original=input();
  let reads=0;
  const env=new Proxy(original.env,{getOwnPropertyDescriptor(target,key){
    const property=Reflect.getOwnPropertyDescriptor(target,key);
    return key==='PAYMENT_STRIPE_BILLING_CASE' && ++reads>1 ? {...property,value:'unreviewed'} : property;
  }});
  assert.equal(validateBillingAcceptanceOptions({...original,env,selectedCase:'unreviewed'}),null);
});

test('a browser close failure keeps total cleanup unconfirmed even after both proofs',async()=>{
  const f=await harness();
  await assert.rejects(f.owner.run(async port=>{
    await journey(port);
    throw Error('private browser close error');
  },limits()));
  assert.deepEqual(f.calls,['declined','recovered']);
  assert.equal(f.owner.resources().cleanupConfirmed,false);
  assert.equal(f.owner.resources().declineRetryAccepted,false);
});
