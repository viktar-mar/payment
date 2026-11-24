import test from 'node:test';
import assert from 'node:assert/strict';
import * as bootstrap from './stripe-sandbox-bootstrap.mjs';
import * as cli from './stripe-sandbox-cli.mjs';
import * as owned from './stripe-sandbox-owned.mjs';
const module=await import('./stripe-billing-acceptance-options.mjs').catch(()=>({}));
const options=()=>({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true'},runId:'11111111-2222-4333-8444-555555555555',expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:'initial_success'});
test('an explicit private bridge subnet belongs only to initial decline and retry',()=>{
 const key='PAYMENT_STRIPE_BILLING_RETRY_SUBNET',subnet='10.254.76.0/28',base=options();
 const selectedCase='initial_decline_retry';
 const input={...base,selectedCase,env:{...base.env,PAYMENT_STRIPE_BILLING_CASE:selectedCase,[key]:subnet}};
 const snapshot=module.snapshotBillingAcceptanceEnvironment(input.env);
 assert.equal(snapshot?.[key],subnet);
 const admitted=module.validateBillingAcceptanceOptions(input);
 assert.equal(admitted?.env[key],subnet);
 assert.deepEqual(module.validateBillingAcceptanceOptions(admitted),admitted);
 for(const bad of ['', '10.254.76.1/28', '10.254.76.0/16', '10.254.076.0/28', '10.255.256.0/28', '192.168.76.0/28','8.8.8.0/28','10.254.76.0/28\n']){
  const env={...input.env,[key]:bad};
  assert.equal(module.snapshotBillingAcceptanceEnvironment(env),null);
  assert.equal(module.validateBillingAcceptanceOptions({...input,env}),null);
 }
 assert.equal(module.validateBillingAcceptanceOptions({...base,env:{...base.env,[key]:subnet}}),null);
});
test('initial 3DS admits its own saved Sandbox profile and aligned private bridge',()=>{
 const base=options(),selectedCase='initial_3ds',profile='PAYMENT_STRIPE_BILLING_3DS_CLI_PROFILE_RUN_ID',subnet='PAYMENT_STRIPE_BILLING_3DS_SUBNET';
 const env={...base.env,PAYMENT_STRIPE_BILLING_CASE:selectedCase,
  [profile]:'22222222-3333-4444-8555-666666666666',[subnet]:'10.254.81.0/28'};
 const input={...base,selectedCase,env};
 assert.deepEqual(module.snapshotBillingAcceptanceEnvironment(env),env);
 const admitted=module.validateBillingAcceptanceOptions(input);
 assert.deepEqual(admitted?.env,env);
 assert.deepEqual(admitted?.budget,{create:1,confirm:1,nextAction:1});
 assert.deepEqual(module.validateBillingAcceptanceOptions(admitted),admitted);
 for(const changed of [
  {[profile]:'031b5ae7-6603-4339-b1b3-77231291ff34'}, {[profile]:base.runId}, {[profile]:'../private'},
  {[subnet]:'192.168.81.0/28'}, {[subnet]:'10.254.81.1/28'}, {[subnet]:'10.254.81.0/24'},
  {PAYMENT_STRIPE_BILLING_RETRY_CLI_PROFILE_RUN_ID:env[profile]},
  {PAYMENT_STRIPE_BILLING_RETRY_SUBNET:env[subnet]},
 ])assert.equal(module.validateBillingAcceptanceOptions({...input,env:{...env,...changed}}),null);
 for(const other of ['initial_decline_retry','initial_cancel_withdraw','initial_missed_reordered']){
  const changed={...env,PAYMENT_STRIPE_BILLING_CASE:other};
  assert.equal(module.snapshotBillingAcceptanceEnvironment(changed),null);
  assert.equal(module.validateBillingAcceptanceOptions({...input,env:changed,selectedCase:other}),null);
 }
 for(const key of [profile,subnet]){
  const inherited=Object.assign(Object.create({[key]:env[key]}),env);
  delete inherited[key];
  assert.equal(module.snapshotBillingAcceptanceEnvironment(inherited),null);
  const accessor={...env};
  Object.defineProperty(accessor,key,{get(){assert.fail('runtime flag accessor invoked');},enumerable:true});
  assert.equal(module.snapshotBillingAcceptanceEnvironment(accessor),null);
 }
});
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
test('explicit native environment snapshot admits process.env prototype without erasing mixed/accessor/inherited flags',()=>{
 assert.equal(typeof module.snapshotBillingAcceptanceEnvironment,'function');
 // Only process.env prototype metadata is inspected; no real environment values are read or logged.
 const nativeShape=Object.create(Object.getPrototypeOf(process.env));Object.assign(nativeShape,options().env);
 assert.equal(module.validateBillingAcceptanceOptions({...options(),env:nativeShape}),null);
 const snapshot=module.snapshotBillingAcceptanceEnvironment(nativeShape);assert.equal(Object.isFrozen(snapshot),true);assert.ok(module.validateBillingAcceptanceOptions({...options(),env:snapshot}));
 let reads=0;for(const mutate of [v=>v.PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS='true',v=>v.PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT='true',v=>Object.defineProperty(v,'DEBUG',{enumerable:true,get(){reads++;return '';}}),v=>Object.setPrototypeOf(v,{PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND:'false'}),v=>delete v.PAYMENT_STRIPE_BILLING_ACCEPTANCE]){const value=Object.create({});Object.assign(value,options().env);mutate(value);assert.equal(module.snapshotBillingAcceptanceEnvironment(value),null);}
 const withUnrelated=Object.assign(Object.create({}),options().env);Object.defineProperty(withUnrelated,'UNRELATED_PRIVATE_VALUE',{get(){reads++;throw null;}});assert.ok(module.snapshotBillingAcceptanceEnvironment(withUnrelated));assert.equal(reads,0);
});
test('ordinary cancellation requires the exact case, scope and bounded budget',()=>{
 const original=options(),caseName='initial_cancel_withdraw';
 const value={...original,env:{...original.env,PAYMENT_STRIPE_BILLING_CASE:caseName},selectedCase:caseName};
 const admitted=module.validateBillingAcceptanceOptions(value);
 assert.ok(admitted);
 assert.deepEqual(admitted.budget,{create:1,confirm:1,schedule:1,withdraw:1});
 assert.deepEqual(module.validateBillingAcceptanceOptions(admitted),admitted);
 assert.deepEqual(module.snapshotBillingAcceptanceEnvironment(value.env),value.env);
 for(const bad of [{...value,env:original.env},{...value,selectedCase:'initial_success'},{...value,budget:{...admitted.budget,schedule:2}},
   {...value,env:{...value.env,PAYMENT_STRIPE_BILLING_CASE:'other'}}])assert.equal(module.validateBillingAcceptanceOptions(bad),null);
});
