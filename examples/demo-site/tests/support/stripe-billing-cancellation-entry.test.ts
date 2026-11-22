import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import type {FullResult,TestCase,TestResult} from '@playwright/test/reporter';
// @ts-ignore Node executes this suite directly.
import Reporter from './stripe-billing-cancellation-reporter.ts';

test('cancellation is isolated from ordinary test discovery and requires its own case before acquiring Chrome',async()=>{
  const read=(name:string)=>readFile(new URL(name,import.meta.url),'utf8');
  const [ordinary,isolated,entry]=await Promise.all([read('../../playwright.config.ts'),read('../../playwright.stripe-billing-cancellation.config.ts'),read('../stripe-billing-cancellation.spec.ts')]);
  assert.match(ordinary,/testIgnore:[^\]]*"stripe-billing-cancellation\.spec\.ts"/);
  assert.match(ordinary,/testIgnore:[^\]]*"\*\*\/support\/stripe-billing-cancellation\*\.test\.ts"/);
  assert.match(isolated,/testMatch:'stripe-billing-cancellation\.spec\.ts'/);
  assert.match(isolated,/workers:1,retries:0/);
  assert.match(entry,/PAYMENT_STRIPE_BILLING_CASE!=='initial_cancel_withdraw'/);
  const start=entry.indexOf('test.beforeAll(async({},info)'),owner=entry.indexOf('owner=await createOwnedBillingCancellation'),browser=entry.indexOf('async({browser})');
  assert.ok(start>=0&&start<owner&&owner<browser);
});

for(const scenario of ['pass','wrong-file','retry','repeat','test-failed','overall-failed','no-test','setup-error']){
  test('cancellation reporter requires exactly one verified case: '+scenario,()=>{
    const reporter=new Reporter(),lines:string[]=[];
    const t={expectedStatus:'passed',title:'OWNED_PERIOD_END_CANCELLATION',location:{file:scenario==='wrong-file'?'foreign':fileURLToPath(new URL('../stripe-billing-cancellation.spec.ts',import.meta.url))},
      parent:{project:()=>({name:'owned-period-end-cancellation'})}} as TestCase;
    const r={retry:scenario==='retry'?1:0,status:scenario==='test-failed'?'failed':'passed'} as TestResult;
    const original=process.stdout.write;
    try{
      process.stdout.write=((s:string)=>{lines.push(s);return true;}) as typeof original;
      reporter.onStdOut('private fixture\n');
      if(scenario==='setup-error')reporter.onError();
      if(scenario!=='no-test'){reporter.onTestBegin(t,r);reporter.onTestEnd(t,r);}
      if(scenario==='repeat'){reporter.onTestBegin(t,r);reporter.onTestEnd(t,r);}
      reporter.onEnd({status:scenario==='overall-failed'?'failed':'passed'} as FullResult);
    }finally{process.stdout.write=original;}
    const expected=scenario==='pass'?'owned_period_end_cancellation_passed full5b=NOT_ACCEPTED\n':'owned_period_end_cancellation_not_accepted full5b=NOT_ACCEPTED\n';
    assert.deepEqual(lines,scenario==='setup-error'?['billing_cancellation_setup_failed\n',expected]:[expected]);
  });
}
