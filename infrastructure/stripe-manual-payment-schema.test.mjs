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
