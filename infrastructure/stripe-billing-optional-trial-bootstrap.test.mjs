import assert from 'node:assert/strict';
import test from 'node:test';
import * as bootstrap from './stripe-sandbox-bootstrap.mjs';
import * as cli from './stripe-sandbox-cli.mjs';

const options=()=>({
  env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL:'true',
    PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL_CLI_PROFILE_RUN_ID:'22345678-1234-4234-8234-123456789abc',
    PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL_SUBNET:'10.94.40.0/28'},
  runId:'12345678-1234-4234-8234-123456789abc',
  expectedSource:'e'.repeat(40),
  daemon:'unix:///Users/test/docker.sock',
  ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},
  controlPort:18083,
  approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},
  selectedCase:'optional_trial_pause_resume',
});

test('optional trial construction is inert and surfaces only the dedicated runtime access',async()=>{
  assert.equal(typeof bootstrap.createOwnedBillingOptionalTrialBootstrap,'function');
  assert.equal(typeof cli.createOwnedBillingOptionalTrialCliAdapter,'function');
  const dependency=()=>assert.fail('construction must not run a process, file access, or network request');
  const runtime=await bootstrap.createOwnedBillingOptionalTrialBootstrap(options(),{
    execute:dependency,lstat:dependency,open:dependency,fetch:dependency,
  });
  assert.equal(typeof runtime.withOptionalTrialRuntime,'function');
  assert.equal(typeof runtime.withDatabaseCredentials,'function');
  assert.equal(runtime.withFirstPaymentRuntime,undefined);
  assert.equal(runtime.withFailedRenewalRuntime,undefined);
});

test('optional trial refuses mixed flags, inherited settings, old clock time and unsafe topology',async()=>{
  assert.equal(typeof bootstrap.createOwnedBillingOptionalTrialBootstrap,'function');
  for(const alter of [
    o=>{o.env.PAYMENT_STRIPE_BILLING_ACCEPTANCE='true';},
    o=>{o.env.PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS='true';},
    o=>{delete o.env.PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL_CLI_PROFILE_RUN_ID;},
    o=>{o.env.PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL_CLI_PROFILE_RUN_ID=o.runId;},
    o=>{o.env.PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL_SUBNET='10.94.40.1/28';},
    o=>{o.controlPort=o.ports.backend;},
    o=>{o.controlPort=8080;},
    o=>{o.frozenTime=1790000000;},
    o=>{o.env.PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL_FROZEN_TIME='1790000000';},
    o=>{o.env.PAYMENT_STRIPE_BILLING_OPTIONAL_TRIAL_ACCOUNT_ID='33345678-1234-4234-8234-123456789abc';},
    o=>{o.approvedBinding.externalAccountId='acct_foreign';},
    o=>{Object.setPrototypeOf(o.env,{PAYMENT_STRIPE_BILLING_FIRST_PAYMENT:'true'});},
  ]){
    const o=options();alter(o);
    const dependencies=new Proxy({}, {get(){assert.fail('invalid configuration reached dependencies');}});
    await assert.rejects(bootstrap.createOwnedBillingOptionalTrialBootstrap(o,dependencies),/OPT_IN_REQUIRED/);
  }
});
