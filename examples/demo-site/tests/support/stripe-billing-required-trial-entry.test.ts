import assert from 'node:assert/strict';
import test from 'node:test';
import {readFile} from 'node:fs/promises';
// @ts-ignore The offline runner imports the original TypeScript module.
import {requiredTrialTerminalLine,runRequiredTrialCase} from './stripe-billing-required-trial-terminal.ts';

const complete=Object.freeze({scope:'INITIAL_REQUIRED_TRIAL',status:'CLOSED',phase:'COMPLETE',code:null,
  cleanupConfirmed:true,initialAccepted:false,requiredTrialAccepted:true,full5bAccepted:false});

test('ordinary Playwright discovery excludes required trial and the scoped runner cannot retry or record traffic',async()=>{
 const read=(name:string)=>readFile(new URL(name,import.meta.url),'utf8');
 const [ordinary,isolated,entry]=await Promise.all([
  read('../../playwright.config.ts'),read('../../playwright.stripe-billing-required-trial.config.ts'),
  read('../stripe-billing-required-trial.spec.ts'),
 ]);
 assert.match(ordinary,/testIgnore:[^\]]*"stripe-billing-required-trial\.spec\.ts"/);
 assert.match(isolated,/testMatch:'stripe-billing-required-trial\.spec\.ts'/);
 assert.match(isolated,/workers:1,retries:0/);
 assert.match(isolated,/trace:'off',video:'off',screenshot:'off'/);
 assert.ok(entry.indexOf('billingRunSettingsAllowed(info,reporter)') < entry.indexOf('owner=await createOwnedBillingRequiredTrial'));
 assert.ok(entry.indexOf('owner=await createOwnedBillingRequiredTrial') < entry.indexOf('async({browser},info)'));
});

test('terminal requires the exact non-cash trial proof and confirmed cleanup on run and close',async()=>{
 const amendments=[{}, {initialAccepted:true}, {requiredTrialAccepted:false}, {cleanupConfirmed:false}, {full5bAccepted:true}];
 for(const [index,override] of amendments.entries()){
  let starts=0,closes=0;
  const owner={run:async()=>{starts++;return complete;},
    close:async()=>{closes++;return {...complete,...override};},resources:()=>complete};
  const result=runRequiredTrialCase(owner,async()=>true,[]);
  if(index===0)await result;else await assert.rejects(result,/billing_NOT_ACCEPTED/);
  assert.equal(starts,1);
  assert.equal(closes,1);
 }
});

test('terminal allows setup and proof checkpoints and excludes raw data',()=>{
 const marker='owned_initial_required_trial_terminal phase=TRIAL_EVIDENCE code=';
 assert.equal(requiredTrialTerminalLine(marker+'TRIAL_EVIDENCE cleanup=CONFIRMED'),marker+'TRIAL_EVIDENCE cleanup=CONFIRMED');
 assert.equal(requiredTrialTerminalLine(marker+'TRIAL_ORDER cleanup=UNCONFIRMED'),marker+'TRIAL_ORDER cleanup=UNCONFIRMED');
 assert.equal(requiredTrialTerminalLine(marker+'SK_TEST_PRIVATE cleanup=CONFIRMED'),null);
});
