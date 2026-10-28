import {test} from 'node:test';
import assert from 'node:assert/strict';
const module=await import('./stripe-manual-payment-schema.mjs').catch(()=>({}));
const {validateManualPaymentReport=()=>null,formatManualPaymentReport=()=>null,parseManualPaymentReport=()=>null}=module;
export const allTrueSuccess={version:1,mode:'MANUAL_PAYMENT',result:'SUCCEEDED',phase:'SUCCEEDED',confirmation_granted:true,payment_confirmed:true,provider_confirmed:true,webhook_verified:true,journal_verified:true,callback_verified:true,gateway_closed:true,listener_closed:true,runtime_closed:true,browser_closure:'NOT_OWNED',provenance:'CONTROLLER_REPORTED'};
test('accepts complete verified success and round trips the finite wire',()=>{
  assert.equal(validateManualPaymentReport(allTrueSuccess)?.result,'SUCCEEDED');
  assert.deepEqual(parseManualPaymentReport(formatManualPaymentReport(allTrueSuccess)),allTrueSuccess);
});
test('success requires each independent payment and cleanup proof',()=>{
  assert.equal(validateManualPaymentReport({...allTrueSuccess,webhook_verified:false}),null);
  assert.equal(validateManualPaymentReport({...allTrueSuccess,runtime_closed:false}),null);
  for(const key of Object.keys(allTrueSuccess).filter(key=>typeof allTrueSuccess[key]==='boolean'))assert.equal(validateManualPaymentReport({...allTrueSuccess,[key]:false}),null,key);
});
test('report rejects impossible success/unused matrices and nondata or extra fields',()=>{
  for(const result of ['NOT_STARTED','FAILED'])assert.equal(validateManualPaymentReport({...allTrueSuccess,result}),null);
  assert.equal(validateManualPaymentReport({...allTrueSuccess,phase:'VERIFY'}),null);
  assert.equal(validateManualPaymentReport({...allTrueSuccess,secret:'sk_test_canary'}),null);
  let reads=0;const accessor={...allTrueSuccess};Object.defineProperty(accessor,'result',{get(){reads++;return 'SUCCEEDED';},enumerable:true});
  assert.equal(validateManualPaymentReport(accessor),null);assert.equal(reads,0);
});
test('wire rejects duplicate decoded keys, extra lines, oversized and canary values',()=>{
  const wire=JSON.stringify(allTrueSuccess)+'\n';
  for(const invalid of [wire.replace('"version":1','"version":1,"version":1'),wire.replace('"version":1','"version":1,"\\u0076ersion":1'),wire+wire,' '.repeat(4097)+wire,wire.replace('CONTROLLER_REPORTED','sk_test_canary')])assert.equal(parseManualPaymentReport(invalid),null);
});
// Break: accepting success without 3DS proof, mixed report modes or hostile fields.
test('manual 3DS report requires finite mode-specific and consistent evidence',()=>{
  const success={...allTrueSuccess,mode:'MANUAL_3DS',three_ds_verified:true};
  assert.deepEqual(validateManualPaymentReport(success),success);
  const wire=formatManualPaymentReport(success);assert.equal(typeof wire,'string');assert.deepEqual(parseManualPaymentReport(wire),success);
  for(const key of Object.keys(success).filter(key=>typeof success[key]==='boolean'))assert.equal(validateManualPaymentReport({...success,[key]:false}),null,key);
  const pending={...success,result:'PENDING',phase:'VERIFY',provider_confirmed:false,three_ds_verified:false};assert.deepEqual(validateManualPaymentReport(pending),pending);
  const unused={...success,result:'NOT_STARTED',phase:'VERIFY',confirmation_granted:false,payment_confirmed:false,provider_confirmed:false,webhook_verified:false,journal_verified:false,callback_verified:false,three_ds_verified:false};assert.deepEqual(validateManualPaymentReport(unused),unused);
  for(const invalid of [{...allTrueSuccess,three_ds_verified:true},{...success,mode:'MANUAL_PAYMENT'},{...success,extra:true},{...pending,three_ds_verified:true},{...unused,three_ds_verified:true},{...success,three_ds_verified:'true'}])assert.equal(validateManualPaymentReport(invalid),null);
  const missing={...success};delete missing.three_ds_verified;assert.equal(validateManualPaymentReport(missing),null);
  let reads=0;const hostile={...success};Object.defineProperty(hostile,'three_ds_verified',{enumerable:true,get(){reads++;return true;}});assert.equal(validateManualPaymentReport(hostile),null);assert.equal(reads,0);
  for(const bad of [wire.replace('"three_ds_verified":true','"three_ds_verified":true,"three_ds_verified":true'),wire.replace('"three_ds_verified":true','"three_ds_verified":true,"\\u0074hree_ds_verified":true'),wire+wire,' '.repeat(4097)+wire])assert.equal(parseManualPaymentReport(bad),null);
});
