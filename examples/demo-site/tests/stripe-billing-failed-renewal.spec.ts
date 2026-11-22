import {test} from '@playwright/test';
import {join} from 'node:path';
import {openProfileOperator,writeProfileHandoff} from '../scripts/profile-operator.mjs';
import {createOwnedBillingFailedRenewal,formatBillingFailedRenewalDiagnostic} from '../../../infrastructure/stripe-billing-first-payment.mjs';
import {snapshotBillingFailedRenewalEnvironment} from '../../../infrastructure/stripe-billing-failed-renewal-options.mjs';
import {runFailedRenewalBrowser} from './support/stripe-billing-failed-renewal';
import {loadFailedRenewalStaticOriginMatcher} from './support/stripe-billing-failed-renewal-static-origin-pin';
import {failedRenewalRunSettingsAllowed} from './support/stripe-billing-failed-renewal-settings';

let owner:Awaited<ReturnType<typeof createOwnedBillingFailedRenewal>>;
test.beforeAll(async({},info)=>{
  const raw=process.env, env=snapshotBillingFailedRenewalEnvironment(raw);
  if(!env || info.project.name!=='owned-failed-renewal-manual-chrome' || !failedRenewalRunSettingsAllowed(info))throw Error('failed_renewal_OPT_IN_REQUIRED');
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
test('OWNED_FAILED_RENEWAL_GRACE_RECOVERY',async({browser},info)=>{
  const controller=new AbortController();
  let operator:Awaited<ReturnType<typeof openProfileOperator>>|undefined;
  try{
    if(process.env.PAYMENT_STRIPE_PROFILE_OPERATOR==='1'){
      const runId=process.env.PAYMENT_STRIPE_SANDBOX_RUN_ID;
      if(!runId || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(runId))throw Error('Invalid profile run');
      operator=await openProfileOperator({rehearsal:false,onAbandon:()=>controller.abort()});
      await writeProfileHandoff(join(info.project.outputDir,runId,'operator.html'),operator);
    }
    const journey=operator?runFailedRenewalBrowser(browser,controller,loadFailedRenewalStaticOriginMatcher(),operator.wait):
      runFailedRenewalBrowser(browser,controller,loadFailedRenewalStaticOriginMatcher());
    const result=await owner.run(journey,{deadline:Date.now()+2700000,signal:controller.signal});
    if(result.scope!=='OWNED_FAILED_RENEWAL_GRACE_RECOVERY' || !result.initialAccepted || !result.failedRenewalAccepted ||
        !result.cleanupConfirmed || result.full5bAccepted!==false)throw Error('failed_renewal_NOT_ACCEPTED');
  }finally{
    controller.abort();
    try{
      const terminal=await owner.close();
      const diagnostic=formatBillingFailedRenewalDiagnostic(terminal);
      if(diagnostic)info.annotations.push({type:'owned-failed-renewal-terminal',description:diagnostic});
    }finally{await operator?.close();}
  }
});
