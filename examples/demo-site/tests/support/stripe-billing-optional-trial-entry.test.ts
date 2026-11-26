import assert from 'node:assert/strict';
import test from 'node:test';
import {readFile} from 'node:fs/promises';
// @ts-ignore The offline runner imports the original TypeScript module.
import {optionalTrialTerminalLine,runOptionalTrialCase} from './stripe-billing-optional-trial-terminal.ts';

const complete=Object.freeze({scope:'OPTIONAL_TRIAL_PAUSE_RESUME',status:'CLOSED',phase:'COMPLETE',code:null,
  cleanupConfirmed:true,initialAccepted:false,optionalTrialAccepted:true,full5bAccepted:false});

test('default discovery excludes this lane and the dedicated configuration cannot retry or record',async()=>{
  const read=(name:string)=>readFile(new URL(name,import.meta.url),'utf8');
  const [ordinary,isolated,entry]=await Promise.all([read('../../playwright.config.ts'),
    read('../../playwright.stripe-billing-optional-trial.config.ts'),read('../stripe-billing-optional-trial.spec.ts')]);
  assert.match(ordinary,/testIgnore:[^\]]*"stripe-billing-optional-trial\.spec\.ts"/);
  assert.match(isolated,/testMatch:'stripe-billing-optional-trial\.spec\.ts'/);
  assert.match(isolated,/workers:1, retries:0/);
  assert.match(isolated,/trace:'off',video:'off',screenshot:'off'/);
  assert.ok(entry.indexOf('billingRunSettingsAllowed(info,reporter)')<entry.indexOf('owner = createOwnedBillingOptionalTrial'));
});

test('terminal requires successful owned proof and cleanup on both run and close',async()=>{
  for(const override of [{},{cleanupConfirmed:false},{optionalTrialAccepted:false},{full5bAccepted:true}]){
    let closed=0;
    const attempt=runOptionalTrialCase({
      run:async()=>complete,close:async()=>{closed++;return {...complete,...override};},resources:()=>complete,
    },async()=>true,[]);
    if(Object.keys(override).length)await assert.rejects(attempt,/billing_NOT_ACCEPTED/);
    else await attempt;
    assert.equal(closed,1);
  }
});

test('terminal limits failure to allowlisted phase and error labels',()=>{
  const label='owned_optional_trial_pause_resume_terminal phase=SETUP_PERMIT code=';
  assert.equal(optionalTrialTerminalLine(label+'SETUP_PERMIT cleanup=CONFIRMED'),label+'SETUP_PERMIT cleanup=CONFIRMED');
  assert.equal(optionalTrialTerminalLine(label+'SECRET_VALUE cleanup=CONFIRMED'),null);
});
