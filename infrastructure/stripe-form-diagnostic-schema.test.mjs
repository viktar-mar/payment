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
  resource_http_5xx_mask:0,environment_freshness:'SAMPLED'});

// Break: sample v1 accidentally becomes a v1 wire success, or absent evidence fabricates readiness.
test('literal sample1 needs sampled independent environment and emits wire2 only',()=>{
  const sample=ready();assert.equal(validate(sample).version,1);
  const frame=build(sample,'OBSERVING',proofs,environment());
  assert.equal(frame.version,2);assert.equal(frame.result,'FORM_READY_BEFORE_15S');
  assert.equal(frame.environment_freshness,'SAMPLED');assert.deepEqual(parse(format(frame)),frame);
  assert.equal(build(sample,'OBSERVING',proofs,{...environment(),csp_enforced_mask:1}).result,'FORM_READY_BEFORE_15S');
  for(const env of [undefined,{...environment(),environment_freshness:'CACHED'},
    {...environment(),csp_observation:'UNAVAILABLE'}]){
    const unavailable=build(sample,'OBSERVING',proofs,env);
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
  const frame=build(ready(),'OBSERVING',proofs,environment()),hidden={...frame};Object.defineProperty(hidden,'private',{value:'private-sentinel'});
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
  const frame=build(ready(),'OBSERVING',proofs,environment());
  assert.ok(Object.isFrozen(frame));assert.equal(frame.result,'FORM_READY_BEFORE_15S');
  assert.deepEqual(build(null,'FAILED',proofs,environment()),{version:2,result:'OPERATION_FAILED',...proofs,...environment()});
  assert.deepEqual(build(null,'OBSERVING',proofs,environment()),{version:2,result:'INSTRUMENTATION_UNAVAILABLE',...proofs,...environment()});
  assert.equal(build(ready(),'OBSERVING',{...proofs,no_confirmation:'true'},environment()).no_confirmation,false);
  assert.equal(format({...frame,toJSON(){throw Error('private-sentinel');}}),null);
  assert.equal(format({...frame,result:'WAITING_CLIENT_SESSION'}),null);
  assert.equal(format({version:1,result:'FORM_READY_BEFORE_15S',...proofs}),null);
});

// Break: ambiguous duplicate keys, escaped duplicates and trailing bytes bypass validation.
test('wire parser accepts only one bounded flat finite JSON frame without duplicate keys', () => {
  const frame=build(ready(),'OBSERVING',proofs,environment()),wire=format(frame);
  assert.ok(wire.endsWith('\n'));assert.ok(Buffer.byteLength(wire)<=4096);
  assert.deepEqual(parse(wire),frame);
  const body=wire.trimEnd();
  assert.equal(parse(body),null);
  for (const value of [body.slice(0,-1),body+'\n'+body,body.replace('"version":2','"version":2,"version":1'),
    body.replace('"version":2','"version":2,"\\u0076ersion":1'),body.replace('"version":2','"version":2,"secret":"private-sentinel"'),
    ' '.repeat(4097)+body,'null','[]','{"version":1}',body+'private-sentinel']) assert.equal(parse(value+'\n'),null);
  assert.equal(parse(new Proxy({},{get(){throw null;}})),null);
  assert.equal(format(new Proxy({},{ownKeys(){throw null;}})),null);
});
