import {test} from '@playwright/test';
import {createOwnedBillingInvoiceWebhooks} from '../../../infrastructure/stripe-billing-acceptance.mjs';
import {snapshotBillingAcceptanceEnvironment} from '../../../infrastructure/stripe-billing-acceptance-options.mjs';
import {billingRunSettingsAllowed} from './support/stripe-billing-failed-renewal-settings';
import {runInvoiceWebhooksCase} from './support/stripe-billing-invoice-webhooks-terminal';
import {loadFailedRenewalStaticOriginMatcher} from './support/stripe-billing-failed-renewal-static-origin-pin';
import {runBillingInvoiceWebhooksBrowser} from './support/stripe-billing-acceptance';

let owner:Awaited<ReturnType<typeof createOwnedBillingInvoiceWebhooks>>;
test.beforeAll(async({},info)=>{
  const raw=process.env,env=snapshotBillingAcceptanceEnvironment(raw);
  const reporter=new URL('./support/stripe-billing-invoice-webhooks-reporter.ts',import.meta.url);
  if(env?.PAYMENT_STRIPE_BILLING_CASE!=='initial_missed_reordered' || info.project.name!=='owned-invoice-webhooks' ||
      !billingRunSettingsAllowed(info,reporter))throw Error('billing_OPT_IN_REQUIRED');
  const ports={backend:0,demo:0,receiver:0,postgres:0,mailpitHttp:0,mailpitSmtp:0};
  for(const name of Object.keys(ports) as (keyof typeof ports)[]){
    const value=raw['PAYMENT_STRIPE_SANDBOX_PORT_'+name.toUpperCase()];
    ports[name]=value && /^[1-9]\d{3,4}$/.test(value)?Number(value):0;
  }
  owner=await createOwnedBillingInvoiceWebhooks({env,ports,runId:raw.PAYMENT_STRIPE_SANDBOX_RUN_ID,
    expectedSource:raw.PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE,daemon:raw.PAYMENT_STRIPE_SANDBOX_DAEMON,
    approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:'initial_missed_reordered'});
});
test('OWNED_INITIAL_MISSED_REORDERED',async({browser},info)=>{
  await runInvoiceWebhooksCase(owner,(port,limits)=>
    runBillingInvoiceWebhooksBrowser(browser,port,limits,loadFailedRenewalStaticOriginMatcher()),info.annotations);
});
