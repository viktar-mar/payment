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
  const expired='owned_optional_trial_pause_resume_terminal phase=CLI_PROFILE code=CLI_PROFILE_EXPIRED cleanup=CONFIRMED';
  assert.equal(optionalTrialTerminalLine(expired),expired);
});

const resumeDiagnostic='OPTIONAL_TRIAL_RESUME_STRIPE_BILLING_CYCLE_ANCHOR';

test('terminal publishes only approved optional trial resume diagnostics',async()=>{
  const code=resumeDiagnostic;
  const stopped={...complete,phase:'RESUME',code,optionalTrialAccepted:false};
  const annotations:{type:string;description?:string}[]=[];
  await assert.rejects(runOptionalTrialCase({
    run:async()=>{throw Error('billing_'+code);},close:async()=>stopped,resources:()=>stopped,
  },async()=>true,annotations),/billing_NOT_ACCEPTED/);
  const prefix='owned_optional_trial_pause_resume_terminal phase=RESUME code=';
  const line=prefix+code+' cleanup=CONFIRMED';
  assert.deepEqual(annotations,[{type:'owned-optional-trial-terminal',description:line}]);
  assert.equal(optionalTrialTerminalLine(line),line);
  assert.equal(optionalTrialTerminalLine(prefix+'OPTIONAL_TRIAL_RESUME_PRIVATE_DETAIL cleanup=CONFIRMED'),null);
});

test('terminal parser rejects a resume code from a different phase',()=>{
  const line='owned_optional_trial_pause_resume_terminal phase=OPEN_PROOF code='+resumeDiagnostic+' cleanup=CONFIRMED';
  assert.equal(optionalTrialTerminalLine(line),null);
});

test('terminal formatter withholds a resume code from a different phase',async()=>{
  const stopped={...complete,phase:'OPEN_PROOF',code:resumeDiagnostic,optionalTrialAccepted:false};
  const annotations:{type:string;description?:string}[]=[];
  await assert.rejects(runOptionalTrialCase({
    run:async()=>{throw Error('billing_OPEN_PROOF');},close:async()=>stopped,resources:()=>stopped,
  },async()=>true,annotations),/billing_NOT_ACCEPTED/);
  assert.deepEqual(annotations,[]);
});
