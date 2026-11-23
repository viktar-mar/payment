import test,{before,after} from 'node:test';
import assert from 'node:assert/strict';
import {opts,scope,initial,prepared,confirmed,confirmRequest,methods,routes,responses,id,F,E,G,iso} from './fixtures/failed-renewal-descriptors.mjs';
import {data,inspect} from './fixtures/failed-renewal-evidence-rows.mjs';
import {provisionHarness} from './fixtures/failed-renewal-provision-harness.mjs';
import * as initialEvidence from './stripe-billing-first-payment-evidence.mjs';
import * as owners from './stripe-billing-first-payment.mjs';
import * as clients from './stripe-billing-first-payment-runtime.mjs';
import {browserFailure,finishBrowserCleanup,rememberBrowserProviderOrigin} from './stripe-billing-first-payment-browser-diagnostic.mjs';
const saved=globalThis.fetch;let escapes=0;
before(()=>{globalThis.fetch=()=>{escapes++;throw Error('network blocked');};});after(()=>{globalThis.fetch=saved;assert.equal(escapes,0);});
const limits=()=>({deadline:Date.now()+5000});
const proof=()=>({ownedVerified:true,cashVerified:true,journalVerified:true,signedCallbacksVerified:true});
const initialProof=()=>({...proof(),anchor:{
 initial_bill_id:id(11),initial_payment_id:id(12),initial_invoice_id:'in_original',
 initial_allocation_id:'inpay_original',initial_payment_intent_id:'pi_original'
}});
function harness(){
 const calls=[],mark=name=>async()=>{calls.push(name);return true;},vs=responses();
 const fixture={resources:scope,provision:mark('fixture'),acknowledgeCustomer:mark('customer'),beforeDispatch:mark('dispatch'),acknowledge:mark('initial-ack'),beforeDeclineArm:mark('arm-dispatch'),acknowledgeDeclineArm:mark('arm-ack'),close:mark('fixture-close')};
 const bootstrap={resources:()=>({cliDirectory:'/inert'}),prepare:mark('prepare'),start:mark('start'),stop:mark('stop'),verifyForwardTarget(){},persistWebhookSecret(){}};
 const cli={validateBinding:mark('binding'),startListener:mark('listener'),stopListener:mark('listener-stop'),assertBillingListener:mark('listen-check')};
 const runtime={ready:mark('ready'),prepare:async()=>prepared(),confirm:async()=>confirmed(),observe:async()=>{calls.push('observe');return initial();},...Object.fromEntries(methods.map((name,i)=>[name,async()=>{calls.push(name);return vs[i];}])),close:mark('runtime-close')};
 const dependencies={preflight:mark('preflight'),createBootstrap:async()=>bootstrap,createCli:async()=>cli,createFixture:async()=>fixture,createRuntime:()=>runtime,login:mark('login'),pause:mark('pause'),prove:async()=>{calls.push('initial-proof');return initialProof();},proveFailedRenewal:async()=>{calls.push('final-proof');return proof();}};
 const journey={initial:async(p,l)=>{await p.prepare({token:'inert',origin:scope().endpoints.demo},'sdk-create',l);await p.confirm(confirmRequest(),l);},method:async(p,l)=>{
  assert.deepEqual(Object.keys(p).sort(),['confirmMethod','prepareMethod','selectMethod']);
  const {subscriptionId,operationId,action}=await p.prepareMethod(l),session=await p.selectMethod(l),permit=await p.confirmMethod(l);
  assert.equal(subscriptionId,id(7));assert.equal(operationId,id(35));
  assert.equal(action.secret,session.action_token);assert.equal(permit.browser_confirm_allowed,true);calls.push('manual-confirm');return true;
 },close:mark('browser-close')};
 return {journey,calls,vs,fixture,bootstrap,cli,runtime,dependencies};
}
const owner=h=>owners.createOwnedBillingFailedRenewal(opts(),h.dependencies);
test('failed-renewal owner uses only binding when a prior CLI profile was selected',async()=>{
 const o=opts();o.env.PAYMENT_STRIPE_BILLING_FAILED_RENEWAL_CLI_PROFILE_RUN_ID=id(99);
 for(const accepted of [true,false]){
  const h=harness();
  h.dependencies.login=async()=>{h.calls.push('login');throw Error('unexpected login');};
  h.cli.validateBinding=async()=>{h.calls.push('binding');return accepted;};
  const controller=await owners.createOwnedBillingFailedRenewal(o,h.dependencies);
  if(accepted)await controller.run(h.journey,limits());else await assert.rejects(controller.run(h.journey,limits()));
  const result=await controller.close();
  assert.equal(h.calls.includes('login'),false);
  assert.equal(h.calls.includes('listener'),accepted);
  assert.equal(h.calls.includes('fixture'),accepted);
  assert.equal(result.failedRenewalAccepted,accepted);
  if(!accepted)assert.equal(result.code,'BINDING');
 }
});
const persistCodes=['PERSIST_ADMISSION','PERSIST_OWNERSHIP','PERSIST_INPUT','PERSIST_UNCHANGED','PERSIST_FINAL_STORE','PERSIST_ACK'];
const terminalLine=(checkpoint='PERSIST_FINAL_STORE',cleanup='CONFIRMED',witness='NONE')=>'owned_failed_renewal_diagnostic stage=LISTENER checkpoint='+checkpoint+' cleanup='+cleanup+' witness='+witness;
const browserLine=(checkpoint='BROWSER_CARD_NUMBER',cleanup='CONFIRMED',witness='NONE')=>'owned_failed_renewal_diagnostic stage=BROWSER checkpoint='+checkpoint+' cleanup='+cleanup+' witness='+witness;
const commandCheckpoint='START_MAILPIT_COMMAND_NONZERO_EXIT';
const startLine=(checkpoint=commandCheckpoint,cleanup='CONFIRMED',witness='NONE')=>'owned_failed_renewal_diagnostic stage=START checkpoint='+checkpoint+' cleanup='+cleanup+' witness='+witness;
const provisionLine=(checkpoint,cleanup='CONFIRMED',witness='NONE')=>'owned_failed_renewal_diagnostic stage=PROVISION checkpoint='+checkpoint+' cleanup='+cleanup+' witness='+witness;
const provisionCheckpoints=['PROVISION_CALLBACK_INTENT','PROVISION_CALLBACK_PUT','PROVISION_CALLBACK_VALIDATE','PROVISION_RECEIVER_CONFIGURE','PROVISION_MAILPIT_READ','PROVISION_MAILPIT_EMPTY','PROVISION_FIXTURE_READY','PROVISION_UNKNOWN'];
const privateOrigin='https://evidence-only.stripe.com';
function originalOriginError(runId=opts().runId,source=opts().expectedSource){
 const error=browserFailure('BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER');assert.equal(rememberBrowserProviderOrigin(error,runId,source,privateOrigin),true);return error;
}
for(const [checkpoint,origin] of [
 ['BROWSER_PROVIDER_ORIGIN_OTHER','https://private-provider.invalid'],
 ['BROWSER_PROVIDER_ORIGIN_STRIPE_NETWORK','https://private-provider.stripe.network'],
])test('initial refusal offers the exact origin only to the private writer after CREATE: '+checkpoint,async()=>{
 const h=harness();
 const error=browserFailure(checkpoint),offers=[],dispatches=[];
 assert.equal(rememberBrowserProviderOrigin(error,opts().runId,opts().expectedSource,origin),true);
 h.bootstrap.persistRefusedStripeOrigin=async witness=>{offers.push(witness);};
 h.fixture.beforeDispatch=async kind=>{dispatches.push(kind);return true;};
 h.journey.initial=async(port,budget)=>{
  await port.prepare({token:'inert',origin:scope().endpoints.demo},'sdk-create',budget);
  throw error;
 };
 const controller=await owner(h);
 await assert.rejects(controller.run(h.journey,limits()));
 const result=await controller.close();
 assert.deepEqual(offers,[{runId:opts().runId,expectedSource:opts().expectedSource,checkpoint,origin}]);
 assert.deepEqual(dispatches,['create']);
 assert.equal(h.calls.includes('arm-dispatch'),false);
 assert.equal(result.failedRenewalAccepted,false);
 assert.equal(result.cleanupConfirmed,true);
 assert.equal(JSON.stringify(result).includes(origin),false);
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),browserLine(checkpoint));
});
// Break: evidence is joined to owner work, mutates its first fault, or starts financial continuation.
for(const sink of ['success','sync throw','hostile reject','hostile then','stall'])test('private refused origin '+sink+' is detached from owner close and STOP',{timeout:2000},async()=>{
 const h=harness(),error=originalOriginError(),offers=[],l=limits(),dispatches=[];let reads=0;
 const hostile=new Proxy({},{get(){reads++;throw Error('private rejection canary');}});
 h.bootstrap.persistRefusedStripeOrigin=(w,budget)=>{offers.push({w,budget});if(sink==='sync throw')throw hostile;if(sink==='hostile reject')return Promise.reject(hostile);if(sink==='hostile then')return {get then(){throw hostile;}};if(sink==='stall')return new Promise(()=>{});return Promise.resolve(true);};
 h.fixture.beforeDispatch=async kind=>{dispatches.push(kind);return true;};
 h.journey.initial=async(p,child)=>{await p.prepare({token:'inert',origin:scope().endpoints.demo},'sdk-create',child);throw error;};
 h.journey.close=async()=>{h.calls.push('browser-close');finishBrowserCleanup(error,true);return true;};
 const o=await owner(h);await assert.rejects(o.run(h.journey,l));const result=await o.close();
 assert.equal(offers.length,1);assert.deepEqual(offers[0].w,{runId:opts().runId,expectedSource:opts().expectedSource,origin:privateOrigin});
 assert.ok(offers[0].budget.deadline<=l.deadline+120000);assert.ok(offers[0].budget.deadline<=Date.now()+2000);
 assert.equal(reads,0);assert.equal(result.status,'CLOSED');assert.equal(result.phase,'BROWSER');assert.equal(result.code,'BROWSER');assert.equal(result.diagnostic,'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER');
 assert.equal(result.cleanupConfirmed,true);assert.equal(result.cleanupFailure,null);assert.equal(result.initialAccepted,false);assert.equal(result.failedRenewalAccepted,false);assert.equal(result.full5bAccepted,false);
 assert.deepEqual(dispatches,['create']);assert.equal(h.calls.filter(x=>x==='stop').length,1);assert.equal(h.calls.includes('arm-dispatch'),false);assert.equal(h.calls.includes('manual-confirm'),false);assert.equal(h.calls.some(x=>methods.includes(x)),false);
 assert.equal(JSON.stringify(result).includes(privateOrigin),false);assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),browserLine('BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER'));
 await assert.rejects(o.run(h.journey,l));await o.close();assert.equal(offers.length,1);
});
for(const kind of ['foreign','mismatched run','mismatched source','earlier owner fault','missing capability','accessor capability','inherited capability'])test('private refused origin skips '+kind+' without evaluating foreign getters',async()=>{
 const h=harness();let offers=0,reads=0;const error=kind==='mismatched run'?originalOriginError(id(99)):kind==='mismatched source'?originalOriginError(opts().runId,'a'.repeat(40)):originalOriginError();
 const persist=()=>{offers++;return Promise.resolve(true);};
 if(kind==='accessor capability')Object.defineProperty(h.bootstrap,'persistRefusedStripeOrigin',{get(){reads++;throw Error('private getter canary');}});
 else if(kind==='inherited capability')Object.setPrototypeOf(h.bootstrap,{persistRefusedStripeOrigin:persist});
 else if(kind!=='missing capability')h.bootstrap.persistRefusedStripeOrigin=persist;
 h.journey.initial=async(p,l)=>{if(kind==='earlier owner fault'){try{await p.confirm(confirmRequest(),l);}catch{throw error;}}throw kind==='foreign'?new Proxy({},{get(){reads++;throw Error('foreign error canary');}}):error;};
 h.journey.close=async()=>{h.calls.push('browser-close');finishBrowserCleanup(error,true);return true;};
 const o=await owner(h);await assert.rejects(o.run(h.journey,limits()));const result=await o.close();
 assert.equal(offers,0);assert.equal(reads,0);assert.equal(result.cleanupConfirmed,true);assert.equal(h.calls.filter(x=>x==='stop').length,1);assert.equal(JSON.stringify(result).includes(privateOrigin),false);
 if(kind==='earlier owner fault')assert.equal(result.code,'CONFIRM');
});
test('original private origin arriving during bounded browser drain survives the abort race once',async()=>{
 const h=harness(),controller=new AbortController(),error=originalOriginError(),offers=[];let rejectInitial;
 h.bootstrap.persistRefusedStripeOrigin=(...args)=>{offers.push(args);return new Promise(()=>{});};
 h.journey.initial=async()=>{controller.abort();return new Promise((_,reject)=>{rejectInitial=reject;});};
 h.journey.close=async()=>{h.calls.push('browser-close');finishBrowserCleanup(error,true);rejectInitial(error);return true;};
 const o=await owner(h);await assert.rejects(o.run(h.journey,{...limits(),signal:controller.signal}));const result=await o.close();
 assert.equal(offers.length,1);assert.equal(result.code,'ABORTED');assert.equal(result.diagnostic,'BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER');assert.equal(result.cleanupConfirmed,true);assert.equal(h.calls.filter(x=>x==='stop').length,1);
});
test('private origin settled only after CLOSED cannot start evidence persistence',async()=>{
 const h=harness(),error=originalOriginError();let rejectInitial,offers=0;
 h.bootstrap.persistRefusedStripeOrigin=()=>{offers++;return Promise.resolve(true);};
 h.journey.initial=async()=>new Promise((_,reject)=>{rejectInitial=reject;});
 // A refused close does not await the unresolved held promise.
 h.journey.close=async()=>{throw Error('inert close refusal');};
 const o=await owner(h),running=o.run(h.journey,limits());
 for(let i=0;i<100&&!rejectInitial;i++)await new Promise(r=>setImmediate(r));
 assert.equal(typeof rejectInitial,'function');const closed=o.close();await assert.rejects(running);const result=await closed;assert.equal(result.status,'CLOSED');
 rejectInitial(error);await new Promise(r=>setImmediate(r));assert.equal(offers,0);assert.equal(o.resources().diagnostic,null);assert.equal(h.calls.filter(x=>x==='stop').length,1);
});
const providerOriginCheckpoints=['BROWSER_PROVIDER_ORIGIN_STRIPE_COM_M','BROWSER_PROVIDER_ORIGIN_STRIPE_COM_R','BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER','BROWSER_PROVIDER_ORIGIN_STRIPE_COM'];
const providerNavigationCheckpoints=[
 'BROWSER_PROVIDER_STATIC_REQUEST_NAVIGATION',
 'BROWSER_PROVIDER_STATIC_REQUEST_NAVIGATION_MAIN_FRAME',
 'BROWSER_PROVIDER_STATIC_REQUEST_NAVIGATION_CHECKOUT_CHILD',
 'BROWSER_PROVIDER_STATIC_REQUEST_NAVIGATION_STRIPE_PARENT',
 'BROWSER_PROVIDER_STATIC_REQUEST_NAVIGATION_OTHER_PARENT',
 'BROWSER_PROVIDER_STATIC_REQUEST_NAVIGATION_FRAME_UNAVAILABLE',
 'BROWSER_PROVIDER_STATIC_NAVIGATION_BLOCK',
];
for(const checkpoint of [
 ...providerOriginCheckpoints,
 'BROWSER_PROVIDER_STATIC_REQUEST',
 'BROWSER_PROVIDER_STATIC_REQUEST_PHASE',
 'BROWSER_PROVIDER_STATIC_REQUEST_METHOD',
 'BROWSER_PROVIDER_STATIC_REQUEST_BODY',
 ...providerNavigationCheckpoints,
 'BROWSER_PROVIDER_STATIC_REQUEST_URL',
 'BROWSER_PROVIDER_STATIC_REQUEST_CREDENTIALS',
 'BROWSER_PROVIDER_STATIC_REQUEST_RESOURCE_TYPE',
 'BROWSER_PROVIDER_STATIC_REQUEST_SUFFIX',
])test('failed-renewal provider refusal terminal roundtrip '+checkpoint,async()=>{
 for(const close of [true,false]){
  const h=harness(),error=browserFailure(checkpoint),dispatches=[];let leaked;
  h.fixture.beforeDispatch=async kind=>{dispatches.push(kind);return true;};
  h.journey.initial=async(p,l)=>{leaked=p;await p.prepare({token:'inert',origin:scope().endpoints.demo},'sdk-create',l);throw error;};
  h.journey.close=async()=>{h.calls.push('browser-close');finishBrowserCleanup(error,close);return close;};
  const o=await owner(h);await assert.rejects(o.run(h.journey,limits()));const result=await o.close();
  assert.equal(result.status,'CLOSED');assert.equal(result.phase,'BROWSER');assert.equal(result.code,'BROWSER');assert.equal(result.diagnostic,checkpoint);
  assert.equal(result.cleanupConfirmed,close);assert.equal(result.cleanupFailure,close?null:'BROWSER_CLOSE');
  assert.equal(result.initialAccepted,false);assert.equal(result.failedRenewalAccepted,false);assert.equal(result.full5bAccepted,false);
  const line=browserLine(checkpoint,close?'CONFIRMED':'UNCONFIRMED',close?'NONE':'BROWSER_CLOSE');
  assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),line);assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
  assert.equal(owners.formatBillingFailedRenewalDiagnostic({...result,status:'RUNNING'}),null);
  assert.equal(owners.formatBillingFailedRenewalDiagnostic({...result,cleanupConfirmed:!close}),null);
  for(const raw of [line+' https://m.stripe.com/private',line+'\n',line.replace(checkpoint,checkpoint+'_PRIVATE')])assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(raw),null);
  for(const diagnostic of [checkpoint+' https://m.stripe.com/private',checkpoint+'\n',checkpoint+'_PRIVATE'])assert.equal(owners.formatBillingFailedRenewalDiagnostic({...result,diagnostic}),null);
  await assert.rejects(leaked.confirm(confirmRequest(),limits()));assert.deepEqual(dispatches,['create']);
  assert.equal(h.calls.includes('observe'),false);assert.equal(h.calls.includes('arm-dispatch'),false);assert.equal(h.calls.includes('manual-confirm'),false);
  assert.equal(h.calls.some(call=>methods.includes(call)),false);
 }
});
for(const checkpoint of [...providerOriginCheckpoints,...providerNavigationCheckpoints])test('failed-renewal refuses forged provider terminal '+checkpoint,async()=>{
 for(const foreign of [checkpoint,{diagnostic:checkpoint,cleanupConfirmed:true},new Error(checkpoint+' https://m.stripe.com/private')]){
  const h=harness(),dispatches=[];
  h.fixture.beforeDispatch=async kind=>{dispatches.push(kind);return true;};
  h.journey.initial=async(p,l)=>{await p.prepare({token:'inert',origin:scope().endpoints.demo},'sdk-create',l);throw foreign;};
  h.journey.close=async()=>{h.calls.push('browser-close');return false;};
  const o=await owner(h);await assert.rejects(o.run(h.journey,limits()));const result=await o.close();
  assert.equal(result.status,'CLOSED');assert.equal(result.phase,'BROWSER');assert.equal(result.code,'BROWSER');assert.equal(result.diagnostic,null);
  assert.equal(result.cleanupConfirmed,false);assert.equal(result.cleanupFailure,'BROWSER_CLOSE');
  assert.equal(result.initialAccepted,false);assert.equal(result.failedRenewalAccepted,false);assert.equal(result.full5bAccepted,false);
  assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),null);assert.deepEqual(dispatches,['create']);
  assert.equal(h.calls.includes('observe'),false);assert.equal(h.calls.includes('arm-dispatch'),false);assert.equal(h.calls.includes('manual-confirm'),false);
  assert.equal(h.calls.some(call=>methods.includes(call)),false);
 }
});
for(const timing of ['rejection','abort-late'])for(const close of [true,false])test('browser terminal retains original held checkpoint '+timing+' close='+close,async()=>{
 const h=harness(),controller=new AbortController(),error=browserFailure('BROWSER_CARD_NUMBER'),dispatches=[];let rejectInitial,leaked;
 h.fixture.beforeDispatch=async kind=>{dispatches.push(kind);return true;};
 h.journey.initial=async(p,l)=>{leaked=p;await p.prepare({token:'inert',origin:scope().endpoints.demo},'sdk-create',l);if(timing==='abort-late'){controller.abort();return new Promise((_,reject)=>{rejectInitial=reject;});}throw error;};
 h.journey.close=async()=>{h.calls.push('browser-close');finishBrowserCleanup(error,close);rejectInitial?.(error);return close;};
 const o=await owner(h);await assert.rejects(o.run(h.journey,{...limits(),signal:controller.signal}));const result=await o.close();
 assert.equal(result.phase,'BROWSER');assert.equal(result.code,timing==='abort-late'?'ABORTED':'BROWSER');assert.equal(result.diagnostic,'BROWSER_CARD_NUMBER');
 assert.equal(result.cleanupConfirmed,close);assert.equal(result.cleanupFailure,close?null:'BROWSER_CLOSE');
 assert.equal(result.initialAccepted,false);assert.equal(result.failedRenewalAccepted,false);assert.equal(result.full5bAccepted,false);
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),browserLine('BROWSER_CARD_NUMBER',close?'CONFIRMED':'UNCONFIRMED',close?'NONE':'BROWSER_CLOSE'));
 await assert.rejects(leaked.confirm(confirmRequest(),limits()));assert.deepEqual(dispatches,['create']);assert.equal(h.calls.includes('observe'),false);assert.equal(h.calls.includes('arm-dispatch'),false);assert.equal(h.calls.includes('manual-confirm'),false);
});
test('browser terminal never inspects foreign rejection or overwrites earlier owner failure',async()=>{
 for(const earlier of [false,true]){
  const h=harness();let reads=0;const foreign=new Proxy({}, {get(){reads++;throw Error('canary');}});
  h.journey.initial=async(p,l)=>{if(earlier){try{await p.confirm(confirmRequest(),l);}catch{throw browserFailure('BROWSER_CARD_NUMBER');}}throw foreign;};
  const o=await owner(h);await assert.rejects(o.run(h.journey,limits()));const result=await o.close();
  assert.equal(reads,0);assert.equal(result.diagnostic,null);assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),null);if(earlier)assert.equal(result.code,'CONFIRM');
 }
});
test('browser terminal formatter and parser enforce stage-specific finite own data',()=>{
 const valid={scope:'OWNED_FAILED_RENEWAL_GRACE_RECOVERY',status:'CLOSED',phase:'BROWSER',code:'ABORTED',diagnostic:'BROWSER_CARD_NUMBER',initialAccepted:false,failedRenewalAccepted:false,full5bAccepted:false,cleanupConfirmed:true,cleanupFailure:null};
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(valid),browserLine());assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(browserLine()),browserLine());
 for(const change of [{phase:'LISTENER'},{code:'LISTENER'},{diagnostic:'PERSIST_ACK'},{initialAccepted:true},{failedRenewalAccepted:true},{full5bAccepted:true},{cleanupFailure:'BROWSER_CLOSE'}])assert.equal(owners.formatBillingFailedRenewalDiagnostic({...valid,...change}),null);
 for(const line of [browserLine().replace('BROWSER_CARD_NUMBER','PERSIST_ACK'),terminalLine().replace('PERSIST_FINAL_STORE','BROWSER_CARD_NUMBER'),browserLine()+'\n',browserLine().replace('witness=NONE','witness=BROWSER_CLOSE')])assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),null);
 let reads=0;const hostile={...valid};Object.defineProperty(hostile,'diagnostic',{get(){reads++;return 'BROWSER_CARD_NUMBER';}});assert.equal(owners.formatBillingFailedRenewalDiagnostic(hostile),null);assert.equal(reads,0);assert.equal(owners.formatBillingFailedRenewalDiagnostic(Object.create(valid)),null);
});
for(const settled of [true,false])test('listener cancellation waits within the stop budget for startup to settle: '+settled,async t=>{
 t.mock.timers.enable({apis:['Date','setTimeout'],now:1000});
 const h=harness(),controller=new AbortController();
 const entered=Promise.withResolvers(),startup=Promise.withResolvers();
 let starting=true,stops=0;
 h.bootstrap.persistenceDiagnostic=()=>'PERSIST_INPUT';
 h.cli.startListener=async()=>{entered.resolve();try{return await startup.promise;}finally{starting=false;}};
 h.cli.stopListener=async()=>{stops++;return !starting;};
 const o=await owner(h),rejected=assert.rejects(o.run(h.journey,{deadline:200000,signal:controller.signal}),{message:'billing_ABORTED'});
 await entered.promise;controller.abort();await new Promise(resolve=>setImmediate(resolve));
 assert.equal(stops,0);
 if(settled)startup.resolve(false);else t.mock.timers.tick(30000);
 await rejected;const result=await o.close();
 assert.equal(result.phase,'LISTENER');assert.equal(result.diagnostic,'PERSIST_INPUT');
 assert.equal(result.cleanupConfirmed,settled);assert.equal(result.cleanupFailure,settled?null:'LISTENER_STOP');
 assert.equal(result.initialAccepted,false);assert.equal(h.calls.includes('start'),false);
 if(!settled){startup.resolve(false);await new Promise(resolve=>setImmediate(resolve));}
 assert.equal(stops,settled?1:0);
});
for(const checkpoint of persistCodes)for(const cleanup of ['confirmed','listener-refused','bootstrap-refused'])test('persistence terminal preserves LISTENER and '+checkpoint+' with '+cleanup,async()=>{
 const h=harness();h.bootstrap.persistenceDiagnostic=()=>checkpoint;
 h.cli.startListener=async()=>{h.calls.push('listener');throw Error('raw-secret-canary');};
 if(cleanup==='listener-refused')h.cli.stopListener=async()=>{h.calls.push('listener-stop');throw Error('raw-cleanup-canary');};
 if(cleanup==='bootstrap-refused')h.bootstrap.stop=async()=>{h.calls.push('stop');return false;};
 const o=await owner(h);await assert.rejects(o.run(h.journey,limits()),{message:'billing_LISTENER'});
 const result=await o.close(),confirmed=cleanup==='confirmed',witness=confirmed?'NONE':cleanup==='listener-refused'?'LISTENER_STOP':'BOOTSTRAP_STOP';
 assert.equal(result.code,'LISTENER');assert.equal(result.phase,'LISTENER');assert.equal(result.diagnostic,checkpoint);
 assert.equal(result.cleanupConfirmed,confirmed);assert.equal(result.cleanupFailure,confirmed?null:witness);
 assert.equal(result.failedRenewalAccepted,false);assert.equal(result.initialAccepted,false);assert.equal(result.full5bAccepted,false);
 assert.deepEqual(h.calls,['preflight','prepare','login','binding','listener','browser-close','listener-stop','stop']);
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),terminalLine(checkpoint,confirmed?'CONFIRMED':'UNCONFIRMED',witness));
 await assert.rejects(o.run(h.journey,limits()));assert.equal(h.calls.filter(v=>v==='listener').length,1);
});
for(const kind of ['missing','getter','inherited','throw','object','foreign'])test('persistence terminal refuses hostile '+kind+' checkpoint without printing values',async()=>{
 const h=harness();let reads=0;
 if(kind==='getter')Object.defineProperty(h.bootstrap,'persistenceDiagnostic',{get(){reads++;throw Error('canary');}});
 if(kind==='inherited')Object.setPrototypeOf(h.bootstrap,{persistenceDiagnostic(){reads++;return 'PERSIST_ACK';}});
 if(kind==='throw')h.bootstrap.persistenceDiagnostic=()=>{throw Error('canary');};
 if(kind==='object')h.bootstrap.persistenceDiagnostic=()=>({toString(){reads++;return 'canary';}});
 if(kind==='foreign')h.bootstrap.persistenceDiagnostic=()=> 'PERSIST_ACK canary';
 h.cli.startListener=async()=>{throw Error('canary');};
 const o=await owner(h);await assert.rejects(o.run(h.journey,limits()));const result=await o.close();
 assert.equal(reads,0);assert.equal(result.diagnostic,'PERSIST_UNKNOWN');assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),terminalLine('PERSIST_UNKNOWN'));
});
for(const outcome of ['aborted','false'])test('persistence terminal preserves '+outcome+' LISTENER refusal and its checkpoint',async()=>{
 const h=harness(),controller=new AbortController();h.bootstrap.persistenceDiagnostic=()=> 'PERSIST_ACK';
 h.cli.startListener=async()=>{h.calls.push('listener');if(outcome==='aborted')controller.abort();return false;};
 const o=await owner(h);await assert.rejects(o.run(h.journey,{...limits(),signal:controller.signal}));const result=await o.close();
 assert.equal(result.code,outcome==='aborted'?'ABORTED':'LISTENER');assert.equal(result.phase,'LISTENER');assert.equal(result.diagnostic,'PERSIST_ACK');
 assert.equal(result.cleanupConfirmed,true);assert.equal(result.cleanupFailure,null);assert.equal(result.failedRenewalAccepted,false);
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),terminalLine('PERSIST_ACK'));
 assert.deepEqual(h.calls,['preflight','prepare','login','binding','listener','browser-close','listener-stop','stop']);
});
test('persistence formatter and parser accept only closed finite own data',()=>{
 const valid={scope:'OWNED_FAILED_RENEWAL_GRACE_RECOVERY',status:'CLOSED',phase:'LISTENER',code:'LISTENER',diagnostic:'PERSIST_ACK',initialAccepted:false,failedRenewalAccepted:false,full5bAccepted:false,cleanupConfirmed:true,cleanupFailure:null};
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(valid),terminalLine('PERSIST_ACK'));
 for(const change of [{status:'RUNNING'},{scope:'OWNED_INITIAL_PAYMENT'},{phase:'START'},{code:'START'},{diagnostic:'secret'},{initialAccepted:true},{failedRenewalAccepted:true},{full5bAccepted:true},{cleanupConfirmed:true,cleanupFailure:'LISTENER_STOP'},{cleanupConfirmed:false,cleanupFailure:null},{cleanupConfirmed:false,cleanupFailure:'canary'}])assert.equal(owners.formatBillingFailedRenewalDiagnostic({...valid,...change}),null);
 let reads=0;const hostile={...valid};Object.defineProperty(hostile,'diagnostic',{get(){reads++;return 'PERSIST_ACK';}});
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(hostile),null);assert.equal(reads,0);
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(Object.create(valid)),null);
 for(const code of [...persistCodes,'PERSIST_UNKNOWN']){
  const line=terminalLine(code);assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
  for(const bad of [line+'\n',line+' canary',' '+line,line.replace('LISTENER','START'),line.replace(code,'PERSIST_CANARY'),line.replace('witness=NONE','witness=LISTENER_STOP')])assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(bad),null);
 }
 assert.equal(owners.parseBillingFailedRenewalDiagnosticLine({toString(){reads++;return terminalLine();}}),null);assert.equal(reads,0);
});
for(const cleanup of ['confirmed','listener-refused','bootstrap-refused'])test('Mailpit START command failure preserves its first checkpoint with '+cleanup,async()=>{
 const h=harness();let checkpoint=commandCheckpoint,reads=0;
 h.bootstrap.start=async()=>{h.calls.push('start');throw new Proxy({},{get(){reads++;throw Error('private command output');}});};
 h.bootstrap.startupDiagnostic=()=>checkpoint;
 h.cli.stopListener=async()=>{h.calls.push('listener-stop');checkpoint='START_BACKEND_COMMAND_ABORT';return cleanup!=='listener-refused';};
 h.bootstrap.stop=async()=>{h.calls.push('stop');checkpoint=null;return cleanup!=='bootstrap-refused';};
 const o=await owner(h);await assert.rejects(o.run(h.journey,limits()),{message:'billing_START'});
 const result=await o.close(),confirmed=cleanup==='confirmed',witness=confirmed?null:cleanup==='listener-refused'?'LISTENER_STOP':'BOOTSTRAP_STOP';
 assert.equal(reads,0);assert.equal(result.status,'CLOSED');assert.equal(result.phase,'START');assert.equal(result.code,'START');assert.equal(result.diagnostic,commandCheckpoint);
 assert.equal(result.initialAccepted,false);assert.equal(result.failedRenewalAccepted,false);assert.equal(result.full5bAccepted,false);
 assert.equal(result.cleanupConfirmed,confirmed);assert.equal(result.cleanupFailure,witness);
 assert.deepEqual(h.calls,['preflight','prepare','login','binding','listener','start','browser-close','listener-stop','stop']);
 const line=startLine(commandCheckpoint,confirmed?'CONFIRMED':'UNCONFIRMED',witness??'NONE');
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),line);assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
 await assert.rejects(o.run(h.journey,limits()),/SINGLE_USE/);assert.deepEqual(await o.close(),result);
});
for(const kind of ['missing','getter','inherited','throw','object','foreign','malformed','wrong stage'])test('START uses a finite fallback for '+kind+' diagnostic capability',async()=>{
 const h=harness();let reads=0;
 if(kind==='getter')Object.defineProperty(h.bootstrap,'startupDiagnostic',{get(){reads++;return ()=>commandCheckpoint;}});
 else if(kind==='inherited')Object.setPrototypeOf(h.bootstrap,{startupDiagnostic(){reads++;return commandCheckpoint;}});
 else if(kind==='throw')h.bootstrap.startupDiagnostic=()=>{throw Error('private command output');};
 else if(kind==='object')h.bootstrap.startupDiagnostic=()=>({toString(){reads++;return commandCheckpoint;}});
 else if(kind==='foreign')h.bootstrap.startupDiagnostic=()=> 'START_PRIVATE_COMMAND_NONZERO_EXIT';
 else if(kind==='malformed')h.bootstrap.startupDiagnostic=()=>commandCheckpoint+'\nprivate command output';
 else if(kind==='wrong stage')h.bootstrap.startupDiagnostic=()=> 'PERSIST_ACK';
 h.bootstrap.start=async()=>{throw Object.assign(Error(commandCheckpoint),{diagnostic:commandCheckpoint});};
 const o=await owner(h);await assert.rejects(o.run(h.journey,limits()),{message:'billing_START'});
 const result=await o.close(),line=startLine('START_UNKNOWN');
 assert.equal(reads,0);assert.equal(result.diagnostic,'START_UNKNOWN');
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),line);assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
});
test('START false retains an available checkpoint and a PREPARE failure has no START fallback',async()=>{
 const h=harness();h.bootstrap.start=async()=>false;h.bootstrap.startupDiagnostic=()=> 'START_BOOTSTRAP_ADMISSION_REJECTED';
 const o=await owner(h);await assert.rejects(o.run(h.journey,limits()),{message:'billing_START'});
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(await o.close()),startLine('START_BOOTSTRAP_ADMISSION_REJECTED'));
 const beforeStart=harness();let reads=0;
 beforeStart.bootstrap.prepare=async()=>false;beforeStart.bootstrap.startupDiagnostic=()=>{reads++;return commandCheckpoint;};
 const rejected=await owner(beforeStart);await assert.rejects(rejected.run(beforeStart.journey,limits()),{message:'billing_PREPARE'});
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(await rejected.close()),null);assert.equal(reads,0);
});
for(const cancellation of ['abort','deadline'])for(const outcome of ['late checkpoint','retain checkpoint','unsettled'])test('START '+cancellation+' with '+outcome+' keeps bounded cleanup and its diagnostic',async t=>{
 t.mock.timers.enable({apis:['Date','setTimeout'],now:1000});
 const h=harness(),controller=new AbortController();let enter,release,reads=0;
 const entered=new Promise(r=>enter=r),held=new Promise((_,reject)=>release=()=>reject(new Proxy({},{get(){reads++;throw Error('private command output');}})));
 let checkpoint=outcome==='retain checkpoint'?commandCheckpoint:null;
 const lateCheckpoint='START_MAILPIT_COMMAND_'+(cancellation==='abort'?'ABORT':'TIMEOUT');
 h.bootstrap.start=async()=>{h.calls.push('start');enter();try{return await held;}finally{checkpoint=lateCheckpoint;}};
 h.bootstrap.startupDiagnostic=()=>checkpoint;
 h.bootstrap.stop=async l=>{h.calls.push('stop');assert.equal(l.signal.aborted,false);assert.equal(checkpoint,lateCheckpoint);checkpoint=null;return true;};
 const o=await owner(h),running=o.run(h.journey,{deadline:6000,signal:controller.signal}),rejected=assert.rejects(running,{message:'billing_ABORTED'});
 await entered;
 if(cancellation==='abort')controller.abort();else t.mock.timers.tick(5000);
 await new Promise(r=>setImmediate(r));
 assert.equal(o.resources().status,'RUNNING');assert.equal(owners.formatBillingFailedRenewalDiagnostic(o.resources()),null);
 assert.deepEqual(h.calls.slice(-3),['start','browser-close','listener-stop']);
 if(outcome==='unsettled')t.mock.timers.tick(120000);else release();
 await rejected;const result=await o.close(),settled=outcome!=='unsettled';
 assert.equal(result.phase,'START');assert.equal(result.code,'ABORTED');assert.equal(result.cleanupConfirmed,settled);assert.equal(result.cleanupFailure,settled?null:'START_SETTLEMENT');
 const expected=outcome==='retain checkpoint'?commandCheckpoint:settled?lateCheckpoint:'START_UNKNOWN';
 assert.equal(result.diagnostic,expected);
 const line=startLine(expected,settled?'CONFIRMED':'UNCONFIRMED',settled?'NONE':'START_SETTLEMENT');
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),line);assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
 if(!settled){release();await new Promise(r=>setImmediate(r));}
 assert.equal(reads,0);assert.equal(h.calls.filter(call=>call==='stop').length,settled?1:0);assert.deepEqual(await o.close(),result);
});
test('START formatter and parser accept only closed stage-specific finite own data',()=>{
 const valid={scope:'OWNED_FAILED_RENEWAL_GRACE_RECOVERY',status:'CLOSED',phase:'START',code:'ABORTED',diagnostic:commandCheckpoint,initialAccepted:false,failedRenewalAccepted:false,full5bAccepted:false,cleanupConfirmed:true,cleanupFailure:null};
 for(const checkpoint of [commandCheckpoint,'START_MAILPIT_COMMAND_SPAWN_FAILURE','START_MAILPIT_HEALTH_WAIT_TIMEOUT','START_BACKEND_HEALTH_UNHEALTHY_CONNECTION_FAILED','START_UNKNOWN']){
  const line=startLine(checkpoint);assert.equal(owners.formatBillingFailedRenewalDiagnostic({...valid,diagnostic:checkpoint}),line);assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
 }
 for(const change of [{status:'RUNNING'},{scope:'OWNED_INITIAL_PAYMENT'},{phase:'LISTENER'},{phase:'BROWSER'},{code:'LISTENER'},{code:null},{diagnostic:'PERSIST_ACK'},{diagnostic:'BROWSER_CARD_NUMBER'},{diagnostic:'START_MAILPIT_COMMAND_PRIVATE'},{initialAccepted:true},{failedRenewalAccepted:true},{full5bAccepted:true},{cleanupFailure:'BOOTSTRAP_STOP'},{cleanupConfirmed:false},{cleanupConfirmed:false,cleanupFailure:'private'}])assert.equal(owners.formatBillingFailedRenewalDiagnostic({...valid,...change}),null);
 for(const line of [startLine()+'\n',startLine()+' private',startLine('PERSIST_ACK'),startLine('BROWSER_CARD_NUMBER'),startLine('START_MAILPIT_COMMAND_PRIVATE'),terminalLine('START_UNKNOWN'),browserLine(commandCheckpoint),startLine(commandCheckpoint,'CONFIRMED','BOOTSTRAP_STOP'),startLine(commandCheckpoint,'UNCONFIRMED'),startLine(commandCheckpoint,'UNCONFIRMED','PRIVATE')])assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),null);
 assert.equal(owners.parseBillingFirstPaymentDiagnosticLine('owned_initial_payment_diagnostic code=START_UNKNOWN cleanup=CONFIRMED'),null);
 assert.equal(owners.parseBillingFirstRenewalDiagnosticLine('owned_initial_and_first_renewal_diagnostic code=START_UNKNOWN cleanup=CONFIRMED'),null);
 let reads=0;const hostile={...valid};Object.defineProperty(hostile,'diagnostic',{get(){reads++;return commandCheckpoint;}});
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(hostile),null);assert.equal(owners.formatBillingFailedRenewalDiagnostic(Object.create(valid)),null);
 assert.equal(owners.parseBillingFailedRenewalDiagnosticLine({toString(){reads++;return startLine();}}),null);assert.equal(reads,0);
});
for(const refusal of ['throws','false'])test('PROVISION snapshots only fixture-owned evidence on '+refusal,async()=>{
 const h=harness();let reads=0,provisions=0;
 const hostile=new Proxy({},{get(){reads++;throw Error('private-callback-canary');}});
 Object.defineProperty(h.fixture,'provisionDiagnostic',{get(){reads++;return 'PROVISION_CALLBACK_PUT';}});
 h.fixture.provision=async()=>{provisions++;if(refusal==='throws')throw hostile;return false;};
 h.fixture.close=async()=>{throw Error('private-cleanup-canary');};
 const o=await owner(h);
 await assert.rejects(o.run(h.journey,limits()),{message:'billing_PROVISION'});
 const result=await o.close();
 assert.equal(result.code,'PROVISION');assert.equal(result.diagnostic,'PROVISION_UNKNOWN');
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),provisionLine('PROVISION_UNKNOWN','UNCONFIRMED','FIXTURE_CLOSE'));
 assert.equal(result.failedRenewalAccepted,false);assert.equal(result.full5bAccepted,false);
 assert.equal(h.calls.includes('dispatch'),false);assert.equal(reads,0);
 await assert.rejects(o.run(h.journey,limits()),/SINGLE_USE/);assert.equal(provisions,1);
});
test('PROVISION terminal protocol accepts only matching stage, scope, rejection and fixed checkpoint',()=>{
 const valid={scope:'OWNED_FAILED_RENEWAL_GRACE_RECOVERY',status:'CLOSED',phase:'PROVISION',code:'PROVISION',diagnostic:'PROVISION_CALLBACK_PUT',initialAccepted:false,failedRenewalAccepted:false,full5bAccepted:false,cleanupConfirmed:true,cleanupFailure:null};
 for(const diagnostic of provisionCheckpoints){
  const line=provisionLine(diagnostic);
  for(const code of ['PROVISION','ABORTED'])assert.equal(owners.formatBillingFailedRenewalDiagnostic({...valid,diagnostic,code}),line);
  assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
  assert.equal(owners.formatBillingFirstPaymentDiagnostic({...valid,scope:'OWNED_INITIAL_PAYMENT',diagnostic}),null);
  assert.equal(owners.parseBillingFirstPaymentDiagnosticLine('owned_initial_payment_diagnostic code='+diagnostic+' cleanup=CONFIRMED'),null);
  assert.equal(owners.parseBillingFirstRenewalDiagnosticLine('owned_initial_and_first_renewal_diagnostic code='+diagnostic+' cleanup=CONFIRMED'),null);
 }
 for(const change of [{status:'RUNNING'},{scope:'OWNED_INITIAL_AND_FIRST_RENEWAL'},{phase:'START'},{code:'FIXTURE'},{diagnostic:'START_UNKNOWN'},{diagnostic:'PROVISION_CALLBACK_PUT_PRIVATE'},{initialAccepted:true},{failedRenewalAccepted:true},{full5bAccepted:true},{cleanupFailure:'FIXTURE_CLOSE'},{cleanupConfirmed:false}])assert.equal(owners.formatBillingFailedRenewalDiagnostic({...valid,...change}),null);
 for(const line of [null,provisionLine('PERSIST_UNKNOWN'),startLine('PROVISION_CALLBACK_PUT'),browserLine('PROVISION_CALLBACK_PUT'),provisionLine('PROVISION_CALLBACK_PUT')+'\n',provisionLine('PROVISION_CALLBACK_PUT')+' private',provisionLine('PROVISION_'+'A'.repeat(250)),provisionLine('PROVISION_CALLBACK_VALIDATE','UNCONFIRMED','NONE')])assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),null);
});
const refusal=new Error('private site preparation canary');
const siteAck='billing-005-site-ack.json',policyDispatch='billing-006-policy-dispatch.json';
const identitiesPath='/api/v1/admin/sites/'+id(3)+'/identity-providers';
const policyPath='/api/v1/admin/sites/'+id(3)+'/subscription-policy';
const refuseWrite=name=>({onWrite(file){if(file===name)throw refusal;}});
const refuseVerifyAfter=(path,method)=>({onVerify({requests}){
 const last=requests.at(-1);
 return last?.path!==path||last.method!==method;
}});
const refuseRead=(target,reply)=>({onFetch(path,settings,response){return path===target&&settings.method==='GET'?reply():response;}});
const httpRefusal=()=>new Response('private body canary',{status:503});
const unavailableProvider=()=>Response.json([{mode:'EMAIL_OTP',status:'ACTIVE',readiness:'CONFIGURATION_UNAVAILABLE'}]);
for(const [checkpoint,injection,lastPath,lastWrite=siteAck] of [
 ['SITE_ACK',refuseWrite(siteAck),'/api/admin/v1/sites'],
 ['IDENTITY_PROVIDERS_VERIFY',refuseVerifyAfter('/api/admin/v1/sites','POST'),'/api/admin/v1/sites'],
 ['IDENTITY_PROVIDERS_REQUEST',refuseRead(identitiesPath,()=>{throw refusal;}),identitiesPath],
 ['IDENTITY_PROVIDERS_HTTP',refuseRead(identitiesPath,httpRefusal),identitiesPath],
 ['IDENTITY_PROVIDERS_RESPONSE',refuseRead(identitiesPath,()=>new Response('private invalid JSON')),identitiesPath],
 ['IDENTITY_PROVIDERS_VALIDATE',refuseRead(identitiesPath,unavailableProvider),identitiesPath],
 ['SUBSCRIPTION_POLICY_VERIFY',refuseVerifyAfter(identitiesPath,'GET'),identitiesPath],
 ['SUBSCRIPTION_POLICY_REQUEST',refuseRead(policyPath,()=>{throw refusal;}),policyPath],
 ['SUBSCRIPTION_POLICY_HTTP',refuseRead(policyPath,httpRefusal),policyPath],
 ['SUBSCRIPTION_POLICY_RESPONSE',refuseRead(policyPath,()=>new Response('private invalid JSON')),policyPath],
 ['SUBSCRIPTION_POLICY_VALIDATE',refuseRead(policyPath,()=>Response.json({version:'0'})),policyPath],
 ['POLICY_INTENT',refuseWrite(policyDispatch),policyPath,policyDispatch],
])test('PROVISION distinguishes a site preparation refusal at '+checkpoint,async()=>{
 const h=harness(),{fixture,writes,requests}=await provisionHarness(injection);
 h.dependencies.createFixture=async()=>fixture;
 let initialCalls=0;
 h.journey.initial=async()=>{initialCalls++;};
 const o=await owner(h);
 await assert.rejects(o.run(h.journey,limits()),{message:'billing_PROVISION'});
 const result=await o.close(),line=provisionLine('PROVISION_'+checkpoint);
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),line);
 assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
 assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line+' private canary'),null);
 assert.equal(writes.at(-1).name,lastWrite);
 assert.equal(requests.at(-1).path,lastPath);
 assert.equal(requests.filter(req=>req.path==='/api/admin/v1/sites'&&req.method==='POST').length,1);
 assert.ok(!requests.some(req=>req.path===policyPath&&req.method==='POST'));
 assert.equal(initialCalls,0);
});

test('PROVISION freezes the site preparation request checkpoint on abort',async()=>{
 const h=harness(),entered=Promise.withResolvers(),release=Promise.withResolvers(),abort=new AbortController();
 const {fixture,requests}=await provisionHarness(refuseRead(identitiesPath,()=>{entered.resolve();return release.promise;}));
 h.dependencies.createFixture=async()=>fixture;
 const o=await owner(h),closed=assert.rejects(o.run(h.journey,{...limits(),signal:abort.signal}),{message:'billing_ABORTED'});
 await entered.promise;
 abort.abort();await closed;
 const result=await o.close();
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),provisionLine('PROVISION_IDENTITY_PROVIDERS_REQUEST'));
 release.resolve(unavailableProvider());await new Promise(resolve=>setImmediate(resolve));
 assert.equal(requests.at(-1).path,identitiesPath);
 assert.deepEqual(await o.close(),result);
});

test('PROVISION clears the policy intent checkpoint before sending the update',async()=>{
 const h=harness(),{fixture,requests,writes}=await provisionHarness({onFetch(path,settings,response){
  return path===policyPath&&settings.method==='POST'?httpRefusal():response;
 }});
 h.dependencies.createFixture=async()=>fixture;
 const o=await owner(h);
 await assert.rejects(o.run(h.journey,limits()),{message:'billing_PROVISION'});
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(await o.close()),provisionLine('PROVISION_UNKNOWN'));
 assert.equal(writes.at(-1).name,policyDispatch);
 assert.equal(requests.filter(req=>req.path===policyPath&&req.method==='POST').length,1);
});

test('PROVISION preserves the publication verification stop before any publish request',async()=>{
 const h=harness(),{fixture,requests,writes}=await provisionHarness({
  onVerify:({writes})=>!writes.at(-1)?.name.endsWith('-publish-dispatch.json'),
 });
 h.dependencies.createFixture=async()=>fixture;
 h.journey.initial=async()=>{assert.fail('browser must stay closed');};
 const o=await owner(h);
 await assert.rejects(o.run(h.journey,limits()),{message:'billing_PROVISION'});
 const line=provisionLine('PROVISION_PUBLISH_VERIFY');
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(await o.close()),line);
 assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
 assert.equal(writes.at(-1).name,'billing-012-publish-dispatch.json');
 assert.ok(!requests.some(request=>request.path.endsWith('/publish')));
});

for(const outcome of ['rejected','abort','deadline'])test('PROVISION records the active mock receiver request on '+outcome,async t=>{
 if(outcome==='deadline')t.mock.timers.enable({apis:['Date','setTimeout'],now:1000});
 const h=harness(),controller=new AbortController(),entered=Promise.withResolvers(),release=Promise.withResolvers();
 const {fixture,writes,requests}=await provisionHarness({onFetch:async(path,settings,response)=>{
  if(path==='/configure'){entered.resolve();await release.promise;}
  return response;
 }});
 h.dependencies.createFixture=async()=>fixture;
 const o=await owner(h);
 const code=outcome==='rejected'?'PROVISION':'ABORTED';
 const run=assert.rejects(o.run(h.journey,{...limits(),signal:controller.signal}),{message:'billing_'+code});
 await entered.promise;
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(o.resources()),null);
 if(outcome==='deadline')t.mock.timers.tick(5000);
 else if(outcome==='abort')controller.abort();
 else release.reject(Error('private receiver canary'));
 await run;
 const result=await o.close(),line=provisionLine('PROVISION_RECEIVER_CONFIGURE');
 assert.equal(result.phase,'PROVISION');assert.equal(result.code,code);
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),line);
 assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
 release.resolve();await new Promise(r=>setImmediate(r));
 assert.deepEqual(await o.close(),result);
 assert.equal(writes.at(-1).name.endsWith('-callback-dispatch.json'),true);
 assert.equal(requests.at(-1).path,'/configure');
 assert.equal(requests.filter(r=>r.path.endsWith('/callback')).length,1);
});
test('PROVISION cannot report the checkpoint of a previously consumed genuine fixture',async()=>{
 const h=harness(),{fixture,requests,writes}=await provisionHarness({onFetch(path,settings,response){
  if(path==='/configure')throw Error('private receiver canary');
  return response;
 }});
 await assert.rejects(fixture.provision(limits()));
 assert.equal(requests.at(-1).path,'/configure');
 const originalRequests=requests.length,originalWrites=writes.length;
 h.dependencies.createFixture=async()=>fixture;
 const o=await owner(h);
 await assert.rejects(o.run(h.journey,limits()),{message:'billing_PROVISION'});
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(await o.close()),provisionLine('PROVISION_UNKNOWN'));
 assert.equal(requests.length,originalRequests);assert.equal(writes.length,originalWrites);
});
for(const foreign of [{runId:id(77)},{expectedSource:'a'.repeat(40)}])test('PROVISION rejects a genuine checkpoint from a different '+Object.keys(foreign)[0],async()=>{
 const h=harness(),{fixture,requests}=await provisionHarness({configuration:{...opts(),...foreign},onFetch(path,settings,response){
  if(path==='/configure')throw Error('private receiver canary');
  return response;
 }});
 h.dependencies.createFixture=async()=>fixture;
 const o=await owner(h);
 await assert.rejects(o.run(h.journey,limits()),{message:'billing_PROVISION'});
 assert.equal(requests.at(-1).path,'/configure');
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(await o.close()),provisionLine('PROVISION_UNKNOWN'));
});
test('successful PROVISION leaves subsequent failures attributed to their own stage',async()=>{
 const h=harness(),{fixture,writes}=await provisionHarness();
 h.dependencies.createFixture=async()=>fixture;
 h.journey.initial=async()=>{throw browserFailure('BROWSER_WAIT_READY');};
 const o=await owner(h);
 await assert.rejects(o.run(h.journey,limits()),{message:'billing_BROWSER'});
 const result=await o.close();
 assert.equal(writes.at(-1).name.endsWith('-fixture.json'),true);
 assert.equal(result.phase,'BROWSER');assert.equal(result.diagnostic,'BROWSER_WAIT_READY');
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),browserLine('BROWSER_WAIT_READY'));
});
function useIndependentInitialProof(h){
 const callbacks=data().row.callbacks.slice(0,2);
 const row={callbacks,owner_run:id(1),clock_reference:'clock_original',frozen_time:F,
  receipt_period_start:F,receipt_period_end:E,period_start:F,period_end:E,
  customer_reference:'cus_original',subscription_reference:'sub_original',product_reference:'prod_original',price_reference:'price_original',
  payment_id:id(12),billing_record_id:id(11),invoice_reference:'in_original',allocation_reference:'inpay_original',intent_reference:'pi_original',
  proof:{cash:true,journal:true,original:true,owned:true}};
 h.bootstrap.withDatabaseCredentials=cb=>cb({});
 h.fixture.inspectCallbacks=async()=>{h.calls.push('initial-receiver');return inspect(callbacks);};
 const connect=()=>({
  begin:async(mode,cb)=>{assert.equal(mode,'read only');return cb({unsafe:async query=>{
   if(query.startsWith('set local'))return [];
   h.calls.push('initial-select');return [row];
  }});},
  end:async()=>{}
 });
 h.dependencies.prove=async(options,l)=>{
  // Exercise the current four-flag reader on the baseline so the test reaches the arm gate there.
  const read=initialEvidence.proveBillingFailedRenewalInitialEvidence??initialEvidence.proveBillingFirstPaymentEvidence;
  const verified=await read(options,l,{connect});
  h.calls.push('initial-proof');return verified;
 };
}
for(const [field,substitute] of Object.entries({
 initial_bill_id:id(77),initial_payment_id:id(78),initial_invoice_id:'in_other',
 initial_allocation_id:'inpay_other',initial_payment_intent_id:'pi_other'
}))test('boundary cannot substitute '+field+' from the independently verified initial payment',async()=>{
 const h=harness();
 useIndependentInitialProof(h);
 for(const v of h.vs)v[field]=substitute;
 const o=await owner(h);
 await assert.rejects(o.run(h.journey,limits()));
 assert.deepEqual(h.calls.filter(v=>['initial-select','initial-receiver','initial-proof','boundary'].includes(v)),
  ['initial-select','initial-receiver','initial-proof','boundary']);
 assert.equal(h.calls.includes('arm-dispatch'),false);
 assert.equal(h.calls.includes('armDecline'),false);
 assert.equal((await o.close()).failedRenewalAccepted,false);
});
test('four legacy proof flags alone cannot authorize failed renewal',async()=>{
 const h=harness();
 h.dependencies.prove=async()=>proof();
 const o=await owner(h);
 await assert.rejects(o.run(h.journey,limits()));
 assert.equal(h.calls.includes('arm-dispatch'),false);
 assert.equal(h.calls.includes('armDecline'),false);
});
for(const pending of ['invocation','listener','ledger'])test('owner detaches session before '+pending+' completes',async()=>{
 const h=harness(),session={token:'original',origin:scope().endpoints.demo},transport=client();
 const change=async()=>{session.token='substituted';session.origin='https://other.invalid';return true;};
 if(pending==='listener')h.cli.assertBillingListener=change;
 if(pending==='ledger')h.fixture.beforeDispatch=change;
 h.runtime.ready=transport.runtime.ready;
 h.runtime.prepare=transport.runtime.prepare;
 h.journey.initial=async(p,l)=>{
  const sent=p.prepare(session,'sdk-create',l);
  if(pending==='invocation')await change();
  await sent;
  await p.confirm(confirmRequest(),l);
 };
 const o=await owner(h);
 await o.run(h.journey,limits());
 assert.equal(transport.calls.length,2);
 assert.equal(new URL(transport.calls[1][0]).pathname,'/initial-payment/prepare');
 assert.deepEqual(JSON.parse(transport.calls[1][1].body),{
  run_id:id(1),source_sha:opts().expectedSource,account_id:id(2),site_id:id(3),
  customer_id:id(4),price_id:id(5),origin:scope().endpoints.demo,create_key:'sdk-create',customer_token:'original'
 });
});
test('owner rejects session accessors before listener, ledger or transport without invoking them',async()=>{
 const h=harness(),session={origin:scope().endpoints.demo};let reads=0,sends=0;
 Object.defineProperty(session,'token',{enumerable:true,get(){reads++;return 'inert';}});
 h.journey.initial=(p,l)=>p.prepare(session,'sdk-create',l);
 h.runtime.prepare=async()=>{sends++;return prepared();};
 const o=await owner(h);
 await assert.rejects(o.run(h.journey,limits()));
 assert.equal(reads,0);assert.equal(sends,0);
 assert.equal(h.calls.includes('listen-check'),false);assert.equal(h.calls.includes('dispatch'),false);
});
test('owner detaches initial confirmation at invocation',async()=>{
 const h=harness(),request=confirmRequest(),bodies=[];
 h.runtime.confirm=async body=>{bodies.push({...body});return confirmed();};
 h.journey.initial=async(p,l)=>{
  await p.prepare({token:'inert',origin:scope().endpoints.demo},'sdk-create',l);
  const sent=p.confirm(request,l);
  request.confirmation_token='ctoken_substituted';request.idempotency_key='sdk-substituted';
  await sent;
 };
 const o=await owner(h);
 await o.run(h.journey,limits());
 assert.deepEqual(bodies,[confirmRequest()]);
});
test('same held journey, original proof before E, ledger before fixed arm, genuine publication before proof and definite close',async()=>{
 const h=harness(),o=await owner(h),r=await o.run(h.journey,limits());
 assert.deepEqual(h.calls.slice(h.calls.indexOf('observe')),['observe','initial-proof','boundary','arm-dispatch','armDecline','arm-ack',...methods.slice(2,9),'manual-confirm',...methods.slice(9),'final-proof','browser-close','runtime-close','listener-stop','stop','fixture-close']);
 assert.equal(r.scope,'OWNED_FAILED_RENEWAL_GRACE_RECOVERY');assert.equal(r.cleanupConfirmed,true);assert.equal(r.failedRenewalAccepted,true);assert.equal(r.initialAccepted,true);assert.equal(r.full5bAccepted,false);assert.ok(!JSON.stringify(r).includes('method-inert'));await assert.rejects(o.run(h.journey,limits()));
});
const postArmLine=(stage,checkpoint,cleanup='CONFIRMED',witness='NONE')=>
 'owned_failed_renewal_diagnostic stage='+stage+' checkpoint='+checkpoint+' cleanup='+cleanup+' witness='+witness;
for(const [checkpoint,abortFirst,close] of [
 ['BROWSER_METHOD_MOUNT',false,true],
 ['BROWSER_PROVIDER_STATIC_REQUEST_NAVIGATION_STRIPE_PARENT',true,true],
 ['BROWSER_METHOD_CONFIRM',true,false],
])test('method browser terminal preserves its original held rejection '+checkpoint,async()=>{
 const h=harness(),abort=new AbortController(),error=browserFailure(checkpoint,'METHOD');
 let rejectMethod;
 h.journey.method=async(p,l)=>{
  await p.prepareMethod(l);await p.selectMethod(l);
  if(checkpoint==='BROWSER_METHOD_CONFIRM')await p.confirmMethod(l);
  if(abortFirst){abort.abort();return new Promise((_,reject)=>{rejectMethod=reject;});}
  throw error;
 };
 h.journey.close=async()=>{rejectMethod?.(error);return close;};
 const o=await owner(h);await assert.rejects(o.run(h.journey,{...limits(),signal:abort.signal}));
 const result=await o.close(),line=postArmLine('METHOD_BROWSER',checkpoint,close?'CONFIRMED':'UNCONFIRMED',close?'NONE':'BROWSER_CLOSE');
 assert.equal(result.code,abortFirst?'ABORTED':'METHOD_BROWSER');
 assert.equal(result.diagnostic,checkpoint);
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),line);
 assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
 assert.equal(result.failedRenewalAccepted,false);assert.ok(!h.calls.includes('applyMethod'));
});
test('method browser terminal excludes initial, fabricated and unknown errors',async()=>{
 let reads=0;const code='BROWSER_CARD_NUMBER',trusted=browserFailure(code,'METHOD');
 const hostile=new Proxy({},{get(){reads++;throw Error('private canary');}});
 for(const error of [browserFailure(code),browserFailure('BROWSER_OTP_VERIFY','METHOD'),browserFailure('BROWSER_PRIVATE','METHOD'),Object.create(trusted),hostile,new Error(code),code]){
  const h=harness();h.journey.method=async()=>{throw error;};
  const o=await owner(h);await assert.rejects(o.run(h.journey,limits()));
  assert.equal(owners.formatBillingFailedRenewalDiagnostic(await o.close()),postArmLine('METHOD_BROWSER','METHOD_BROWSER_ACTION'));
 }
 const h=harness();h.journey.initial=async()=>{throw trusted;};
 const o=await owner(h);await assert.rejects(o.run(h.journey,limits()));
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(await o.close()),null);
 assert.equal(reads,0);
 for(const code of ['BROWSER_OTP_VERIFY','BROWSER_INITIAL_MOUNT','BROWSER_PROVIDER_STATIC_NAVIGATION_BLOCK','BROWSER_CLOSE','BROWSER_PRIVATE']){
  assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(postArmLine('METHOD_BROWSER',code)),null);
 }
 for(const code of ['BROWSER_METHOD_MOUNT','BROWSER_METHOD_CONFIRM']){
  assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(browserLine(code)),null);
 }
});
test('method browser terminal keeps earlier owner request, response or order failure',async()=>{
 for(const boundary of ['REQUEST','RESPONSE','ORDER']){
  const h=harness(),error=browserFailure('BROWSER_SESSION_BRIDGE','METHOD');
  h.runtime.selectMethod=async()=>{if(boundary==='REQUEST')throw Error('private transport');return {};};
  h.journey.method=async(p,l)=>{
   if(boundary!=='ORDER')await p.prepareMethod(l);
   try{await p.selectMethod(l);}catch{throw error;}
   return true;
  };
  const o=await owner(h);await assert.rejects(o.run(h.journey,limits()));
  const checkpoint=boundary==='ORDER'?'METHOD_ORDER':'METHOD_SELECT_'+boundary;
  assert.equal(owners.formatBillingFailedRenewalDiagnostic(await o.close()),postArmLine('METHOD_BROWSER',checkpoint));
 }
});
test('a method-tagged private origin cannot reach the initial witness writer through either journey phase',async()=>{
 for(const phase of ['initial','method']){
  const h=harness(),code='BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER',error=browserFailure(code,'METHOD');
  let writes=0;
  h.bootstrap.persistRefusedStripeOrigin=()=>{writes++;return true;};
  assert.equal(rememberBrowserProviderOrigin(error,opts().runId,opts().expectedSource,privateOrigin),true);
  h.journey[phase]=async()=>{throw error;};
  const o=await owner(h);await assert.rejects(o.run(h.journey,limits()));const result=await o.close();
  assert.equal(writes,0);assert.equal(JSON.stringify(result).includes(privateOrigin),false);
  assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),phase==='method'?postArmLine('METHOD_BROWSER',code):null);
 }
});
test('method rejection arriving after a refused close cannot refine the closed terminal',async()=>{
 const h=harness(),abort=new AbortController(),error=browserFailure('BROWSER_MANUAL_PAY_WAIT','METHOD');
 let rejectMethod;
 h.journey.method=async()=>{
  abort.abort();return new Promise((_,reject)=>{rejectMethod=reject;});
 };
 h.journey.close=async()=>{throw error;};
 const o=await owner(h);await assert.rejects(o.run(h.journey,{...limits(),signal:abort.signal}));
 const line=postArmLine('METHOD_BROWSER','METHOD_BROWSER_ACTION','UNCONFIRMED','BROWSER_CLOSE');
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(await o.close()),line);
 rejectMethod(error);await new Promise(resolve=>setImmediate(resolve));
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(o.resources()),line);
 assert.ok(!h.calls.includes('applyMethod'));
});
const afterArm=[
 ['failedRenewal','FAILED_RENEWAL'],['graceLastSecond','GRACE_LAST_SECOND'],['graceAt','GRACE_AT'],['graceAfter','GRACE_AFTER'],
 ['prepareMethod','METHOD_PREPARE','METHOD_BROWSER'],['selectMethod','METHOD_SELECT','METHOD_BROWSER'],['confirmMethod','METHOD_CONFIRM','METHOD_BROWSER'],
 ['applyMethod','METHOD_APPLY'],['confirmRecovery','RECOVERY_CONFIRM'],['observeRecovery','RECOVERY_OBSERVE']
];
for(const [method,operation,stage=operation] of afterArm)for(const boundary of ['REQUEST','RESPONSE'])
test('post-arm '+method+' stops at its own '+boundary,async()=>{
 const h=harness();let inspected=0;
 const refused=new Proxy({},{get(){inspected++;throw Error('private fixture canary');}});
 h.runtime[method]=async()=>{h.calls.push(method);if(boundary==='REQUEST')throw refused;return {secret:'private response canary'};};
 const o=await owner(h);await assert.rejects(o.run(h.journey,limits()));const r=await o.close();
 const line=postArmLine(stage,operation+'_'+boundary);
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(r),line);
 assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
 assert.equal(inspected,0);assert.equal(r.failedRenewalAccepted,false);
 assert.deepEqual(h.calls.filter(v=>methods.includes(v)),methods.slice(0,methods.indexOf(method)+1));
 assert.equal(h.calls.filter(v=>v==='arm-ack').length,1);assert.ok(!h.calls.includes('final-proof'));
});
for(const [stage,checkpoint,change] of [
 ['ARM_ACK','ARM_ACK_PERSIST',h=>h.fixture.acknowledgeDeclineArm=async()=>false],
 ['METHOD_BROWSER','METHOD_BROWSER_ACTION',h=>h.journey.method=async(p,l)=>{await p.prepareMethod(l);throw Error('private browser canary');}],
 ['METHOD_BROWSER','METHOD_ORDER',h=>h.journey.method=async()=>true],
 ['METHOD_BROWSER','METHOD_ORDER',h=>h.journey.method=(p,l)=>p.selectMethod(l)],
 ['RECOVERY_EVIDENCE','RECOVERY_EVIDENCE_READ',h=>h.dependencies.proveFailedRenewal=async()=>{throw Error('private evidence canary');}],
 ['RECOVERY_EVIDENCE','RECOVERY_EVIDENCE_VERIFY',h=>h.dependencies.proveFailedRenewal=async()=>null],
 ['CLEANUP','POST_ARM_CLEANUP',h=>h.journey.close=async()=>false],
])test('post-arm distinguishes '+stage+' / '+checkpoint,async()=>{
 const h=harness();change(h);const o=await owner(h);await assert.rejects(o.run(h.journey,limits()));
 const r=await o.close(),line=postArmLine(stage,checkpoint,...(stage==='CLEANUP'?['UNCONFIRMED','BROWSER_CLOSE']:[]));
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(r),line);assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
 assert.equal(r.failedRenewalAccepted,false);if(stage==='ARM_ACK')assert.ok(!h.calls.includes('failedRenewal'));
});
test('post-arm abort freezes the pending request before late response and independent cleanup refusal',async()=>{
 const h=harness(),abort=new AbortController();let finish;
 h.runtime.failedRenewal=async()=>{h.calls.push('failedRenewal');abort.abort();return new Promise(resolve=>{finish=resolve;});};
 h.bootstrap.stop=async()=>false;
 const o=await owner(h);await assert.rejects(o.run(h.journey,{...limits(),signal:abort.signal}));const r=await o.close();
 const line=postArmLine('FAILED_RENEWAL','FAILED_RENEWAL_REQUEST','UNCONFIRMED','BOOTSTRAP_STOP');
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(r),line);
 finish(h.vs[2]);await new Promise(resolve=>setImmediate(resolve));
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(o.resources()),line);assert.ok(!h.calls.includes('graceLastSecond'));
});
test('cancellation as final recovery proof settles retains its verification checkpoint',async()=>{
 // Exercise both the bounded proof and the outer final lifetime check.
 for(const delay of [1,2,3,4,5]){
  const h=harness(),abort=new AbortController();
  h.dependencies.proveFailedRenewal=async()=>({
   ...proof(),get signedCallbacksVerified(){
    let ticks=delay;const cancel=()=>{if(--ticks===0)abort.abort();else queueMicrotask(cancel);};
    queueMicrotask(cancel);return true;
   }
  });
  const o=await owner(h);await assert.rejects(o.run(h.journey,{...limits(),signal:abort.signal}));
  const result=await o.close();assert.equal(result.code,'ABORTED');
  assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),postArmLine('RECOVERY_EVIDENCE','RECOVERY_EVIDENCE_VERIFY'));
  assert.equal(result.failedRenewalAccepted,false);
 }
});
for(const [fault,checkpoint] of [['refused','FAILED_RENEWAL_HTTP'],['invalid reply','FAILED_RENEWAL_RESPONSE'],['abort in fetch','FAILED_RENEWAL_TRANSPORT'],
 ['server stage','FAILED_RENEWAL_SERVER_OBSERVATION'],['observer substage','FAILED_RENEWAL_SERVER_OBSERVATION_FAILED_INTENT'],
 ['foreign recovery stage','FAILED_RENEWAL_HTTP']])
test('post-arm owner uses the real runtime checkpoint on '+fault,async()=>{
 const h=harness(),paths=[],abort=new AbortController();
 const replies=[{phase:'NEW',code:'OK',run_id:id(1)},prepared(),confirmed(),initial(),...h.vs.slice(0,2)];
 h.dependencies.createRuntime=()=>clients.createBillingFailedRenewalRuntime({configuration:opts(),bootstrap:{
  withFailedRenewalRuntime:cb=>cb({endpoint:'http://127.0.0.1:18083',capability:'x'.repeat(43)})
 }},{fetch:async url=>{
  const path=new URL(url).pathname;paths.push(path);
  if(path==='/failed-renewal/abort')return new Response('{"code":"QUIESCENT"}');
  if(path==='/failed-renewal/failed'){
   if(fault==='abort in fetch'){abort.abort();return new Promise(()=>{});}
   if(fault==='server stage')return new Response('{"code":"FAILED_RENEWAL_STOPPED","stage":"FAILED_RENEWAL_OBSERVATION"}',{status:400});
   if(fault==='observer substage')return new Response('{"code":"FAILED_RENEWAL_STOPPED","stage":"FAILED_RENEWAL_OBSERVATION_FAILED_INTENT"}',{status:400});
   if(fault==='foreign recovery stage')return new Response('{"code":"FAILED_RENEWAL_STOPPED","stage":"RECOVERY_CONFIRM_CONFIRMATION_POST_STRIPE_CARD"}',{status:400});
   return new Response('private response canary',{status:fault==='refused'?503:200});
  }
  return new Response(JSON.stringify(replies.shift()));
 }});
 const o=await owner(h);await assert.rejects(o.run(h.journey,{...limits(),signal:abort.signal}));
 const result=await o.close(),line=postArmLine('FAILED_RENEWAL',checkpoint);
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),line);
 assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
 assert.equal(paths.at(-2),'/failed-renewal/failed');assert.equal(paths.at(-1),'/failed-renewal/abort');
 assert.equal(paths.filter(path=>path==='/failed-renewal/failed').length,1);
 assert.equal(result.failedRenewalAccepted,false);
});
for(const [fault,checkpoint] of [
 ['authority','INITIAL_CONFIRM_AUTHORITY'],
 ['transport','INITIAL_CONFIRM_TRANSPORT'],
 ['abort','INITIAL_CONFIRM_TRANSPORT'],
 ['http','INITIAL_CONFIRM_HTTP'],
 ['server','INITIAL_CONFIRM_SERVER_SERVICE'],
 ['duplicate server stage','INITIAL_CONFIRM_HTTP'],
 ['unknown server stage','INITIAL_CONFIRM_HTTP'],
 ['invalid response','INITIAL_CONFIRM_RESPONSE'],
 ['mismatched response','INITIAL_CONFIRM_RESPONSE'],
 ['ack','INITIAL_CONFIRM_ACK'],
])test('initial CONFIRM keeps a bounded terminal checkpoint for '+fault,async()=>{
 const h=harness(),paths=[],ledger=[],abort=new AbortController();
 const lateReply=fault==='abort'?Promise.withResolvers():null;
 const initialPaths=['/initial-payment/status','/initial-payment/prepare'];
 const replies=[{phase:'NEW',code:'OK',run_id:id(1)},prepared()];
 const confirmPath='/initial-payment/confirm',abortPath='/failed-renewal/abort';
 h.fixture.beforeDispatch=async kind=>{ledger.push(kind+':DISPATCHING');return true;};
 h.fixture.acknowledge=async kind=>{
  if(kind==='confirm'&&fault==='ack')return false;
  ledger.push(kind+':ACK');return true;
 };
 h.dependencies.createRuntime=()=>clients.createBillingFailedRenewalRuntime({configuration:opts(),bootstrap:{
  withFailedRenewalRuntime:cb=>fault==='authority'&&replies.length===0?false:cb({endpoint:'http://127.0.0.1:18083',capability:'x'.repeat(43)})
 }},{fetch:async url=>{
  const path=new URL(url).pathname;paths.push(path);
  if(path===abortPath)return new Response('{"code":"QUIESCENT"}');
  if(path!==confirmPath)return new Response(JSON.stringify(replies.shift()));
  switch(fault){
   case 'abort':abort.abort();return lateReply.promise;
   case 'transport':throw new Error('raw private transport canary');
   case 'http':return new Response('raw private HTTP canary',{status:503});
   case 'server':return new Response('{"code":"INITIAL_PAYMENT_STOPPED","stage":"CONFIRM_SERVICE"}',{status:400});
   case 'duplicate server stage':return new Response('{"code":"INITIAL_PAYMENT_STOPPED","stage":"CONFIRM_SERVICE","stage":"CONFIRM_STATE"}',{status:400});
   case 'unknown server stage':return new Response('{"code":"INITIAL_PAYMENT_STOPPED","stage":"CONFIRM_PRIVATE_CANARY"}',{status:400});
   case 'invalid response':return new Response('raw private response canary');
   case 'mismatched response':return new Response(JSON.stringify({...confirmed(),attempt_id:id(99)}));
   default:return new Response(JSON.stringify(confirmed()));
  }
 }});
 if(fault==='ack')h.bootstrap.stop=async()=>false;
 const o=await owner(h);await assert.rejects(o.run(h.journey,{...limits(),signal:abort.signal}));const result=await o.close();
 const line=fault==='ack'?browserLine(checkpoint,'UNCONFIRMED','BOOTSTRAP_STOP'):browserLine(checkpoint);
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),line);
 assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
 for(const stage of ['PROVISION','INITIAL_CONFIRM']){
  assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line.replace('stage=BROWSER','stage='+stage)),null);
  assert.equal(owners.formatBillingFailedRenewalDiagnostic({...result,phase:stage,code:stage}),null);
 }
 assert.equal(JSON.stringify(result).toLowerCase().includes('canary'),false);
 assert.equal(result.initialAccepted,false);
 assert.equal(result.failedRenewalAccepted,false);
 assert.deepEqual(ledger,['create:DISPATCHING','create:ACK','confirm:DISPATCHING']);
 assert.deepEqual(paths,[...initialPaths,...(fault==='authority'?[]:[confirmPath]),abortPath]);
 if(lateReply){
  lateReply.resolve(new Response('{"code":"INITIAL_PAYMENT_STOPPED","stage":"CONFIRM_SERVICE"}',{status:400}));
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(owners.formatBillingFailedRenewalDiagnostic(o.resources()),line);
 }
 await assert.rejects(o.run(h.journey,limits()));
 await o.close();
 assert.equal(paths.filter(path=>path===confirmPath).length,fault==='authority'?0:1);
 assert.equal(paths.filter(path=>path===abortPath).length,1);
});
test('post-arm owner reports the grace clock refusal without invoking another financial step',async()=>{
 const h=harness(),paths=[];
 const replies=[{phase:'NEW',code:'OK',run_id:id(1)},prepared(),confirmed(),initial(),...h.vs.slice(0,3)];
 h.dependencies.createRuntime=()=>clients.createBillingFailedRenewalRuntime({configuration:opts(),bootstrap:{
  withFailedRenewalRuntime:cb=>cb({endpoint:'http://127.0.0.1:18083',capability:'x'.repeat(43)})
 }},{fetch:async url=>{
  const path=new URL(url).pathname;paths.push(path);
  if(path==='/failed-renewal/abort')return new Response('{"code":"QUIESCENT"}');
  if(path==='/failed-renewal/grace-last-second')
   return new Response('{"code":"FAILED_RENEWAL_STOPPED","stage":"GRACE_LAST_SECOND_CLOCK_POST"}',{status:400});
  return new Response(JSON.stringify(replies.shift()));
 }});
 const o=await owner(h);await assert.rejects(o.run(h.journey,limits()));
 const result=await o.close(),line=postArmLine('GRACE_LAST_SECOND','GRACE_LAST_SECOND_SERVER_CLOCK_POST');
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),line);
 assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
 assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(postArmLine('GRACE_AT','GRACE_LAST_SECOND_SERVER_CLOCK_POST')),null);
 assert.deepEqual(paths.slice(-3),['/failed-renewal/failed','/failed-renewal/grace-last-second','/failed-renewal/abort']);
 assert.equal(result.failedRenewalAccepted,false);
});
const recoveryPostStages=[
 'ADMISSION','WORKER_ADMISSION','WAIT_BUDGET','TIMEOUT','INTERRUPTED','REPLY_BUDGET',
 'STRIPE_CARD','STRIPE_INVALID_REQUEST','STRIPE_INVALID_REQUEST_NO_PARAM',
 'STRIPE_INVALID_PARAM_PAYMENT_METHOD','STRIPE_INVALID_PARAM_OFF_SESSION',
 'STRIPE_INVALID_PARAM_ERROR_ON_REQUIRES_ACTION','STRIPE_INVALID_PARAM_OTHER',
 'STRIPE_ACCESS','STRIPE_RATE_LIMIT','STRIPE_IDEMPOTENCY',
 'STRIPE_CONNECTION','STRIPE_API','STRIPE_OTHER',
];
for(const [fault,checkpoint] of [
 ['authority','AUTHORITY'],['transport','TRANSPORT'],['late server reply','TRANSPORT'],['http','HTTP'],['invalid response','RESPONSE'],
 ['server confirmation','SERVER_CONFIRMATION'],['server post','SERVER_CONFIRMATION_POST'],['server provider reply','SERVER_CONFIRMATION_REPLY'],
 ...recoveryPostStages.map(stage=>['server post '+stage,'SERVER_CONFIRMATION_POST_'+stage]),
 ['post-ack access','SERVER_ACCESS'],['extra server field','HTTP'],['unknown server post suffix','HTTP'],['wrong server route','HTTP'],
])test('recovery confirmation preserves '+fault+' without observing or repeating the debt operation',async()=>{
 const h=harness(),paths=[],abort=new AbortController();
 let releaseLateReply;
 const replies=[{phase:'NEW',code:'OK',run_id:id(1)},prepared(),confirmed(),initial(),...h.vs.slice(0,10)];
 h.dependencies.createRuntime=()=>clients.createBillingFailedRenewalRuntime({configuration:opts(),bootstrap:{
  withFailedRenewalRuntime:cb=>fault==='authority'&&replies.length===0?false:cb({endpoint:'http://127.0.0.1:18083',capability:'x'.repeat(43)})
 }},{fetch:async url=>{
  const path=new URL(url).pathname;paths.push(path);
  if(path==='/failed-renewal/abort')return new Response('{"code":"QUIESCENT"}');
  if(path==='/failed-renewal/recovery/confirm'){
   if(fault==='transport')throw Error('private recovery canary');
   if(fault==='late server reply')return new Promise(resolve=>{releaseLateReply=resolve;abort.abort();});
   if(fault==='http')return new Response('private recovery canary',{status:503});
   if(fault==='invalid response')return new Response(JSON.stringify({...h.vs[10],recovery_charge_id:'private recovery canary'}));
   const confirmPost='RECOVERY_CONFIRM_CONFIRMATION_POST_';
   const stage=fault==='wrong server route'?'FAILED_RENEWAL_ADMISSION':
    fault==='unknown server post suffix'?confirmPost+'STRIPE_INVALID_PARAM_PRIVATE':
    fault==='extra server field'?confirmPost+'STRIPE_INVALID_REQUEST':
    'RECOVERY_CONFIRM_'+checkpoint.slice('SERVER_'.length);
   const body={code:'FAILED_RENEWAL_STOPPED',stage};
   if(fault==='extra server field')body.detail='private recovery canary';
   return new Response(JSON.stringify(body),{status:400});
  }
  return new Response(JSON.stringify(replies.shift()));
 }});
 const o=await owner(h);await assert.rejects(o.run(h.journey,{...limits(),signal:abort.signal}));
 const result=await o.close(),line=postArmLine('RECOVERY_CONFIRM','RECOVERY_CONFIRM_'+checkpoint);
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),line);
 assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
 assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(postArmLine('RECOVERY_OBSERVE','RECOVERY_CONFIRM_'+checkpoint)),null);
 assert.equal(paths.at(-1),'/failed-renewal/abort');
 assert.equal(paths.filter(path=>path==='/failed-renewal/recovery/confirm').length,fault==='authority'?0:1);
 assert.ok(!paths.includes('/failed-renewal/recovery/observe'));
 assert.equal(result.failedRenewalAccepted,false);
 assert.ok(!JSON.stringify(result).includes('private recovery canary'));
 if(fault==='late server reply'){
  assert.equal(result.code,'ABORTED');
  releaseLateReply(new Response('{"code":"FAILED_RENEWAL_STOPPED","stage":"RECOVERY_CONFIRM_CONFIRMATION_POST_STRIPE_CONNECTION"}',{status:400}));
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(owners.formatBillingFailedRenewalDiagnostic(o.resources()),line);
 }
 const requestCount=paths.length;
 await assert.rejects(o.run(h.journey,limits()),/SINGLE_USE/);
 assert.deepEqual(await o.close(),result);
 assert.equal(paths.length,requestCount);
});
test('recovery confirmation reports the longest parameter checkpoint with an unconfirmed cleanup witness',()=>{
 const diagnostic='RECOVERY_CONFIRM_SERVER_CONFIRMATION_POST_STRIPE_INVALID_PARAM_ERROR_ON_REQUIRES_ACTION';
 const result={scope:'OWNED_FAILED_RENEWAL_GRACE_RECOVERY',status:'CLOSED',phase:'RECOVERY_CONFIRM',code:'RECOVERY_CONFIRM',
  diagnostic,initialAccepted:false,failedRenewalAccepted:false,full5bAccepted:false,cleanupConfirmed:false,cleanupFailure:'BROWSER_UNSETTLED'};
 const line=postArmLine('RECOVERY_CONFIRM',diagnostic,'UNCONFIRMED','BROWSER_UNSETTLED');
 assert.equal(owners.formatBillingFailedRenewalDiagnostic(result),line);
 assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),line);
});
test('post-arm terminal protocol rejects foreign stages, mismatched codes, arbitrary text and false cleanup claims',()=>{
 const base={scope:'OWNED_FAILED_RENEWAL_GRACE_RECOVERY',status:'CLOSED',phase:'FAILED_RENEWAL',code:'FAILED_RENEWAL',diagnostic:'FAILED_RENEWAL_REQUEST',initialAccepted:false,failedRenewalAccepted:false,full5bAccepted:false,cleanupConfirmed:true,cleanupFailure:null};
 for(const changes of [{diagnostic:'GRACE_AT_REQUEST'},{scope:'OWNED_INITIAL_PAYMENT'},{phase:'PRIVATE'},{code:'METHOD_ORDER'},{initialAccepted:true},{failedRenewalAccepted:true},{full5bAccepted:true},{status:'RUNNING'},{diagnostic:'FAILED_RENEWAL_PRIVATE'}])assert.equal(owners.formatBillingFailedRenewalDiagnostic({...base,...changes}),null);
 for(const line of [postArmLine('FAILED_RENEWAL','GRACE_AT_REQUEST'),postArmLine('METHOD_BROWSER','GRACE_AT_RESPONSE'),postArmLine('GRACE_AT','FAILED_RENEWAL_SERVER_ADMISSION'),postArmLine('PRIVATE','PRIVATE_REQUEST'),postArmLine('CLEANUP','POST_ARM_CLEANUP'),postArmLine('FAILED_RENEWAL','FAILED_RENEWAL_REQUEST')+'\n'])assert.equal(owners.parseBillingFailedRenewalDiagnosticLine(line),null);
 assert.equal(owners.parseBillingFirstPaymentDiagnosticLine('owned_initial_payment_diagnostic code=FAILED_RENEWAL_REQUEST cleanup=CONFIRMED'),null);
 assert.equal(owners.parseBillingFirstRenewalDiagnosticLine('owned_initial_and_first_renewal_diagnostic code=FAILED_RENEWAL_REQUEST cleanup=CONFIRMED'),null);
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
const prepareScope=()=>({...scope(),origin:scope().endpoints.demo,createKey:'sdk-create',customerToken:'inert'});
async function settle(r,l){await r.ready(l);await r.prepare(prepareScope(),l);await r.confirm(confirmRequest(),l);await r.observe(l);}
test('failed client sends the original selected confirmation body after the caller mutates every field',async()=>{
 const {runtime:r,calls}=client(),l=limits(),original=confirmRequest();
 await r.ready(l);await r.prepare(prepareScope(),l);
 const sent=r.confirm(original,l);
 Object.assign(original,{attempt_id:id(77),capability:'foreign',confirmation_token:'ctoken_other',idempotency_key:'sdk-other'});
 await sent;
 assert.equal(calls.length,3);
 assert.equal(new URL(calls[2][0]).pathname,'/initial-payment/confirm');
 assert.deepEqual(JSON.parse(calls[2][1].body),confirmRequest());
});
for(const [name,change] of [
 ['wrong attempt',p=>{p.attempt_id=id(77);}],
 ['wrong capability',p=>{p.capability='foreign';}],
 ['extra property',p=>{p.target='foreign';}],
 ['hidden extra',p=>Object.defineProperty(p,'extra',{value:'foreign'})],
 ['custom prototype',p=>Object.setPrototypeOf(p,{extra:true})],
 ['attempt accessor',(p,touched)=>Object.defineProperty(p,'attempt_id',{get(){touched();return id(9);}})],
 ['token accessor',(p,touched)=>Object.defineProperty(p,'confirmation_token',{get(){touched();return 'ctoken_other';}})],
 ['toJSON',(p,touched)=>{p.toJSON=()=>{touched();return confirmRequest();};}]
])test('failed client consumes '+name+' without dispatch or invoking caller code',async()=>{
 const {runtime:r,calls}=client(),l=limits(),original=confirmRequest();let touches=0;
 await r.ready(l);await r.prepare(prepareScope(),l);
 change(original,()=>{touches++;});
 await assert.rejects(r.confirm(original,l));
 await assert.rejects(r.confirm(confirmRequest(),l));
 assert.equal(calls.length,2);
 assert.equal(touches,0);
});
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

for(const key of ['origin','createKey','customerToken'])test('failed direct prepare requires own '+key+' before transport',async()=>{
 for(const variant of ['missing','inherited','getter']){
  const {runtime:r,calls}=client(),l=limits(),s=prepareScope();let reads=0;
  await r.ready(l);delete s[key];
  if(variant==='inherited')Object.setPrototypeOf(s,{[key]:'borrowed'});
  if(variant==='getter')Object.defineProperty(s,key,{enumerable:true,get(){reads++;return 'borrowed';}});
  await assert.rejects(r.prepare(s,l));await assert.rejects(r.prepare(prepareScope(),l));assert.equal(calls.length,1);assert.equal(reads,0);
 }
});

for(const terminal of ['quiescent','unproved','transport-error'])test('actual owner sends one independent terminal control during a busy arm: '+terminal,async()=>{
 const h=harness(),external=new AbortController(),financial=Promise.withResolvers(),browserClosing=Promise.withResolvers();
 const release=Promise.withResolvers(),releaseBrowser=Promise.withResolvers(),calls=[],l=limits();
 const queue=[{phase:'NEW',code:'OK',run_id:id(1)},prepared(),confirmed(),initial(),responses()[0]];
 let busy=false;
 h.bootstrap.withFailedRenewalRuntime=async cb=>{
  assert.equal(busy,false,'ordinary bootstrap is busy during financial transport');busy=true;
  try{return await cb({endpoint:'http://127.0.0.1:18083',capability:'x'.repeat(43)});}finally{busy=false;}
 };
 h.dependencies.createRuntime=options=>clients.createBillingFailedRenewalRuntime(options,{fetch:async(url,r)=>{
  const path=new URL(url).pathname;calls.push(path);
  if(path==='/failed-renewal/arm-decline'){
   financial.resolve();await release.promise;
   if(terminal==='transport-error')throw Error('uncertain arm transport');
   return new Response(JSON.stringify(responses()[1]));
  }
  if(path==='/failed-renewal/abort'){
   assert.equal(external.signal.aborted,terminal!=='transport-error');assert.equal(r.signal.aborted,false);
   assert.equal(r.method,'POST');assert.equal(r.body,'{}');assert.equal(r.redirect,'error');assert.equal(r.headers.Authorization,'Bearer '+'x'.repeat(43));
   return new Response(JSON.stringify({code:terminal==='unproved'?'RECEIVED':'QUIESCENT'}));
  }
  assert.ok(queue.length);return new Response(JSON.stringify(queue.shift()));
 }});
 h.journey.close=async()=>{browserClosing.resolve();await releaseBrowser.promise;return true;};
 const o=await owner(h),run=assert.rejects(o.run(h.journey,{...l,signal:external.signal}));
 try{
  await financial.promise;
  if(terminal==='transport-error')release.resolve();else external.abort();
  await browserClosing.promise;
  await new Promise(r=>setImmediate(r));
  assert.equal(calls.filter(p=>p==='/failed-renewal/abort').length,1,'revoke before browser drain, independent of busy callback');
 }finally{release.resolve();releaseBrowser.resolve();await run;}
 const result=await o.close();
 assert.equal(result.cleanupConfirmed,terminal!=='unproved');assert.equal(result.failedRenewalAccepted,false);
 assert.equal(calls.filter(p=>p==='/failed-renewal/abort').length,1);
 assert.equal(calls.filter(p=>p==='/failed-renewal/arm-decline').length,1);
 assert.ok(h.calls.includes('arm-dispatch'));assert.ok(!h.calls.includes('arm-ack'));assert.ok(!calls.includes('/failed-renewal/failed'));
});
