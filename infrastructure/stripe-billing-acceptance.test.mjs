import test from 'node:test';
import assert from 'node:assert/strict';
const api=await import('./stripe-billing-acceptance.mjs').catch(()=>({}));
export const options=()=>({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true'},runId:'11111111-2222-4333-8444-555555555555',expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:'initial_success'});
const limits=()=>({deadline:Date.now()+10000});
function harness(change={}) {
 const calls=[],record=name=>async()=>{calls.push(name);return true;};
 const fixture={provision:record('provision'),resources:()=>({siteId:'site'}),beforeDispatch:record('persist'),acknowledge:record('ack'),close:record('fixture-close')};
 const bootstrap={prepare:record('prepare'),start:record('start'),verify:record('verify'),resources:()=>({cliDirectory:'/private/cli'}),stop:record('stop'),verifyForwardTarget(){},persistWebhookSecret(){}};
 const cli={loginInvocation:record('login'),completeLoginInvocation:record('complete'),validateBinding:record('binding'),startListener:record('listen'),stopListener:record('listener-stop'),assertBillingListener:record('guard'),bindOwnedBillingGraph:record('graph-bind'),observeOwnedBillingGraph:async()=>({providerVerified:true,initialCashVerified:true,lineCount:1,allocationCount:1,chargeCount:1})};
 const dependencies={preflight:record('preflight'),createBootstrap:async()=>bootstrap,createCli:async()=>cli,login:record('authorize'),createFixture:async()=>fixture,prove:async()=>({graph:{},cashVerified:true,journalVerified:true,signedCallbacksVerified:true}),...change};
 return {calls,fixture,bootstrap,cli,dependencies};
}
const journey=async(port)=>{await port.beforeDispatch('create','create-original');await port.acknowledge('create',{subscriptionId:'s',operationId:'o'});await port.beforeDispatch('confirm','confirm-original');await port.acknowledge('confirm',{state:'OBSERVE'});return true;};
test('factory is inert and admission precedes dependency access',async()=>{
 assert.equal(typeof api.createOwnedBillingAcceptance,'function');let reads=0;const deps=new Proxy({},{get(){reads++;throw Error('secret');}});
 await assert.rejects(()=>api.createOwnedBillingAcceptance({...options(),env:{}},deps),/OPT_IN_REQUIRED/);assert.equal(reads,0);
 const h=harness(),owner=await api.createOwnedBillingAcceptance(options(),h.dependencies);assert.deepEqual(h.calls,[]);assert.equal(owner.resources().initialAccepted,false);
});
test('one original dispatch chain requires all receipts and cleanup before scoped acceptance',async()=>{
 const h=harness(),owner=await api.createOwnedBillingAcceptance(options(),h.dependencies);const result=await owner.run(journey,limits());
 assert.equal(result.scope,'INITIAL_NON_CLOCK');assert.equal(result.initialAccepted,true);assert.equal(result.full5bAccepted,false);
 assert.deepEqual(h.calls.filter(x=>['prepare','authorize','binding','listen','start','provision','persist','ack','graph-bind','fixture-close','listener-stop','stop'].includes(x)),['prepare','authorize','binding','listen','start','provision','persist','ack','persist','ack','graph-bind','fixture-close','listener-stop','stop']);
 await assert.rejects(()=>owner.run(journey,limits()),/SINGLE_USE/);await owner.close(limits());
});
for(const missing of ['cashVerified','journalVerified','signedCallbacksVerified'])test('rejects missing '+missing,async()=>{
 const h=harness({prove:async()=>({graph:{},cashVerified:true,journalVerified:true,signedCallbacksVerified:true,[missing]:false})});const owner=await api.createOwnedBillingAcceptance(options(),h.dependencies);
 await assert.rejects(()=>owner.run(journey,limits()),/EVIDENCE/);assert.equal(owner.resources().initialAccepted,false);assert.ok(h.calls.includes('stop'));
});
for(const mode of ['duplicate','lost-ack','unknown','secret'])test('first failure stops '+mode+' without replay',async()=>{
 const h=harness(),owner=await api.createOwnedBillingAcceptance(options(),h.dependencies);
 await assert.rejects(()=>owner.run(async p=>{await p.beforeDispatch('create','key');if(mode==='duplicate')await p.beforeDispatch('create','key');if(mode==='unknown')await p.acknowledge('create',{state:'UNKNOWN'});if(mode==='secret')throw Error('sk_test_SECRET');return true;},limits()),e=>!e.message.includes('SECRET'));
 assert.equal(h.calls.filter(x=>x==='persist').length,1);assert.equal(owner.resources().initialAccepted,false);assert.ok(!h.calls.includes('graph-bind'));
});
test('abort interrupts hanging work, late receipt cannot accept, cleanup stays finite',async()=>{
 let resolve;const h=harness({prove:()=>new Promise(r=>resolve=r)}),owner=await api.createOwnedBillingAcceptance(options(),h.dependencies),controller=new AbortController();
 const pending=owner.run(journey,{deadline:Date.now()+10000,signal:controller.signal});await new Promise(r=>setTimeout(r,20));controller.abort();await assert.rejects(pending,/ABORTED/);
 resolve?.({graph:{},cashVerified:true,journalVerified:true,signedCallbacksVerified:true});assert.equal(owner.resources().initialAccepted,false);assert.ok(h.calls.includes('stop'));
});
test('concurrent run rejected and close revokes active journey',async()=>{
 const h=harness(),owner=await api.createOwnedBillingAcceptance(options(),h.dependencies);const pending=owner.run(()=>new Promise(()=>{}),limits());pending.catch(()=>{});await new Promise(r=>setTimeout(r,10));
 await assert.rejects(()=>owner.run(journey,limits()),/SINGLE_USE/);await owner.close(limits());await assert.rejects(pending,/ABORTED/);assert.equal(h.calls.filter(x=>x==='stop').length,1);
});
test('caught persistence failure remains sticky and cannot regain financial authority',async()=>{
 const h=harness();h.fixture.beforeDispatch=async()=>{throw Error('private-sentinel');};const owner=await api.createOwnedBillingAcceptance(options(),h.dependencies);
 await assert.rejects(()=>owner.run(async p=>{try{await p.beforeDispatch('create','key');}catch{}await p.acknowledge('create',{});return true;},limits()));assert.equal(h.calls.includes('ack'),false);assert.equal(h.calls.includes('graph-bind'),false);
});
test('cleanup failure cannot turn complete evidence into acceptance',async()=>{const h=harness();h.bootstrap.stop=async()=>{throw Error('secret');};const owner=await api.createOwnedBillingAcceptance(options(),h.dependencies);await assert.rejects(()=>owner.run(journey,limits()),/CLEANUP/);assert.equal(owner.resources().initialAccepted,false);});
test('deadline bounds a hanging step and cancels its owned signal',async()=>{let signal;const h=harness({preflight:(_c,l)=>{signal=l.signal;return new Promise(()=>{});}}),owner=await api.createOwnedBillingAcceptance(options(),h.dependencies);await assert.rejects(()=>owner.run(journey,{deadline:Date.now()+20}));assert.equal(signal.aborted,true);assert.equal(owner.resources().initialAccepted,false);});
test('literal false cleanup receipt blocks acceptance',async()=>{const h=harness();h.bootstrap.stop=async()=>false;const owner=await api.createOwnedBillingAcceptance(options(),h.dependencies);await assert.rejects(()=>owner.run(journey,limits()),/CLEANUP/);});
test('literal false listener readiness never permits financial dispatch',async()=>{const h=harness();h.cli.assertBillingListener=async()=>false;const owner=await api.createOwnedBillingAcceptance(options(),h.dependencies);await assert.rejects(()=>owner.run(journey,limits()));assert.equal(h.calls.includes('persist'),false);});
test('hostile rejection accessors never enter failure diagnostics',async()=>{let reads=0;const h=harness({preflight:async()=>{throw Object.defineProperty({},'message',{get(){reads++;throw Error('secret');}});}}),owner=await api.createOwnedBillingAcceptance(options(),h.dependencies);await assert.rejects(()=>owner.run(journey,limits()),/^Error: billing_/);assert.equal(reads,0);assert.equal(owner.resources().initialAccepted,false);});
test('resources distinguish owner closure from unconfirmed resource cleanup',async()=>{const h=harness();h.bootstrap.stop=async()=>false;const owner=await api.createOwnedBillingAcceptance(options(),h.dependencies);await assert.rejects(()=>owner.run(journey,limits()));assert.equal(owner.resources().cleanupConfirmed,false);});
