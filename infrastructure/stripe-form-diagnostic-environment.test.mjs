import test from 'node:test';
import assert from 'node:assert/strict';
import {validateFormEnvironmentEvidence,unavailableFormEnvironmentEvidence} from './stripe-form-diagnostic-environment.mjs';

const observed=()=>({csp_observation:'OBSERVED',csp_enforced_mask:1,csp_report_only_mask:4,
  resource_observation:'OBSERVED',resource_transport_failure_mask:2,resource_http_4xx_mask:1,
  resource_http_5xx_mask:4,fetch_failure_destination:'NONE',fetch_failure_code:'NONE',environment_freshness:'SAMPLED'});
// Break caught: transport categories absent from the exact environment boundary.
test('fetch tuple sentinels track availability and the fetch transport bit exactly',()=>{
  const none={...observed(),fetch_failure_destination:'NONE',fetch_failure_code:'NONE'};
  assert.deepEqual(validateFormEnvironmentEvidence(none),none);
  const tuple={...none,resource_transport_failure_mask:4,fetch_failure_destination:'STRIPE_API',fetch_failure_code:'NAME_NOT_RESOLVED'};
  assert.deepEqual(validateFormEnvironmentEvidence(tuple),tuple);
  for(const patch of [{fetch_failure_code:'NONE'},{fetch_failure_destination:'UNAVAILABLE'},{resource_transport_failure_mask:0}])
    assert.equal(validateFormEnvironmentEvidence({...tuple,...patch}),null);
  for(const destination of ['OWN_APP','STRIPE_API','STRIPE_JS','OTHER','UNKNOWN'])
    for(const code of ['ABORTED','NAME_NOT_RESOLVED','CONNECTION_REFUSED','CONNECTION_RESET','TIMED_OUT','CERTIFICATE','BLOCKED_BY_CLIENT','GENERIC_FAILED','UNKNOWN'])
      assert.ok(validateFormEnvironmentEvidence({...tuple,fetch_failure_destination:destination,fetch_failure_code:code}));
  for(const key of ['fetch_failure_destination','fetch_failure_code'])for(const value of ['invalid','NONE','UNAVAILABLE',null,undefined,{},1])
    assert.equal(validateFormEnvironmentEvidence({...tuple,[key]:value}),null);
});
// Break caught: accepted observations discarded or kept as mutable caller data.
test('validator accepts exact finite evidence and freezes an independent scalar copy',()=>{
  const input=observed(),copy=validateFormEnvironmentEvidence(input);
  assert.deepEqual(copy,observed());assert.ok(Object.isFrozen(copy));assert.notEqual(copy,input);
  input.csp_enforced_mask=15;assert.equal(copy.csp_enforced_mask,1);
  assert.deepEqual(validateFormEnvironmentEvidence({...observed(),environment_freshness:'CACHED'}),{...observed(),environment_freshness:'CACHED'});
});
// Break caught: unavailable and partially available sources conflated with observed zero.
test('source availability and freshness obey the exact cross-field contract',()=>{
  const unavailable={csp_observation:'UNAVAILABLE',csp_enforced_mask:0,csp_report_only_mask:0,
    resource_observation:'UNAVAILABLE',resource_transport_failure_mask:0,resource_http_4xx_mask:0,
    resource_http_5xx_mask:0,fetch_failure_destination:'UNAVAILABLE',fetch_failure_code:'UNAVAILABLE',environment_freshness:'UNAVAILABLE'};
  assert.deepEqual(unavailableFormEnvironmentEvidence(),unavailable);assert.ok(Object.isFrozen(unavailableFormEnvironmentEvidence()));
  assert.deepEqual(validateFormEnvironmentEvidence(unavailable),unavailable);
  for(const freshness of ['SAMPLED','CACHED']) {
    assert.equal(validateFormEnvironmentEvidence({...unavailable,environment_freshness:freshness}),null);
    for(const source of ['csp','resource']) {
      const partial={...unavailable,[source+'_observation']:'OBSERVED',environment_freshness:freshness,
        ...(source==='resource'?{fetch_failure_destination:'NONE',fetch_failure_code:'NONE'}:{})};
      assert.deepEqual(validateFormEnvironmentEvidence(partial),partial);
    }
  }
  assert.equal(validateFormEnvironmentEvidence({...observed(),environment_freshness:'UNAVAILABLE'}),null);
  assert.equal(validateFormEnvironmentEvidence({...observed(),csp_observation:'UNAVAILABLE'}),null);
  assert.equal(validateFormEnvironmentEvidence({...observed(),resource_observation:'UNAVAILABLE'}),null);
});
// Break caught: invalid mask/domain or extra/missing/accessor data reaches the wire.
test('validator rejects malformed values without invoking accessors',()=>{
  const masks=['csp_enforced_mask','csp_report_only_mask','resource_transport_failure_mask','resource_http_4xx_mask','resource_http_5xx_mask'];
  for(const key of masks) {
    for(const value of [0,1,2,4,8,15])assert.ok(validateFormEnvironmentEvidence({...observed(),[key]:value,
      ...(key==='resource_transport_failure_mask'&&(value&4)?{fetch_failure_destination:'UNKNOWN',fetch_failure_code:'UNKNOWN'}:{})}));
    for(const value of [NaN,Infinity,-1,16,1.5,'1',null,undefined])assert.equal(validateFormEnvironmentEvidence({...observed(),[key]:value}),null);
  }
  for(const key of Object.keys(observed())) {
    const absent=observed();delete absent[key];assert.equal(validateFormEnvironmentEvidence(absent),null);
    let reads=0;const accessor=observed();Object.defineProperty(accessor,key,{enumerable:true,get(){reads++;throw new Error('private');}});
    assert.equal(validateFormEnvironmentEvidence(accessor),null);assert.equal(reads,0);
    const hidden=observed();Object.defineProperty(hidden,key,{enumerable:false});assert.equal(validateFormEnvironmentEvidence(hidden),null);
  }
  for(const value of [null,[],1,{...observed(),extra:0},{...observed(),[Symbol('extra')]:0},
    {...observed(),csp_observation:'MISSING'},{...observed(),resource_observation:'MISSING'},
    {...observed(),environment_freshness:'FRESH'},Object.create(observed()),new Proxy({},{ownKeys(){throw null;}})])
    assert.equal(validateFormEnvironmentEvidence(value),null);
});
