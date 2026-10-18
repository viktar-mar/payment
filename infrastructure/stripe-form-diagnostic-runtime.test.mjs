import test from 'node:test';
import assert from 'node:assert/strict';
const module=await import('./stripe-form-diagnostic-runtime.mjs').catch(()=>({}));
const flags={PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:'true'};
const options=()=>({env:{...flags},runId:'11111111-2222-4333-8444-555555555555',expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',
  ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025}});
for(const patch of [{PAYMENT_STRIPE_SANDBOX_ENABLED:''},{PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC:''},{PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY:''},
  {PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'},{PAYMENT_STRIPE_SANDBOX_FULL_RUN:'true'},{PAYMENT_STRIPE_SANDBOX_OWNED_RUN:'true'},{DEBUG:'pw:api'},{PWDEBUG:'1'},{PW_TRACE:'on'}])
test('diagnostic admission rejects '+Object.keys(patch)[0]+' before dependency access',async()=>{
  assert.equal(typeof module.createFormDiagnosticRuntime,'function');let touched=0;
  await assert.rejects(module.createFormDiagnosticRuntime({...options(),env:{...flags,...patch}},new Proxy({},{get(){touched++;throw null;}})));
  assert.equal(touched,0);
});
test('runtime factory is inert and returns only one-shot prepare/proof/close capabilities',async()=>{
  assert.equal(typeof module.createFormDiagnosticRuntime,'function');const r=await module.createFormDiagnosticRuntime(options());
  assert.deepEqual(Object.keys(r).sort(),['close','prepare','verifyNoConfirmation']);assert.ok(Object.isFrozen(r));
  await assert.rejects(r.verifyNoConfirmation({deadline:Date.now()+1000}));
});
