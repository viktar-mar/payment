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

const publishRequest = replacement => replaceResponse('/publish', replacement);
const publicationProblem = (body = {}) => ({
 type:'urn:payment:problem:PROVIDER_RESULT_UNKNOWN',
 title:'Provider result requires recovery',
 status:409,
 code:'PROVIDER_RESULT_UNKNOWN',
 request_id:'private-request-canary',
 ...body,
});
const publishConflict = text => new Response(text,{status:409,headers:{'Content-Type':'application/problem+json'}});
for(const [stage,injection,sent] of [
 ['INTENT',failWrite('-publish-dispatch.json'),false],
 ['VERIFY',{onVerify:({writes})=>!writes.at(-1)?.name.endsWith('-publish-dispatch.json')},false],
 ['REQUEST',publishRequest(()=>{throw Error('private transport failure');}),true],
 ['HTTP_SERVER_ERROR',publishRequest(()=>new Response('private response',{status:503})),true],
 ['HTTP_PROVIDER_RESULT_UNKNOWN',publishRequest(()=>publishConflict(JSON.stringify(publicationProblem()))),true],
 ['HTTP_CONFLICT',publishRequest(()=>publishConflict(JSON.stringify(publicationProblem({extra:'private data'})))),true],
 ['HTTP_CONFLICT',publishRequest(()=>publishConflict(JSON.stringify(publicationProblem()).replace('"status":409','"status":409,"status":409'))),true],
 ['HTTP_CONFLICT',publishRequest(()=>publishConflict(JSON.stringify(publicationProblem({status:500})))),true],
 ['HTTP_CONFLICT',publishRequest(()=>publishConflict(' '.repeat(1025)+JSON.stringify(publicationProblem()))),true],
 ['RESPONSE',publishRequest(()=>new Response('private invalid JSON')),true],
 ['VALIDATE',publishRequest(()=>Response.json({provider_price_id:'private invalid provider reference'})),true],
 ['ACK',failWrite('-publish-ack.json'),true],
])test('price publication preserves '+stage+' without another mutation',async()=>{
 const {fixture,writes,requests}=await provisionHarness(injection);
 const limits={deadline:Date.now()+10000};
 await assert.rejects(fixture.provision(limits));
 const code='PROVISION_PUBLISH_'+stage;
 assert.equal(checkpoint(fixture,limits),code);
 assert.equal(api.isFailedRenewalProvisionCheckpoint(code),true);
 const publicationRequests=requests.filter(request=>request.path.endsWith('/publish'));
 assert.equal(publicationRequests.length,Number(sent));
 assert.ok(!requests.some(request=>request.path==='/inspection'));
 assert.equal(writes.at(-1).name.endsWith(stage==='ACK'?'-publish-ack.json':'-publish-dispatch.json'),true);
 const before=[writes.length,requests.length];
 await assert.rejects(fixture.provision(limits));
 assert.deepEqual([writes.length,requests.length],before);
 await fixture.close();
 assert.equal(checkpoint(fixture,limits),code);
});

test('a 409 retains its header checkpoint when the problem body arrives after abort',async()=>{
 const entered=Promise.withResolvers(),release=Promise.withResolvers(),abort=new AbortController();
 const body=new ReadableStream({async pull(stream){
  entered.resolve();await release.promise;
  stream.enqueue(Buffer.from(JSON.stringify(publicationProblem())));stream.close();
 }},{highWaterMark:0});
 const {fixture,writes}=await provisionHarness(publishRequest(()=>publishConflict(body)));
 const limits={deadline:Date.now()+10000,signal:abort.signal};
 const rejected=assert.rejects(fixture.provision(limits),/billing_ABORTED/);
 await entered.promise;
 assert.equal(checkpoint(fixture,limits),'PROVISION_PUBLISH_HTTP_CONFLICT');
 abort.abort();await rejected;
 release.resolve();
 await new Promise(resolve=>setImmediate(resolve));
 assert.equal(checkpoint(fixture,limits),'PROVISION_PUBLISH_HTTP_CONFLICT');
 assert.equal(writes.at(-1).name.endsWith('-publish-dispatch.json'),true);
});

test('a slow publication can finish beyond the default HTTP budget without sending twice',async t=>{
 t.mock.timers.enable({apis:['Date','setTimeout'],now:1000});
 const entered=Promise.withResolvers(),release=Promise.withResolvers();
 const {fixture,requests}=await provisionHarness({async onFetch(path,settings,response){
  if(path.endsWith('/publish')){entered.resolve(settings.signal);await release.promise;}
  return response;
 }});
 const provision=fixture.provision({deadline:121000});
 const checked=assert.doesNotReject(provision);
 const signal=await entered.promise;
 t.mock.timers.tick(16000);
 const stillActive=!signal.aborted;
 release.resolve();
 await checked;
 assert.equal(stillActive,true);
 assert.equal(requests.filter(request=>request.path.endsWith('/publish')).length,1);
});

for(const budget of [25000,90000])test('publish stops at its effective '+budget+' ms limit and ignores a late success',async t=>{
 t.mock.timers.enable({apis:['Date','setTimeout'],now:1000});
 const entered=Promise.withResolvers(),release=Promise.withResolvers();
 const {fixture,requests,writes}=await provisionHarness({async onFetch(path,settings,response){
  if(path.endsWith('/publish')){entered.resolve(settings.signal);await release.promise;}
  return response;
 }});
 const limits={deadline:1000+(budget===25000?budget:120000)};
 const rejected=assert.rejects(fixture.provision(limits),/billing_ABORTED/);
 const signal=await entered.promise;
 t.mock.timers.tick(budget-1);
 assert.equal(signal.aborted,false);
 t.mock.timers.tick(1);
 await rejected;
 release.resolve();
 await new Promise(resolve=>setImmediate(resolve));
 assert.equal(signal.aborted,true);
 assert.equal(checkpoint(fixture,limits),'PROVISION_PUBLISH_REQUEST');
 assert.equal(requests.filter(request=>request.path.endsWith('/publish')).length,1);
 assert.equal(writes.at(-1).name.endsWith('-publish-dispatch.json'),true);
});

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
