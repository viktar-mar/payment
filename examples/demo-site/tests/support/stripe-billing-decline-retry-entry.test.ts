import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import type {FullResult,TestCase,TestResult} from '@playwright/test/reporter';
// @ts-ignore Node executes this suite directly.
import Reporter from './stripe-billing-decline-retry-reporter.ts';

test('decline-retry is isolated from ordinary test discovery and requires its own case before acquiring Chrome',async()=>{
  const read=(name:string)=>readFile(new URL(name,import.meta.url),'utf8');
  const [ordinary,isolated,entry]=await Promise.all([read('../../playwright.config.ts'),read('../../playwright.stripe-billing-decline-retry.config.ts'),read('../stripe-billing-decline-retry.spec.ts')]);
  assert.match(ordinary,/testIgnore:[^\]]*"stripe-billing-decline-retry\.spec\.ts"/);
  assert.match(ordinary,/testIgnore:[^\]]*"\*\*\/support\/stripe-billing-decline-retry\*\.test\.ts"/);
  assert.match(isolated,/testMatch:'stripe-billing-decline-retry\.spec\.ts'/);
  assert.match(isolated,/workers:1,retries:0/);
  assert.match(entry,/PAYMENT_STRIPE_BILLING_CASE!=='initial_decline_retry'/);
  const start=entry.indexOf('test.beforeAll(async({},info)'),owner=entry.indexOf('owner=await createOwnedBillingDeclineRetry'),browser=entry.indexOf('async({browser},info)');
  assert.ok(start>=0&&start<owner&&owner<browser);
});

for(const scenario of ['pass','wrong-file','retry','repeat','test-failed','overall-failed','no-test','setup-error']){
  test('decline-retry reporter requires exactly one verified case: '+scenario,()=>{
    const reporter=new Reporter(),lines:string[]=[];
    const t={expectedStatus:'passed',title:'OWNED_INITIAL_DECLINE_RETRY',location:{file:scenario==='wrong-file'?'foreign':fileURLToPath(new URL('../stripe-billing-decline-retry.spec.ts',import.meta.url))},
      parent:{project:()=>({name:'owned-initial-decline-retry'})}} as TestCase;
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
    const expected=scenario==='pass'?'owned_initial_decline_retry_passed full5b=NOT_ACCEPTED\n':'owned_initial_decline_retry_not_accepted full5b=NOT_ACCEPTED\n';
    assert.deepEqual(lines,scenario==='setup-error'?['billing_decline_retry_setup_failed\n',expected]:[expected]);
  });
}

test('the closed failed owner supplies only its finite phase, code and cleanup to its own result',async()=>{
 // @ts-ignore Node executes the worker source directly.
 const {runDeclineRetryCase}=await import('./stripe-billing-decline-retry-terminal.ts');
 const reporter=new Reporter(),lines:string[]=[];
 const terminal={scope:'INITIAL_DECLINE_RETRY',status:'CLOSED',phase:'RETRY_EVIDENCE',code:'RETRY_EVIDENCE',initialAccepted:false,declineRetryAccepted:false,full5bAccepted:false,cleanupConfirmed:true,runId:'private-id'};
 for(const mode of ['original','replaced-result','duplicate','preexisting','failed-close','canary','browser-session','browser-pinned','browser-frame','browser-confirm','frame-detail-canary']){
  const report=mode==='original'?reporter:new Reporter();
  const browserCode=mode==='browser-session'?'BROWSER_SESSION_REQUEST_REJECTED':mode==='browser-pinned'?'BROWSER_PINNED_ASSET_MEDIA':
   mode==='browser-frame'?'BROWSER_PINNED_FRAME_ASSET_FULFILL_CHANGED':mode==='browser-confirm'?'BROWSER_CONFIRM_FORM_VALIDATION':null;
  const t={expectedStatus:'passed',title:'OWNED_INITIAL_DECLINE_RETRY',location:{file:fileURLToPath(new URL('../stripe-billing-decline-retry.spec.ts',import.meta.url))},
   parent:{project:()=>({name:'owned-initial-decline-retry'})},annotations:[]} as unknown as TestCase;
  const r={retry:0,status:'failed',annotations:[]} as unknown as TestResult;
  const owner={run:async()=>{throw Error('raw-provider-canary');},resources:()=>terminal,close:async()=>{
   if(mode==='failed-close')throw Error('raw-close-canary');
   if(browserCode)return {...terminal,phase:'RETRY_ACK',code:browserCode};
   if(mode==='frame-detail-canary')return {...terminal,phase:'RETRY_ACK',code:'BROWSER_PINNED_FRAME_ASSET_CAPTURE_PRIVATE_CANARY'};
   return mode==='canary'?{...terminal,code:'raw-canary'}:terminal;
  }};
  if(mode==='preexisting')r.annotations.push({type:'owned-decline-retry-terminal',description:'private'});
  report.onTestBegin(t,r);
  await assert.rejects(runDeclineRetryCase(owner as never,async()=>true,r.annotations),/billing_NOT_ACCEPTED/);
  if(mode==='duplicate')r.annotations.push({...r.annotations.at(-1)!});
  lines.length=0;const write=process.stdout.write;
  try{
   process.stdout.write=((line:string)=>{lines.push(line);return true;}) as typeof write;
   report.onTestEnd(t,mode==='replaced-result'?{...r}:r);
   report.onEnd({status:'failed'} as FullResult);
  }finally{process.stdout.write=write;}
  const stage=browserCode?'phase=RETRY_ACK code='+browserCode:'phase=RETRY_EVIDENCE code=RETRY_EVIDENCE';
  const expected='owned_initial_decline_retry_terminal '+stage+' cleanup='+(mode==='failed-close'?'UNCONFIRMED':'CONFIRMED')+'\n';
  assert.deepEqual(lines, [...(browserCode||['original','failed-close'].includes(mode)?[expected]:[]),'owned_initial_decline_retry_not_accepted full5b=NOT_ACCEPTED\n']);
 }
});

test('an isolated owner preflight refusal reaches the dedicated reporter with its observed cleanup',async()=>{
 const {createOwnedBillingDeclineRetry}=await import('../../../../infrastructure/stripe-billing-acceptance.mjs');
 // @ts-ignore Node executes the worker source directly.
 const {runDeclineRetryCase}=await import('./stripe-billing-decline-retry-terminal.ts');
 const owner=await createOwnedBillingDeclineRetry({
  env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true',PAYMENT_STRIPE_BILLING_CASE:'initial_decline_retry'},
  runId:'11111111-2222-4333-8444-555555555555',expectedSource:'b'.repeat(40),daemon:'unix:///Users/test/docker.sock',
  ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},
  approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:'initial_decline_retry',
 },{preflight:async()=>{throw Error('private-preflight-error');},createBootstrap:async()=>assert.fail('refusal must precede all acquisition')});
 const reporter=new Reporter();
 const t={expectedStatus:'passed',title:'OWNED_INITIAL_DECLINE_RETRY',location:{file:fileURLToPath(new URL('../stripe-billing-decline-retry.spec.ts',import.meta.url))},
  parent:{project:()=>({name:'owned-initial-decline-retry'})},annotations:[]} as unknown as TestCase;
 const r={retry:0,status:'failed',annotations:[]} as unknown as TestResult;
 reporter.onTestBegin(t,r);
 await assert.rejects(runDeclineRetryCase(owner,async()=>assert.fail('browser must not open'),r.annotations),/billing_NOT_ACCEPTED/);
 const lines:string[]=[],write=process.stdout.write;
 try{
  process.stdout.write=((line:string)=>{lines.push(line);return true;}) as typeof write;
  reporter.onTestEnd(t,r);reporter.onEnd({status:'failed'} as FullResult);
 }finally{process.stdout.write=write;}
 assert.deepEqual(lines,['owned_initial_decline_retry_terminal phase=PREFLIGHT code=PREFLIGHT cleanup=CONFIRMED\n','owned_initial_decline_retry_not_accepted full5b=NOT_ACCEPTED\n']);
});
