import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import type {TestCase,TestResult,FullResult} from '@playwright/test/reporter';
import {createOwnedBillingInvoiceWebhooks} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
// @ts-ignore Offline tests execute the same worker and reporter modules.
import {runInvoiceWebhooksCase} from './stripe-billing-invoice-webhooks-terminal.ts';
// @ts-ignore Offline tests execute the same worker and reporter modules.
import Reporter from './stripe-billing-invoice-webhooks-reporter.ts';

test('ordinary discovery excludes the invoice case and its synthetic checks',async()=>{
 const read=(name:string)=>readFile(new URL(name,import.meta.url),'utf8');
 const [ordinary,isolated,entry]=await Promise.all([read('../../playwright.config.ts'),read('../../playwright.stripe-billing-invoice-webhooks.config.ts'),read('../stripe-billing-invoice-webhooks.spec.ts')]);
 assert.match(ordinary,/testIgnore:[^\]]*"stripe-billing-invoice-webhooks\.spec\.ts"/);
 assert.match(ordinary,/testIgnore:[^\]]*"\*\*\/support\/stripe-billing-invoice-webhooks\*\.test\.ts"/);
 assert.match(isolated,/testMatch:'stripe-billing-invoice-webhooks\.spec\.ts'/);
 assert.match(isolated,/workers:1,retries:0/);assert.match(isolated,/trace:'off',video:'off',screenshot:'off'/);
 assert.match(entry,/PAYMENT_STRIPE_BILLING_CASE!=='initial_missed_reordered'/);
 assert.ok(entry.indexOf('owner=await createOwnedBillingInvoiceWebhooks')<entry.indexOf('async({browser},info)'));
});
for(const mode of ['original','copied result','duplicate','foreign project','second attempt']){
 test('new owner failure reaches only its own result: '+mode,async()=>{
  const owner=await createOwnedBillingInvoiceWebhooks({
   env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true',PAYMENT_STRIPE_BILLING_CASE:'initial_missed_reordered'},
   runId:'11111111-2222-4333-8444-555555555555',expectedSource:'b'.repeat(40),daemon:'unix:///Users/test/docker.sock',
   ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},
   approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:'initial_missed_reordered',
  },{preflight:async()=>{throw Error('private error');}});
  const report=new Reporter(),t={expectedStatus:'passed',title:'OWNED_INITIAL_MISSED_REORDERED',location:{file:fileURLToPath(new URL('../stripe-billing-invoice-webhooks.spec.ts',import.meta.url))},
   parent:{project:()=>({name:mode==='foreign project'?'other':'owned-invoice-webhooks'})},annotations:[]} as unknown as TestCase;
  const r={retry:mode==='second attempt'?1:0,status:'failed',annotations:[]} as unknown as TestResult;
  report.onTestBegin(t,r);
  await assert.rejects(runInvoiceWebhooksCase(owner,async()=>assert.fail(),r.annotations),/billing_NOT_ACCEPTED/);
  if(mode==='duplicate')r.annotations.push({...r.annotations.at(-1)!});
  const lines:string[]=[],original=process.stdout.write;
  try{
   process.stdout.write=((s:string)=>{lines.push(s);return true;}) as typeof original;
   report.onTestEnd(t,mode==='copied result'?{...r}:r);report.onEnd({status:'failed'} as FullResult);
  }finally{process.stdout.write=original;}
  assert.deepEqual(lines,[...(mode==='original'?['owned_initial_missed_reordered_terminal phase=PREFLIGHT code=PREFLIGHT cleanup=CONFIRMED\n']:[]),'owned_initial_missed_reordered_not_accepted full5b=NOT_ACCEPTED\n']);
 });
}
