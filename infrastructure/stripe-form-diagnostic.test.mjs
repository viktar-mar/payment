import test from 'node:test';
import assert from 'node:assert/strict';

// Break: new-mode exclusion after normalization or evaluating an accessor.
test('remainder exclusion precedes snapshots configuration and dependencies',async()=>{
  const {runFormLoadDiagnostic:accept}=await import('./stripe-form-diagnostic.mjs');const base={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'};
  let reads=0;const trap=new Proxy({},{get(){reads++;throw null;}});
  for(const shape of ['true','malformed','boolean','accessor','hidden','inherited']){
    const raw=shape==='inherited'?Object.assign(Object.create({PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:'true'}),base):{...base};
    if(shape!=='inherited')Object.defineProperty(raw,'PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER',shape==='accessor'?{enumerable:true,get(){reads++;throw null;}}:{enumerable:shape!=='hidden',value:shape==='true'||shape==='hidden'?'true':shape==='boolean'?false:{private:'canary'}});
    const value=await accept({env:raw},{deadline:Date.now()+1000,signal:new AbortController().signal},trap);assert.equal(value.exitCode,1);assert.equal(value.frames.at(-1).observation_end,'BROWSER_NOT_STARTED');
  }
  assert.equal(reads,0);
});
test('automatic diagnostic refuses env-container accessors without evaluation',async()=>{
  let touched=0;const value=await runFormLoadDiagnostic({get env(){touched++;throw 'secret_canary';}},null,new Proxy({},{get(){touched++;throw null;}}));
  assert.equal(value.exitCode,1);assert.equal(value.frames.at(-1).observation_end,'BROWSER_NOT_STARTED');assert.equal(touched,0);
});
import {runFormLoadDiagnostic} from './stripe-form-diagnostic.mjs';

const options={env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'}};
test('automatic diagnostic rejects new payment flag before dependencies',async()=>{let touched=0;const result=await runFormLoadDiagnostic({env:{...options.env,PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT:'true'}},null,new Proxy({},{get(){touched++;throw null;}}));assert.equal(result.exitCode,1);assert.equal(touched,0);});
const limits=()=>({deadline:Date.now()+900000,signal:new AbortController().signal});
const ready={version:1,binding:'BOUND',capability_started:true,capability_ready:true,loader_started:true,loader_ready:true,
  element_created:true,mount_called:true,mount_returned:true,ready_seen:true,loaderror_seen:false,view_completed:true,widget_completed:true,
  confirmation_blocked:false,catchup:'NOT_STARTED',operation:'COMPLETED',widget_state:'ready',element_state:'FRAME',pay_state:'ENABLED',pay_visible:true,bucket:'BEFORE_15S'};
const environment=()=>({csp_observation:'OBSERVED',csp_enforced_mask:0,csp_report_only_mask:0,resource_observation:'OBSERVED',resource_transport_failure_mask:0,resource_http_4xx_mask:0,resource_http_5xx_mask:0,fetch_failure_destination:'NONE',fetch_failure_code:'NONE',environment_freshness:'SAMPLED'});
const observation=sample=>({sample,environment:environment()});
// Break caught: parent ignores exact five-key/outcome-end validation or overwrites historical provenance.
test('parent validates browser end compatibility without invoking accessors',async()=>{
  const allowed={LOCAL_LIMIT:['FAILED'],ABORTED:['ABORTED'],ACTIVE_DEADLINE:['DEADLINE_REACHED'],BROWSER_OPERATION_FAILED:['FAILED'],
    FORM_READY:['OBSERVING','FAILED'],SDK_TERMINAL:['OBSERVING','FAILED'],INSTRUMENTATION_UNAVAILABLE:['OBSERVING','INSTRUMENTATION_UNAVAILABLE','FAILED']};
  for(const [end,outcomes] of Object.entries(allowed))for(const outcome of ['OBSERVING','FAILED','ABORTED','DEADLINE_REACHED','INSTRUMENTATION_UNAVAILABLE','UNEXPECTED_ACTIVITY']){
    const f=fixture();f.deps.runBrowser=async()=>({observations:[observation(end==='SDK_TERMINAL'?{...ready,loaderror_seen:true}:ready)],outcome,observationEnd:end,browserClosed:true,contextClosed:true});
    const result=await runFormLoadDiagnostic(options,limits(),f.deps);
    assert.equal(result.frames[0].observation_end,outcomes.includes(outcome)?end:'INSTRUMENTATION_UNAVAILABLE',end+'/'+outcome);
    if(!outcomes.includes(outcome))assert.equal(result.frames[0].result,'INSTRUMENTATION_UNAVAILABLE');
  }
  for(const end of ['OBSERVING','BROWSER_NOT_STARTED','invalid',undefined]){
    const f=fixture();f.deps.runBrowser=async()=>({observations:[observation(ready)],outcome:'OBSERVING',observationEnd:end,browserClosed:true,contextClosed:true});
    assert.equal((await runFormLoadDiagnostic(options,limits(),f.deps)).frames[0].observation_end,'INSTRUMENTATION_UNAVAILABLE');
  }
  let reads=0;const f=fixture();f.deps.runBrowser=async()=>Object.defineProperty({observations:[observation(ready)],outcome:'OBSERVING',browserClosed:true,contextClosed:true},'observationEnd',{enumerable:true,get(){reads++;throw null;}});
  assert.equal((await runFormLoadDiagnostic(options,limits(),f.deps)).frames[0].observation_end,'INSTRUMENTATION_UNAVAILABLE');assert.equal(reads,0);
});
test('prepare failure and browser rejection retain distinct parent end provenance',async()=>{
  const f=fixture();f.runtime.prepare=async()=>{throw null;};
  assert.equal((await runFormLoadDiagnostic(options,limits(),f.deps)).frames[0].observation_end,'BROWSER_NOT_STARTED');
  const g=fixture();g.deps.runBrowser=async()=>{throw new Proxy({},{get(){assert.fail('external error inspected');}});};
  assert.equal((await runFormLoadDiagnostic(options,limits(),g.deps)).frames[0].observation_end,'BROWSER_OPERATION_FAILED');
});
// Break: legacy samples or malformed composite evidence gets promoted to financial success.
test('old samples, mixed observation envelopes and getters reject without raw output',async()=>{
  let reads=0;
  const accessor={sample:ready};Object.defineProperty(accessor,'environment',{enumerable:true,get(){reads++;throw null;}});
  for(const payload of [{samples:[ready],outcome:'OBSERVING',observationEnd:'FORM_READY',browserClosed:true,contextClosed:true},
    {observations:[observation(ready)],samples:[ready],outcome:'OBSERVING',observationEnd:'FORM_READY',browserClosed:true,contextClosed:true},
    ...[accessor,{sample:ready,environment:{...environment(),private:'private-sentinel'}},
      {...observation(ready),extra:true}].map(value=>({observations:[value],outcome:'OBSERVING',observationEnd:'FORM_READY',browserClosed:true,contextClosed:true}))]){
    const f=fixture();f.deps.runBrowser=async()=>payload;
    const result=await runFormLoadDiagnostic(options,limits(),f.deps);
    assert.equal(result.exitCode,1);assert.equal(result.frames[0].version,3);
    assert.equal(result.frames[0].result,'INSTRUMENTATION_UNAVAILABLE');assert.equal(result.frames[0].environment_freshness,'UNAVAILABLE');
    assert.equal(JSON.stringify(result).includes('private-sentinel'),false);
  }
  assert.equal(reads,0);
});
test('cached or unavailable evidence cannot promote literal ready sample1 to success',async()=>{
  for(const environment of [{csp_observation:'UNAVAILABLE',csp_enforced_mask:0,csp_report_only_mask:0,
    resource_observation:'UNAVAILABLE',resource_transport_failure_mask:0,resource_http_4xx_mask:0,resource_http_5xx_mask:0,fetch_failure_destination:'UNAVAILABLE',fetch_failure_code:'UNAVAILABLE',environment_freshness:'UNAVAILABLE'},
    {...observation(ready).environment,environment_freshness:'CACHED'},
    {...observation(ready).environment,resource_observation:'UNAVAILABLE'}]){
    const f=fixture();f.deps.runBrowser=async()=>({observations:[{sample:ready,environment}],outcome:'OBSERVING',observationEnd:'FORM_READY',browserClosed:true,contextClosed:true});
    const result=await runFormLoadDiagnostic(options,limits(),f.deps);
    assert.equal(result.exitCode,1);assert.equal(result.frames[0].result,'INSTRUMENTATION_UNAVAILABLE');
    assert.equal(Object.hasOwn(result.frames[0],'bucket'),false);
  }
});
function fixture() {
  const calls=[],handoff=Object.freeze({private:'transient'});
  const runtime={prepare:async l=>{calls.push(['prepare',l]);return handoff;},verifyNoConfirmation:async l=>{calls.push(['proof',l]);return true;},close:async l=>{calls.push(['close',l]);return true;}};
  const deps={createRuntime:async()=>{calls.push(['factory']);return runtime;},runBrowser:async(h,l)=>{assert.equal(h,handoff);calls.push(['browser',l]);return {observations:[observation(ready)],outcome:'OBSERVING',observationEnd:'FORM_READY',browserClosed:true,contextClosed:true};}};
  return {calls,runtime,deps};
}
test('only complete genuine readiness plus independent final proof and cleanup can exit0',async()=>{
  const f=fixture(),original=limits(),result=await runFormLoadDiagnostic(options,original,f.deps);
  assert.equal(result.exitCode,0);assert.equal(result.frames.length,1);assert.equal(result.frames[0].result,'FORM_READY_BEFORE_15S');
  assert.equal(result.frames[0].no_confirmation,true);assert.equal(result.frames[0].runtime_closed,true);
  assert.equal(result.frames[0].observation_end,'FORM_READY');
  assert.deepEqual(f.calls.map(v=>v[0]),['factory','prepare','browser','proof','close']);
  for(const call of f.calls.filter(v=>['prepare','browser','proof'].includes(v[0])))assert.deepEqual(call[1],original);
  assert.equal(f.calls.at(-1)[1].deadline,original.deadline+120000);
});
test('missing or mixed flags reject before dependency getters',async()=>{
  let reads=0;const deps=new Proxy({},{get(){reads++;throw null;}});
  for(const env of [{}, {...options.env,PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'}, {...options.env,PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true'}, {...options.env,PAYMENT_STRIPE_SANDBOX_FULL_RUN:'true'}, {...options.env,PW_TRACE:'on'}, {...options.env,DEBUG:'private'}]){
    const result=await runFormLoadDiagnostic({env},limits(),deps);assert.equal(result.exitCode,1);
  }
  assert.equal(reads,0);
});
test('financial uncertainty overrides ready; browser failure remains original finite failure',async()=>{
  const f=fixture();f.runtime.verifyNoConfirmation=async()=>{throw new Proxy({},{get(){throw null;}});};
  const result=await runFormLoadDiagnostic(options,limits(),f.deps);assert.equal(result.exitCode,1);assert.equal(result.frames[0].result,'UNEXPECTED_ACTIVITY');
  assert.equal(result.frames[0].observation_end,'FORM_READY');
  const g=fixture();g.deps.runBrowser=async()=>({observations:[observation(ready)],outcome:'ABORTED',observationEnd:'ABORTED',browserClosed:true,contextClosed:true});
  const aborted=await runFormLoadDiagnostic(options,limits(),g.deps);assert.equal(aborted.exitCode,1);assert.equal(aborted.frames[0].result,'ABORTED');
});
test('browser/proof/runtime uncertainty can never be called acceptance or renewed',async()=>{
  const f=fixture();f.runtime.close=async()=>{throw null;};
  const result=await runFormLoadDiagnostic(options,limits(),f.deps);assert.equal(result.exitCode,1);assert.equal(result.frames[0].runtime_closed,false);
  assert.equal(result.frames[0].observation_end,'FORM_READY');
  assert.equal(JSON.stringify(result).includes('transient'),false);assert.equal(Object.hasOwn(result.frames[0],'accepted'),false);
});
test('expired original active window is reported as deadline and never renewed for final read',async t=>{
  t.mock.timers.enable({apis:['Date'],now:1000000});
  const f=fixture(),original=limits();f.deps.runBrowser=async()=>{
    t.mock.timers.setTime(original.deadline);return {observations:[observation(ready)],outcome:'OBSERVING',observationEnd:'FORM_READY',browserClosed:true,contextClosed:true};
  };
  const result=await runFormLoadDiagnostic(options,original,f.deps);assert.equal(result.exitCode,1);
  assert.equal(result.frames[0].result,'DEADLINE_REACHED');assert.equal(f.calls.some(call=>call[0]==='proof'),false);
  assert.equal(f.calls.at(-1)[1].deadline,original.deadline+120000);
});
test('malformed/accessor browser result fails closed without reading private payload',async()=>{
  const f=fixture();let reads=0;f.deps.runBrowser=async()=>Object.defineProperty({observations:[observation(ready)],outcome:'OBSERVING',observationEnd:'FORM_READY',browserClosed:true},'contextClosed',{get(){reads++;throw null;},enumerable:true});
  const result=await runFormLoadDiagnostic(options,limits(),f.deps);assert.equal(reads,0);assert.equal(result.exitCode,1);
  assert.equal(result.frames[0].result,'INSTRUMENTATION_UNAVAILABLE');assert.equal(Object.hasOwn(result.frames[0],'bucket'),false);
  const g=fixture();g.deps.runBrowser=async()=>({observations:[observation(ready),observation(ready),observation(ready)],outcome:'OBSERVING',observationEnd:'FORM_READY',browserClosed:true,contextClosed:true});
  assert.equal((await runFormLoadDiagnostic(options,limits(),g.deps)).exitCode,1);
});
for(const trigger of ['abort','deadline'])for(const primary of ['OBSERVING','FAILED'])for(const settlement of ['rejection','late success']){
  test(`${trigger} during final verification ${settlement} retains original control cause or earlier ${primary} failure`,async t=>{
    t.mock.timers.enable({apis:['Date'],now:1000000});
    const f=fixture(),controller=new AbortController(),original={deadline:Date.now()+900000,signal:controller.signal};let reads=0;
    f.deps.runBrowser=async()=>({observations:[observation(ready)],outcome:primary,observationEnd:'FORM_READY',browserClosed:true,contextClosed:true});
    f.runtime.verifyNoConfirmation=async l=>{
      assert.deepEqual(l,original);f.calls.push(['proof',l]);
      if(trigger==='abort')controller.abort();else t.mock.timers.setTime(original.deadline);
      if(settlement==='late success')return true;
      throw new Proxy({},{get(){reads++;throw null;}});
    };
    const result=await runFormLoadDiagnostic(options,original,f.deps);
    assert.equal(result.frames[0].result,primary==='FAILED'?'OPERATION_FAILED':trigger==='abort'?'ABORTED':'DEADLINE_REACHED');
    assert.equal(result.frames[0].no_confirmation,false);assert.equal(result.exitCode,1);assert.equal(reads,0);
    assert.equal(f.calls.filter(call=>call[0]==='proof').length,1);
    assert.equal(f.calls.at(-1)[1].deadline,original.deadline+120000);
  });
}
test('a pending15s checkpoint keeps its observed stage before the finite failed limit frame',async()=>{
  const f=fixture(),checkpoint={...ready,ready_seen:false,view_completed:false,widget_completed:false,
    operation:'PENDING',widget_state:'loading',pay_state:'ABSENT',pay_visible:false,bucket:'AFTER_15S'};
  f.deps.runBrowser=async()=>({observations:[observation(checkpoint),{sample:{...checkpoint,bucket:'AT_LIMIT'},environment:{...environment(),environment_freshness:'CACHED'}}],outcome:'FAILED',observationEnd:'LOCAL_LIMIT',browserClosed:true,contextClosed:true});
  const result=await runFormLoadDiagnostic(options,limits(),f.deps);
  assert.deepEqual(result.frames.map(frame=>frame.result),['WAITING_ELEMENT_READY','OPERATION_FAILED']);
  assert.deepEqual(result.frames.map(frame=>frame.observation_end),['OBSERVING','LOCAL_LIMIT']);
  assert.equal(result.frames[0].bucket,'AFTER_15S');assert.equal(Object.hasOwn(result.frames[1],'bucket'),false);
  assert.deepEqual(result.frames.map(frame=>frame.environment_freshness),['SAMPLED','CACHED']);
  assert.equal(result.exitCode,1);
});
