import test from 'node:test';
import assert from 'node:assert/strict';
const subject=await import('./stripe-manual-form-evidence.mjs').catch(e=>{if(e.code==='ERR_MODULE_NOT_FOUND')return {};throw e;});
const evidence=()=>({loaderstart_seen:false,host_box:'ABSENT',frame_box:'ABSENT',host_visibility:'ABSENT',frame_visibility:'ABSENT',frame_load:'NO_FRAME',csp_observation:'OBSERVED',csp_enforced_mask:0,csp_report_only_mask:0});
const sample={version:1,binding:'ARMED',capability_started:false,capability_ready:false,loader_started:false,loader_ready:false,element_created:false,mount_called:false,mount_returned:false,ready_seen:false,loaderror_seen:false,view_completed:false,widget_completed:false,confirmation_blocked:false,catchup:'NOT_STARTED',operation:'PENDING',widget_state:'idle',element_state:'NO_HOST',pay_state:'ABSENT',pay_visible:false,bucket:'AFTER_15S'};
const observation=()=>({version:2,sample:{...sample},evidence:evidence()});
test('finite manual evidence rejects unsafe descriptors, unknown keys and invalid enum/mask combinations',()=>{
  assert.equal(typeof subject.validateManualFormEvidence,'function');
  assert.deepEqual(subject.validateManualFormEvidence(evidence()),evidence());
  let reads=0;const accessor=evidence();Object.defineProperty(accessor,'loaderstart_seen',{enumerable:true,get(){reads++;throw null;}});
  for(const value of [accessor,{...evidence(),[Symbol('x')]:true},{...evidence(),extra:1},{...evidence(),host_box:'MULTIPLE'},{...evidence(),frame_box:'PRIVATE'},
    {...evidence(),loaderstart_seen:1},{...evidence(),csp_enforced_mask:16},{...evidence(),csp_report_only_mask:-1},{...evidence(),csp_enforced_mask:1.5},
    {...evidence(),csp_observation:'UNAVAILABLE',csp_enforced_mask:1},{...evidence(),csp_observation:'UNAVAILABLE',csp_report_only_mask:2}])assert.equal(subject.validateManualFormEvidence(value),null);
  assert.equal(reads,0);assert.deepEqual(subject.unavailableManualFormEvidence(),{loaderstart_seen:false,host_box:'UNAVAILABLE',frame_box:'UNAVAILABLE',host_visibility:'UNAVAILABLE',frame_visibility:'UNAVAILABLE',frame_load:'UNAVAILABLE',csp_observation:'UNAVAILABLE',csp_enforced_mask:0,csp_report_only_mask:0});
});
test('manual v2 observation validates snapshots without getters and rejects duplicate decoded keys at every depth',()=>{
  assert.equal(typeof subject.validateManualFormObservation,'function');assert.equal(typeof subject.parseManualFormObservation,'function');
  const value=observation(),wire=JSON.stringify(value);assert.deepEqual(subject.parseManualFormObservation(wire),value);
  for(const bad of [{...value,version:1},sample,{...value,evidence:null},{...value,sample:null},{...value,extra:true}])assert.equal(subject.validateManualFormObservation(bad),null);
  let reads=0;for(const field of ['sample','evidence']){const bad=observation();Object.defineProperty(bad,field,{enumerable:true,get(){reads++;throw null;}});assert.equal(subject.validateManualFormObservation(bad),null);}assert.equal(reads,0);
  for(const bad of [wire.replace('"version":2','"version":2,"ver\\u0073ion":2'),wire.replace('"sample":{','"sample":{"version":1,'),wire.replace('"evidence":{','"evidence":{"loaderstart_seen":false,'),wire.replace('"evidence":{','"evidence":{"loaderstart_\\u0073een":false,'),' '.repeat(4097),wire+' '.repeat(4096)])assert.equal(subject.parseManualFormObservation(bad),null);
  assert.ok(Object.isFrozen(subject.validateManualFormObservation(value).evidence));
});
