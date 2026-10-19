import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {EventEmitter} from 'node:events';
const subject=await import('./stripe-manual-form.mjs').catch(error=>{if(error.code==='ERR_MODULE_NOT_FOUND')return {};throw error;});
const flags={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'};
const options=()=>({env:{...flags},runId:'11111111-2222-4333-8444-555555555555',expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',manualPort:18083,
  ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025}});
const ready={version:1,binding:'BOUND',capability_started:true,capability_ready:true,loader_started:true,loader_ready:true,
  element_created:true,mount_called:true,mount_returned:true,ready_seen:true,loaderror_seen:false,view_completed:true,widget_completed:true,
  confirmation_blocked:false,catchup:'NOT_STARTED',operation:'COMPLETED',widget_state:'ready',element_state:'FRAME',pay_state:'ENABLED',pay_visible:true,bucket:'BEFORE_15S'};
const evidence={loaderstart_seen:true,host_box:'POSITIVE_AREA',frame_box:'POSITIVE_AREA',host_visibility:'VISIBLE',frame_visibility:'VISIBLE',frame_load:'SEEN',csp_observation:'OBSERVED',csp_enforced_mask:2,csp_report_only_mask:4};
const result=patch=>({evidence:patch?.sample===null?null:evidence,observation:'READY_REPORTED',end:'READY_REPORTED',sample:ready,...patch});
const defer=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};};
function fixture(){
  let time=1000;const timers=new Map(),calls=[],writes=[],signal=new AbortController();
  const limits={deadline:901000,signal:signal.signal};
  const session={version:2,apiBaseUrl:'http://127.0.0.1:18080',siteKey:'manual-site',priceKey:'manual-price',checkoutId:'11111111-2222-4333-8444-555555555555',checkoutToken:'PRIVATE_SESSION',expiresAt:'2099-01-01T00:00:00.000Z',providerType:'STRIPE',confirmationMode:'CLIENT_PROVIDER',state:{checkoutId:'11111111-2222-4333-8444-555555555555',paymentId:'22222222-2222-4333-8444-555555555555',amountMinor:2000,currency:'EUR',price:{priceId:'33333333-2222-4333-8444-555555555555',priceKey:'manual-price',version:1,amountMinor:2000,currency:'EUR',taxMode:'NONE'},status:'OPEN',expiresAt:'2099-01-01T00:00:00.000Z'}};
  const handoff={runId:options().runId,expectedSource:options().expectedSource,endpoint:'http://127.0.0.1:18083',assetEndpoint:'http://127.0.0.1:18081',session};
  const runtime={prepare:async l=>{calls.push(['prepare',l]);return handoff;},verifyNoConfirmation:async l=>{calls.push(['proof',l]);return true;},close:async l=>{calls.push(['runtime-close',l]);return true;}};
  const gateway={open:async()=>{calls.push(['open']);return {url:'http://127.0.0.1:18083/form-load-manual.html'};},wait:async()=>{calls.push(['wait']);return result();},close:async l=>{calls.push(['gateway-close',l]);return true;}};
  const dependencies={now:()=>time,setTimeout:(fn,delay)=>{const id={};timers.set(id,{fn,at:time+delay});return id;},clearTimeout:id=>timers.delete(id),
    preflight:async o=>{calls.push(['preflight',o]);return {status:'READY'};},createRuntime:async o=>{calls.push(['runtime',o]);return runtime;},
    createGateway:(o,l)=>{calls.push(['gateway',o,l]);return gateway;},write:async text=>{writes.push(text);}};
  const tick=async at=>{time=at;for(const [id,t] of [...timers])if(t.at<=time){timers.delete(id);t.fn();}for(let i=0;i<150;i++)await Promise.resolve();};
  return {limits,signal,session,handoff,runtime,gateway,dependencies,calls,writes,timers,tick};
}
async function run(f,opts=options()){assert.equal(typeof subject.runManualFormDiagnostic,'function');return subject.runManualFormDiagnostic(opts,f.limits,f.dependencies);}
// Break: missing admission guard touches dependencies/private values or starts a fixture.
test('missing/mixed opt-in and hostile options reject before dependency or private access',async()=>{
  assert.equal(typeof subject.runManualFormDiagnostic,'function');let touched=0;
  const trap=new Proxy({},{get(){touched++;throw null;}});
  for(const patch of [{PAYMENT_STRIPE_SANDBOX_ENABLED:''},{PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:''},{PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:''},
    {PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT:'true'},{PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true'},{PAYMENT_STRIPE_SANDBOX_FULL_RUN:'true'},{PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true'},{PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'},{DEBUG:'PRIVATE'}, {NODE_DEBUG:'http'},{PWDEBUG:'1'},{PW_TRACE:'on'}]){
    const value=await subject.runManualFormDiagnostic({...options(),env:{...flags,...patch}},null,trap);assert.equal(value.exitCode,1);assert.equal(value.report.end,'FAILED');
  }
  const input=options();Object.defineProperty(input,'env',{enumerable:true,get(){touched++;throw null;}});
  assert.equal((await subject.runManualFormDiagnostic(input,null,trap)).exitCode,1);assert.equal(touched,0);
});
// Break: retries, reordered final proof, untrusted URL, renewed deadline or leaked handoff.
test('one preflight prepare open wait close proof runtime-close emits only canonical URL and browser provenance',async()=>{
  const f=fixture(),value=await run(f);assert.equal(value.exitCode,0);
  assert.deepEqual(f.calls.map(x=>x[0]),['preflight','runtime','prepare','gateway','open','wait','gateway-close','proof','runtime-close']);
  assert.deepEqual(f.writes,['http://127.0.0.1:18083/form-load-manual.html\n']);
  assert.equal(value.report.browser_closure,'NOT_OWNED');assert.equal(value.report.provenance,'BROWSER_REPORTED');
  assert.equal(value.report.no_confirmation,true);assert.equal(value.report.gateway_closed,true);assert.equal(value.report.runtime_closed,true);
  assert.equal(JSON.stringify(value).includes('PRIVATE_SESSION'),false);
  for(const name of ['prepare','proof'])assert.deepEqual(f.calls.find(x=>x[0]===name)[1],f.limits);
  for(const name of ['gateway-close','runtime-close'])assert.equal(f.calls.find(x=>x[0]===name)[1].deadline,1021000);
  assert.deepEqual(f.calls.find(x=>x[0]==='gateway').slice(1),[{origin:'http://127.0.0.1:18083',assetEndpoint:f.handoff.assetEndpoint,session:f.session},f.limits]);
  assert.equal(f.timers.size,0);
});
for(const stage of ['prepare','open','wait','write','gateway-close','proof','runtime-close'])
test(stage+' failure stays finite and still attempts exactly one bounded final proof',async()=>{
  const f=fixture(),error=new Proxy({},{get(){assert.fail('raw error inspected');}});
  const fail=async()=>{f.calls.push([stage]);throw error;};
  if(stage==='write')f.dependencies.write=fail;
  else if(stage==='prepare')f.runtime.prepare=fail;
  else if(stage==='proof')f.runtime.verifyNoConfirmation=fail;
  else if(stage==='runtime-close')f.runtime.close=fail;
  else if(stage==='gateway-close')f.gateway.close=fail;
  else f.gateway[stage]=fail;
  const value=await run(f);assert.equal(value.exitCode,1);assert.equal(value.report.end,'FAILED');
  assert.equal(f.calls.filter(x=>x[0]==='proof').length,1);assert.equal(f.calls.filter(x=>x[0]==='runtime-close').length,1);
  assert.ok(f.calls.findIndex(x=>x[0]==='proof')<f.calls.findIndex(x=>x[0]==='runtime-close'));
});
test('preflight failure never creates runtime; unknown ownership never invokes a stop',async()=>{
  const f=fixture();f.dependencies.preflight=async()=>({status:'PENDING',private:'PRIVATE'});
  const value=await run(f);assert.equal(value.exitCode,1);assert.deepEqual(f.calls,[]);assert.equal(value.report.runtime_closed,false);
});
test('prepare failure proves gateway was never acquired while preserving the single financial read',async()=>{
  const f=fixture();f.runtime.prepare=async()=>{throw null;};const value=await run(f);
  assert.equal(value.report.gateway_closed,true);assert.equal(value.report.runtime_closed,true);assert.equal(value.report.no_confirmation,true);
});
test('abandoned page zero-attempt proof cannot manufacture form readiness',async()=>{
  const f=fixture();f.gateway.wait=async()=>result({observation:'NOT_STARTED',end:'NOT_STARTED',sample:null});
  const value=await run(f);assert.equal(value.exitCode,1);assert.equal(value.report.end,'NOT_STARTED');assert.equal(value.report.no_confirmation,true);
});
test('historical ready with FAILED end and each negative owned proof stays failed',async()=>{
  for(const fail of ['end','proof','gateway-close','runtime-close']){
    const f=fixture();if(fail==='end')f.gateway.wait=async()=>result({end:'FAILED'});
    if(fail==='proof')f.runtime.verifyNoConfirmation=async()=>false;
    if(fail==='gateway-close')f.gateway.close=async()=>false;
    if(fail==='runtime-close')f.runtime.close=async()=>false;
    const value=await run(f);assert.equal(value.exitCode,1);assert.equal(value.report.end,'FAILED');assert.equal(value.report.observation,'READY_REPORTED');assert.deepEqual(value.report.sample,ready);assert.deepEqual(value.report.evidence,evidence);
  }
});
test('malformed, accessor, null and AT_LIMIT gateway samples fail closed',async()=>{
  let reads=0;const getter=result();Object.defineProperty(getter,'sample',{enumerable:true,get(){reads++;throw null;}});
  for(const payload of [getter,result({extra:true}),result({sample:null}),result({sample:{...ready,bucket:'AT_LIMIT'}}),result({browser_closed:true})]){
    const f=fixture();f.gateway.wait=async()=>payload;const value=await run(f);assert.equal(value.exitCode,1);assert.equal(value.report.observation,'INVALID_REPORT');assert.equal(value.report.sample,null);
  }assert.equal(reads,0);
});
test('wrong handoff binding or gateway URL never prints private/caller-controlled output',async()=>{
  for(const field of ['endpoint','assetEndpoint','runId','expectedSource','url']){
    const f=fixture();if(field==='url')f.gateway.open=async()=>({url:'http://127.0.0.1:18083/?PRIVATE_SESSION'});else f.handoff[field]='PRIVATE_SESSION';
    const value=await run(f);assert.equal(value.exitCode,1);assert.deepEqual(f.writes,[]);assert.equal(f.calls.filter(x=>x[0]==='proof').length,1);
  }
});
test('expired admission and renewed or oversized limits cannot start dependencies',async()=>{
  for(const deadline of [1000,1001,121000,901001,NaN]){
    const f=fixture();f.limits.deadline=deadline;const value=await run(f);assert.equal(value.exitCode,1);assert.deepEqual(f.calls,[]);
  }
});
// Break: resolving/loading a factory at the admission cutoff starts work after authority expired.
test('factory lookup crossing the admission cutoff never invokes that expired factory',async()=>{
  for(const key of ['createRuntime','createGateway']){
    const f=fixture(),factory=f.dependencies[key];let invoked=0;
    Object.defineProperty(f.dependencies,key,{get(){void f.tick(781000);return (...args)=>{invoked++;return factory(...args);};}});
    const value=await run(f);assert.equal(value.exitCode,1);assert.equal(invoked,0,key);assert.deepEqual(f.writes,[]);
  }
});
test('late preparation cannot start a gateway and must settle before owned runtime closure',async()=>{
  const f=fixture(),pending=defer();f.runtime.prepare=()=>{f.calls.push(['prepare']);return pending.promise;};
  const promise=run(f);await f.tick(1000);await f.tick(781000);assert.equal(f.calls.some(x=>x[0]==='runtime-close'),false);
  pending.resolve(f.handoff);await f.tick(781001);const value=await promise;
  assert.equal(value.exitCode,1);assert.equal(f.calls.some(x=>x[0]==='gateway'),false);assert.equal(f.calls.filter(x=>x[0]==='proof').length,1);
});
test('late preparation settlement still gets one financial read while original active authority remains',async()=>{
  const f=fixture(),pending=defer();f.runtime.prepare=()=>{f.calls.push(['prepare']);return pending.promise;};
  const promise=run(f);await f.tick(1000);await f.tick(781000);await f.tick(871000);
  pending.resolve(f.handoff);await f.tick(880000);const value=await promise;
  assert.equal(value.exitCode,1);assert.equal(f.calls.filter(x=>x[0]==='proof').length,1);assert.equal(f.calls.some(x=>x[0]==='gateway'),false);
});
test('late runtime acquisition is owned and closed without opening or preparing a new fixture',async()=>{
  const f=fixture(),pending=defer();f.dependencies.createRuntime=()=>{f.calls.push(['runtime']);return pending.promise;};
  const promise=run(f);await f.tick(1000);await f.tick(781000);pending.resolve(f.runtime);await f.tick(781001);const value=await promise;
  assert.equal(value.exitCode,1);assert.equal(f.calls.some(x=>x[0]==='prepare'),false);assert.equal(f.calls.filter(x=>x[0]==='runtime-close').length,1);
});
test('unsettled runtime acquisition cannot claim closure at original cleanup ceiling',async()=>{
  const f=fixture(),pending=defer();f.dependencies.createRuntime=()=>pending.promise;
  const promise=run(f);await f.tick(1000);await f.tick(781000);await f.tick(1021000);const value=await promise;
  assert.equal(value.exitCode,1);assert.equal(value.report.runtime_closed,false);
  pending.resolve(f.runtime);await f.tick(1021001);assert.equal(f.calls.filter(x=>x[0]==='runtime-close').length,1);
});
// Break: a gateway acquired after the admission race escapes cleanup or falsely proves closure.
test('late gateway factory stays owned, closes once, and cannot print or start after admission',async()=>{
  const f=fixture(),pending=defer();f.dependencies.createGateway=()=>{f.calls.push(['gateway']);return pending.promise;};
  const promise=run(f);await f.tick(1000);await f.tick(781000);
  pending.resolve(f.gateway);await f.tick(781001);const value=await promise;
  assert.equal(value.exitCode,1);assert.equal(f.calls.filter(x=>x[0]==='gateway-close').length,1);assert.equal(f.calls.some(x=>x[0]==='open'),false);assert.deepEqual(f.writes,[]);
});
test('gateway acquisition unresolved at cleanup ceiling never claims closure and still closes on late settlement',async()=>{
  const f=fixture(),pending=defer();f.dependencies.createGateway=()=>pending.promise;
  const promise=run(f);await f.tick(1000);await f.tick(781000);await f.tick(1021000);const value=await promise;
  assert.equal(value.exitCode,1);assert.equal(value.report.gateway_closed,false);
  pending.resolve(f.gateway);await f.tick(1021001);assert.equal(f.calls.filter(x=>x[0]==='gateway-close').length,1);assert.equal(value.report.gateway_closed,false);
});
test('pending runtime close is not positive closure and late completion cannot mutate the returned report',async()=>{
  const f=fixture(),pending=defer();f.runtime.close=l=>{f.calls.push(['runtime-close',l]);return pending.promise;};
  const promise=run(f);await f.tick(1000);await f.tick(1021000);const value=await promise;
  assert.equal(value.exitCode,1);assert.equal(value.report.runtime_closed,false);assert.equal(f.calls.filter(x=>x[0]==='proof').length,1);
  pending.resolve(true);await f.tick(1021001);assert.equal(value.report.runtime_closed,false);assert.equal(f.calls.filter(x=>x[0]==='runtime-close').length,1);
});
test('observation cutoff reserves financial read time and no late sample upgrades the result',async()=>{
  const f=fixture(),pending=defer();f.gateway.wait=()=>pending.promise;
  const promise=run(f);await f.tick(1000);await f.tick(841000);await f.tick(841001);
  pending.resolve(result());await f.tick(841002);const value=await promise;
  assert.equal(value.exitCode,1);assert.equal(value.report.end,'FAILED');assert.equal(value.report.sample,null);assert.equal(f.calls.filter(x=>x[0]==='proof').length,1);
});
test('in-flight close settles before positive gateway proof and never blocks the single reserved financial read',async()=>{
  const f=fixture(),pending=defer();f.gateway.close=l=>{f.calls.push(['gateway-close',l]);return pending.promise;};
  const promise=run(f);await f.tick(1000);await f.tick(31000);assert.equal(f.calls.filter(x=>x[0]==='proof').length,1);
  pending.resolve(true);await f.tick(31001);const value=await promise;assert.equal(value.exitCode,1);assert.equal(value.report.end,'FAILED');assert.equal(value.report.gateway_closed,true);
});
test('abort or expired final authority cannot renew proof; cleanup retains original ceiling',async()=>{
  for(const abort of [true,false]){
    const f=fixture();f.gateway.wait=async()=>{if(abort)f.signal.abort();else await f.tick(901000);return result();};
    const value=await run(f);assert.equal(value.exitCode,1);assert.equal(value.report.end,abort?'ABORTED':'ACTIVE_DEADLINE');
    assert.equal(f.calls.some(x=>x[0]==='proof'),false);assert.equal(f.calls.find(x=>x[0]==='runtime-close')[1].deadline,1021000);
  }
});
test('import is inert under sanitized child env',()=>{
  const childEnv={PATH:'/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',NO_COLOR:'1'};
  const imported=spawnSync(process.execPath,['--input-type=module','-e',"await import('./infrastructure/stripe-manual-form.mjs')"],{cwd:new URL('..',import.meta.url),env:childEnv,encoding:'utf8',timeout:5000,maxBuffer:8192});
  assert.equal(imported.status,0);assert.equal(imported.stdout,'');assert.equal(imported.stderr,'');
});
test('CLI snapshots only allowlisted raw env and prints URL plus strict final report',async()=>{
  assert.equal(typeof subject.runManualFormCli,'function');const f=fixture();let touched=0;
  const raw=Object.assign(Object.create({rawEnvironment:true}),flags,{PAYMENT_STRIPE_SANDBOX_RUN_ID:options().runId,PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE:options().expectedSource,
    PAYMENT_STRIPE_SANDBOX_DAEMON:options().daemon,PAYMENT_STRIPE_SANDBOX_PORT_MANUAL:'18083'});
  for(const [key,value] of Object.entries(options().ports))raw['PAYMENT_STRIPE_SANDBOX_PORT_'+key.toUpperCase()]=String(value);
  Object.defineProperty(raw,'STRIPE_SECRET_KEY',{get(){touched++;throw null;}});
  const value=await subject.runManualFormCli({env:raw,args:[]},f.dependencies);assert.equal(value.exitCode,0);assert.equal(touched,0);
  assert.equal(f.writes.length,2);assert.equal(f.writes[0],'http://127.0.0.1:18083/form-load-manual.html\n');assert.equal(JSON.parse(f.writes[1]).provenance,'BROWSER_REPORTED');
  assert.equal(f.writes.join('').includes('PRIVATE_SESSION'),false);
});
// Break: a final output error replaces original abort/deadline provenance, or retains a successful/local result.
for(const originalEnd of ['ABORTED','ACTIVE_DEADLINE','READY_REPORTED','LOCAL_LIMIT'])
test('CLI final write failure preserves only dominant end '+originalEnd,async()=>{
  const f=fixture(),raw={...flags,PAYMENT_STRIPE_SANDBOX_RUN_ID:options().runId,PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE:options().expectedSource,
    PAYMENT_STRIPE_SANDBOX_DAEMON:options().daemon,PAYMENT_STRIPE_SANDBOX_PORT_MANUAL:'18083'};
  for(const [key,value] of Object.entries(options().ports))raw['PAYMENT_STRIPE_SANDBOX_PORT_'+key.toUpperCase()]=String(value);
  let stop,finalWrites=0,reportedEnd;
  f.dependencies.subscribe=callback=>{stop=callback;return ()=>{};};
  if(['ABORTED','ACTIVE_DEADLINE'].includes(originalEnd))f.dependencies.preflight=async()=>{
    if(originalEnd==='ABORTED')stop();else await f.tick(901000);
    return {status:'READY'};
  };
  if(originalEnd==='LOCAL_LIMIT')f.gateway.wait=async()=>result({observation:'NO_REPORT',end:'LOCAL_LIMIT',sample:null});
  f.dependencies.write=async text=>{
    f.writes.push(text);if(text.startsWith('http://'))return;
    finalWrites++;reportedEnd=JSON.parse(text).end;
    throw new Proxy({},{get(){assert.fail('raw output failure inspected');}});
  };
  const value=await subject.runManualFormCli({env:raw,args:[]},f.dependencies);
  assert.equal(finalWrites,1);assert.equal(reportedEnd,originalEnd);assert.equal(value.exitCode,1);
  assert.equal(value.report.end,['ABORTED','ACTIVE_DEADLINE'].includes(originalEnd)?originalEnd:'FAILED');
  assert.equal(value.report.browser_closure,'NOT_OWNED');assert.equal(value.report.provenance,'BROWSER_REPORTED');
  assert.equal(f.writes.join('').includes('PRIVATE_SESSION'),false);
});

function cliInput(admitted=true){
  const env={...flags,PAYMENT_STRIPE_SANDBOX_RUN_ID:options().runId,PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE:options().expectedSource,
    PAYMENT_STRIPE_SANDBOX_DAEMON:options().daemon,PAYMENT_STRIPE_SANDBOX_PORT_MANUAL:'18083'};
  for(const [key,value] of Object.entries(options().ports))env['PAYMENT_STRIPE_SANDBOX_PORT_'+key.toUpperCase()]=String(value);
  return {env:admitted?env:{},args:[]};
}
// Break: final output outlives the original ceiling/abort, retains subscriptions,
// retries on late rejection, or upgrades an already returned failure.
for(const admitted of [true,false])for(const boundary of ['deadline','abort'])for(const late of ['resolve','reject'])
test(`CLI pending final writer releases ${admitted?'admitted':'rejected'} invocation on ${boundary}, ignores late ${late}`,async()=>{
  const f=fixture(),pending=defer();let stop,subscriptions=0,finalWrites=0,settled=false,value;
  f.dependencies.subscribe=callback=>{stop=callback;subscriptions++;return ()=>{subscriptions--;};};
  f.dependencies.write=text=>{f.writes.push(text);if(text.startsWith('http://'))return;finalWrites++;return pending.promise;};
  const promise=subject.runManualFormCli(cliInput(admitted),f.dependencies).then(result=>{settled=true;value=result;return result;});
  await f.tick(1000);
  assert.equal(finalWrites,1);assert.equal(settled,false);
  if(boundary==='abort'){
    assert.equal(typeof stop,'function','final emission must remain interruptible after rejected admission too');
    stop();await f.tick(1001);
  }else{
    await f.tick(admitted?1020999:1999);assert.equal(settled,false,'no renewed or shortened original budget');
    await f.tick(admitted?1021000:2000);
  }
  assert.equal(settled,true,'pending output must release CLI without writer settlement');
  await promise;
  assert.equal(subscriptions,0);assert.equal(f.timers.size,0);assert.equal(value.exitCode,1);
  assert.equal(value.report.end,boundary==='abort'?'ABORTED':admitted?'ACTIVE_DEADLINE':'FAILED');
  assert.equal(value.report.observation,admitted?'READY_REPORTED':'NOT_STARTED');
  assert.equal(value.report.no_confirmation,admitted);assert.equal(value.report.gateway_closed,admitted);assert.equal(value.report.runtime_closed,admitted);
  assert.equal(value.report.browser_closure,'NOT_OWNED');assert.equal(value.report.provenance,'BROWSER_REPORTED');
  const frozen=JSON.stringify(value),calls=f.calls.map(x=>x[0]);
  assert.deepEqual(calls,admitted?['preflight','runtime','prepare','gateway','open','wait','gateway-close','proof','runtime-close']:[]);
  pending[late](late==='reject'?new Error('private output failure'):undefined);await f.tick(1021001);
  assert.equal(finalWrites,1);assert.equal(JSON.stringify(value),frozen);assert.deepEqual(f.calls.map(x=>x[0]),calls);
  assert.equal(f.writes.join('').includes('PRIVATE_SESSION'),false);
});

// Break: final emission starts a new budget after slow cleanup, or erases an
// already dominant reason when a later output timeout/abort wins.
for(const originalEnd of ['ABORTED','ACTIVE_DEADLINE'])
test('CLI pending final writer preserves original '+originalEnd+' through output cutoff',async()=>{
  const f=fixture(),pending=defer();let stop,subscriptions=0,finalWrites=0,settled=false,value;
  f.dependencies.subscribe=callback=>{stop=callback;subscriptions++;return ()=>{subscriptions--;};};
  f.dependencies.preflight=async()=>{if(originalEnd==='ABORTED')stop();else await f.tick(901000);return {status:'READY'};};
  f.dependencies.write=()=>{finalWrites++;return pending.promise;};
  const promise=subject.runManualFormCli(cliInput(),f.dependencies).then(result=>{settled=true;value=result;});
  await f.tick(1000);await f.tick(901000);await f.tick(1021000);
  assert.equal(settled,true,'dominant failure cannot wait indefinitely for final output');await promise;
  assert.equal(value.report.end,originalEnd);assert.equal(value.exitCode,1);assert.equal(finalWrites,1);assert.equal(subscriptions,0);assert.equal(f.timers.size,0);
  pending.reject(new Error('late failure'));await f.tick(1021001);assert.equal(value.report.end,originalEnd);assert.equal(finalWrites,1);
});

test('CLI final writer completing after active deadline fails even before its original cleanup ceiling',async()=>{
  const f=fixture(),pending=defer();f.dependencies.subscribe=()=>()=>{};
  f.dependencies.write=text=>text.startsWith('http://')?undefined:pending.promise;
  const promise=subject.runManualFormCli(cliInput(),f.dependencies);await f.tick(1000);await f.tick(901000);pending.resolve();await f.tick(901001);
  const value=await promise;assert.equal(value.exitCode,1);assert.equal(value.report.end,'ACTIVE_DEADLINE');assert.equal(f.timers.size,0);
});

test('CLI slow runtime cleanup leaves only the remainder of the original final output budget',async()=>{
  const f=fixture(),closing=defer(),writing=defer();let settled=false,finalWrites=0,value;
  f.dependencies.subscribe=()=>()=>{};f.runtime.close=()=>closing.promise;
  f.dependencies.write=text=>{if(text.startsWith('http://'))return;finalWrites++;return writing.promise;};
  const promise=subject.runManualFormCli(cliInput(),f.dependencies).then(result=>{value=result;settled=true;});
  await f.tick(1000);await f.tick(1020990);closing.resolve(true);await f.tick(1020990);
  assert.equal(finalWrites,1);assert.equal(settled,false);await f.tick(1021000);
  assert.equal(settled,true,'final output cannot renew budget after runtime cleanup');await promise;
  assert.equal(value.exitCode,1);assert.equal(value.report.end,'ACTIVE_DEADLINE');assert.equal(value.report.runtime_closed,true);assert.equal(f.timers.size,0);
  writing.resolve();await f.tick(1021001);assert.equal(finalWrites,1);
});

// Break: the direct entry only sets exitCode while an unsettled own stdout write
// holds it alive, or removes foreign/error listeners as if a race proved flush.
for(const admitted of [true,false])for(const finish of ['success','error','abort'])
test(`direct entry releases ${admitted?'admitted':'rejected'} pending stdout with late ${finish}`,async()=>{
  assert.equal(typeof subject.runManualFormEntry,'function');
  const f=fixture(),stream=new EventEmitter(),exits=[];let callback,stop,settled=false;
  const foreign=()=>{};stream.on('error',foreign);
  stream.write=(text,done)=>{f.writes.push(text);if(text.startsWith('http://'))done();else callback=done;};
  f.dependencies.subscribe=cb=>{stop=cb;return ()=>{stop=null;};};
  const promise=subject.runManualFormEntry(cliInput(admitted),{stdout:stream,exit:code=>exits.push(code)},f.dependencies).then(()=>{settled=true;});
  await f.tick(1000);assert.equal(typeof callback,'function');assert.equal(settled,false);assert.deepEqual(exits,[]);
  if(finish==='abort'){stop();await f.tick(1001);}else await f.tick(admitted?1021000:2000);
  assert.equal(settled,true);await promise;assert.deepEqual(exits,[1]);assert.equal(stop,null);
  assert.equal(stream.listenerCount('error'),2,'pending write still owns its late error observer');
  if(finish==='error'){callback(new Error('private'));stream.emit('error',new Error('private'));}
  else callback();
  await f.tick(1021001);assert.deepEqual(exits,[1]);assert.deepEqual(stream.listeners('error'),[foreign]);assert.equal(f.timers.size,0);
  assert.equal(f.writes.length,admitted?2:1);assert.equal(f.calls.filter(x=>x[0]==='proof').length,admitted?1:0);
});
test('direct entry waits for actual final stdout callback before successful exit',async()=>{
  assert.equal(typeof subject.runManualFormEntry,'function');
  const f=fixture(),stream=new EventEmitter(),exits=[];let callback;
  stream.write=(text,done)=>{if(text.startsWith('http://'))done();else callback=done;};
  f.dependencies.subscribe=()=>()=>{};
  const promise=subject.runManualFormEntry(cliInput(),{stdout:stream,exit:code=>exits.push(code)},f.dependencies);
  await f.tick(1000);assert.deepEqual(exits,[]);callback();await f.tick(1001);await promise;
  assert.deepEqual(exits,[0]);assert.equal(stream.listenerCount('error'),0);assert.equal(f.timers.size,0);
});
test('direct missing opt-in emits only finite report through synthetic stdout and exit',async()=>{
  assert.equal(typeof subject.runManualFormEntry,'function');
  const f=fixture(),stream=new EventEmitter(),exits=[];
  stream.write=(text,done)=>{f.writes.push(text);done();};f.dependencies.subscribe=()=>()=>{};
  await subject.runManualFormEntry(cliInput(false),{stdout:stream,exit:code=>exits.push(code)},f.dependencies);
  assert.deepEqual(exits,[1]);assert.deepEqual(f.calls,[]);assert.equal(f.writes.length,1);
  const value=JSON.parse(f.writes[0]);assert.equal(value.version,2);assert.equal(value.end,'FAILED');assert.equal(value.browser_closure,'NOT_OWNED');
});
test('direct rejected admission does not read lifecycle capabilities while wiring stdout',async()=>{
  const f=fixture(),stream=new EventEmitter(),exits=[];let touched=0;
  for(const key of ['preflight','createRuntime','createGateway'])Object.defineProperty(f.dependencies,key,{enumerable:true,get(){touched++;throw null;}});
  stream.write=(text,done)=>{f.writes.push(text);done();};f.dependencies.subscribe=()=>()=>{};
  await subject.runManualFormEntry(cliInput(false),{stdout:stream,exit:code=>exits.push(code)},f.dependencies);
  assert.equal(touched,0);assert.deepEqual(exits,[1]);assert.equal(f.writes.length,1);
});
// Break: output cutoff replaces a valid terminal/invalid observation with EMPTY
// because those pairs require FAILED even when a later abort/deadline occurs.
for(const observation of ['INVALID_REPORT','TERMINAL_REPORTED'])for(const boundary of ['abort','deadline'])
test(`CLI output ${boundary} retains ${observation} failure and established owned proofs`,async()=>{
  const f=fixture(),pending=defer();let stop,settled=false,value;
  f.dependencies.subscribe=callback=>{stop=callback;return ()=>{};};
  f.gateway.wait=async()=>observation==='INVALID_REPORT'?null:result({observation,end:'FAILED',sample:{...ready,confirmation_blocked:true}});
  f.dependencies.write=text=>text.startsWith('http://')?undefined:pending.promise;
  const promise=subject.runManualFormCli(cliInput(),f.dependencies).then(result=>{value=result;settled=true;});
  await f.tick(1000);if(boundary==='abort')stop();await f.tick(boundary==='abort'?1001:1021000);
  assert.equal(settled,true);await promise;assert.equal(value.exitCode,1);assert.equal(value.report.end,'FAILED');
  assert.equal(value.report.observation,observation);assert.equal(value.report.no_confirmation,true);assert.equal(value.report.gateway_closed,true);assert.equal(value.report.runtime_closed,true);
  if(observation==='TERMINAL_REPORTED')assert.equal(value.report.sample.confirmation_blocked,true);
  pending.resolve();await f.tick(1021001);assert.equal(value.report.observation,observation);assert.equal(f.timers.size,0);
});
