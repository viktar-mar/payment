import test,{before,after} from 'node:test';
import assert from 'node:assert/strict';
import {opts,scope,initial,prepared,confirmed,confirmRequest,methods,routes,responses,id,F,E,G,iso} from './fixtures/failed-renewal-descriptors.mjs';
import {data,inspect} from './fixtures/failed-renewal-evidence-rows.mjs';
import * as initialEvidence from './stripe-billing-first-payment-evidence.mjs';
import * as owners from './stripe-billing-first-payment.mjs';
import * as clients from './stripe-billing-first-payment-runtime.mjs';
import {browserFailure,finishBrowserCleanup} from './stripe-billing-first-payment-browser-diagnostic.mjs';
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
const persistCodes=['PERSIST_ADMISSION','PERSIST_OWNERSHIP','PERSIST_INPUT','PERSIST_UNCHANGED','PERSIST_FINAL_STORE','PERSIST_ACK'];
const terminalLine=(checkpoint='PERSIST_FINAL_STORE',cleanup='CONFIRMED',witness='NONE')=>'owned_failed_renewal_diagnostic stage=LISTENER checkpoint='+checkpoint+' cleanup='+cleanup+' witness='+witness;
const browserLine=(checkpoint='BROWSER_CARD_NUMBER',cleanup='CONFIRMED',witness='NONE')=>'owned_failed_renewal_diagnostic stage=BROWSER checkpoint='+checkpoint+' cleanup='+cleanup+' witness='+witness;
for(const checkpoint of ['BROWSER_PROVIDER_ORIGIN_STRIPE_COM_M','BROWSER_PROVIDER_ORIGIN_STRIPE_COM_R','BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER','BROWSER_PROVIDER_ORIGIN_STRIPE_COM'])test('failed-renewal refused-origin terminal roundtrip '+checkpoint,async()=>{
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
  await assert.rejects(leaked.confirm(confirmRequest(),limits()));assert.deepEqual(dispatches,['create']);
  assert.equal(h.calls.includes('observe'),false);assert.equal(h.calls.includes('arm-dispatch'),false);assert.equal(h.calls.includes('manual-confirm'),false);
  assert.equal(h.calls.some(call=>methods.includes(call)),false);
 }
});
for(const checkpoint of ['BROWSER_PROVIDER_ORIGIN_STRIPE_COM_M','BROWSER_PROVIDER_ORIGIN_STRIPE_COM_R','BROWSER_PROVIDER_ORIGIN_STRIPE_COM_OTHER','BROWSER_PROVIDER_ORIGIN_STRIPE_COM'])test('failed-renewal refuses forged origin terminal '+checkpoint,async()=>{
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
