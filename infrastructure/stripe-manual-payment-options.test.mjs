import test from 'node:test';
import assert from 'node:assert/strict';

// Break: new-mode exclusion after normalization or evaluating an accessor.
test('remainder exclusion precedes snapshots configuration and dependencies',async()=>{
  const {manualPaymentOptIn:accept}=await import('./stripe-manual-payment-options.mjs'); const base={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT:'true'};
  let reads=0;const trap=new Proxy({},{get(){reads++;throw null;}});
  for(const shape of ['true','malformed','boolean','accessor','hidden','inherited']){
    const raw=shape==='inherited'?Object.assign(Object.create({PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER:'true'}),base):{...base};
    if(shape!=='inherited')Object.defineProperty(raw,'PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER',shape==='accessor'?{enumerable:true,get(){reads++;throw null;}}:{enumerable:shape!=='hidden',value:shape==='true'||shape==='hidden'?'true':shape==='boolean'?false:{private:'canary'}});
    assert.equal(accept(raw),false);
  }
  assert.equal(reads,0);
});
const options=await import('./stripe-manual-payment-options.mjs').catch(()=>({}));
import {manualFormOptIn} from './stripe-manual-form-options.mjs';
import {formDiagnosticOptIn,preflightOwnedSandbox} from './stripe-sandbox-owned.mjs';
const env={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT:'true'};
const value=()=>({env:{...env},runId:'11111111-2222-4333-8444-555555555555',expectedSource:'e'.repeat(40),daemon:'unix:///tmp/docker.sock',ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},manualPort:18083});
test('old modes reject new payment authority before dependency access',async()=>{
  assert.equal(manualFormOptIn({...env,PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:undefined,PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'}),false);
  assert.equal(formDiagnosticOptIn({...env,PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:undefined,PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'}),false);
  let touched=0;assert.deepEqual(await preflightOwnedSandbox(value(),new Proxy({},{get(){touched++;throw null;}})),{status:'PENDING',phase:'OPT_IN',code:'OPT_IN_REQUIRED'});assert.equal(touched,0);
});
test('payment admission snapshots exact plain options without evaluating accessors',()=>{
  assert.equal(typeof options.validateManualPaymentOptions,'function');const input=value(),selected=options.validateManualPaymentOptions(input);assert.ok(selected);input.ports.backend=1;assert.equal(selected.ports.backend,18080);assert.ok(Object.isFrozen(selected.env));
  for(const patch of [{manualPort:18080},{extra:true},{runId:'2f3f49b1-df07-41b1-b978-bc884b390813'},{expectedSource:'E'.repeat(40)},{daemon:'tcp://localhost:2375'}])assert.equal(options.validateManualPaymentOptions({...value(),...patch}),null);
  let touched=0;const hostile=value();Object.defineProperty(hostile,'ports',{get(){touched++;throw null;}});assert.equal(options.validateManualPaymentOptions(hostile),null);assert.equal(touched,0);
});
test('only exact exclusive flags admit payment',()=>{
  assert.equal(typeof options.manualPaymentOptIn,'function');assert.equal(options.manualPaymentOptIn(env),true);
  for(const key of Object.keys(env))for(const v of [undefined,'TRUE','True','false'])assert.equal(options.manualPaymentOptIn({...env,[key]:v}),false);
  for(const key of ['PAYMENT_STRIPE_SANDBOX_FULL_RUN','PAYMENT_STRIPE_SANDBOX_OWNED_RUN','PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC','PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC','PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY','DEBUG','NODE_DEBUG','PWDEBUG','PW_TRACE'])assert.equal(options.manualPaymentOptIn({...env,[key]:'true'}),false);
  const hostile={...env};let touched=0;Object.defineProperty(hostile,'DEBUG',{get(){touched++;throw null;}});assert.equal(options.manualPaymentOptIn(hostile),false);assert.equal(touched,0);
});
