import {test} from '@playwright/test';
import {createOwnedBillingCancellation} from '../../../infrastructure/stripe-billing-acceptance.mjs';
import {snapshotBillingAcceptanceEnvironment} from '../../../infrastructure/stripe-billing-acceptance-options.mjs';
import {billingRunSettingsAllowed} from './support/stripe-billing-failed-renewal-settings';
import {runBillingCancellationBrowser} from './support/stripe-billing-acceptance';

let owner:Awaited<ReturnType<typeof createOwnedBillingCancellation>>;
test.beforeAll(async({},info)=>{
  const raw=process.env,env=snapshotBillingAcceptanceEnvironment(raw);
  const reporter=new URL('./support/stripe-billing-cancellation-reporter.ts',import.meta.url);
  if(env?.PAYMENT_STRIPE_BILLING_CASE!=='initial_cancel_withdraw' || info.project.name!=='owned-period-end-cancellation' ||
      !billingRunSettingsAllowed(info,reporter))throw Error('billing_OPT_IN_REQUIRED');
  const ports={backend:0,demo:0,receiver:0,postgres:0,mailpitHttp:0,mailpitSmtp:0};
  for(const name of Object.keys(ports) as (keyof typeof ports)[]){
    const value=raw['PAYMENT_STRIPE_SANDBOX_PORT_'+name.toUpperCase()];
    ports[name]=value && /^[1-9]\d{3,4}$/.test(value)?Number(value):0;
  }
  owner=await createOwnedBillingCancellation({env,ports,runId:raw.PAYMENT_STRIPE_SANDBOX_RUN_ID,
    expectedSource:raw.PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE,daemon:raw.PAYMENT_STRIPE_SANDBOX_DAEMON,
    approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:'initial_cancel_withdraw'});
});
test('OWNED_PERIOD_END_CANCELLATION',async({browser})=>{
  const controller=new AbortController();
  try{
    const result=await owner.run((port,limits)=>runBillingCancellationBrowser(browser,port,limits),
      {deadline:Date.now()+2700000,signal:controller.signal});
    if(result.scope!=='INITIAL_CANCEL_WITHDRAW'||!result.initialAccepted||!result.cancellationAccepted||
       !result.cleanupConfirmed||result.full5bAccepted!==false)throw Error('billing_NOT_ACCEPTED');
  }finally{controller.abort();await owner.close({deadline:Date.now()+120000});}
});
