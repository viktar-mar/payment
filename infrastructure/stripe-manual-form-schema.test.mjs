import test from 'node:test';
import assert from 'node:assert/strict';
const subject=await import('./stripe-manual-form-schema.mjs').catch(error=>{if(error.code==='ERR_MODULE_NOT_FOUND')return {};throw error;});
const ready={version:1,binding:'BOUND',capability_started:true,capability_ready:true,loader_started:true,loader_ready:true,
  element_created:true,mount_called:true,mount_returned:true,ready_seen:true,loaderror_seen:false,view_completed:true,widget_completed:true,
  confirmation_blocked:false,catchup:'NOT_STARTED',operation:'COMPLETED',widget_state:'ready',element_state:'FRAME',pay_state:'ENABLED',pay_visible:true,bucket:'BEFORE_15S'};
const pending={...ready,ready_seen:false,view_completed:false,widget_completed:false,operation:'PENDING',widget_state:'loading',pay_state:'ABSENT',pay_visible:false,bucket:'AFTER_15S'};
const report=patch=>({version:1,mode:'MANUAL_FORM_DIAGNOSTIC',observation:'READY_REPORTED',end:'READY_REPORTED',sample:ready,
  no_confirmation:true,gateway_closed:true,runtime_closed:true,browser_closure:'NOT_OWNED',provenance:'BROWSER_REPORTED',...patch});
function validate(value){assert.equal(typeof subject.validateManualFormReport,'function');return subject.validateManualFormReport(value);}
// Break: promoting browser-only readiness without owned proofs, or losing historical readiness on failure.
test('only coherent non-late ready plus all three proofs permits a successful end',()=>{
  assert.deepEqual(validate(report()),report());
  for(const key of ['no_confirmation','gateway_closed','runtime_closed']){
    assert.equal(validate(report({[key]:false})),null,key);
    assert.ok(validate(report({[key]:false,end:'FAILED'})),key+' failed history');
  }
  for(const sample of [null,{...ready,bucket:'AT_LIMIT'},{...ready,pay_visible:false},{...ready,loader_started:false},{...ready,confirmation_blocked:true}])
    assert.equal(validate(report({sample})),null);
  assert.ok(validate(report({end:'FAILED'})));
});
// Break: accepting invented observation/end pairs or reclassifying unsupported samples as ready.
test('literal gateway observation mapping and dominant ends are enforced',()=>{
  const rows=[['NOT_STARTED',null,['NOT_STARTED','ABORTED','ACTIVE_DEADLINE','FAILED']],
    ['NO_REPORT',null,['LOCAL_LIMIT','ABORTED','ACTIVE_DEADLINE','FAILED']],['INVALID_REPORT',null,['FAILED']],
    ['READY_REPORTED',ready,['READY_REPORTED','ABORTED','ACTIVE_DEADLINE','FAILED']],
    ['PENDING_REPORTED',pending,['LOCAL_LIMIT','ABORTED','ACTIVE_DEADLINE','FAILED']],
    ['PENDING_REPORTED',{...pending,bucket:'AT_LIMIT'},['LOCAL_LIMIT','ABORTED','ACTIVE_DEADLINE','FAILED']],
    ['TERMINAL_REPORTED',{...ready,loaderror_seen:true},['FAILED']]];
  for(const [observation,sample,ends] of rows)for(const end of ['READY_REPORTED','LOCAL_LIMIT','NOT_STARTED','ABORTED','ACTIVE_DEADLINE','FAILED'])
    assert.equal(validate(report({observation,sample,end}))!==null,ends.includes(end),observation+'/'+end);
  assert.equal(validate(report({observation:'PENDING_REPORTED',sample:ready,end:'LOCAL_LIMIT'})),null);
  assert.equal(validate(report({observation:'PENDING_REPORTED',sample:{...pending,bucket:'BEFORE_15S'},end:'LOCAL_LIMIT'})),null);
  assert.equal(validate(report({observation:'TERMINAL_REPORTED',sample:pending,end:'FAILED'})),null);
});
// Break: leaking accessors, symbols, unknown closure claims or mixed automated wire data into the report.
test('finite exact data rejects accessors, symbols, extra fields and mixed versions without invoking getters',()=>{
  let reads=0;const getter=report();Object.defineProperty(getter,'sample',{enumerable:true,get(){reads++;throw null;}});
  const sampleGetter={...ready};Object.defineProperty(sampleGetter,'ready_seen',{enumerable:true,get(){reads++;throw null;}});
  for(const value of [getter,report({sample:sampleGetter}),report({[Symbol('secret')]:true}),report({private:'PRIVATE_CANARY'}),report({version:3}),
    report({mode:'FORM_DIAGNOSTIC'}),report({browser_closed:true}),report({browser_closure:'CLOSED'}),report({provenance:'INDEPENDENT'}),
    report({accepted:true}),report({no_confirmation:1}),Object.assign(Object.create({}),report())])assert.equal(validate(value),null);
  assert.equal(reads,0);
});
// Break: accepting duplicate encoded keys, oversized/multiline payloads, or invoking toJSON.
test('canonical finite wire roundtrips with a strict 4096-byte ceiling and no duplicate keys',()=>{
  assert.equal(typeof subject.formatManualFormReport,'function');assert.equal(typeof subject.parseManualFormReport,'function');
  const wire=subject.formatManualFormReport(report());assert.equal(wire,JSON.stringify(report())+'\n');
  assert.ok(Buffer.byteLength(wire)<=4096);assert.deepEqual(subject.parseManualFormReport(wire),report());
  for(const bad of [wire.trim(),wire+'\n',' '.repeat(4097),'{',wire.replace('"version":1','"version":1,"version":1'),
    wire.replace('"version":1','"version":1,"ver\\u0073ion":1'),wire.replace('"sample":{','"sample":{"version":1,'),wire.replace('MANUAL_FORM_DIAGNOSTIC','PRIVATE_CANARY')])
    assert.equal(subject.parseManualFormReport(bad),null);
  let reads=0;assert.equal(subject.formatManualFormReport(report({toJSON(){reads++;return {};}})),null);assert.equal(reads,0);
});
