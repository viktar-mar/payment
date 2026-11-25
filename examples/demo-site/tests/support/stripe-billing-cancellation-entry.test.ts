import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import type {FullResult,TestCase,TestResult} from '@playwright/test/reporter';
// @ts-ignore Node executes this suite directly.
import Reporter from './stripe-billing-cancellation-reporter.ts';
// @ts-ignore Node executes the same worker protocol as the native runner.
import {runCancellationCase} from './stripe-billing-cancellation-terminal.ts';

test('cancellation is isolated from ordinary test discovery and requires its own case before acquiring Chrome',async()=>{
  const read=(name:string)=>readFile(new URL(name,import.meta.url),'utf8');
  const [ordinary,isolated,entry]=await Promise.all([read('../../playwright.config.ts'),read('../../playwright.stripe-billing-cancellation.config.ts'),read('../stripe-billing-cancellation.spec.ts')]);
  assert.match(ordinary,/testIgnore:[^\]]*"stripe-billing-cancellation\.spec\.ts"/);
  assert.match(ordinary,/testIgnore:[^\]]*"\*\*\/support\/stripe-billing-cancellation\*\.test\.ts"/);
  assert.match(isolated,/testMatch:'stripe-billing-cancellation\.spec\.ts'/);
  assert.match(isolated,/workers:1,retries:0/);
  assert.match(entry,/PAYMENT_STRIPE_BILLING_CASE!=='initial_cancel_withdraw'/);
  const start=entry.indexOf('test.beforeAll(async({},info)'),owner=entry.indexOf('owner=await createOwnedBillingCancellation'),browser=entry.indexOf('async({browser},info)');
  assert.ok(start>=0&&start<owner&&owner<browser);
  assert.match(entry,/runCancellationCase\(owner,.*info\.annotations\)/);
  assert.match(entry,/runBillingCancellationBrowser\(browser,port,limits,loadFailedRenewalStaticOriginMatcher\(\)\)/);
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

for(const mode of ['original','unconfirmed','copied result','duplicate','foreign project','second attempt','raw reason']){
  test('cancellation reporter emits only a fixed terminal receipt for its original attempt: '+mode,async()=>{
    const reporter=new Reporter(),lines:string[]=[];
    const t={expectedStatus:'passed',title:'OWNED_PERIOD_END_CANCELLATION',location:{file:fileURLToPath(new URL('../stripe-billing-cancellation.spec.ts',import.meta.url))},
      parent:{project:()=>({name:mode==='foreign project'?'other':'owned-period-end-cancellation'})},annotations:[]} as unknown as TestCase;
    const r={retry:mode==='second attempt'?1:0,status:'failed',annotations:[]} as unknown as TestResult;
    const phase=mode==='original'?'CANCELLATION_EVIDENCE':'BROWSER';
    const code=mode==='original'?'CANCELLATION_LOCAL_EVIDENCE':'BROWSER_OTP_REQUEST';
    const line='owned_period_end_cancellation_terminal phase='+phase+' code='+code+' cleanup='+(mode==='unconfirmed'?'UNCONFIRMED':'CONFIRMED');
    reporter.onTestBegin(t,r);
    const terminal={scope:'INITIAL_CANCEL_WITHDRAW',status:'CLOSED',phase,code,
      full5bAccepted:false,initialAccepted:false,cancellationAccepted:false,cleanupConfirmed:mode!=='unconfirmed'};
    const owner={run:async()=>{throw Error('private worker failure canary');},close:async()=>terminal,resources:()=>terminal};
    await assert.rejects(runCancellationCase(owner,async()=>assert.fail(),r.annotations),/billing_NOT_ACCEPTED/);
    assert.deepEqual(r.annotations,[{type:'owned-period-end-cancellation-terminal',description:line}]);
    if(mode==='raw reason')r.annotations[0]!.description+=' raw=canary';
    if(mode==='duplicate')r.annotations.push({...r.annotations[0]!});
    const original=process.stdout.write;
    try{
      process.stdout.write=((s:string)=>{lines.push(s);return true;}) as typeof original;
      reporter.onTestEnd(t,mode==='copied result'?{...r}:r);
      reporter.onEnd({status:'failed'} as FullResult);
    }finally{process.stdout.write=original;}
    assert.deepEqual(lines,[...(['original','unconfirmed'].includes(mode)?[line+'\n']:[]),'owned_period_end_cancellation_not_accepted full5b=NOT_ACCEPTED\n']);
  });
}
