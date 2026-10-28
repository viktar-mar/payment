import test from 'node:test';
import assert from 'node:assert/strict';
const load=()=>import('./stripe-manual-refund-remainder-options.mjs');
test('legacy exclusion checks only the raw own remainder flag on plain or exotic env objects',async()=>{
 const {manualRefundRemainderExcluded:excluded}=await load();
 for(const value of [undefined,'','false']){
  assert.equal(excluded({PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:value}),true);
  const exotic=Object.create({unrelated:'ignored'});exotic.PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER=value;
  assert.equal(excluded(exotic),true);
 }
 assert.equal(excluded({}),true);assert.equal(excluded(Object.create(null)),true);
 for(const value of ['true',true,false,null,0,'FALSE',{private:'secret_canary'}])assert.equal(excluded({PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:value}),false);
 for(const value of ['true','false',undefined])assert.equal(excluded(Object.create({PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:value})),false);
 for(const value of [null,undefined,'true',true])assert.equal(excluded(value),false);
 let reads=0;
 const accessor=Object.defineProperty({},'PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER',{enumerable:true,get(){reads++;throw 'secret_canary';}});
 assert.equal(excluded(accessor),false);
 assert.equal(excluded(Object.defineProperty({},'PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER',{value:'false'})),false);
 const proxy=new Proxy({PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:'false'},{get(){reads++;throw 'secret_canary';}});
 assert.equal(excluded(proxy),true);assert.equal(reads,0);
});
export const env=()=>({PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:'true'});
test('opts require the sole remainder mode without evaluating getters',async()=>{
 const api=await load();assert.equal(typeof api.manualRefundRemainderOptIn,'function');assert.equal(api.manualRefundRemainderOptIn(env()),true);
 for(const key of ['MANUAL_REFUND','MANUAL_PAYMENT','FULL_RUN','OWNED_RUN','FORM_DIAGNOSTIC','MANUAL_FORM_DIAGNOSTIC','ALLOW_PREPARE_ONLY'])assert.equal(api.manualRefundRemainderOptIn({...env(),['PAYMENT_STRIPE_SANDBOX_'+key]:'true'}),false);
 for(const key of Object.keys(env()))for(const value of [undefined,true,'TRUE','false'])assert.equal(api.manualRefundRemainderOptIn({...env(),[key]:value}),false);
 for(const key of ['DEBUG','NODE_DEBUG','PWDEBUG','PW_TRACE'])assert.equal(api.manualRefundRemainderOptIn({...env(),[key]:'true'}),false);
 let reads=0;const e=env();Object.defineProperty(e,'DEBUG',{get(){reads++;throw Error('CANARY');}});assert.equal(api.manualRefundRemainderOptIn(e),false);assert.equal(reads,0);
 const value={env:env(),runId:'11111111-2222-4333-8444-555555555555',expectedSource:'e'.repeat(40),daemon:'unix:///private/tmp/docker.sock'},valid=api.validateManualRefundRemainderOptions(value);assert.ok(valid);assert.ok(Object.isFrozen(valid.env));
 for(const patch of [{runId:'34941d87-12d3-4c74-8c5a-b62b7c914390'},{runId:'031b5ae7-6603-4339-b1b3-77231291ff34'},{extra:1},{daemon:'unix:///private/tmp/../tmp/docker.sock'}])assert.equal(api.validateManualRefundRemainderOptions({...value,...patch}),null);
});
