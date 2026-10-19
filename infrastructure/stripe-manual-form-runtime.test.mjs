import test from 'node:test';
import assert from 'node:assert/strict';
const subject=await import('./stripe-form-diagnostic-runtime.mjs');
const flags={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'};
const options=()=>({env:{...flags},runId:'11111111-2222-4333-8444-555555555555',expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',manualPort:18083,
  ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025}});
for(const patch of [{PAYMENT_STRIPE_SANDBOX_ENABLED:''},{PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC:''},{PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:''},
  {PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true'},{PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'},{PAYMENT_STRIPE_SANDBOX_FULL_RUN:'true'},
  {PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true'},{DEBUG:'pw:api'},{NODE_DEBUG:'http'},{PWDEBUG:'1'},{PW_TRACE:'on'}])
test('manual runtime refuses mixed/absent '+Object.keys(patch)[0]+' without dependency access',async()=>{
  assert.equal(typeof subject.createManualFormDiagnosticRuntime,'function');let touched=0;
  await assert.rejects(subject.createManualFormDiagnosticRuntime({...options(),env:{...flags,...patch}},new Proxy({},{get(){touched++;throw null;}})));
  assert.equal(touched,0);
});
test('manual runtime factory is inert and exports only one-shot prepare/proof/close capabilities',async()=>{
  assert.equal(typeof subject.createManualFormDiagnosticRuntime,'function');const value=await subject.createManualFormDiagnosticRuntime(options());
  assert.deepEqual(Object.keys(value).sort(),['close','prepare','verifyNoConfirmation']);assert.ok(Object.isFrozen(value));
  await assert.rejects(value.verifyNoConfirmation({deadline:Date.now()+1000}));
});
test('manual malformed seventh port and hostile options fail before dependency getters',async()=>{
  assert.equal(typeof subject.createManualFormDiagnosticRuntime,'function');let touched=0;const trap=new Proxy({},{get(){touched++;throw null;}});
  for(const patch of [{manualPort:18081},{manualPort:0},{extra:true}])await assert.rejects(subject.createManualFormDiagnosticRuntime({...options(),...patch},trap));
  const hostile=options();Object.defineProperty(hostile,'ports',{get(){touched++;throw null;}});
  await assert.rejects(subject.createManualFormDiagnosticRuntime(hostile,trap));assert.equal(touched,0);
});
