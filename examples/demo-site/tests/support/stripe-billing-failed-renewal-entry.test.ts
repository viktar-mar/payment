import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

test('the beforeAll gate validates authority, project and owner before requesting the browser fixture',async()=>{
  const spec=await readFile(new URL('../stripe-billing-failed-renewal.spec.ts',import.meta.url),'utf8');
  const registration=spec.indexOf('test.beforeAll(');
  const testCase=spec.indexOf("test('OWNED_FAILED_RENEWAL_GRACE_RECOVERY'");
  assert.ok(registration>=0 && testCase>registration,'requires an admission hook before the browser case');
  const hook=spec.slice(registration,testCase);
  assert.match(hook,/test\.beforeAll\(async\s*\(\s*\{\s*\}\s*,/);
  const snapshot=hook.indexOf('snapshotBillingFailedRenewalEnvironment(');
  const project=hook.indexOf("'owned-failed-renewal-manual-chrome'");
  const deny=hook.indexOf("'failed_renewal_OPT_IN_REQUIRED'");
  const owner=hook.indexOf('await createOwnedBillingFailedRenewal(');
  assert.ok(snapshot>=0 && snapshot<deny && project>=0 && project<deny && deny<owner);
  assert.doesNotMatch(hook,/runFailedRenewalBrowser\(|newContext\(|\.run\(/);
  assert.match(spec.slice(testCase),/async\s*\(\s*\{\s*browser\s*\}/);
  assert.doesNotMatch(spec.slice(testCase),/await createOwnedBillingFailedRenewal\(/);
});

test('failed reporter retains only one clean CLI handoff and fixed result labels',async()=>{
  const {default:Reporter}=await import(new URL('./stripe-billing-failed-renewal-reporter.ts',import.meta.url).href);
  const handoff=(url:string)=>'Authorize the isolated Sandbox CLI at '+url+' using code ABCD EFGH. Keep this terminal open.\n';
  const clean=handoff('https://access.stripe.com/fixture');
  const forbidden=['pi_fixture_secret_fixture','seti_fixture_secret_fixture','bga1_'+'S'.repeat(43),'bca2_'+'C'.repeat(43),'sk_test_fixture','whsec_fixture','client%5Fsecret'];
  const original=process.stdout.write,output:string[]=[];
  process.stdout.write=((v:unknown)=>{output.push(String(v));return true;}) as typeof process.stdout.write;
  try{
    const reporter=new Reporter();
    for(const secret of forbidden){
      reporter.onStdOut('browser session '+secret+'\n');
      reporter.onStdOut(handoff('https://access.stripe.com/fixture?x='+secret));
      reporter.onStdErr(clean+secret+'\n');
    }
    reporter.onStdOut('owned_initial_payment_diagnostic code=PREPARE_REQUEST_TRANSPORT cleanup=UNCONFIRMED\n');
    reporter.onStdOut('x'.repeat(4096)+clean);
    for(const ch of clean)reporter.onStdOut(Buffer.from(ch));
    reporter.onStdOut(clean+clean);
    const opaque=new Proxy({}, {get:()=>assert.fail('reporter accessed raw failure')});
    reporter.onError(opaque);reporter.onTestEnd(opaque,{status:'failed'});
    reporter.onEnd({status:'failed'});
    assert.deepEqual(output,[clean,'failed_renewal_setup_failed\n','owned_failed_renewal_not_accepted\n','owned_failed_renewal_result_not_accepted full5b=NOT_ACCEPTED\n']);
  }finally{process.stdout.write=original;}
});
