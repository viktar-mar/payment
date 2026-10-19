import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
const subject=await import('./stripe-manual-form.mjs').catch(error=>{if(error.code==='ERR_MODULE_NOT_FOUND')return {};throw error;});
const flags={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'};
const options=()=>({env:{...flags},runId:'11111111-2222-4333-8444-555555555555',expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',manualPort:18083,
  ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025}});
const ready={version:1,binding:'BOUND',capability_started:true,capability_ready:true,loader_started:true,loader_ready:true,
  element_created:true,mount_called:true,mount_returned:true,ready_seen:true,loaderror_seen:false,view_completed:true,widget_completed:true,
  confirmation_blocked:false,catchup:'NOT_STARTED',operation:'COMPLETED',widget_state:'ready',element_state:'FRAME',pay_state:'ENABLED',pay_visible:true,bucket:'BEFORE_15S'};
const result=patch=>({observation:'READY_REPORTED',end:'READY_REPORTED',sample:ready,...patch});
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
    {PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true'},{PAYMENT_STRIPE_SANDBOX_FULL_RUN:'true'},{PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true'},{PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'},{DEBUG:'PRIVATE'}, {NODE_DEBUG:'http'},{PWDEBUG:'1'},{PW_TRACE:'on'}]){
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
    const value=await run(f);assert.equal(value.exitCode,1);assert.equal(value.report.end,'FAILED');assert.equal(value.report.observation,'READY_REPORTED');assert.deepEqual(value.report.sample,ready);
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
test('import is inert and direct missing opt-in emits only finite report under sanitized child env',()=>{
  const childEnv={PATH:'/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin',LANG:'C',LC_ALL:'C',NO_COLOR:'1'};
  const imported=spawnSync(process.execPath,['--input-type=module','-e',"await import('./infrastructure/stripe-manual-form.mjs')"],{cwd:new URL('..',import.meta.url),env:childEnv,encoding:'utf8',timeout:5000,maxBuffer:8192});
  assert.equal(imported.status,0);assert.equal(imported.stdout,'');assert.equal(imported.stderr,'');
  const direct=spawnSync(process.execPath,['infrastructure/stripe-manual-form.mjs'],{cwd:new URL('..',import.meta.url),env:childEnv,encoding:'utf8',timeout:5000,maxBuffer:8192});
  assert.equal(direct.status,1);assert.equal(direct.stderr,'');const value=JSON.parse(direct.stdout);assert.equal(value.version,1);assert.equal(value.end,'FAILED');assert.equal(value.browser_closure,'NOT_OWNED');
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
