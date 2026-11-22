import test from 'node:test';
import assert from 'node:assert/strict';
import * as clients from './stripe-billing-first-payment-runtime.mjs';
import {opts,scope,id,prepared,confirmed,confirmRequest,initial,responses} from './fixtures/failed-renewal-descriptors.mjs';

const envelope=step=>JSON.stringify({code:'FAILED_RENEWAL_STOPPED',stage:'FAILED_RENEWAL_'+step});

async function armed(reply,graceReply){
 const sent=[],queue=[{code:'OK',run_id:id(1),phase:'NEW'},prepared(),confirmed(),initial(),...responses().slice(0,2)];
 const configuration=opts();
 const binding={endpoint:'http://127.0.0.1:'+configuration.controlPort,capability:'x'.repeat(43)};
 const bootstrap={withFailedRenewalRuntime:callback=>callback(binding)};
 const runtime=clients.createBillingFailedRenewalRuntime({configuration,bootstrap},{fetch:async url=>{
  const path=new URL(url).pathname;
  sent.push(path);
  if(path==='/failed-renewal/failed')return reply();
  if(path==='/failed-renewal/grace-last-second'&&graceReply)return graceReply();
  if(path==='/failed-renewal/abort')return new Response('{"code":"QUIESCENT"}');
  assert.ok(queue.length);return new Response(JSON.stringify(queue.shift()));
 }});
 const limits={deadline:Date.now()+10000};
 await runtime.ready(limits);
 await runtime.prepare({...scope(),origin:scope().endpoints.demo,createKey:'sdk-create',customerToken:'synthetic'},limits);
 await runtime.confirm(confirmRequest(),limits);
 await runtime.observe(limits);
 await runtime.boundary(limits);
 await runtime.armDecline(limits);
 return {runtime,limits,sent,bootstrap};
}

for(const [name,stage,status,expected] of [
 ['preparation','GRACE_LAST_SECOND_PREPARATION',400,'SERVER_PREPARATION'],
 ['clock advance','GRACE_LAST_SECOND_CLOCK_POST',400,'SERVER_CLOCK_POST'],
 ['access decision','GRACE_LAST_SECOND_ACCESS',400,'SERVER_ACCESS'],
 ['wrong route','FAILED_RENEWAL_ADMISSION',400,'HTTP'],
 ['unclassified','GRACE_LAST_SECOND_PRIVATE',400,'HTTP'],
 ['unexpected status','GRACE_LAST_SECOND_ACCESS',403,'HTTP'],
 ['invalid success','GRACE_LAST_SECOND_ACCESS',200,'RESPONSE'],
])test('grace last second distinguishes '+name,async()=>{
 const {runtime,limits,sent}=await armed(()=>new Response(JSON.stringify(responses()[2])),
  ()=>new Response(JSON.stringify({code:'FAILED_RENEWAL_STOPPED',stage}),{status}));
 await runtime.failedRenewal(limits);
 await assert.rejects(runtime.graceLastSecond(limits),{message:'billing_RUNTIME'});
 assert.equal(clients.billingFailedRenewalRuntimeCheckpoint(runtime,limits),'GRACE_LAST_SECOND_'+expected);
 assert.equal(clients.billingFailedRenewalRuntimeCheckpoint(runtime,{...limits}),null);
 await assert.rejects(runtime.graceLastSecond(limits));
 await assert.rejects(runtime.graceAt(limits));
 assert.equal(sent.filter(path=>path==='/failed-renewal/grace-last-second').length,1);
 assert.equal(sent.at(-1),'/failed-renewal/grace-last-second');
});

const observed=['CLAIM','AUTHORITY','CLOCK_BEFORE','GRAPH','CUSTOMER','SUBSCRIPTION','INVOICES','INITIAL_INVOICE','RENEWAL_INVOICE',
 'FAILED_ALLOCATION','FAILED_INTENT','FAILED_CHARGE','FAILED_REVERSE','CLOCK_AFTER','ACCEPTANCE','PUBLICATION'];
for(const step of ['CLOCK_ADVANCE','ADMISSION','OBSERVATION','EVIDENCE',...observed.map(name=>'OBSERVATION_'+name)])test('failed renewal admits only its own server '+step+' refusal',async()=>{
 const {runtime,limits,sent}=await armed(()=>new Response(envelope(step),{status:400}));
 const code='FAILED_RENEWAL_SERVER_'+step;
 await assert.rejects(runtime.failedRenewal(limits),{message:'billing_RUNTIME'});
 assert.equal(clients.billingFailedRenewalRuntimeCheckpoint(runtime,limits),code);
 assert.equal(clients.billingFailedRenewalRuntimeCheckpoint(runtime,{...limits}),null);
 assert.equal(clients.billingFailedRenewalRuntimeCheckpoint({...runtime},limits),null);
 assert.equal(clients.isFailedRenewalRuntimeCheckpoint(code),true);
 assert.equal(clients.isBillingFirstPaymentDiagnostic(code),false);
 await assert.rejects(runtime.failedRenewal(limits));await assert.rejects(runtime.graceLastSecond(limits));
 assert.equal(sent.at(-1),'/failed-renewal/failed');assert.equal(sent.length,7);
});

for(const [name,wire,status,code] of [
 ['duplicate',envelope('ADMISSION').replace('"stage":','"stage":"private","stage":'),400,'FAILED_RENEWAL_HTTP'],
 ['BOM','\uFEFF'+envelope('ADMISSION'),400,'FAILED_RENEWAL_HTTP'],
 ['suffix',envelope('ADMISSION')+'\n',400,'FAILED_RENEWAL_HTTP'],
 ['oversized', ' '.repeat(17000)+envelope('ADMISSION'),400,'FAILED_RENEWAL_HTTP'],
 ['foreign stage',envelope('private'),400,'FAILED_RENEWAL_HTTP'],
 ['foreign observation step',envelope('OBSERVATION_PRIVATE'),400,'FAILED_RENEWAL_HTTP'],
 ['escaped observation step',envelope('OBSERVATION_FAILED_INTENT').replace('INTENT','\\u0049NTENT'),400,'FAILED_RENEWAL_HTTP'],
 ['other route','{"code":"FAILED_RENEWAL_STOPPED","stage":"GRACE_AFTER"}',400,'FAILED_RENEWAL_HTTP'],
 ['unexpected HTTP status',envelope('OBSERVATION'),403,'FAILED_RENEWAL_HTTP'],
 ['failure body as success',envelope('OBSERVATION'),200,'FAILED_RENEWAL_RESPONSE'],
 ['drifted evidence',JSON.stringify({...responses()[2],failed_bill_id:id(11)}),200,'FAILED_RENEWAL_RESPONSE'],
])test('failed renewal refuses '+name+' without promoting server metadata',async()=>{
 const {runtime,limits}=await armed(()=>new Response(wire,{status}));
 await assert.rejects(runtime.failedRenewal(limits),{message:'billing_RUNTIME'});
 assert.equal(clients.billingFailedRenewalRuntimeCheckpoint(runtime,limits),code);
});

test('failed renewal separates rejected binding and transport from an accepted response',async()=>{
 const success=await armed(()=>new Response(JSON.stringify(responses()[2])));
 await success.runtime.failedRenewal(success.limits);
 assert.equal(clients.billingFailedRenewalRuntimeCheckpoint(success.runtime,success.limits),null);
 const refused=await armed(()=>assert.fail('no transport'));
 refused.bootstrap.withFailedRenewalRuntime=()=>false;
 await assert.rejects(refused.runtime.failedRenewal(refused.limits));
 assert.equal(clients.billingFailedRenewalRuntimeCheckpoint(refused.runtime,refused.limits),'FAILED_RENEWAL_AUTHORITY');
 const lost=await armed(()=>{throw new Error('private transport reason');});
 await assert.rejects(lost.runtime.failedRenewal(lost.limits),{message:'billing_RUNTIME'});
 assert.equal(clients.billingFailedRenewalRuntimeCheckpoint(lost.runtime,lost.limits),'FAILED_RENEWAL_TRANSPORT');
});

test('only the original HTTP status can admit a server substage',async()=>{
 let reads=0;
 const {runtime,limits}=await armed(()=>({
  get status(){return ++reads===1?403:400;},
  body:new Response(envelope('ADMISSION')).body,
 }));
 await assert.rejects(runtime.failedRenewal(limits));
 assert.equal(clients.billingFailedRenewalRuntimeCheckpoint(runtime,limits),'FAILED_RENEWAL_HTTP');
 assert.equal(reads,1);
});

for(const [method,stage,expected] of [
 ['failedRenewal','FAILED_RENEWAL_EVIDENCE','FAILED_RENEWAL_TRANSPORT'],
 ['graceLastSecond','GRACE_LAST_SECOND_ACCESS','GRACE_LAST_SECOND_TRANSPORT'],
])test('late refusal after cancel cannot replace a pending '+method+' transport checkpoint',async()=>{
 const entered=Promise.withResolvers(),release=Promise.withResolvers();
 const late=()=>{entered.resolve();return release.promise;};
 const {runtime,limits}=method==='failedRenewal'
  ?await armed(late)
  :await armed(()=>new Response(JSON.stringify(responses()[2])),late);
 if(method==='graceLastSecond')await runtime.failedRenewal(limits);
 const abort=new AbortController(),budget={...limits,signal:abort.signal};
 const pending=assert.rejects(runtime[method](budget));
 await entered.promise;abort.abort();await pending;await runtime.close(limits);
 release.resolve(new Response(JSON.stringify({code:'FAILED_RENEWAL_STOPPED',stage}),{status:400}));
 await new Promise(resolve=>setImmediate(resolve));
 assert.equal(clients.billingFailedRenewalRuntimeCheckpoint(runtime,budget),expected);
});
