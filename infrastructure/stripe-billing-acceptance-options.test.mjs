import test from 'node:test';
import assert from 'node:assert/strict';
import * as bootstrap from './stripe-sandbox-bootstrap.mjs';
import * as cli from './stripe-sandbox-cli.mjs';
import * as owned from './stripe-sandbox-owned.mjs';
const module=await import('./stripe-billing-acceptance-options.mjs').catch(()=>({}));
const options=()=>({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true'},runId:'11111111-2222-4333-8444-555555555555',expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:'initial_success'});
test('Billing own-data admission freezes fixed initial-success authority without reading accessors',()=>{
  assert.equal(typeof module.validateBillingAcceptanceOptions,'function');
  const input=options(),value=module.validateBillingAcceptanceOptions(input);assert.ok(value);
  assert.deepEqual(value.budget,{create:1,confirm:1});assert.equal(value.protocol,'SERVER_CONFIRM_V2');
  for(const obj of [value,value.env,value.ports,value.approvedBinding,value.budget])assert.equal(Object.isFrozen(obj),true);
  input.ports.backend=19000;assert.equal(value.ports.backend,18080);
  assert.deepEqual(module.validateBillingAcceptanceOptions(value),value);
  let reads=0;
  for(const mutate of [o=>delete o.env.PAYMENT_STRIPE_BILLING_ACCEPTANCE,o=>o.env.PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS='true',o=>o.env=Object.create(o.env),o=>Object.defineProperty(o.env,'PAYMENT_STRIPE_BILLING_ACCEPTANCE',{enumerable:true,get(){reads++;return 'true';}}),o=>Object.defineProperty(o,'runId',{enumerable:true,get(){reads++;return input.runId;}}),o=>o.budget={create:2,confirm:1},o=>o.selectedCase='initial_decline_retry',o=>o.ports.backend=o.ports.demo,o=>o.approvedBinding.externalAccountId='acct_other',o=>o.priorRunId=input.runId,o=>o.daemon='unix:///tmp/../test.sock']){const bad=options();mutate(bad);assert.equal(module.validateBillingAcceptanceOptions(bad),null);}
  assert.equal(reads,0);
});
test('Billing factories reject absent or mixed authority before dependencies',async()=>{
  for(const create of [bootstrap.createOwnedBillingBootstrap,cli.createBillingCliAdapter,owned.preflightOwnedBilling])assert.equal(typeof create,'function');
  let reads=0;const deps=new Proxy({},{get(){reads++;throw null;}});
  for(const env of [{},{PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true',PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true'},Object.create(options().env)]){
    await assert.rejects(bootstrap.createOwnedBillingBootstrap({...options(),env},deps));
    await assert.rejects(cli.createBillingCliAdapter({configuration:{...options(),env}},deps));
    assert.equal((await owned.preflightOwnedBilling({...options(),env},deps)).status,'PENDING');
  }
  assert.equal(reads,0);
});
test('legacy owners reject Billing flag before other inputs or dependencies',async()=>{
  let reads=0;const deps=new Proxy({},{get(){reads++;throw null;}});
  for(const shape of ['true','getter','inherited']){
    const env={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'};
    if(shape==='inherited')Object.setPrototypeOf(env,{PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true'});
    else Object.defineProperty(env,'PAYMENT_STRIPE_BILLING_ACCEPTANCE',shape==='getter'?{enumerable:true,get(){reads++;throw null;}}:{value:'true',enumerable:true});
    const bad={env,get runId(){reads++;throw null;}};
    await assert.rejects(bootstrap.createOwnedSandboxBootstrap(bad,deps),/OPT_IN_REQUIRED/);
    await assert.rejects(cli.createSandboxCliAdapter(bad,deps),/OPT_IN_REQUIRED/);
    assert.equal((await owned.preflightOwnedSandbox(bad,deps)).phase,'OPT_IN');
  }
  assert.equal(reads,0);
});

test('all legacy mode parsers reject Billing before normalization can erase the flag',async()=>{
  const parsers=[['./stripe-manual-payment-options.mjs','manualPaymentOptIn',['ENABLED','ALLOW_TEST_OPERATIONS','MANUAL_PAYMENT']],['./stripe-manual-form-options.mjs','manualFormOptIn',['ENABLED','ALLOW_PREPARE_ONLY','MANUAL_FORM_DIAGNOSTIC']],['./stripe-manual-refund-options.mjs','manualRefundOptIn',['ENABLED','ALLOW_TEST_OPERATIONS','MANUAL_REFUND']],['./stripe-manual-refund-remainder-options.mjs','manualRefundRemainderOptIn',['ENABLED','ALLOW_TEST_OPERATIONS','MANUAL_REFUND_REMAINDER']],['./stripe-sandbox-remaining-options.mjs','remainingOnlyOptIn',['ENABLED','ALLOW_TEST_OPERATIONS','OWNED_RUN','REMAINING_ONLY']]];
  for(const [path,name,keys] of parsers){const parser=(await import(path))[name],env=Object.fromEntries(keys.map(k=>['PAYMENT_STRIPE_SANDBOX_'+k,'true']));assert.equal(parser(env),true);env.PAYMENT_STRIPE_BILLING_ACCEPTANCE='true';assert.equal(parser(env),false,name);}
  const {manualRefundRemainderExcluded}=await import('./stripe-manual-refund-remainder-options.mjs');assert.equal(manualRefundRemainderExcluded(options().env),false);
  const bad=options();bad.env.PAYMENT_STRIPE_SANDBOX_REMAINING_ONLY='true';assert.equal(module.validateBillingAcceptanceOptions(bad),null);
});
