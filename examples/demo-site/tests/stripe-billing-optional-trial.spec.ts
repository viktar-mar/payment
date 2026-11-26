import {test} from '@playwright/test';
import {mkdirSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {createOwnedBillingOptionalTrial} from '../../../infrastructure/stripe-billing-optional-trial-run.mjs';
import {snapshotBillingOptionalTrialEnvironment} from '../../../infrastructure/stripe-billing-optional-trial-options.mjs';
import {billingRunSettingsAllowed} from './support/stripe-billing-failed-renewal-settings';
import {loadFailedRenewalStaticOriginMatcher} from './support/stripe-billing-failed-renewal-static-origin-pin';
import {runOptionalTrialSetupBrowser} from './support/stripe-billing-optional-trial-setup';
import {runOptionalTrialCase} from './support/stripe-billing-optional-trial-terminal';

let owner:ReturnType<typeof createOwnedBillingOptionalTrial>;
test.beforeAll(async ({},info)=>{
  const raw = process.env;
  const env = snapshotBillingOptionalTrialEnvironment(raw);
  const reporter = new URL('./support/stripe-billing-optional-trial-reporter.ts',import.meta.url);
  if(!env || info.project.name!=='owned-optional-trial' || !billingRunSettingsAllowed(info,reporter))
    throw Error('billing_OPT_IN_REQUIRED');
  const hostPort = (name: string)=>{
    const value=raw['PAYMENT_STRIPE_SANDBOX_PORT_'+name];
    return value && /^[1-9]\d{3,4}$/.test(value)?Number(value):0;
  };
  owner = createOwnedBillingOptionalTrial({env,selectedCase:'optional_trial_pause_resume',
    ports:{backend:hostPort('BACKEND'),demo:hostPort('DEMO'),receiver:hostPort('RECEIVER'),
      postgres:hostPort('POSTGRES'),mailpitHttp:hostPort('MAILPITHTTP'),mailpitSmtp:hostPort('MAILPITSMTP')},
    controlPort:hostPort('CONTROL'),runId:raw.PAYMENT_STRIPE_SANDBOX_RUN_ID,
    expectedSource:raw.PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE,daemon:raw.PAYMENT_STRIPE_SANDBOX_DAEMON,
    approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'}});
});
test('OWNED_OPTIONAL_TRIAL_PAUSE_RESUME',async ({browser},info)=>{
  const outputDirectory=info.project.outputDir;
  await runOptionalTrialCase(owner,(permit,limits)=>runOptionalTrialSetupBrowser(
    browser,permit,limits,loadFailedRenewalStaticOriginMatcher(),witness=>{
      // Preserve just this public-asset witness after Playwright discards per-test output.
      mkdirSync(outputDirectory,{recursive:true,mode:0o700});
      writeFileSync(join(outputDirectory,'optional-trial-script-witness.json'),
        JSON.stringify(witness)+'\n',{flag:'wx',mode:0o600});
    }),info.annotations);
});
