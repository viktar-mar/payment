import test from 'node:test';
import assert from 'node:assert/strict';
import {validateManualRefundReport,formatManualRefundReport} from './stripe-manual-refund-schema.mjs';
const report=()=>({version:1,mode:'MANUAL_REFUND',result:'PENDING',phase:'PARTIAL',target_verified:true,callback_rotated:true,partial_confirmed:false,remainder_confirmed:false,provider_verified:false,webhook_verified:false,journal_verified:false,callback_verified:false,listener_closed:false,runtime_closed:false,provenance:'CONTROLLER_REPORTED'});
// Break caught: treating absent proof as absent financial effect or leaking IDs/errors.
test('report preserves finite unverified evidence without inventing financial absence',()=>{
  const r=validateManualRefundReport(report());assert.ok(r);assert.equal(r.result,'PENDING');assert.equal(r.partial_confirmed,false);assert.ok(Object.isFrozen(r));
  const wire=formatManualRefundReport(report());assert.equal(wire,JSON.stringify(report())+'\n');assert.ok(Buffer.byteLength(wire)<=4096);
  for(const key of ['paymentId','error','secret','username','note'])assert.equal(validateManualRefundReport({...report(),[key]:'CREDENTIAL_CANARY'}),null);
  for(const value of [undefined,NaN,Infinity,0,'true',null])assert.equal(validateManualRefundReport({...report(),provider_verified:value}),null);
  let reads=0;const hostile=report();Object.defineProperty(hostile,'result',{get(){reads++;return 'PENDING';}});assert.equal(validateManualRefundReport(hostile),null);assert.equal(reads,0);
});
// Break caught: marking success or remainder before the required evidence exists.
test('success requires every proof and remainder cannot precede confirmed partial',()=>{
  const complete={...report(),result:'SUCCEEDED',phase:'VERIFY',partial_confirmed:true,remainder_confirmed:true,provider_verified:true,webhook_verified:true,journal_verified:true,callback_verified:true,listener_closed:true,runtime_closed:true};assert.ok(validateManualRefundReport(complete));
  for(const key of Object.keys(complete).filter(key=>typeof complete[key]==='boolean'))assert.equal(validateManualRefundReport({...complete,[key]:false}),null);
  assert.equal(validateManualRefundReport({...report(),phase:'REMAINDER'}),null);
  assert.equal(validateManualRefundReport({...report(),remainder_confirmed:true}),null);
  assert.equal(validateManualRefundReport({...report(),partial_confirmed:true,provider_verified:false}),null);
  assert.equal(validateManualRefundReport({...report(),result:'FAILED'}),null);
});
