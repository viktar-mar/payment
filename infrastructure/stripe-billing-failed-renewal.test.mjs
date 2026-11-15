import test,{before,after} from 'node:test';
import assert from 'node:assert/strict';
import {opts,scope,initial,prepared,confirmed,confirmRequest,methods,routes,responses,id,E,G,iso} from './fixtures/failed-renewal-descriptors.mjs';
import * as owners from './stripe-billing-first-payment.mjs';
import * as clients from './stripe-billing-first-payment-runtime.mjs';
const saved=globalThis.fetch;let escapes=0;
before(()=>{globalThis.fetch=()=>{escapes++;throw Error('network blocked');};});after(()=>{globalThis.fetch=saved;assert.equal(escapes,0);});
const limits=()=>({deadline:Date.now()+5000});
const proof=()=>({ownedVerified:true,cashVerified:true,journalVerified:true,signedCallbacksVerified:true});
function harness(){
 const calls=[],mark=name=>async()=>{calls.push(name);return true;},vs=responses();
 const fixture={resources:scope,provision:mark('fixture'),acknowledgeCustomer:mark('customer'),beforeDispatch:mark('dispatch'),acknowledge:mark('initial-ack'),beforeDeclineArm:mark('arm-dispatch'),acknowledgeDeclineArm:mark('arm-ack'),close:mark('fixture-close')};
 const bootstrap={resources:()=>({cliDirectory:'/inert'}),prepare:mark('prepare'),start:mark('start'),stop:mark('stop'),verifyForwardTarget(){},persistWebhookSecret(){}};
 const cli={validateBinding:mark('binding'),startListener:mark('listener'),stopListener:mark('listener-stop'),assertBillingListener:mark('listen-check')};
 const runtime={ready:mark('ready'),prepare:async()=>prepared(),confirm:async()=>confirmed(),observe:async()=>{calls.push('observe');return initial();},...Object.fromEntries(methods.map((name,i)=>[name,async()=>{calls.push(name);return vs[i];}])),close:mark('runtime-close')};
 const dependencies={preflight:mark('preflight'),createBootstrap:async()=>bootstrap,createCli:async()=>cli,createFixture:async()=>fixture,createRuntime:()=>runtime,login:mark('login'),pause:mark('pause'),prove:async()=>{calls.push('initial-proof');return proof();},proveFailedRenewal:async()=>{calls.push('final-proof');return proof();}};
 const journey={initial:async(p,l)=>{await p.prepare({token:'inert',origin:scope().endpoints.demo},'sdk-create',l);await p.confirm(confirmRequest(),l);},method:async(p,l)=>{
  assert.deepEqual(Object.keys(p).sort(),['confirmMethod','prepareMethod','selectMethod']);
  const {subscriptionId,operationId,action}=await p.prepareMethod(l),session=await p.selectMethod(l),permit=await p.confirmMethod(l);
  assert.equal(subscriptionId,id(7));assert.equal(operationId,id(35));
  assert.equal(action.secret,session.action_token);assert.equal(permit.browser_confirm_allowed,true);calls.push('manual-confirm');return true;
 },close:mark('browser-close')};
 return {journey,calls,vs,fixture,bootstrap,cli,runtime,dependencies};
}
const owner=h=>owners.createOwnedBillingFailedRenewal(opts(),h.dependencies);
test('same held journey, original proof before E, ledger before fixed arm, genuine publication before proof and definite close',async()=>{
 const h=harness(),o=await owner(h),r=await o.run(h.journey,limits());
 assert.deepEqual(h.calls.slice(h.calls.indexOf('observe')),['observe','initial-proof','boundary','arm-dispatch','armDecline','arm-ack',...methods.slice(2,9),'manual-confirm',...methods.slice(9),'final-proof','browser-close','runtime-close','listener-stop','stop','fixture-close']);
 assert.equal(r.scope,'OWNED_FAILED_RENEWAL_GRACE_RECOVERY');assert.equal(r.cleanupConfirmed,true);assert.equal(r.failedRenewalAccepted,true);assert.equal(r.initialAccepted,true);assert.equal(r.full5bAccepted,false);assert.ok(!JSON.stringify(r).includes('method-inert'));await assert.rejects(o.run(h.journey,limits()));
});
for(const [name,mutate,forbidden] of [
 ['initial proof',h=>h.dependencies.prove=async()=>null,'boundary'],
 ['arm intent',h=>h.fixture.beforeDeclineArm=async()=>false,'armDecline'],
 ['uncertain arm',h=>h.runtime.armDecline=async()=>{throw Error('sentinel');},'arm-ack'],
 ['arm ACK persistence',h=>h.fixture.acknowledgeDeclineArm=async()=>false,'failedRenewal'],
 ['changed predecessor',h=>h.vs[2].boundary_ready_id=id(77),'graceLastSecond'],
 ['wrong exact access',h=>h.vs[4].access_allowed=true,'graceAfter'],
 ['method phase incomplete',h=>h.journey.method=async()=>true,'applyMethod'],
 ['browser failure',h=>h.journey.method=async()=>{throw Error('sentinel');},'applyMethod'],
 ['replacement PI',h=>h.vs[9].recovery_payment_intent_id='pi_other','confirmRecovery'],
 ['ACK only',h=>h.vs[10].recovery_confirm_status='PENDING','observeRecovery'],
 ['missing publication',h=>delete h.vs[11].recovery_payment_id,'final-proof'],
 ['final evidence',h=>h.dependencies.proveFailedRenewal=async()=>null,'none'],
 ['held browser close failure',h=>h.journey.close=async()=>false,'none']
])test(name+' stops all following authority and cannot accept',async()=>{
 const h=harness();mutate(h);const o=await owner(h);await assert.rejects(o.run(h.journey,limits()));const r=await o.close();
 assert.equal(r.failedRenewalAccepted,false);assert.equal(r.initialAccepted,false);assert.equal(r.cleanupConfirmed,name!=='held browser close failure');
 assert.ok(!h.calls.includes(forbidden));assert.equal(h.calls.at(-1),'fixture-close');await assert.rejects(o.run(h.journey,limits()));
});
test('abort before method confirmation blocks later leaked browser port',async()=>{
 const h=harness(),controller=new AbortController();let leaked;
 h.journey.method=async(p,l)=>{leaked=p;await p.prepareMethod(l);controller.abort();};
 const o=await owner(h);await assert.rejects(o.run(h.journey,{...limits(),signal:controller.signal}));await assert.rejects(leaked.selectMethod(limits()));
 assert.ok(!h.calls.includes('selectMethod'));assert.ok(!h.calls.includes('applyMethod'));
});
for(const invalid of [j=>j.initial,j=>({...j,post:()=>{}}),j=>Object.assign(Object.create(j),{}),j=>Object.defineProperty(j,'method',{get(){throw Error('accessor');}})])test('owner rejects non-closed journey before preflight',async()=>{
 const h=harness(),o=await owner(h);await assert.rejects(o.run(invalid(h.journey),limits()));assert.ok(!h.calls.includes('preflight'));
});
function client(rewrite){
 const vs=responses(),calls=[],queue=[{phase:'NEW',code:'OK',run_id:id(1)},prepared(),confirmed(),initial(),...vs];
 const runtime=clients.createBillingFailedRenewalRuntime({configuration:opts(),bootstrap:{withFailedRenewalRuntime:cb=>cb({endpoint:'http://127.0.0.1:18083',capability:'x'.repeat(43)})}},{fetch:async(url,r)=>{calls.push([url,r]);const row=queue.shift();return new Response(rewrite?rewrite(row,calls.length):JSON.stringify(row));}});
 return {runtime,calls};
}
async function settle(r,l){await r.ready(l);await r.prepare(scope(),l);await r.confirm(confirmRequest(),l);await r.observe(l);}
test('private client exposes only fixed operations, posts exact empty bodies and consumes once',async()=>{
 const {runtime:r,calls}=client(),l=limits();assert.deepEqual(Object.keys(r).sort(),['ready','prepare','confirm','observe','close',...methods].sort());
 await settle(r,l);for(const m of methods)await r[m](l);assert.deepEqual(calls.slice(4).map(([u,v])=>[new URL(u).pathname,v.method,v.body]),routes.map(s=>['/failed-renewal/'+s,'POST','{}']));await assert.rejects(r.observeRecovery(l));assert.equal(calls.length,16);
});
for(const [i,change] of [[0,v=>v.period_start=iso(E+1)],[1,v=>v.arm_run_id=id(88)],[2,v=>v.grace_deadline=iso(G+1)],[5,v=>v.grace_after_frozen_time=G],[7,v=>v.session.action_token='foreign'],[8,v=>v.browser_confirm_allowed=false],[9,v=>v.method_update_step_id=v.method_setup_step_id],[10,v=>v.recovery_confirm_resource_id=id(88)],[11,v=>v.recovery_publication_bill_id=id(88)]])test('client independently burns mismatched '+methods[i],async()=>{
 const {runtime:r,calls}=client((v,n)=>{if(n===5+i)change(v);return JSON.stringify(v);}),l=limits();await settle(r,l);for(const m of methods.slice(0,i))await r[m](l);
 await assert.rejects(r[methods[i]](l));await assert.rejects(r[methods[i]](l));assert.equal(calls.length,5+i);
});
for(const wire of [t=>t.replace('"secret":','"secret":"other","secret":'),t=>t.replace('"secret":','"se\\u0063ret":')])test('duplicate/escaped nested browser response keys consume client',async()=>{
 const {runtime:r}=client((v,n)=>n===11?wire(JSON.stringify(v)):JSON.stringify(v)),l=limits();await settle(r,l);for(const m of methods.slice(0,6))await r[m](l);await assert.rejects(r.prepareMethod(l));await assert.rejects(r.selectMethod(l));
});
test('expired arm never dispatches or resumes',async()=>{const {runtime:r,calls}=client(),l=limits();await settle(r,l);await r.boundary(l);await assert.rejects(r.armDecline({deadline:1}));await assert.rejects(r.armDecline(l));assert.equal(calls.length,5);});
