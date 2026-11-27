import {join} from 'node:path';
import {openProfileOperator} from '../scripts/profile-operator.mjs';
import {test} from '@playwright/test';
import {createOwnedBillingInitial3ds} from '../../../infrastructure/stripe-billing-acceptance.mjs';
import {snapshotBillingAcceptanceEnvironment} from '../../../infrastructure/stripe-billing-acceptance-options.mjs';
import {billingRunSettingsAllowed} from './support/stripe-billing-failed-renewal-settings';
import {recordInitial3dsReplayStatus,runInitial3dsCase,type IssuerReplayStatus} from './support/stripe-billing-initial-3ds-terminal';
import {loadFailedRenewalStaticOriginMatcher} from './support/stripe-billing-failed-renewal-static-origin-pin';
import {loadPinnedInitial3dsIssuerOrigin} from './support/stripe-billing-initial-3ds-issuer-pin';
import {runBillingInitial3dsBrowser} from './support/stripe-billing-acceptance';
import {prepareIssuerReplayCapture} from './support/stripe-billing-initial-3ds-replay-capture';

let owner:Awaited<ReturnType<typeof createOwnedBillingInitial3ds>>;
let captureRefusedIssuerFixture:ReturnType<typeof prepareIssuerReplayCapture>;
test.beforeAll(async({},info)=>{
  const raw=process.env,env=snapshotBillingAcceptanceEnvironment(raw);
  const reporter=new URL('./support/stripe-billing-initial-3ds-reporter.ts',import.meta.url);
  if(env?.PAYMENT_STRIPE_BILLING_CASE!=='initial_3ds' || info.project.name!=='owned-initial-3ds' ||
      !billingRunSettingsAllowed(info,reporter))throw Error('billing_OPT_IN_REQUIRED');
  captureRefusedIssuerFixture=prepareIssuerReplayCapture(raw.PAYMENT_STRIPE_BILLING_3DS_REPLAY_FIXTURE_RUN_ID,raw.PAYMENT_STRIPE_SANDBOX_RUN_ID);
  const ports={backend:0,demo:0,receiver:0,postgres:0,mailpitHttp:0,mailpitSmtp:0};
  for(const name of Object.keys(ports) as (keyof typeof ports)[]){
    const value=raw['PAYMENT_STRIPE_SANDBOX_PORT_'+name.toUpperCase()];
    ports[name]=value && /^[1-9]\d{3,4}$/.test(value)?Number(value):0;
  }
  owner=await createOwnedBillingInitial3ds({env,ports,runId:raw.PAYMENT_STRIPE_SANDBOX_RUN_ID,
    expectedSource:raw.PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE,daemon:raw.PAYMENT_STRIPE_SANDBOX_DAEMON,
    approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:'initial_3ds'});
});
test('OWNED_INITIAL_3DS',async({browser},info)=>{
  let replayStatus:IssuerReplayStatus|undefined;
  const issuerReplay=captureRefusedIssuerFixture?{
    save:captureRefusedIssuerFixture,report:(status:IssuerReplayStatus)=>{replayStatus=status;},
  }:undefined;
  try{
    await runInitial3dsCase(owner,async(port,limits)=>{
      const runId=port.resources().runId;
      if(!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(runId))throw Error('billing_BROWSER');
      const controller=new AbortController(),signal=limits.signal?AbortSignal.any([limits.signal,controller.signal]):controller.signal;
      const operator=await openProfileOperator({rehearsal:false,mode:'challenge',onAbandon:()=>controller.abort(),
        handoffPath:join(info.project.outputDir,runId,'operator.html')});
      try{
        return await runBillingInitial3dsBrowser(browser,port,{...limits,signal},l=>operator.wait('challenge',l),
          loadFailedRenewalStaticOriginMatcher(),loadPinnedInitial3dsIssuerOrigin(),issuerReplay);
      }finally{controller.abort();await operator.close();}
    },info.annotations);
  }finally{recordInitial3dsReplayStatus(info.annotations,replayStatus);}
});
