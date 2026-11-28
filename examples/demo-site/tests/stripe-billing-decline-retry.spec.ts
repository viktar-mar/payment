import {test} from '@playwright/test';
import {createOwnedBillingDeclineRetry} from '../../../infrastructure/stripe-billing-acceptance.mjs';
import {snapshotBillingAcceptanceEnvironment} from '../../../infrastructure/stripe-billing-acceptance-options.mjs';
import {billingRunSettingsAllowed} from './support/stripe-billing-failed-renewal-settings';
import {runDeclineRetryCase} from './support/stripe-billing-decline-retry-terminal';
import {loadFailedRenewalStaticOriginMatcher} from './support/stripe-billing-failed-renewal-static-origin-pin';
import {runBillingDeclineRetryBrowser} from './support/stripe-billing-acceptance';

let owner:Awaited<ReturnType<typeof createOwnedBillingDeclineRetry>>;
test.beforeAll(async({},info)=>{
  const raw=process.env,env=snapshotBillingAcceptanceEnvironment(raw);
  const reporter=new URL('./support/stripe-billing-decline-retry-reporter.ts',import.meta.url);
  if(env?.PAYMENT_STRIPE_BILLING_CASE!=='initial_decline_retry' || info.project.name!=='owned-initial-decline-retry' ||
      !billingRunSettingsAllowed(info,reporter))throw Error('billing_OPT_IN_REQUIRED');
  const ports={backend:0,demo:0,receiver:0,postgres:0,mailpitHttp:0,mailpitSmtp:0};
  for(const name of Object.keys(ports) as (keyof typeof ports)[]){
    const value=raw['PAYMENT_STRIPE_SANDBOX_PORT_'+name.toUpperCase()];
    ports[name]=value && /^[1-9]\d{3,4}$/.test(value)?Number(value):0;
  }
  owner=await createOwnedBillingDeclineRetry({env,ports,runId:raw.PAYMENT_STRIPE_SANDBOX_RUN_ID,
    expectedSource:raw.PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE,daemon:raw.PAYMENT_STRIPE_SANDBOX_DAEMON,
    approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:'initial_decline_retry'});
});
test('OWNED_INITIAL_DECLINE_RETRY',async({browser},info)=>{
  await runDeclineRetryCase(owner,(port,limits)=>runBillingDeclineRetryBrowser(browser,port,limits,loadFailedRenewalStaticOriginMatcher()),info.annotations);
});
