import test from 'node:test';
import assert from 'node:assert/strict';
import * as api from './stripe-manual-refund-options.mjs';
const env=()=>({PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND:'true'});
const options=()=>({env:env(),runId:'11111111-2222-4333-8444-555555555555',expectedSource:'e'.repeat(40),daemon:'unix:///private/tmp/docker.sock'});
// Break caught: bypassing the exclusive opt-in permits other financial modes.
test('refund requires exact flags and rejects every competing mode and debug accessor',()=>{
  assert.equal(typeof api.manualRefundOptIn,'function');assert.equal(api.manualRefundOptIn(env()),true);
  for(const key of Object.keys(env()))for(const value of [undefined,'TRUE',true,'false'])assert.equal(api.manualRefundOptIn({...env(),[key]:value}),false);
  for(const key of ['FULL_RUN','OWNED_RUN','FORM_DIAGNOSTIC','MANUAL_FORM_DIAGNOSTIC','ALLOW_PREPARE_ONLY','MANUAL_PAYMENT'])assert.equal(api.manualRefundOptIn({...env(),['PAYMENT_STRIPE_SANDBOX_'+key]:'true'}),false);
  for(const key of ['DEBUG','NODE_DEBUG','PWDEBUG','PW_TRACE'])assert.equal(api.manualRefundOptIn({...env(),[key]:'true'}),false);
  let reads=0;const input=env();Object.defineProperty(input,'DEBUG',{get(){reads++;throw Error('CANARY');}});assert.equal(api.manualRefundOptIn(input),false);assert.equal(reads,0);
});
// Break caught: accepting caller-controlled original identities or mutable flags.
test('refund options snapshot only the four bounded inputs without invoking accessors',()=>{
  assert.equal(typeof api.validateManualRefundOptions,'function');const input=options(),actual=api.validateManualRefundOptions(input);assert.ok(actual);input.env.PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND='false';assert.equal(actual.env.PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND,'true');assert.ok(Object.isFrozen(actual));assert.ok(Object.isFrozen(actual.env));
  for(const change of [{ports:{}},{paymentId:'CANARY'},{runId:'031b5ae7-6603-4339-b1b3-77231291ff34'},{runId:'2f3f49b1-df07-41b1-b978-bc884b390813'},{expectedSource:'f0620e40004162d2b3eededa60512f50a492f4cc'},{daemon:'unix:///private/tmp/../tmp/docker.sock'},{daemon:'tcp://localhost:2375'},{expectedSource:'E'.repeat(40)}])assert.equal(api.validateManualRefundOptions({...options(),...change}),null);
  let reads=0;const hostile=options();Object.defineProperty(hostile,'runId',{get(){reads++;return 'CANARY';}});assert.equal(api.validateManualRefundOptions(hostile),null);assert.equal(reads,0);
});
