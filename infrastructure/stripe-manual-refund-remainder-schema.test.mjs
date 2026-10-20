import test from 'node:test';
import assert from 'node:assert/strict';
import {validateManualRefundRemainderReport,formatManualRefundRemainderReport} from './stripe-manual-refund-remainder-schema.mjs';

const succeeded={version:1,mode:'MANUAL_REFUND_REMAINDER',result:'SUCCEEDED',phase:'CLEANUP',target_verified:true,prior_partial_verified:true,prior_callback_delivery_verified:true,callback_rotated:true,remainder_confirmed:true,provider_verified:true,webhook_verified:true,journal_verified:true,callback_signature_verified:true,final_total_verified:true,listener_closed:true,runtime_closed:true,provenance:'CONTROLLER_REPORTED'};
test('report differentiates freshly verified prior delivery from new signature proof',()=>{
  assert.deepEqual(validateManualRefundRemainderReport(succeeded),succeeded);
  for(const field of ['target_verified','prior_partial_verified','prior_callback_delivery_verified','callback_rotated','remainder_confirmed','provider_verified','webhook_verified','journal_verified','callback_signature_verified','final_total_verified','listener_closed','runtime_closed'])assert.equal(validateManualRefundRemainderReport({...succeeded,[field]:false}),null,field);
  for(const unknown of ['partial_confirmed','callback_verified','error','targetId'])assert.equal(validateManualRefundRemainderReport({...succeeded,[unknown]:'private_canary'}),null);
  assert.equal(formatManualRefundRemainderReport(succeeded),JSON.stringify(succeeded)+'\n');
  assert.ok(Buffer.byteLength(formatManualRefundRemainderReport(succeeded))<=4096);
});
test('pending proofs obey causal dependencies without asserting absence of side effects',()=>{
  const pending={...succeeded,result:'PENDING',phase:'REMAINDER',remainder_confirmed:false,final_total_verified:false};
  assert.ok(validateManualRefundRemainderReport(pending));
  for(const field of ['target_verified','prior_partial_verified','prior_callback_delivery_verified'])assert.equal(validateManualRefundRemainderReport({...pending,[field]:false}),null);
  assert.equal(validateManualRefundRemainderReport({...succeeded,result:'FAILED'}),null);
  assert.equal(validateManualRefundRemainderReport({...pending,callback_rotated:false}),null);
});
test('schema copies only finite own data without getters, prototypes, symbols or raw failures',()=>{
  let reads=0;
  const diagnostic={stage:'DETAIL',reason:'INTEGRITY',probes:2};
  const valid=validateManualRefundRemainderReport({...succeeded,result:'PENDING',diagnostic});
  diagnostic.reason='private_canary';assert.deepEqual(valid.diagnostic,{stage:'DETAIL',reason:'INTEGRITY',probes:2});assert.ok(Object.isFrozen(valid)&&Object.isFrozen(valid.diagnostic));
  const accessor={...succeeded};Object.defineProperty(accessor,'runtime_closed',{enumerable:true,get(){reads++;throw null;}});
  const nested={stage:'DETAIL',probes:1};Object.defineProperty(nested,'reason',{enumerable:true,get(){reads++;throw null;}});
  for(const value of [accessor,Object.assign(Object.create({}),succeeded),{...succeeded,[Symbol('secret')]:true},{...succeeded,result:'PENDING',diagnostic:nested},{...succeeded,result:'PENDING',diagnostic:{stage:'DETAIL',reason:'PRIVATE',probes:1}},{...succeeded,diagnostic:{stage:'DETAIL',reason:'INTEGRITY',probes:1}}])assert.equal(validateManualRefundRemainderReport(value),null);
  assert.equal(reads,0);
});
