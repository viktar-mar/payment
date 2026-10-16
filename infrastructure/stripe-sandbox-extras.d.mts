import type {SandboxFixture} from './smoke-stage-5a.mjs';

export interface SandboxExtrasLimits { signal?:AbortSignal; deadline:number }
export type SandboxDonationKind = 'anonymous'|'emailOtp';
export interface SandboxDonationFacts {
  siteKey:string; campaignKey:string; amountMinor:500; hasCustomerAuthority:boolean;
}
export interface SandboxDonationIdentity {
  checkoutId:string; paymentId:string; donationId:string; campaignKey:string;
  campaignVersion:number; amountMinor:500; currency:'EUR';
}
export interface SandboxExtrasOptions {
  env:Record<string,string|undefined>;
  bootstrap:{
    resources():unknown;
    verify(limits:SandboxExtrasLimits):Promise<boolean>;
    withAdminCredentials(callback:(value:Readonly<{username:string;password:string}>)=>true):Promise<boolean>;
  };
  provisioner:{
    resources():unknown;
    withMissedCheckout(callback:(value:Readonly<{checkoutToken:string;checkoutId:string;paymentId:string}>)=>true):boolean;
  };
  cli:{
    validateBinding(limits:SandboxExtrasLimits):Promise<boolean>;
    bindMissedIntent(value:Readonly<{paymentId:string;siteId:string;operationId:string;intentId:string;
      amountMinor:2000;currency:'EUR';source:'CATALOG';externalAccountId:'acct_1UPiMpELswAd8tpR'}>):Promise<boolean>;
    stopListener(limits:SandboxExtrasLimits):Promise<boolean>;
    inspectIntent(limits:SandboxExtrasLimits):Promise<Readonly<{captured:true}>>;
  };
}
export interface SandboxExtrasDependencies {
  lstat?:typeof import('node:fs/promises').lstat;
  realpath?:typeof import('node:fs/promises').realpath;
  open?:typeof import('node:fs/promises').open;
  rename?:typeof import('node:fs/promises').rename;
  unlink?:typeof import('node:fs/promises').unlink;
  fetch?:typeof globalThis.fetch;
  now?:()=>number;
  setTimeout?:typeof globalThis.setTimeout;
  clearTimeout?:typeof globalThis.clearTimeout;
}
export interface SandboxExtrasPaymentEvidence {
  readonly phase:string; readonly confirmed:boolean;
  readonly key?:string; readonly checkoutId?:string; readonly paymentId?:string; readonly donationId?:string;
  readonly operationId?:string; readonly intentId?:string; readonly attemptId?:string;
  readonly eventId?:string; readonly outboxId?:string; readonly deliveryId?:string; readonly receiverVerified?:boolean;
}
export interface SandboxExtrasResources {
  readonly runId:string; readonly status:'NEW'|'OPENING'|'OPEN'|'CLOSED'|'PENDING';
  readonly donations:Readonly<Record<SandboxDonationKind,SandboxExtrasPaymentEvidence>>;
  readonly otp:Readonly<{phase:string;dispatchedAt?:number;challengeId?:string;expiresAt?:number;customerId?:string}>;
  readonly missed:SandboxExtrasPaymentEvidence & Readonly<{
    listenerStopped?:boolean;providerCaptured?:boolean;
    negativeBaseline?:Readonly<{journalIds:readonly string[];outboxIds:readonly string[];deliveryIds:readonly string[];receiverIds:readonly string[]}>;
  }>;
}
export interface SandboxAcceptanceExtras {
  open(limits:SandboxExtrasLimits):Promise<true>;
  beforeDonationCreate(kind:SandboxDonationKind,facts:SandboxDonationFacts,limits:SandboxExtrasLimits):Promise<string>;
  ackDonationCreate(kind:SandboxDonationKind,identity:SandboxDonationIdentity,limits:SandboxExtrasLimits):Promise<true>;
  beforeDonationPay(kind:SandboxDonationKind,limits:SandboxExtrasLimits):Promise<true>;
  confirmDonation(kind:SandboxDonationKind,limits:SandboxExtrasLimits):Promise<true>;
  beginOtp(callback:(value:Readonly<{email:string;siteKey:string}>)=>true,limits:SandboxExtrasLimits):Promise<true>;
  ackOtpChallenge(value:{challengeId:string;expiresAt:string},limits:SandboxExtrasLimits):Promise<true>;
  withOtpCode(callback:(code:string)=>true,limits:SandboxExtrasLimits):Promise<true>;
  beforeOtpVerify(limits:SandboxExtrasLimits):Promise<true>;
  ackOtpVerification(value:{challengeId:string;customerId:string},limits:SandboxExtrasLimits):Promise<true>;
  missedFixture(limits:SandboxExtrasLimits):Promise<SandboxFixture>;
  close(limits:SandboxExtrasLimits):Promise<true>;
  resources():SandboxExtrasResources;
}
export function createSandboxAcceptanceExtras(options:SandboxExtrasOptions,dependencies?:SandboxExtrasDependencies):Promise<SandboxAcceptanceExtras>;
