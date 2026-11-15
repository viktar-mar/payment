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
  assert.match(spec.slice(testCase),/runFailedRenewalBrowser\(browser,\s*controller\)/);
  assert.match(spec.slice(testCase),/signal:\s*controller\.signal/);
});

test('failed reporter retains only one clean CLI handoff and fixed result labels',async()=>{
  const {default:Reporter}=await import(new URL('./stripe-billing-failed-renewal-reporter.ts',import.meta.url).href);
  const handoff=(url:string,code='ABCD-EFGH')=>'Authorize the isolated Sandbox CLI at '+url+' using code '+code+'. Keep this terminal open.\n';
  const device='https://access.stripe.com/stripecli/oauth2/device';
  const clean=handoff(device);
  const forbidden=['pi_fixture_secret_fixture','seti_fixture_secret_fixture','bga1_'+'S'.repeat(43),'bca2_'+'C'.repeat(43),'sk_test_fixture','whsec_fixture','client%5Fsecret'];
  const original=process.stdout.write,output:string[]=[];
  process.stdout.write=((v:unknown)=>{output.push(String(v));return true;}) as typeof process.stdout.write;
  try{
    const reporter=new Reporter();
    const opaqueBearer='T'.repeat(43);
    for(const bad of [handoff(device+'?x='+opaqueBearer),handoff(device,opaqueBearer),handoff(device+'#'+opaqueBearer),
      handoff(device+'/'+opaqueBearer),handoff(device+'?x=benign'),handoff(device.replace('access.','dashboard.')),
      handoff(device,'abcd-efgh'),handoff(device,'ABCD EFGH'),handoff(device,'ABCD-1234')])reporter.onStdOut(bad);
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

test('effective project capture and repeat settings are rejected before owner or browser acquisition',async()=>{
  const spec=await readFile(new URL('../stripe-billing-failed-renewal.spec.ts',import.meta.url),'utf8');
  const hook=spec.slice(spec.indexOf('test.beforeAll('),spec.indexOf("test('OWNED_FAILED_RENEWAL_GRACE_RECOVERY'"));
  const gate=hook.indexOf('failedRenewalRunSettingsAllowed(info)'),owner=hook.indexOf('await createOwnedBillingFailedRenewal(');
  assert.ok(gate>=0 && gate<owner,'effective settings gate must run before owner construction');
  const {failedRenewalRunSettingsAllowed}=await import(new URL('./stripe-billing-failed-renewal-settings.ts',import.meta.url).href);
  const valid=()=>({config:{workers:1,fullyParallel:false,preserveOutput:'never'},project:{repeatEach:1,retries:0,
    use:{trace:'off',screenshot:'off',video:'off'}},retry:0,repeatEachIndex:0});
  assert.equal(failedRenewalRunSettingsAllowed(valid()),true);
  for(const patch of [
    (i:any)=>{i.project.use.trace='on';},(i:any)=>{i.project.use.trace={mode:'retain-on-failure'};},
    (i:any)=>{i.project.use.video='on-first-retry';},(i:any)=>{i.project.use.screenshot='only-on-failure';},
    (i:any)=>{i.project.use.contextOptions={recordHar:{path:'private.har'}};},
    (i:any)=>{i.project.use.contextOptions={recordVideo:{dir:'private'}};},
    (i:any)=>{i.project.retries=1;},(i:any)=>{i.project.repeatEach=2;},
    (i:any)=>{i.retry=1;},(i:any)=>{i.repeatEachIndex=1;},(i:any)=>{i.config.workers=2;},
    (i:any)=>{i.config.fullyParallel=true;},(i:any)=>{i.config.preserveOutput='always';},
    (i:any)=>{delete i.project.use.trace;},
  ]){
    const i=valid();patch(i);assert.equal(failedRenewalRunSettingsAllowed(i),false);
  }
});

test('reporter requires exactly one passing target completion and an overall pass',async()=>{
  const {default:Reporter}=await import(new URL('./stripe-billing-failed-renewal-reporter.ts',import.meta.url).href);
  const native=new URL('../stripe-billing-failed-renewal.spec.ts',import.meta.url).pathname;
  const target={title:'OWNED_FAILED_RENEWAL_GRACE_RECOVERY',location:{file:native},expectedStatus:'passed',
    parent:{project:()=>({name:'owned-failed-renewal-manual-chrome'})}};
  const original=process.stdout.write,output:string[]=[];
  process.stdout.write=((v:unknown)=>{output.push(String(v));return true;}) as typeof process.stdout.write;
  const complete=(t:any=target,status='passed')=>[t,{status,retry:0}] as const;
  try{
    for(const [results,overall,permit] of [
      [[], 'passed', false],[[complete(target,'skipped')],'passed',false],[[complete(),complete()],'passed',false],
      [[complete({...target,title:'another case'})],'passed',false],[[complete({...target,location:{file:native+'.other'}})],'passed',false],
      [[complete({...target,parent:{project:()=>({name:'other'})}})],'passed',false],[[complete()],'failed',false],[[complete()],'passed',true],
    ] as const){
      const reporter=new Reporter();
      for(const [t,r] of results)reporter.onTestEnd(t,r);
      reporter.onEnd({status:overall});
      assert.equal(output.at(-1),permit?'owned_failed_renewal_result_passed full5b=NOT_ACCEPTED\n':'owned_failed_renewal_result_not_accepted full5b=NOT_ACCEPTED\n');
    }
  }finally{process.stdout.write=original;}
});
