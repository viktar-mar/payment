import test from 'node:test';
import assert from 'node:assert/strict';

const schema = await import('./stripe-form-diagnostic-schema.mjs').catch(() => ({}));
const {validateFormDiagnosticSample: validate, classifyFormDiagnosticSample: classify,
  buildFormDiagnosticFrame: build, formatFormDiagnosticFrame: format, parseFormDiagnosticFrame: parse} = schema;

const ready = () => ({version:1,binding:'BOUND',capability_started:true,capability_ready:true,
  loader_started:true,loader_ready:true,element_created:true,mount_called:true,mount_returned:true,
  ready_seen:true,loaderror_seen:false,view_completed:true,widget_completed:true,confirmation_blocked:false,
  catchup:'RESOLVED',operation:'COMPLETED',widget_state:'ready',element_state:'FRAME',pay_state:'ENABLED',
  pay_visible:true,bucket:'BEFORE_15S'});
const proofs = {no_confirmation:true,browser_closed:true,context_closed:true,runtime_closed:true};
const environment=()=>({csp_observation:'OBSERVED',csp_enforced_mask:0,csp_report_only_mask:0,
  resource_observation:'OBSERVED',resource_transport_failure_mask:0,resource_http_4xx_mask:0,
  resource_http_5xx_mask:0,fetch_failure_destination:'NONE',fetch_failure_code:'NONE',environment_freshness:'SAMPLED'});
// Break caught: wire version and terminal provenance omitted or mislabelled.
test('wire3 keeps observation end independent from a dominant failure',()=>{
  const frame=build(ready(),'FAILED',proofs,environment(),'FORM_READY');
  assert.equal(frame.version,3);assert.equal(frame.observation_end,'FORM_READY');assert.equal(frame.result,'OPERATION_FAILED');
  assert.equal(build(ready(),'OBSERVING',proofs,environment(),'invalid').result,'INSTRUMENTATION_UNAVAILABLE');
  assert.equal(Object.hasOwn(build(ready(),'OBSERVING',proofs,environment(),'invalid'),'bucket'),false);
});
// Break caught: mismatched stage/end values and legacy/mixed protocols accepted by the consumer.
test('wire3 rejects legacy versions and incompatible or malformed observation ends',()=>{
  const frame=build(ready(),'OBSERVING',proofs,environment(),'FORM_READY');
  for(const version of [1,2])assert.equal(parse(JSON.stringify({...frame,version})+'\n'),null);
  for(const reason of ['OBSERVING','LOCAL_LIMIT','invalid',null,undefined])assert.equal(format({...frame,observation_end:reason}),null);
  const missing={...frame};delete missing.observation_end;assert.equal(format(missing),null);
  let reads=0;const accessor={...frame};Object.defineProperty(accessor,'observation_end',{enumerable:true,get(){reads++;throw null;}});
  assert.equal(format(accessor),null);assert.equal(reads,0);
  const pending={...ready(),ready_seen:false,view_completed:false,widget_completed:false,catchup:'NOT_STARTED',operation:'PENDING'};
  const checkpoint=build(pending,'OBSERVING',proofs,environment(),'OBSERVING');
  assert.equal(checkpoint.result,'WAITING_ELEMENT_READY');assert.ok(format(checkpoint));assert.equal(format({...checkpoint,observation_end:'LOCAL_LIMIT'}),null);
  for(const end of ['FORM_READY','SDK_TERMINAL','LOCAL_LIMIT','ABORTED','ACTIVE_DEADLINE','INSTRUMENTATION_UNAVAILABLE','BROWSER_OPERATION_FAILED','BROWSER_NOT_STARTED'])
    assert.ok(format(build(ready(),'FAILED',proofs,environment(),end)));
  const invalid=build(ready(),'FAILED',proofs,environment(),'invalid');
  assert.equal(invalid.result,'OPERATION_FAILED');assert.equal(invalid.observation_end,'INSTRUMENTATION_UNAVAILABLE');
  assert.equal(Object.hasOwn(invalid,'bucket'),false);
});
// Break caught: terminal SDK error is allowed to masquerade as an unfinished checkpoint.
test('element loaderror uses terminal SDK provenance and invalid end compatibility removes stage',()=>{
  const sample={...ready(),loaderror_seen:true};
  const terminal=build(sample,'OBSERVING',proofs,environment(),'SDK_TERMINAL');
  assert.equal(terminal.result,'ELEMENT_LOADERROR');assert.equal(terminal.observation_end,'SDK_TERMINAL');
  assert.ok(format(terminal));assert.equal(format({...terminal,observation_end:'OBSERVING'}),null);
  const invalid=build(ready(),'OBSERVING',proofs,environment(),'OBSERVING');
  assert.equal(invalid.result,'INSTRUMENTATION_UNAVAILABLE');assert.equal(Object.hasOwn(invalid,'bucket'),false);
  const incompatible=build(ready(),'OBSERVING',proofs,environment(),'LOCAL_LIMIT');
  assert.equal(incompatible.observation_end,'INSTRUMENTATION_UNAVAILABLE');assert.equal(Object.hasOwn(incompatible,'bucket'),false);
});

// Break: sample v1 accidentally becomes a v1 wire success, or absent evidence fabricates readiness.
test('literal sample1 needs sampled independent environment and emits wire3 only',()=>{
  const sample=ready();assert.equal(validate(sample).version,1);
  const frame=build(sample,'OBSERVING',proofs,environment(),'FORM_READY');
  assert.equal(frame.version,3);assert.equal(frame.result,'FORM_READY_BEFORE_15S');
  assert.equal(frame.environment_freshness,'SAMPLED');assert.deepEqual(parse(format(frame)),frame);
  assert.equal(build(sample,'OBSERVING',proofs,{...environment(),csp_enforced_mask:1},'FORM_READY').result,'FORM_READY_BEFORE_15S');
  for(const env of [undefined,{...environment(),environment_freshness:'CACHED'},
    {...environment(),csp_observation:'UNAVAILABLE'}]){
    const unavailable=build(sample,'OBSERVING',proofs,env,'FORM_READY');
    assert.equal(unavailable.result,'INSTRUMENTATION_UNAVAILABLE');assert.equal(Object.hasOwn(unavailable,'bucket'),false);
  }
  assert.equal(parse(JSON.stringify({...frame,version:1})+'\n'),null);
});

// Break: observation accessors or an obsolete wire sample transports private data.
test('composite boundary freezes independent copies and rejects accessors and invalid sample versions',()=>{
  const validateObservation=schema.validateFormDiagnosticObservation;
  assert.equal(typeof validateObservation,'function');
  const original={sample:ready(),environment:environment()},copy=validateObservation(original);
  assert.deepEqual(copy,original);assert.ok(Object.isFrozen(copy));assert.ok(Object.isFrozen(copy.sample));
  assert.ok(Object.isFrozen(copy.environment));assert.notEqual(copy.environment,original.environment);
  let reads=0;const accessor={sample:ready()};Object.defineProperty(accessor,'environment',{enumerable:true,get(){reads++;throw null;}});
  for(const value of [accessor,{...original,extra:'private-sentinel'},{...original,sample:{...ready(),version:2}},
    {...original,environment:{...environment(),resource_http_4xx_mask:NaN}},{...original,sample:undefined}])assert.equal(validateObservation(value),null);
  assert.equal(reads,0);assert.equal(validateObservation({...original,sample:null}).sample,null);
  const frame=build(ready(),'OBSERVING',proofs,environment(),'FORM_READY'),hidden={...frame};Object.defineProperty(hidden,'private',{value:'private-sentinel'});
  const envAccessor={...frame};Object.defineProperty(envAccessor,'csp_enforced_mask',{enumerable:true,get(){reads++;throw null;}});
  assert.equal(format(hidden),null);assert.equal(format(envAccessor),null);assert.equal(reads,0);
});

// Break: absent API cannot enforce the public privacy boundary.
test('schema exposes a validator, classifier and bounded wire boundary', () => {
  for (const name of ['validateFormDiagnosticSample','classifyFormDiagnosticSample','buildFormDiagnosticFrame','formatFormDiagnosticFrame','parseFormDiagnosticFrame'])
    assert.equal(typeof schema[name], 'function');
});

// Break: false readiness from visibility, completion, binding or mount inference.
test('readiness requires independent completed mount, current binding and visible enabled Pay', () => {
  assert.equal(classify(ready()), 'FORM_READY_BEFORE_15S');
  assert.equal(classify({...ready(),bucket:'AFTER_15S'}), 'FORM_READY_AFTER_15S');
  for (const change of [{pay_visible:false},{pay_state:'HIDDEN'},{widget_completed:false},{operation:'PENDING'}])
    assert.notEqual(classify({...ready(),...change}), 'FORM_READY_BEFORE_15S');
  assert.equal(classify({...ready(),binding:'LOST'}), 'BINDING_LOST');
  assert.equal(classify({...ready(),mount_returned:false}), 'INSTRUMENTATION_UNAVAILABLE');
});

// Break: failures incorrectly classified as a pending stage or late readiness.
test('opaque outcomes and latched failures dominate every ready flag', () => {
  for (const [outcome,want] of [['FAILED','OPERATION_FAILED'],['ABORTED','ABORTED'],['DEADLINE_REACHED','DEADLINE_REACHED'],
    ['INSTRUMENTATION_UNAVAILABLE','INSTRUMENTATION_UNAVAILABLE'],['UNEXPECTED_ACTIVITY','UNEXPECTED_ACTIVITY']])
    assert.equal(classify(ready(),outcome),want);
  assert.equal(classify({...ready(),confirmation_blocked:true}), 'CONFIRMATION_BLOCKED');
  assert.equal(classify({...ready(),operation:'FAILED'}), 'OPERATION_FAILED');
  assert.equal(classify({...ready(),operation:'ABORTED'}), 'ABORTED');
  assert.equal(classify({...ready(),catchup:'REJECTED'}), 'OPERATION_FAILED');
  assert.equal(classify({...ready(),catchup:'ABORTED'}), 'ABORTED');
  assert.equal(classify(ready(),new Error('private-sentinel')), 'INSTRUMENTATION_UNAVAILABLE');
});

// Break: iframe alone masks ready waiting, catch-up waiting, or incomplete setup.
test('pending stages distinguish capability, loader, ready, catch-up and handoff', () => {
  const base = {...ready(),operation:'PENDING',widget_state:'loading',pay_state:'ABSENT',pay_visible:false,
    view_completed:false,widget_completed:false,ready_seen:false,catchup:'NOT_STARTED',bucket:'AT_LIMIT'};
  assert.equal(classify(base),'WAITING_ELEMENT_READY');
  assert.equal(classify({...base,ready_seen:true,catchup:'PENDING'}),'WAITING_PRESENTATION_CATCHUP');
  assert.equal(classify({...base,ready_seen:true,catchup:'RESOLVED',view_completed:true}),'WIDGET_HANDOFF_INCOMPLETE');
  assert.equal(classify({...base,element_created:false,mount_called:false,mount_returned:false}),'ELEMENT_SETUP_INCOMPLETE');
  assert.equal(classify({...base,loader_ready:false,element_created:false,mount_called:false,mount_returned:false}),'WAITING_STRIPE_LOADER');
  assert.equal(classify({...base,capability_ready:false,loader_started:false,loader_ready:false,element_created:false,mount_called:false,mount_returned:false}),'WAITING_CLIENT_SESSION');
  assert.equal(classify({...base,loaderror_seen:true}),'ELEMENT_LOADERROR');
  assert.equal(classify({...base,operation:'COMPLETED'}),'INSTRUMENTATION_UNAVAILABLE');
  assert.equal(classify({...ready(),pay_visible:false}),'WIDGET_HANDOFF_INCOMPLETE');
  assert.equal(classify({...ready(),pay_state:'DISABLED'}),'WIDGET_HANDOFF_INCOMPLETE');
});

// Break: unknown fields or getters transport private provider data.
test('validation returns a frozen scalar copy and rejects malformed hostile inputs without getters', () => {
  const original=ready(),copy=validate(original);
  assert.deepEqual(copy,original);assert.notEqual(copy,original);assert.ok(Object.isFrozen(copy));
  let reads=0;
  const accessor={...ready()};Object.defineProperty(accessor,'pay_visible',{get(){reads++;throw null;}});
  const hidden={...ready()};Object.defineProperty(hidden,'private',{value:'private-sentinel'});
  for (const value of [null,[],new Date(),accessor,hidden,{...ready(),extra:'private-sentinel'},
    {...ready(),version:2},{...ready(),pay_visible:'true'},{...ready(),widget_state:'READY'},
    {...ready(),binding:{}},{...ready(),[Symbol('private')]:true},new Proxy({},{ownKeys(){throw null;}})])
    assert.equal(validate(value),null);
  assert.equal(reads,0);
});

// Break: serializer invokes toJSON or fabricates observations on missing samples.
test('builder uses exact sampled or opaque-failure shape with independently validated proofs', () => {
  const frame=build(ready(),'OBSERVING',proofs,environment(),'FORM_READY');
  assert.ok(Object.isFrozen(frame));assert.equal(frame.result,'FORM_READY_BEFORE_15S');
  assert.deepEqual(build(null,'FAILED',proofs,environment(),'FORM_READY'),{version:3,result:'OPERATION_FAILED',...proofs,...environment(),observation_end:'FORM_READY'});
  assert.deepEqual(build(null,'OBSERVING',proofs,environment(),'FORM_READY'),{version:3,result:'INSTRUMENTATION_UNAVAILABLE',...proofs,...environment(),observation_end:'FORM_READY'});
  assert.equal(build(ready(),'OBSERVING',{...proofs,no_confirmation:'true'},environment(),'FORM_READY').no_confirmation,false);
  assert.equal(format({...frame,toJSON(){throw Error('private-sentinel');}}),null);
  assert.equal(format({...frame,result:'WAITING_CLIENT_SESSION'}),null);
  assert.equal(format({version:1,result:'FORM_READY_BEFORE_15S',...proofs}),null);
});

// Break: ambiguous duplicate keys, escaped duplicates and trailing bytes bypass validation.
test('wire parser accepts only one bounded flat finite JSON frame without duplicate keys', () => {
  const frame=build(ready(),'OBSERVING',proofs,environment(),'FORM_READY'),wire=format(frame);
  assert.ok(wire.endsWith('\n'));assert.ok(Buffer.byteLength(wire)<=4096);
  assert.deepEqual(parse(wire),frame);
  const body=wire.trimEnd();
  assert.equal(parse(body),null);
  for (const value of [body.slice(0,-1),body+'\n'+body,body.replace('"version":3','"version":3,"version":1'),
    body.replace('"version":3','"version":3,"\\u0076ersion":1'),body.replace('"version":3','"version":3,"secret":"private-sentinel"'),
    ' '.repeat(4097)+body,'null','[]','{"version":1}',body+'private-sentinel']) assert.equal(parse(value+'\n'),null);
  assert.equal(parse(new Proxy({},{get(){throw null;}})),null);
  assert.equal(format(new Proxy({},{ownKeys(){throw null;}})),null);
});
