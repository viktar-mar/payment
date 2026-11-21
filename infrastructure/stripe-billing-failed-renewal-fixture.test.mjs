import test,{before,after} from 'node:test';import assert from 'node:assert/strict';
import * as api from './stripe-billing-provision.mjs';
import {opts,responses} from './fixtures/failed-renewal-descriptors.mjs';
import {provisionHarness} from './fixtures/failed-renewal-provision-harness.mjs';
const saved=globalThis.fetch;let escaped=0;before(()=>{globalThis.fetch=()=>{escaped++;throw Error('blocked');};});after(()=>{globalThis.fetch=saved;assert.equal(escaped,0);});
for(const reject of ['none','dispatch','ack'])test('fixed fixture consumes the arm once when ledger rejection is '+reject,async()=>{
 const {fixture,ids,writes,requests}=await provisionHarness({onWrite(name){if(name.endsWith('decline-arm-'+reject+'.json'))throw Error('uncertain ledger write');}});
 await fixture.provision({deadline:Date.now()+10000});assert.equal(requests.filter(r=>r.path.endsWith('/prices'))[0].body.type,'SUBSCRIPTION');assert.equal(requests.find(r=>r.path.endsWith('/subscription-policy')&&r.method==='POST').headers['Idempotency-Key'].startsWith('billing:'),true);
 await fixture.acknowledgeCustomer({customerId:ids[4],siteId:ids[1]},{deadline:Date.now()+1000});
 await fixture.beforeDispatch('create','sdk-original-create',undefined,{deadline:Date.now()+1000});assert.equal(writes.at(-1).value.key,'sdk-original-create');
 await fixture.acknowledge('create',{subscriptionId:ids[5],operationId:ids[6]},{deadline:Date.now()+1000});
 await fixture.beforeDispatch('confirm','sdk-original-confirm',{subscriptionId:ids[5],operationId:ids[6],attemptId:ids[7]},{deadline:Date.now()+1000});
 await fixture.acknowledge('confirm',{attemptId:ids[7],state:'OBSERVE',nextActionAllowed:false},{deadline:Date.now()+1000});
 const l={deadline:Date.now()+5000},[boundary,armed]=responses();
 assert.equal(requests.find(r=>r.path.endsWith('/subscription-policy')&&r.method==='POST').body.grace_days,3);
 if(reject!=='none'){
  if(reject==='dispatch')await assert.rejects(fixture.beforeDeclineArm(boundary,l));
  else{await fixture.beforeDeclineArm(boundary,l);await assert.rejects(fixture.acknowledgeDeclineArm(armed,l));}
  await assert.rejects(fixture.beforeDeclineArm(boundary,l));await assert.rejects(fixture.acknowledgeDeclineArm(armed,l));
  assert.deepEqual(writes.filter(w=>w.name.includes('decline-arm')).map(w=>w.value.phase),reject==='dispatch'?['DISPATCHING']:['DISPATCHING','ACKNOWLEDGED']);
  await fixture.close();return;
 }
 assert.equal(await fixture.beforeDeclineArm(boundary,l),true);
 assert.equal(writes.at(-1).value.phase,'DISPATCHING');
 assert.equal(await fixture.acknowledgeDeclineArm(armed,l),true);
 assert.equal(writes.at(-1).value.phase,'ACKNOWLEDGED');
 await assert.rejects(fixture.beforeDeclineArm(boundary,l));await assert.rejects(fixture.acknowledgeDeclineArm(armed,l));
 assert.ok(!JSON.stringify(writes).includes('secret-sentinel'));await fixture.close();
});

const failWrite = suffix => ({onWrite(name) {
 if (name.endsWith(suffix)) throw Error('private write failure');
}});
const replaceResponse = (suffix, replacement) => ({onFetch(path, settings, response) {
 return path.endsWith(suffix) ? replacement() : response;
}});
const checkpoints = [
 ['PROVISION_CALLBACK_INTENT', failWrite('-callback-dispatch.json')],
 ['PROVISION_CALLBACK_PUT', replaceResponse('/callback', () => { throw Error('private transport failure'); })],
 ['PROVISION_CALLBACK_PUT', replaceResponse('/callback', () => new Response('private malformed JSON'))],
 ['PROVISION_CALLBACK_VALIDATE', replaceResponse('/callback', () => Response.json({secret:'private-invalid-secret'}))],
 ['PROVISION_RECEIVER_CONFIGURE', replaceResponse('/configure', () => new Response(null,{status:403}))],
 ['PROVISION_MAILPIT_READ', replaceResponse('/api/v1/messages', () => new Response(null,{status:503}))],
 ['PROVISION_MAILPIT_EMPTY', replaceResponse('/api/v1/messages', () => Response.json({total:1,messages:[{}]}))],
 ['PROVISION_FIXTURE_READY', failWrite('-fixture.json')],
];

const {runId,expectedSource}=opts();
const checkpoint=(fixture,limits)=>api.billingFailedRenewalProvisionCheckpoint(fixture,limits,runId,expectedSource);

for (const [index, [expected, injection]] of checkpoints.entries()) {
 test('site preparation preserves '+expected+' after synthetic failure '+index, async () => {
  const {fixture,writes,requests}=await provisionHarness(injection);
  const limits={deadline:Date.now()+10000};
  assert.equal(checkpoint(fixture,limits),null);
  await assert.rejects(fixture.provision(limits));
  assert.equal(checkpoint(fixture,limits),expected);
  assert.equal(api.isFailedRenewalProvisionCheckpoint(expected),true);
  const before=[writes.length,requests.length];
  await assert.rejects(fixture.provision({deadline:Date.now()+10000}));
  assert.deepEqual([writes.length,requests.length],before);
  await fixture.close();
  assert.equal(checkpoint(fixture,limits),expected);
 });
}

test('checkpoint authority belongs to the failed-renewal fixture and ends on successful provision',async () => {
 const limits={deadline:Date.now()+10000};
 const {fixture,writes}=await provisionHarness();
 await fixture.provision(limits);
 assert.equal(checkpoint(fixture,limits),null);
 assert.deepEqual(writes.slice(-2).map(w=>w.value.phase),['DISPATCHING','READY']);
 const early=await provisionHarness(replaceResponse('/inspection', () => Response.json({})));
 await assert.rejects(early.fixture.provision(limits));
 assert.equal(checkpoint(early.fixture,limits),null);
 const configuration=opts();
 delete configuration.env.PAYMENT_STRIPE_BILLING_FAILED_RENEWAL;
 const ordinary=await provisionHarness({...failWrite('-fixture.json'),configuration,factory:api.createBillingFixture});
 await assert.rejects(ordinary.fixture.provision(limits));
 assert.ok(ordinary.writes.at(-1).name.endsWith('-fixture.json'));
 assert.equal(checkpoint(ordinary.fixture,limits),null);
 const before=[ordinary.writes.length,ordinary.requests.length];
 await assert.rejects(ordinary.fixture.provision(limits));
 assert.deepEqual([ordinary.writes.length,ordinary.requests.length],before);
 const foreign=new Proxy({}, {get(){throw Error('untrusted getter');}});
 assert.equal(checkpoint(foreign,limits),null);
 assert.equal(api.isFailedRenewalProvisionCheckpoint(foreign),false);
 assert.equal(api.isFailedRenewalProvisionCheckpoint('PROVISION_CALLBACK_OK'),false);
});

test('pending receiver configuration records its checkpoint and ignores late completion after abort',async () => {
 const entered=Promise.withResolvers(),release=Promise.withResolvers(),controller=new AbortController();
 const {fixture,requests,writes}=await provisionHarness({async onFetch(path,settings,response) {
  if(path==='/configure'){entered.resolve();await release.promise;}
  return response;
 }});
 const limits={signal:controller.signal,deadline:Date.now()+10000};
 const provision=fixture.provision(limits);
 await entered.promise;
 assert.equal(checkpoint(fixture,limits),'PROVISION_RECEIVER_CONFIGURE');
 controller.abort();
 await assert.rejects(provision,/billing_ABORTED/);
 release.resolve();
 await new Promise(resolve=>setImmediate(resolve));
 assert.equal(requests.at(-1).path,'/configure');
 assert.equal(writes.at(-1).value.phase,'DISPATCHING');
 assert.equal(checkpoint(fixture,limits),'PROVISION_RECEIVER_CONFIGURE');
});

test('expired READY write retains its checkpoint and cannot unlock customer operations',async t => {
 const now=Date.now(),deadline=now+10000;
 t.mock.timers.enable({apis:['Date'],now});
 const entered=Promise.withResolvers(),release=Promise.withResolvers();
 const {fixture,ids,writes}=await provisionHarness({async onWrite(name) {
  if(name.endsWith('-fixture.json')){entered.resolve();await release.promise;}
 }});
 const limits={deadline};
 const provision=fixture.provision(limits);
 await entered.promise;
 assert.equal(checkpoint(fixture,limits),'PROVISION_FIXTURE_READY');
 const customer={customerId:ids[4],siteId:ids[1]};
 await assert.rejects(fixture.acknowledgeCustomer(customer,{deadline}),/billing_FIXTURE/);
 t.mock.timers.tick(10000);
 release.resolve();
 await assert.rejects(provision,/billing_ABORTED/);
 assert.equal(checkpoint(fixture,limits),'PROVISION_FIXTURE_READY');
 await assert.rejects(fixture.acknowledgeCustomer(customer,{deadline:Date.now()+1000}),/billing_FIXTURE/);
 assert.equal(writes.at(-1).value.phase,'READY');
});

test('a stale checkpoint cannot describe another invocation, run, or source',async () => {
 const {fixture}=await provisionHarness(failWrite('-callback-dispatch.json'));
 const limits={deadline:Date.now()+10000};
 await assert.rejects(fixture.provision(limits));
 assert.equal(checkpoint(fixture,limits),'PROVISION_CALLBACK_INTENT');
 const foreign=new Proxy({}, {get(){throw Error('untrusted getter');}});
 assert.equal(checkpoint(fixture,{...limits}),null);
 assert.equal(api.billingFailedRenewalProvisionCheckpoint(fixture),null);
 assert.equal(api.billingFailedRenewalProvisionCheckpoint(fixture,limits,'different-run',expectedSource),null);
 assert.equal(api.billingFailedRenewalProvisionCheckpoint(fixture,limits,runId,'different-source'),null);
 assert.equal(checkpoint(fixture,foreign),null);
 assert.equal(api.billingFailedRenewalProvisionCheckpoint(fixture,limits,foreign,foreign),null);
});
