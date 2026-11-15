import {test} from '@playwright/test';
import {createOwnedBillingFailedRenewal} from '../../../infrastructure/stripe-billing-first-payment.mjs';
import {snapshotBillingFailedRenewalEnvironment} from '../../../infrastructure/stripe-billing-failed-renewal-options.mjs';
import {runFailedRenewalBrowser} from './support/stripe-billing-failed-renewal';

let owner:Awaited<ReturnType<typeof createOwnedBillingFailedRenewal>>;
test.beforeAll(async({},info)=>{
  const raw=process.env, env=snapshotBillingFailedRenewalEnvironment(raw);
  if(!env || info.project.name!=='owned-failed-renewal-manual-chrome')throw Error('failed_renewal_OPT_IN_REQUIRED');
  const integer=(value:string|undefined)=>value && /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value))?Number(value):0;
  const ports={backend:0,demo:0,receiver:0,postgres:0,mailpitHttp:0,mailpitSmtp:0};
  for(const name of Object.keys(ports) as (keyof typeof ports)[])ports[name]=integer(raw['PAYMENT_STRIPE_SANDBOX_PORT_'+name.toUpperCase()]);
  owner=await createOwnedBillingFailedRenewal({
    env,ports,runId:raw.PAYMENT_STRIPE_SANDBOX_RUN_ID,expectedSource:raw.PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE,
    daemon:raw.PAYMENT_STRIPE_SANDBOX_DAEMON,controlPort:integer(raw.PAYMENT_STRIPE_BILLING_FAILED_RENEWAL_CONTROL_PORT),
    frozenTime:integer(raw.PAYMENT_STRIPE_BILLING_FAILED_RENEWAL_FROZEN_TIME),
    approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},
    selectedCase:'initial_failed_renewal_grace_recovery',
  });
});
test('OWNED_FAILED_RENEWAL_GRACE_RECOVERY',async({browser})=>{
  const controller=new AbortController();
  try{
    const result=await owner.run(runFailedRenewalBrowser(browser),{deadline:Date.now()+2700000,signal:controller.signal});
    if(result.scope!=='OWNED_FAILED_RENEWAL_GRACE_RECOVERY' || !result.initialAccepted || !result.failedRenewalAccepted ||
        !result.cleanupConfirmed || result.full5bAccepted!==false)throw Error('failed_renewal_NOT_ACCEPTED');
  }finally{controller.abort();await owner.close();}
});
