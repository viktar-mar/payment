import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import type {TestCase,TestResult,FullResult} from '@playwright/test/reporter';
import {createOwnedBillingInitial3ds} from '../../../../infrastructure/stripe-billing-acceptance.mjs';
// @ts-ignore Offline tests execute the same worker and reporter modules.
import {challengeTerminalLine,runInitial3dsCase} from './stripe-billing-initial-3ds-terminal.ts';
// @ts-ignore Offline tests execute the same worker and reporter modules.
import Reporter from './stripe-billing-initial-3ds-reporter.ts';

test('ordinary discovery excludes the challenge and its synthetic checks',async()=>{
 const read=(name:string)=>readFile(new URL(name,import.meta.url),'utf8');
 const [ordinary,isolated,entry]=await Promise.all([read('../../playwright.config.ts'),read('../../playwright.stripe-billing-initial-3ds.config.ts'),read('../stripe-billing-initial-3ds.spec.ts')]);
 assert.match(ordinary,/testIgnore:[^\]]*"stripe-billing-initial-3ds\.spec\.ts"/);
 assert.match(ordinary,/testIgnore:[^\]]*"\*\*\/support\/stripe-billing-initial-3ds\*\.test\.ts"/);
 assert.match(isolated,/testMatch:'stripe-billing-initial-3ds\.spec\.ts'/);
 assert.match(isolated,/workers:1,retries:0/);assert.match(isolated,/trace:'off',video:'off',screenshot:'off'/);
 assert.match(entry,/PAYMENT_STRIPE_BILLING_CASE!=='initial_3ds'/);
 assert.ok(entry.indexOf('owner=await createOwnedBillingInitial3ds')<entry.indexOf('async({browser},info)'));
});
test('3DS terminal accepts only fixed provisioning diagnostics in the provisioning phase',async()=>{
 const terminal=(phase:string,code:string)=>'owned_initial_3ds_terminal phase='+phase+' code='+code+' cleanup=CONFIRMED';
 const exact=terminal('PROVISION','PROVISION_LOGIN_VERIFY');
 assert.equal(challengeTerminalLine(exact),exact);
 assert.equal(challengeTerminalLine(terminal('BROWSER','PROVISION_LOGIN_VERIFY')),null);
 assert.equal(challengeTerminalLine(terminal('PROVISION','PROVISION_LOGIN_PRIVATE')),null);
 assert.equal(challengeTerminalLine(exact+' detail=private'),null);
 const state={scope:'INITIAL_3DS',status:'CLOSED',phase:'PROVISION',code:'PROVISION_LOGIN_VERIFY',
  initialAccepted:false,initial3dsAccepted:false,full5bAccepted:false,cleanupConfirmed:true};
 const annotations:{type:string;description?:string}[]=[];
 const owner={run:async()=>{throw new Error('private sentinel');},close:async()=>state,resources:()=>state};
 await assert.rejects(runInitial3dsCase(owner,async()=>assert.fail(),annotations),/billing_NOT_ACCEPTED/);
 assert.equal(annotations[0]?.description,exact);
 assert.equal(annotations.length,1);
});
for(const mode of ['original','copied result','duplicate','foreign project','second attempt']){
 test('new owner failure reaches only its own result: '+mode,async()=>{
  const owner=await createOwnedBillingInitial3ds({
   env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_ACCEPTANCE:'true',PAYMENT_STRIPE_BILLING_CASE:'initial_3ds'},
   runId:'11111111-2222-4333-8444-555555555555',expectedSource:'b'.repeat(40),daemon:'unix:///Users/test/docker.sock',
   ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},
   approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:'initial_3ds',
  },{preflight:async()=>{throw Error('private error');}});
  const report=new Reporter(),t={expectedStatus:'passed',title:'OWNED_INITIAL_3DS',location:{file:fileURLToPath(new URL('../stripe-billing-initial-3ds.spec.ts',import.meta.url))},
   parent:{project:()=>({name:mode==='foreign project'?'other':'owned-initial-3ds'})},annotations:[]} as unknown as TestCase;
  const r={retry:mode==='second attempt'?1:0,status:'failed',annotations:[]} as unknown as TestResult;
  report.onTestBegin(t,r);
  await assert.rejects(runInitial3dsCase(owner,async()=>assert.fail(),r.annotations),/billing_NOT_ACCEPTED/);
  if(mode==='duplicate')r.annotations.push({...r.annotations.at(-1)!});
  const lines:string[]=[],original=process.stdout.write;
  try{
   process.stdout.write=((s:string)=>{lines.push(s);return true;}) as typeof original;
   report.onTestEnd(t,mode==='copied result'?{...r}:r);report.onEnd({status:'failed'} as FullResult);
  }finally{process.stdout.write=original;}
  assert.deepEqual(lines,[...(mode==='original'?['owned_initial_3ds_terminal phase=PREFLIGHT code=PREFLIGHT cleanup=CONFIRMED\n']:[]),'owned_initial_3ds_not_accepted full5b=NOT_ACCEPTED\n']);
 });
}
