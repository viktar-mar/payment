import test from 'node:test';
import assert from 'node:assert/strict';
import {runFormLoadDiagnostic} from './stripe-form-diagnostic.mjs';

const options={env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'}};
const limits=()=>({deadline:Date.now()+900000,signal:new AbortController().signal});
const ready={version:1,binding:'BOUND',capability_started:true,capability_ready:true,loader_started:true,loader_ready:true,
  element_created:true,mount_called:true,mount_returned:true,ready_seen:true,loaderror_seen:false,view_completed:true,widget_completed:true,
  confirmation_blocked:false,catchup:'NOT_STARTED',operation:'COMPLETED',widget_state:'ready',element_state:'FRAME',pay_state:'ENABLED',pay_visible:true,bucket:'BEFORE_15S'};
const environment=()=>({csp_observation:'OBSERVED',csp_enforced_mask:0,csp_report_only_mask:0,resource_observation:'OBSERVED',resource_transport_failure_mask:0,resource_http_4xx_mask:0,resource_http_5xx_mask:0,environment_freshness:'SAMPLED'});
const observation=sample=>({sample,environment:environment()});
// Break: legacy samples or malformed composite evidence gets promoted to financial success.
test('old samples, mixed observation envelopes and getters reject without raw output',async()=>{
  let reads=0;
  const accessor={sample:ready};Object.defineProperty(accessor,'environment',{enumerable:true,get(){reads++;throw null;}});
  for(const payload of [{samples:[ready],outcome:'OBSERVING',browserClosed:true,contextClosed:true},
    {observations:[observation(ready)],samples:[ready],outcome:'OBSERVING',browserClosed:true,contextClosed:true},
    ...[accessor,{sample:ready,environment:{...environment(),private:'private-sentinel'}},
      {...observation(ready),extra:true}].map(value=>({observations:[value],outcome:'OBSERVING',browserClosed:true,contextClosed:true}))]){
    const f=fixture();f.deps.runBrowser=async()=>payload;
    const result=await runFormLoadDiagnostic(options,limits(),f.deps);
    assert.equal(result.exitCode,1);assert.equal(result.frames[0].version,2);
    assert.equal(result.frames[0].result,'INSTRUMENTATION_UNAVAILABLE');assert.equal(result.frames[0].environment_freshness,'UNAVAILABLE');
    assert.equal(JSON.stringify(result).includes('private-sentinel'),false);
  }
  assert.equal(reads,0);
});
test('cached or unavailable evidence cannot promote literal ready sample1 to success',async()=>{
  for(const environment of [{csp_observation:'UNAVAILABLE',csp_enforced_mask:0,csp_report_only_mask:0,
    resource_observation:'UNAVAILABLE',resource_transport_failure_mask:0,resource_http_4xx_mask:0,resource_http_5xx_mask:0,environment_freshness:'UNAVAILABLE'},
    {...observation(ready).environment,environment_freshness:'CACHED'},
    {...observation(ready).environment,resource_observation:'UNAVAILABLE'}]){
    const f=fixture();f.deps.runBrowser=async()=>({observations:[{sample:ready,environment}],outcome:'OBSERVING',browserClosed:true,contextClosed:true});
    const result=await runFormLoadDiagnostic(options,limits(),f.deps);
    assert.equal(result.exitCode,1);assert.equal(result.frames[0].result,'INSTRUMENTATION_UNAVAILABLE');
    assert.equal(Object.hasOwn(result.frames[0],'bucket'),false);
  }
});
function fixture() {
  const calls=[],handoff=Object.freeze({private:'transient'});
  const runtime={prepare:async l=>{calls.push(['prepare',l]);return handoff;},verifyNoConfirmation:async l=>{calls.push(['proof',l]);return true;},close:async l=>{calls.push(['close',l]);return true;}};
  const deps={createRuntime:async()=>{calls.push(['factory']);return runtime;},runBrowser:async(h,l)=>{assert.equal(h,handoff);calls.push(['browser',l]);return {observations:[observation(ready)],outcome:'OBSERVING',browserClosed:true,contextClosed:true};}};
  return {calls,runtime,deps};
}
test('only complete genuine readiness plus independent final proof and cleanup can exit0',async()=>{
  const f=fixture(),original=limits(),result=await runFormLoadDiagnostic(options,original,f.deps);
  assert.equal(result.exitCode,0);assert.equal(result.frames.length,1);assert.equal(result.frames[0].result,'FORM_READY_BEFORE_15S');
  assert.equal(result.frames[0].no_confirmation,true);assert.equal(result.frames[0].runtime_closed,true);
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
  const g=fixture();g.deps.runBrowser=async()=>({observations:[observation(ready)],outcome:'ABORTED',browserClosed:true,contextClosed:true});
  const aborted=await runFormLoadDiagnostic(options,limits(),g.deps);assert.equal(aborted.exitCode,1);assert.equal(aborted.frames[0].result,'ABORTED');
});
test('browser/proof/runtime uncertainty can never be called acceptance or renewed',async()=>{
  const f=fixture();f.runtime.close=async()=>{throw null;};
  const result=await runFormLoadDiagnostic(options,limits(),f.deps);assert.equal(result.exitCode,1);assert.equal(result.frames[0].runtime_closed,false);
  assert.equal(JSON.stringify(result).includes('transient'),false);assert.equal(Object.hasOwn(result.frames[0],'accepted'),false);
});
test('expired original active window is reported as deadline and never renewed for final read',async t=>{
  t.mock.timers.enable({apis:['Date'],now:1000000});
  const f=fixture(),original=limits();f.deps.runBrowser=async()=>{
    t.mock.timers.setTime(original.deadline);return {observations:[observation(ready)],outcome:'OBSERVING',browserClosed:true,contextClosed:true};
  };
  const result=await runFormLoadDiagnostic(options,original,f.deps);assert.equal(result.exitCode,1);
  assert.equal(result.frames[0].result,'DEADLINE_REACHED');assert.equal(f.calls.some(call=>call[0]==='proof'),false);
  assert.equal(f.calls.at(-1)[1].deadline,original.deadline+120000);
});
test('malformed/accessor browser result fails closed without reading private payload',async()=>{
  const f=fixture();let reads=0;f.deps.runBrowser=async()=>Object.defineProperty({observations:[observation(ready)],outcome:'OBSERVING',browserClosed:true},'contextClosed',{get(){reads++;throw null;},enumerable:true});
  const result=await runFormLoadDiagnostic(options,limits(),f.deps);assert.equal(reads,0);assert.equal(result.exitCode,1);
  assert.equal(result.frames[0].result,'INSTRUMENTATION_UNAVAILABLE');assert.equal(Object.hasOwn(result.frames[0],'bucket'),false);
  const g=fixture();g.deps.runBrowser=async()=>({observations:[observation(ready),observation(ready),observation(ready)],outcome:'OBSERVING',browserClosed:true,contextClosed:true});
  assert.equal((await runFormLoadDiagnostic(options,limits(),g.deps)).exitCode,1);
});
for(const trigger of ['abort','deadline'])for(const primary of ['OBSERVING','FAILED'])for(const settlement of ['rejection','late success']){
  test(`${trigger} during final verification ${settlement} retains original control cause or earlier ${primary} failure`,async t=>{
    t.mock.timers.enable({apis:['Date'],now:1000000});
    const f=fixture(),controller=new AbortController(),original={deadline:Date.now()+900000,signal:controller.signal};let reads=0;
    f.deps.runBrowser=async()=>({observations:[observation(ready)],outcome:primary,browserClosed:true,contextClosed:true});
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
  f.deps.runBrowser=async()=>({observations:[observation(checkpoint),{sample:{...checkpoint,bucket:'AT_LIMIT'},environment:{...environment(),environment_freshness:'CACHED'}}],outcome:'FAILED',browserClosed:true,contextClosed:true});
  const result=await runFormLoadDiagnostic(options,limits(),f.deps);
  assert.deepEqual(result.frames.map(frame=>frame.result),['WAITING_ELEMENT_READY','OPERATION_FAILED']);
  assert.equal(result.frames[0].bucket,'AFTER_15S');assert.equal(Object.hasOwn(result.frames[1],'bucket'),false);
  assert.deepEqual(result.frames.map(frame=>frame.environment_freshness),['SAMPLED','CACHED']);
  assert.equal(result.exitCode,1);
});
