import test from 'node:test';
import assert from 'node:assert/strict';
import type {BrowserType} from '@playwright/test';
import type {FormDiagnosticHandoff} from '../../../../infrastructure/stripe-form-diagnostic-runtime.mjs';
import {createContext,runInContext} from 'node:vm';
import {setImmediate as immediate} from 'node:timers/promises';
// @ts-expect-error Native Node source type erasure.
import {runFormLoadBrowser} from './form-load-diagnostic.ts';
import {runFormLoadDiagnostic} from '../../../../infrastructure/stripe-form-diagnostic.mjs';
import {classifyFormDiagnosticSample} from '../../../../infrastructure/stripe-form-diagnostic-schema.mjs';

const pending={version:1,binding:'BOUND',capability_started:true,capability_ready:true,loader_started:true,loader_ready:true,
  element_created:true,mount_called:true,mount_returned:true,ready_seen:false,loaderror_seen:false,view_completed:false,
  widget_completed:false,confirmation_blocked:false,catchup:'NOT_STARTED',operation:'PENDING',
  widget_state:'loading',element_state:'FRAME',pay_state:'ABSENT'};
const ready={...pending,ready_seen:true,view_completed:true,widget_completed:true,operation:'COMPLETED',widget_state:'ready',pay_state:'ENABLED'};
const handoff:FormDiagnosticHandoff={runId:'00000000-0000-4000-8000-000000000001',expectedSource:'a'.repeat(40),endpoint:'http://127.0.0.1:14173',
  session:{version:2,apiBaseUrl:'http://127.0.0.1:18080',siteKey:'site',priceKey:'price',checkoutId:'00000000-0000-4000-8000-000000000002',checkoutToken:'a'.repeat(43),
    expiresAt:'2099-01-01T00:00:00.000Z',providerType:'STRIPE',confirmationMode:'CLIENT_PROVIDER',state:{checkoutId:'00000000-0000-4000-8000-000000000002',paymentId:'00000000-0000-4000-8000-000000000003',status:'REQUIRES_PAYMENT_METHOD',amountMinor:2000,currency:'EUR',price:null,expiresAt:'2099-01-01T00:00:00.000Z'}}};
const composite=(sample:unknown,includeForm=true)=>({handshake:true,sample:includeForm?sample:null,csp:{csp_observation:'OBSERVED',csp_enforced_mask:0,csp_report_only_mask:0}});
function fake(sample=ready) {
  const events:string[]=[],writes:unknown[]=[];
  const locator={count:async()=>sample.pay_state==='ENABLED'?1:0,isEnabled:async()=>sample.pay_state==='ENABLED',isVisible:async()=>sample.pay_state==='ENABLED',
    click:async()=>{events.push('resume');}};
  const handlers=new Map<string,Function>(),retained=new Map<string,Function>();
  const cspListeners:Function[]=[];
  const realm=createContext({Date,location:{origin:handoff.endpoint},document:{addEventListener:(_name:string,fn:Function)=>cspListeners.push(fn)},sessionStorage:{setItem:()=>{}},syntheticSample:sample});
  // Preserve mutable host fixtures, but return same-realm records with their original descriptors intact.
  runInContext(`globalThis.top=globalThis;globalThis.__formLoadDiagnostic={handshake(){return true},
    snapshot(){return Object.defineProperties({},Object.getOwnPropertyDescriptors(syntheticSample))}}`,realm);
  const page={on:(name:string,fn:Function)=>{handlers.set(name,fn);retained.set(name,fn);return page;},off:(name:string)=>{handlers.delete(name);return page;},goto:async()=>{events.push('goto');},evaluate:async(fn:Function,arg?:unknown)=>structuredClone(runInContext('('+fn.toString()+')',realm)(arg)),
    locator:(selector:string)=>{assert.equal(selector,'generic-payment-widget');return {count:async()=>1,
      getByRole:(_role:string,query:{name:string;exact:boolean})=>{assert.deepEqual(query,{name:'Resume existing payment',exact:true});return locator;},
      locator:(selector:string)=>{assert.equal(selector,'button[aria-label="Pay"]:enabled');return locator;}};}};
  const context={addInitScript:async(_fn:Function,value:unknown)=>{writes.push(value);events.push('init');runInContext('('+_fn.toString()+')',realm)(value);},newPage:async()=>page,isClosed:()=>false,close:async()=>{events.push('context-close');}};
  const browser={newContext:async(options:unknown)=>{assert.deepEqual(options,{serviceWorkers:'block',acceptDownloads:false});events.push('context');return context;},close:async()=>{events.push('browser-close');}};
  const type={launch:async(options:unknown)=>{assert.equal((options as {headless:boolean}).headless,true);events.push('launch');return browser;}} as unknown as Pick<BrowserType,'launch'>;
  return {type,browser,context,page,locator,events,writes,realm,handlers,retained,cspListeners};
}
const limits=()=>({deadline:Date.now()+900000,signal:new AbortController().signal});
// Break caught: malformed/noncanonical origins reach launch or become opaque browser errors.
test('both distinct owned origins are validated before any browser launch',async()=>{
  for(const origin of ['malformed','http://127.0.0.1:1023','http://127.0.0.1:65536','http://127.0.0.1:18080/',
    'http://127.0.0.1:18080?private=sentinel','http://user@127.0.0.1:18080','https://127.0.0.1:18080','http://localhost:18080',handoff.endpoint]){
    const f=fake(),input={...handoff,session:{...handoff.session,apiBaseUrl:origin}};
    const result=await runFormLoadBrowser(f.type,input,limits());
    assert.deepEqual(f.events,[],origin);assert.equal(result.observationEnd,'INSTRUMENTATION_UNAVAILABLE',origin);
    assert.equal(result.outcome,'INSTRUMENTATION_UNAVAILABLE');
  }
});
// Break caught: local limit and opaque browser rejection collapse to the same provenance.
test('actual driver distinguishes local limit, read rejection, abort, deadline and bootstrap absence',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  for(const ending of ['local','read','abort','deadline','bootstrap'] as const){
    const f=fake({...pending} as typeof ready),controller=new AbortController();
    if(ending==='read')f.page.evaluate=async()=>{throw new Proxy({},{get(){assert.fail('external error inspected');}});};
    if(ending==='bootstrap')runInContext('delete __formLoadDiagnostic',f.realm);
    const original={deadline:Date.now()+900000,signal:controller.signal};
    const run=runFormLoadBrowser(f.type,handoff,original);await immediate();
    if(ending==='local')t.mock.timers.tick(60000);
    if(ending==='abort')controller.abort();
    if(ending==='deadline')t.mock.timers.tick(900000);
    const result=await run;
    assert.equal(result.observationEnd,({local:'LOCAL_LIMIT',read:'BROWSER_OPERATION_FAILED',abort:'ABORTED',deadline:'ACTIVE_DEADLINE',bootstrap:'INSTRUMENTATION_UNAVAILABLE'} as const)[ending]);
    assert.equal(result.outcome,ending==='abort'?'ABORTED':ending==='deadline'?'DEADLINE_REACHED':ending==='bootstrap'?'INSTRUMENTATION_UNAVAILABLE':'FAILED');
  }
});
// Only external resource operations are synthetic; the driver, parent and schema run.
function throughParent(f:ReturnType<typeof fake>,original=limits()) {
  return runFormLoadDiagnostic({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'},
    runId:handoff.runId,expectedSource:handoff.expectedSource,daemon:'synthetic-daemon',
    ports:{backend:18080,demo:14173,receiver:14174,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025}},original,{
    createRuntime:async()=>({prepare:async()=>handoff,verifyNoConfirmation:async()=>true,
      close:async(cleanup:{deadline:number})=>{assert.equal(cleanup.deadline,original.deadline+120000);f.events.push('runtime-close');return true;}}),
    runBrowser:(h:FormDiagnosticHandoff,l:ReturnType<typeof limits>)=>runFormLoadBrowser(f.type,h,l),
  });
}
// Break: unavailable SDK bootstrap hides CSP evidence and starts a client session.
for(const sdk of ['missing','throwing','accessor'] as const)test(`${sdk} SDK preserves enforced CSP script evidence in opaque wire3 without Resume`,async()=>{
  const f=fake();let reads=0;
  if(sdk==='missing')runInContext('delete globalThis.__formLoadDiagnostic',f.realm);
  else if(sdk==='throwing')f.realm.__formLoadDiagnostic={handshake(){throw Symbol();}};
  else Object.defineProperty(f.realm,'__formLoadDiagnostic',{get(){reads++;throw Symbol();}});
  const goto=f.page.goto;
  f.page.goto=async()=>{await goto();assert.equal(f.cspListeners.length,1);f.cspListeners[0]!({isTrusted:true,effectiveDirective:'script-src-elem',disposition:'enforce'});};
  const result=await throughParent(f),frame=result.frames[0]!;
  assert.equal(result.exitCode,1);assert.equal(frame.version,3);assert.equal(frame.result,'INSTRUMENTATION_UNAVAILABLE');
  assert.equal(frame.csp_observation,'OBSERVED');assert.equal(frame.csp_enforced_mask,1);
  assert.equal(frame.environment_freshness,'CACHED');assert.equal(Object.hasOwn(frame,'binding'),false);
  assert.equal(f.events.includes('resume'),false);assert.equal(reads,0);
});
// Break: one failed collector hides another source or partial instrumentation is accepted.
test('CSP failure preserves distinct transport document, HTTP404 script and HTTP503 fetch masks without Resume',async()=>{
  const f=fake(),goto=f.page.goto;
  f.page.goto=async()=>{await goto();f.cspListeners[0]!({isTrusted:true,effectiveDirective:'script-src',disposition:'invalid'});
    f.handlers.get('requestfailed')!({resourceType:()=> 'document'});
    for(const [status,type] of [[404,'script'],[503,'fetch']] as const)f.handlers.get('response')!({status:()=>status,request:()=>({resourceType:()=>type})});
  };
  const result=await throughParent(f),frame=result.frames[0]!;
  assert.equal(frame.result,'INSTRUMENTATION_UNAVAILABLE');assert.equal(frame.csp_observation,'UNAVAILABLE');
  assert.equal(frame.resource_observation,'OBSERVED');assert.equal(frame.resource_transport_failure_mask,2);
  assert.equal(frame.resource_http_4xx_mask,1);assert.equal(frame.resource_http_5xx_mask,4);
  assert.equal(result.exitCode,1);assert.equal(f.events.includes('resume'),false);
});
test('independent masks do not cancel actual sample1 readiness and all closure proofs',async()=>{
  const f=fake(),goto=f.page.goto;f.page.goto=async()=>{await goto();f.handlers.get('response')!({status:()=>404,request:()=>({resourceType:()=> 'script'})});};
  const result=await throughParent(f),frame=result.frames[0]!;
  assert.equal(result.exitCode,0);assert.equal(frame.version,3);assert.equal(frame.resource_http_4xx_mask,1);
  assert.equal(frame.environment_freshness,'SAMPLED');assert.equal(frame.result,'FORM_READY_BEFORE_15S');
});
// Break: partial evidence after Resume suppresses an independently observed primary SDK failure.
for(const [change,want] of [[{operation:'FAILED'},'OPERATION_FAILED'],[{operation:'ABORTED'},'ABORTED'],
  [{confirmation_blocked:true},'CONFIRMATION_BLOCKED']] as const)test(`late source failure cannot hide ${want}`,async()=>{
  const f=fake({...ready,...change} as typeof ready),click=f.locator.click;
  f.locator.click=async()=>{await click();f.handlers.get('response')!({status:()=>NaN});};
  const result=await throughParent(f);
  assert.equal(result.exitCode,1);assert.equal(result.frames[0]!.result,want);
  assert.equal(result.frames[0]!.resource_observation,'UNAVAILABLE');
  assert.equal(result.frames[0]!.fetch_failure_destination,'UNAVAILABLE');assert.equal(result.frames[0]!.fetch_failure_code,'UNAVAILABLE');
  assert.equal(result.frames[0]!.observation_end,'SDK_TERMINAL');
});
test('actual terminal loaderror retains SDK_TERMINAL through the parent wire3 producer',async()=>{
  const f=fake({...ready,loaderror_seen:true}),result=await throughParent(f);
  assert.equal(result.exitCode,1);assert.equal(result.frames[0]!.result,'ELEMENT_LOADERROR');
  assert.equal(result.frames[0]!.observation_end,'SDK_TERMINAL');
});
// Break: the parent paints checkpoint with later masks, or failure publishes a new read.
test('checkpoint owns its environment while latest complete observation is cached after abort',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake({...pending} as typeof ready),controller=new AbortController(),evaluate=f.page.evaluate;let reads=0;
  f.page.evaluate=async(fn:Function,arg?:unknown)=>{reads++;return evaluate(fn,arg);};
  const run=throughParent(f,{deadline:1900000,signal:controller.signal});await immediate();
  f.cspListeners[0]!({isTrusted:true,effectiveDirective:'script-src',disposition:'enforce'});
  f.handlers.get('response')!({status:()=>404,request:()=>({resourceType:()=> 'script'})});
  t.mock.timers.tick(15000);await immediate();
  f.cspListeners[0]!({isTrusted:true,effectiveDirective:'frame-src',disposition:'report'});
  f.handlers.get('response')!({status:()=>503,request:()=>({resourceType:()=> 'fetch'})});
  t.mock.timers.tick(100);await immediate();const before=reads;controller.abort();
  const result=await run;
  assert.equal(reads,before);assert.equal(result.exitCode,1);assert.equal(result.frames.length,2);
  const [checkpoint,final]=result.frames;
  assert.equal(checkpoint!.result,'WAITING_ELEMENT_READY');assert.equal(checkpoint!.csp_enforced_mask,1);
  assert.equal(checkpoint!.csp_report_only_mask,0);assert.equal(checkpoint!.resource_http_5xx_mask,0);
  assert.equal(checkpoint!.environment_freshness,'SAMPLED');assert.equal(final!.result,'ABORTED');
  assert.equal(checkpoint!.observation_end,'OBSERVING');assert.equal(final!.observation_end,'ABORTED');
  assert.equal(final!.csp_enforced_mask,1);assert.equal(final!.csp_report_only_mask,2);
  assert.equal(final!.resource_http_4xx_mask,1);assert.equal(final!.resource_http_5xx_mask,4);
  assert.equal(final!.environment_freshness,'CACHED');assert.equal(Object.hasOwn(final!,'bucket'),false);
});
// Break caught: Node categories are lost or mixed across actual driver/parent snapshots and teardown.
test('driver carries latest same-event fetch pair from navigation through frozen checkpoint and cached abort',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake({...pending} as typeof ready),goto=f.page.goto,controller=new AbortController();
  f.page.goto=async()=>{await goto();f.handlers.get('requestfailed')!({resourceType:()=> 'fetch',url:()=>handoff.session.apiBaseUrl+'/private?secret=sentinel',failure:()=>({errorText:'net::ERR_ABORTED'})});};
  const run=throughParent(f,{deadline:1900000,signal:controller.signal});await immediate();
  t.mock.timers.tick(15000);await immediate();
  f.handlers.get('requestfailed')!({resourceType:()=> 'xhr',url:()=> 'https://api.stripe.com/private',failure:()=>({errorText:'net::ERR_NAME_NOT_RESOLVED'})});
  t.mock.timers.tick(100);await immediate();controller.abort();const result=await run;
  assert.deepEqual(result.frames.map(frame=>[frame.fetch_failure_destination,frame.fetch_failure_code]),[['OWN_APP','ABORTED'],['STRIPE_API','NAME_NOT_RESOLVED']]);
  assert.deepEqual(result.frames.map(frame=>frame.resource_transport_failure_mask),[4,4]);
  assert.deepEqual(result.frames.map(frame=>frame.observation_end),['OBSERVING','ABORTED']);
  assert.deepEqual(result.frames.map(frame=>frame.environment_freshness),['SAMPLED','CACHED']);
  assert.equal(JSON.stringify(result).includes('sentinel'),false);
});
// Break: retained callbacks consume late payloads or private CSP bits alter frozen output.
for(const end of ['abort','local','deadline'] as const)test(`retained actual CSP callback after ${end} can change only private bits; resource callbacks/readers stay inert`,async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake({...pending} as typeof ready),controller=new AbortController(),evaluate=f.page.evaluate;let reads=0,payloadReads=0;
  f.page.evaluate=async(fn:Function,arg?:unknown)=>{reads++;return evaluate(fn,arg);};
  const run=throughParent(f,{deadline:1900000,signal:controller.signal});await immediate();
  const before=reads,poison=new Proxy({},{get(){payloadReads++;throw null;}});
  if(end==='abort')controller.abort();else t.mock.timers.setTime(end==='local'?1060000:1900000);
  f.retained.get('response')!(poison);f.retained.get('requestfailed')!(poison);
  f.cspListeners[0]!({isTrusted:true,effectiveDirective:'script-src',disposition:'enforce'});
  if(end!=='abort')t.mock.timers.tick(0);
  const result=await run,wire=JSON.stringify(result);
  assert.equal(reads,before);assert.equal(payloadReads,0);
  assert.equal(result.frames[0]!.environment_freshness,'CACHED');assert.equal(result.frames[0]!.csp_enforced_mask,0);
  assert.equal(result.frames[0]!.observation_end,end==='abort'?'ABORTED':end==='local'?'LOCAL_LIMIT':'ACTIVE_DEADLINE');
  assert.equal(runInContext('__formDiagnosticCsp.snapshot().csp_enforced_mask',f.realm),end==='deadline'?0:1);
  f.retained.get('response')!(poison);f.retained.get('requestfailed')!(poison);
  f.cspListeners[0]!({isTrusted:true,effectiveDirective:'connect-src',disposition:'enforce'});
  assert.equal(runInContext('__formDiagnosticCsp.snapshot().csp_enforced_mask',f.realm),end==='deadline'?0:5);
  assert.equal(JSON.stringify(result),wire);assert.equal(reads,before);assert.equal(payloadReads,0);
});
test('failed resource subscription retains CSP baseline and never authorizes Resume',async()=>{
  const f=fake(),on=f.page.on;
  f.page.on=(name:string,fn:Function)=>{on(name,fn);throw Symbol();};
  const result=await throughParent(f),frame=result.frames[0]!;
  assert.equal(result.exitCode,1);assert.equal(frame.result,'INSTRUMENTATION_UNAVAILABLE');
  assert.equal(frame.resource_observation,'UNAVAILABLE');assert.equal(frame.csp_observation,'OBSERVED');
  assert.equal(f.events.includes('resume'),false);
});
for(const part of ['outer','sample','csp'] as const)test(`${part} data accessors are rejected before Pay and final evidence uses baseline cache`,async()=>{
  const f=fake(),evaluate=f.page.evaluate;let getterReads=0,payReads=0,reads=0;
  f.locator.count=async()=>{payReads++;return 1;};
  f.page.evaluate=async(fn:Function,includeForm?:unknown)=>{
    reads++;if(!includeForm)return evaluate(fn,includeForm);
    const raw=composite({...ready}),target=part==='outer'?raw:part==='sample'?raw.sample:raw.csp;
    Object.defineProperty(target!,part==='outer'?'handshake':part==='sample'?'version':'csp_enforced_mask',
      {enumerable:true,get(){getterReads++;throw Symbol();}});
    return raw;
  };
  const result=await throughParent(f),frame=result.frames[0]!;
  assert.equal(frame.result,'INSTRUMENTATION_UNAVAILABLE');assert.equal(frame.environment_freshness,'CACHED');
  assert.equal(Object.hasOwn(frame,'bucket'),false);assert.equal(getterReads,0);assert.equal(payReads,0);assert.equal(reads,2);
});
// Break: copying an unexpected __proto__ key through a setter silently removes it before validation.
test('unexpected prototype-spelled stage key cannot disappear into a ready observation',async()=>{
  const f=fake(),evaluate=f.page.evaluate;let payReads=0;
  f.locator.count=async()=>{payReads++;return 1;};
  f.page.evaluate=async(fn:Function,includeForm?:unknown)=>{
    if(!includeForm)return evaluate(fn,includeForm);
    const raw=composite({...ready});Object.defineProperty(raw.sample!,'__proto__',{value:null,enumerable:true});return raw;
  };
  const result=await throughParent(f);
  assert.equal(result.exitCode,1);assert.equal(result.frames[0]!.result,'INSTRUMENTATION_UNAVAILABLE');assert.equal(payReads,0);
});
// Break: getters/hidden keys disappear through normal evaluate cloning and manufacture readiness.
for(const source of ['sample','csp'] as const)for(const kind of ['returning getter','throwing getter','prototype-spelled','hidden','symbol','nested','invalid domain'] as const)
test(`normal serialized ${source} facade rejects ${kind} without Pay or false readiness`,async()=>{
  const f=fake(),evaluate=f.page.evaluate;let payReads=0;
  f.locator.count=async()=>{payReads++;return 1;};
  const field=source==='sample'?'version':'csp_enforced_mask',scalar=source==='sample'?1:0;
  const mutation=kind==='returning getter'?`Object.defineProperty(snapshot,'${field}',{enumerable:true,get(){getterReads++;return ${scalar}}})`
    :kind==='throwing getter'?`Object.defineProperty(snapshot,'${field}',{enumerable:true,get(){getterReads++;throw poison}})`
    :kind==='prototype-spelled'?`Object.defineProperty(snapshot,'__proto__',{enumerable:true,value:null})`
    :kind==='hidden'?`Object.defineProperty(snapshot,'extra',{value:0})`
    :kind==='symbol'?`snapshot[Symbol('extra')]=0`
    :kind==='nested'?`snapshot.${field}={get valueOf(){forbiddenReads++;throw poison},get toJSON(){forbiddenReads++;throw poison}}`
    :`snapshot.${field}=Infinity`;
  runInContext(`globalThis.getterReads=0;globalThis.forbiddenReads=0;globalThis.calls=0;
    globalThis.poison={get message(){forbiddenReads++;throw 1}};
    globalThis.snapshot=${JSON.stringify(source==='sample'?ready:composite(null).csp)};${mutation};
    ${source==='sample'?`__formLoadDiagnostic.snapshot=()=>snapshot`:
      `globalThis.__formDiagnosticCsp={snapshot(){return ++calls===1?{csp_observation:'OBSERVED',csp_enforced_mask:0,csp_report_only_mask:0}:snapshot}}`}`,f.realm);
  const transferred:unknown[]=[];
  // Observe the normal evaluate result; malformed records enter only through the VM facade methods.
  f.page.evaluate=async(fn:Function,arg?:unknown)=>{const value=await evaluate(fn,arg);transferred.push(value);return value;};
  const result=await throughParent(f),frame=result.frames[0]!;
  assert.equal(runInContext('getterReads',f.realm),0);assert.equal(runInContext('forbiddenReads',f.realm),0);
  assert.equal(result.exitCode,1);assert.equal(frame.result,'INSTRUMENTATION_UNAVAILABLE');
  assert.equal(frame.environment_freshness,'CACHED');assert.equal(frame.csp_observation,'OBSERVED');
  assert.equal(frame.resource_observation,'OBSERVED');assert.equal(Object.hasOwn(frame,'bucket'),false);assert.equal(payReads,0);
  assert.deepEqual(transferred[1],{handshake:true,sample:source==='sample'?null:ready,
    csp:source==='csp'?null:composite(null).csp});
});
// Break caught: browser shutdown invalidates otherwise successful deferred context work.
// Playwright context.close yields before its channel close; browser shutdown can
// dispose that dispatcher and emit context Close without completing this attempt.
for(const deferred of [false,true])test(`${deferred?'deferred':'immediate'} independent context completion precedes browser invalidation and permits parent exit0`,async()=>{
  const sample={...ready},f=fake(sample);let dispatcherAvailable=true;
  f.context.close=async()=>{
    f.events.push('context-close-start');
    if(deferred)await immediate();
    if(!dispatcherAvailable)throw Symbol('synthetic-target-closed');
    Object.assign(sample,pending);f.events.push('context-close-fulfilled');
  };
  f.browser.close=async()=>{dispatcherAvailable=false;f.events.push('browser-close');};
  const result=await throughParent(f);
  assert.equal(result.exitCode,0);assert.equal(result.frames[0]?.result,'FORM_READY_BEFORE_15S');
  assert.equal(result.frames[0]?.context_closed,true);assert.equal(result.frames[0]?.browser_closed,true);
  assert.equal(result.frames[0]?.runtime_closed,true);
  const frame=result.frames[0];assert.ok(frame&&'ready_seen'in frame);assert.equal(frame.ready_seen,true);
  assert.ok(Object.isFrozen(result.frames[0]));
  assert.deepEqual(f.events,['launch','context','init','init','goto','resume','context-close-start','context-close-fulfilled','browser-close','runtime-close']);
});
// Break caught: the first owned close is a fulfilled no-op after external closure,
// manufacturing independent context proof and parent exit0 from a ready snapshot.
// Installed Playwright 1.63.0 BrowserContext.isClosed() includes closing; close()
// immediately fulfills when that public method is true (coreBundle.js62620-62622).
for(const externalState of ['closing','closed'] as const)test(`externally ${externalState} context before queued close dispatch cannot earn independent proof`,async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake();let state:'open'|'closing'|'closed'='open';
  f.context.isClosed=()=>state!=='open';
  f.context.close=async()=>{
    f.events.push(state==='open'?'context-close':'context-close-noop');
    if(f.context.isClosed())return;
    state='closed';
  };
  f.locator.isVisible=async()=>{
    // Yield across the bounded visibility result and its caller's continuations:
    // the external event lands after the sample copy, before queued close I/O.
    let externalEvent=Promise.resolve();
    for(let turn=0;turn<4;turn++)externalEvent=externalEvent.then(()=>{
      if(turn===3){state=externalState;f.events.push('external-context-'+externalState);}
    });
    return true;
  };
  const result=await throughParent(f);
  assert.equal(result.exitCode,1);assert.equal(result.frames[0]?.context_closed,false);
  assert.equal(result.frames[0]?.result,'OPERATION_FAILED');assert.equal(result.frames[0]?.browser_closed,true);
  assert.equal(result.frames[0]?.runtime_closed,true);assert.equal(Date.now(),1000000);
  const frame=result.frames[0];assert.ok(frame&&'ready_seen'in frame);
  assert.equal(frame.ready_seen,true);assert.equal(frame.pay_visible,true);assert.ok(Object.isFrozen(frame));
  assert.deepEqual(f.events,['launch','context','init','init','goto','resume','external-context-'+externalState,'browser-close','runtime-close']);
});
// Break caught: success at/after grace wins when the timer callback has not run.
for(const [elapsed,wantContext,wantExit] of [[4999,true,0],[5000,false,1],[5001,false,1]] as const){
  test(`context completion at ${elapsed}ms respects strict grace cutoff before timer dispatch`,async t=>{
    t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
    const f=fake();f.context.close=async()=>{f.events.push('context-close');await immediate();t.mock.timers.setTime(1000000+elapsed);};
    const result=await throughParent(f);
    assert.equal(result.exitCode,wantExit);assert.equal(result.frames[0]?.context_closed,wantContext);
    assert.equal(result.frames[0]?.browser_closed,true);assert.equal(f.events.filter(s=>s==='resume').length,1);
    assert.equal(f.events.filter(s=>s==='context-close').length,1);assert.equal(f.events.filter(s=>s==='browser-close').length,1);
  });
}
// Break caught: full five-second grace consumes the ten-second browser reserve.
for(const [elapsed,wantContext] of [[999,true],[1000,false],[1001,false]] as const){
  test(`reserve-clamped context completion at ${elapsed}ms keeps original parent cleanup deadline`,async t=>{
    t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
    const f=fake(),original=limits();
    f.locator.isVisible=async()=>{t.mock.timers.setTime(2009000);return true;};
    f.context.close=async()=>{f.events.push('context-close');await immediate();t.mock.timers.setTime(2009000+elapsed);};
    const result=await throughParent(f,original);
    assert.equal(result.exitCode,1);assert.equal(result.frames[0]?.context_closed,wantContext);
    assert.equal(result.frames[0]?.browser_closed,true);assert.equal(result.frames[0]?.runtime_closed,true);
    assert.deepEqual(f.events.slice(-3),['context-close','browser-close','runtime-close']);
  });
}
// Break caught: a pending context consumes browser reserve instead of falling back.
test('reserve-clamped pending context falls back after one second and drains late rejection',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake(),original=limits();let reject!:()=>void;
  f.locator.isVisible=async()=>{t.mock.timers.setTime(2009000);return true;};
  f.context.close=()=>{f.events.push('context-close');return new Promise<void>((_resolve,no)=>{reject=()=>no(Symbol('opaque'));});};
  const run=throughParent(f,original);await immediate();
  t.mock.timers.tick(999);await immediate();assert.equal(f.events.includes('browser-close'),false);
  t.mock.timers.tick(1);await immediate();assert.equal(f.events.includes('browser-close'),true);
  t.mock.timers.tick(10000);await immediate();const result=await run;
  assert.equal(Date.now(),2020000);assert.equal(result.exitCode,1);
  assert.equal(result.frames[0]?.context_closed,false);assert.equal(result.frames[0]?.browser_closed,true);
  assert.equal(result.frames[0]?.runtime_closed,true);
  reject();await immediate();assert.equal(result.frames[0]?.context_closed,false);
  assert.equal(f.events.filter(s=>s==='context-close').length,1);assert.equal(f.events.filter(s=>s==='browser-close').length,1);
});
// Break caught: successful context closure renews the browser's absolute bound.
test('hung browser close retains the original shared ceiling after independent context success',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake(),original=limits();let release!:()=>void;
  f.browser.close=()=>{f.events.push('browser-close');return new Promise<void>(resolve=>{release=resolve;});};
  const run=throughParent(f,original);await immediate();
  assert.equal(f.events.includes('browser-close'),true);assert.equal(f.events.includes('runtime-close'),false);
  t.mock.timers.tick(1020000);await immediate();const result=await run;
  assert.equal(Date.now(),2020000);assert.equal(result.exitCode,1);
  assert.equal(result.frames[0]?.context_closed,true);assert.equal(result.frames[0]?.browser_closed,false);
  assert.equal(result.frames[0]?.runtime_closed,true);release();await immediate();
  assert.equal(result.frames[0]?.browser_closed,false);assert.equal(f.events.filter(s=>s==='browser-close').length,1);
});
// Break caught: zero grace or expired ceiling dispatches new work with false certainty.
for(const [cleanupAt,wantBrowser,wantCalls] of [[2010000,true,['context-close','browser-close']],[2010001,true,['context-close','browser-close']],[2020000,false,[]]] as const){
  test(`cleanup starting at ${cleanupAt} grants no grace and dispatches only before original ceiling`,async t=>{
    t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
    const f=fake(),original=limits();f.locator.isVisible=async()=>{t.mock.timers.setTime(cleanupAt);return true;};
    const result=await throughParent(f,original);
    assert.equal(result.exitCode,1);assert.equal(result.frames[0]?.context_closed,false);
    assert.equal(result.frames[0]?.browser_closed,wantBrowser);
    assert.deepEqual(f.events.filter(s=>s.endsWith('-close')&&s!=='runtime-close'),wantCalls);
  });
}
// Break caught: fallback or browser/context Close side effects upgrade a late success.
test('grace fallback latches context uncertainty through late fulfillment before the ceiling',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake();let release!:()=>void,browserClosed=false;
  f.context.close=async()=>{f.events.push('context-close');await new Promise<void>(resolve=>{release=resolve;});};
  f.browser.close=async()=>{browserClosed=true;f.events.push('browser-close');};
  const run=throughParent(f);await immediate();
  t.mock.timers.tick(4999);await immediate();assert.equal(browserClosed,false);
  t.mock.timers.tick(1);await immediate();assert.equal(browserClosed,true);
  release();await immediate();const result=await run;
  assert.equal(Date.now(),1005000);assert.equal(result.exitCode,1);
  assert.equal(result.frames[0]?.context_closed,false);assert.equal(result.frames[0]?.browser_closed,true);
  assert.equal(f.events.filter(s=>s==='context-close').length,1);assert.equal(f.events.filter(s=>s==='browser-close').length,1);
});
// Break caught: awaiting a rejection-swallowing helper mistakes failure for success.
test('context rejection triggers browser fallback immediately without claiming independent context proof',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake();f.context.close=async()=>{f.events.push('context-close');throw Symbol('opaque');};
  const result=await throughParent(f);
  assert.equal(Date.now(),1000000);assert.equal(result.exitCode,1);
  assert.equal(result.frames[0]?.context_closed,false);assert.equal(result.frames[0]?.browser_closed,true);
  assert.deepEqual(f.events.slice(-3),['context-close','browser-close','runtime-close']);
});
// Break caught: missing context ownership is manufactured into a successful proof.
test('failed context acquisition still closes the owned browser once without context proof',async()=>{
  const f=fake();f.browser.newContext=async()=>{throw Symbol('opaque');};
  const result=await throughParent(f);
  assert.equal(result.exitCode,1);assert.equal(result.frames[0]?.context_closed,false);
  assert.equal(result.frames[0]?.browser_closed,true);assert.deepEqual(f.events,['launch','browser-close','runtime-close']);
});
// Break caught: late acquisition after the original ceiling dispatches fresh close I/O.
for(const kind of ['browser','context'] as const)test(`late ${kind} acquired after cleanup ceiling cannot dispatch close or rewrite frozen outcome`,async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake();let release!:(value:unknown)=>void;
  const acquired=new Promise<unknown>(resolve=>{release=resolve;});
  if(kind==='browser')f.type.launch=async()=>await acquired as never;
  else f.browser.newContext=async()=>await acquired as never;
  const run=runFormLoadBrowser(f.type,handoff,{deadline:1001000,signal:new AbortController().signal});
  await immediate();t.mock.timers.tick(121000);await immediate();const result=await run;
  const before=[...f.events];release(kind==='browser'?f.browser:f.context);await immediate();
  assert.equal(result.outcome,'DEADLINE_REACHED');assert.equal(result.contextClosed,false);
  assert.equal(result.browserClosed,false);assert.ok(Object.isFrozen(result));assert.deepEqual(f.events,before);
});
test('one ready load uses exactly one Resume and closes context before browser; no payment capability',async()=>{
  const f=fake(),result=await runFormLoadBrowser(f.type,handoff,limits());
  assert.equal(result.outcome,'OBSERVING');assert.equal(result.observations.length,1);
  assert.equal(result.observations[0]?.sample?.bucket,'BEFORE_15S');assert.equal(result.observations[0]?.sample?.pay_visible,true);
  assert.equal(result.browserClosed,true);assert.equal(result.contextClosed,true);assert.ok(Object.isFrozen(result.observations[0]));
  assert.deepEqual(f.events,['launch','context','init','init','goto','resume','context-close','browser-close']);
  assert.equal(JSON.stringify(result).includes(handoff.session.checkoutToken),false);
});
test('unavailable instrumentation and opaque page failure do not become waiting diagnoses',async()=>{
  const f=fake();f.page.evaluate=async()=>false as never;
  const result=await runFormLoadBrowser(f.type,handoff,limits());
  assert.equal(result.outcome,'INSTRUMENTATION_UNAVAILABLE');assert.deepEqual(result.observations,[]);
  assert.equal(f.events.includes('resume'),false);assert.equal(result.browserClosed,true);
  const g=fake();let reads=0;g.page.goto=async()=>{throw new Proxy({},{get(){reads++;throw null;}});};
  const failed=await runFormLoadBrowser(g.type,handoff,limits());assert.equal(failed.outcome,'FAILED');assert.equal(reads,0);
});
test('abort before launch accesses no browser capability',async()=>{
  const controller=new AbortController();controller.abort();const f=fake();
  assert.equal((await runFormLoadBrowser(f.type,handoff,{deadline:Date.now()+1000,signal:controller.signal})).outcome,'ABORTED');assert.deepEqual(f.events,[]);
});
test('uncertain context cleanup stays unconfirmed even after successful browser close',async()=>{
  const f=fake();f.context.close=async()=>{throw null;};
  const result=await runFormLoadBrowser(f.type,handoff,limits());assert.equal(result.contextClosed,false);assert.equal(result.browserClosed,true);
  assert.equal(result.outcome,'FAILED');
  assert.equal(result.observationEnd,'FORM_READY');
});
test('hanging context close cannot prevent browser close and parent cleanup keeps the original ceiling',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake(),original=limits();let resolveContext!:()=>void;
  f.context.close=()=>{f.events.push('context-close');return new Promise<void>(resolve=>{resolveContext=resolve;});};
  const run=runFormLoadDiagnostic({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'},
    runId:handoff.runId,expectedSource:handoff.expectedSource,daemon:'synthetic-daemon',
    ports:{backend:18080,demo:14173,receiver:14174,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025}},original,{
    createRuntime:async()=>({prepare:async()=>handoff,verifyNoConfirmation:async()=>{f.events.push('proof');return true;},
      close:async(cleanup:{deadline:number})=>{assert.equal(cleanup.deadline,original.deadline+120000);f.events.push('runtime-close');return true;}}),
    runBrowser:(h:FormDiagnosticHandoff,l:ReturnType<typeof limits>)=>runFormLoadBrowser(f.type,h,l),
  });
  await immediate();
  t.mock.timers.tick(4999);await immediate();assert.equal(f.events.includes('browser-close'),false);
  t.mock.timers.tick(1);await immediate();
  const closedBeforeCeiling=f.events.includes('browser-close');
  assert.equal(f.events.includes('runtime-close'),false);
  t.mock.timers.tick(1015000);await immediate();
  const result=await run;
  assert.equal(closedBeforeCeiling,true);
  assert.equal(result.exitCode,1);assert.equal(result.frames[0]?.context_closed,false);
  assert.equal(result.frames[0]?.browser_closed,true);
  assert.deepEqual(f.events.slice(-3),['context-close','browser-close','runtime-close']);
  resolveContext();await immediate();assert.equal(result.frames[0]?.context_closed,false);
});
test('pending checkpoint then late ready yields two bounded snapshots and one Resume',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const sample={...pending},f=fake(sample as typeof ready),run=runFormLoadBrowser(f.type,handoff,limits());
  await immediate();t.mock.timers.tick(15000);await immediate();
  Object.assign(sample,ready);t.mock.timers.tick(5000);await immediate();
  const result=await run;assert.equal(result.observations.length,2);
  assert.deepEqual(result.observations.map(s=>s.sample?.bucket),['AFTER_15S','AFTER_15S']);
  assert.equal(result.observations[0]?.sample?.ready_seen,false);assert.equal(result.observations[1]?.sample?.pay_visible,true);
  assert.equal(f.events.filter(s=>s==='resume').length,1);
});
test('pending form stops at original60s window, without retry or new capability',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake({...pending} as typeof ready),run=runFormLoadBrowser(f.type,handoff,limits());
  await immediate();t.mock.timers.tick(15000);await immediate();t.mock.timers.tick(45000);await immediate();
  const result=await run;assert.equal(result.outcome,'FAILED');assert.equal(result.observations.length,2);
  assert.equal(result.observations[1]?.sample?.bucket,'AT_LIMIT');assert.equal(f.events.filter(s=>s==='resume').length,1);
  assert.equal(classifyFormDiagnosticSample(result.observations[1]?.sample,result.outcome),'OPERATION_FAILED');
});
test('readiness bucket includes completion of the actual Pay visibility proof across15s',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake();f.page.evaluate=async(fn:Function,includeForm?:unknown)=>{
    if(!includeForm)return composite(null,false);
    t.mock.timers.setTime(1014999);return composite(ready);
  };
  f.locator.isVisible=async()=>{t.mock.timers.setTime(1015001);return true;};
  const result=await runFormLoadBrowser(f.type,handoff,limits());
  assert.equal(result.outcome,'OBSERVING');assert.equal(result.observations.length,1);
  assert.equal(classifyFormDiagnosticSample(result.observations[0]?.sample),'FORM_READY_AFTER_15S');
});
for(const boundary of [60000,60001])for(const stage of ['snapshot','visibility']){
  test(`${stage} settlement at ${boundary}ms cannot beat the local deadline timer or promote readiness`,async t=>{
    t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
    const sample={...pending},f=fake(sample as typeof ready);let reads=0,payReads=0;
    const payCount=f.locator.count;f.locator.count=async()=>{payReads++;return payCount();};
    f.page.evaluate=async(fn:Function,includeForm?:unknown)=>{
      if(!includeForm)return composite(null,false);
      if(++reads===1)return composite({...pending});
      Object.assign(sample,ready);
      t.mock.timers.setTime(1000000+(stage==='snapshot'?boundary:59999));return composite({...ready});
    };
    f.locator.isVisible=async()=>{t.mock.timers.setTime(1000000+boundary);return true;};
    const run=runFormLoadBrowser(f.type,handoff,limits());
    await immediate();t.mock.timers.tick(100);await immediate();
    const result=await run;
    assert.equal(result.outcome,'FAILED');assert.equal(result.observations.length,1);
    assert.equal(result.observationEnd,'LOCAL_LIMIT');
    assert.equal(result.observations[0]?.sample?.bucket,'AT_LIMIT');assert.equal(result.observations[0]?.sample?.ready_seen,false);
    assert.equal(result.observations[0]?.sample?.pay_visible,false);
    assert.equal(payReads,stage==='snapshot'?1:2);
    assert.equal(classifyFormDiagnosticSample(result.observations[0]?.sample,result.outcome),'OPERATION_FAILED');
    assert.equal(result.contextClosed,true);assert.equal(result.browserClosed,true);
  });
}
test('expired local window admits no new snapshot even with ample original active time',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const sample={...pending},f=fake(sample as typeof ready);let reads=0;
  f.page.evaluate=async(_fn:Function,includeForm?:unknown)=>!includeForm?composite(null,false):(reads++,composite({...sample}));
  const run=runFormLoadBrowser(f.type,handoff,limits());await immediate();
  Object.assign(sample,ready);t.mock.timers.tick(60000);await immediate();
  const result=await run;
  assert.equal(reads,1);assert.equal(result.outcome,'FAILED');
  assert.equal(result.observations[0]?.sample?.bucket,'AT_LIMIT');assert.equal(result.observations[0]?.sample?.ready_seen,false);
});
test('late browser acquisition is closed and never upgrades uncertainty to success',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake();let resolve!:(browser:unknown)=>void;
  const launch=new Promise<unknown>(yes=>{resolve=yes;});
  const type={launch:()=>launch} as unknown as Pick<BrowserType,'launch'>;
  const run=runFormLoadBrowser(type,handoff,{deadline:Date.now()+1000,signal:new AbortController().signal});
  await immediate();t.mock.timers.tick(1000);await immediate();resolve(f.browser);await immediate();
  const result=await run;assert.equal(result.outcome,'DEADLINE_REACHED');assert.equal(result.browserClosed,false);
  assert.deepEqual(f.events,['browser-close']);
});
test('a hung local observation cannot extend the60s window to the15min active ceiling',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake({...pending} as typeof ready),controller=new AbortController();let reads=0,settled=false;
  f.page.evaluate=async(_fn:Function,includeForm?:unknown)=>!includeForm?composite(null,false):++reads<3?composite(pending):await new Promise(()=>{}) as never;
  const run=runFormLoadBrowser(f.type,handoff,{deadline:Date.now()+900000,signal:controller.signal}).then(result=>{settled=true;return result;});
  await immediate();t.mock.timers.tick(15000);await immediate();t.mock.timers.tick(100);await immediate();
  t.mock.timers.tick(44900);await immediate();
  try {assert.equal(settled,true);}finally {controller.abort();await run;}
});
test('late context acquisition is closed without converting unknown context ownership into success',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const f=fake();let resolve!:(context:unknown)=>void;
  const acquired=new Promise<unknown>(yes=>{resolve=yes;});f.browser.newContext=async()=>await acquired as never;
  const run=runFormLoadBrowser(f.type,handoff,{deadline:Date.now()+1000,signal:new AbortController().signal});
  await immediate();t.mock.timers.tick(1000);await immediate();resolve(f.context);await immediate();
  const result=await run;assert.equal(result.outcome,'DEADLINE_REACHED');assert.equal(result.contextClosed,false);
  assert.equal(result.browserClosed,true);assert.equal(f.events.filter(v=>v==='context-close').length,1);
  assert.equal(f.events.includes('resume'),false);
});
test('abort during local observation preserves frozen checkpoint and prevents late readiness improvement',async t=>{
  t.mock.timers.enable({apis:['Date','setTimeout'],now:1000000});
  const sample={...pending},f=fake(sample as typeof ready),controller=new AbortController();
  const run=runFormLoadBrowser(f.type,handoff,{deadline:Date.now()+900000,signal:controller.signal});
  await immediate();t.mock.timers.tick(15000);await immediate();controller.abort();Object.assign(sample,ready);
  const result=await run;assert.equal(result.outcome,'ABORTED');assert.equal(result.observations.length,2);
  assert.equal(result.observations[0]?.sample?.ready_seen,false);assert.equal(result.observations[1]?.environment.environment_freshness,'CACHED');assert.equal(result.observations[1]?.sample?.pay_visible,false);
  assert.equal(result.contextClosed,true);assert.equal(result.browserClosed,true);
});
