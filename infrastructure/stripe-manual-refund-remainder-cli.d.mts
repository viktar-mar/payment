import type {Limits,RemainderOptions,RemainderTarget,PriorPartialIdentity,RemainderIdentity} from './stripe-manual-refund-remainder-state.mjs';
import type {RemainderRuntime} from './stripe-manual-refund-remainder-runtime.mjs';
export interface RemainderCliOptions {
  readonly env:RemainderOptions['env'];
  readonly originalRunId:'031b5ae7-6603-4339-b1b3-77231291ff34';
  readonly priorRunId:'34941d87-12d3-4c74-8c5a-b62b7c914390';
  readonly cliDirectory:string;
  readonly backendPort:number;
  readonly verifyForwardTarget:RemainderRuntime['verifyForwardTarget'];
  readonly persistWebhookSecret:RemainderRuntime['verifyWebhookSecret'];
  readonly approvedBinding:Readonly<{externalAccountId:'acct_1UPiMpELswAd8tpR';apiVersion:'2026-09-30.endive'}>;
}
export interface RemainderCli {
  validateBinding(limits:Limits):Promise<true>;
  startListener(limits:Limits):Promise<true>;
  bindRemainderTarget(target:RemainderTarget,limits:Limits):Promise<true>;
  assertRemainderListener(limits:Limits):true;
  verifyPriorPartial(value:PriorPartialIdentity,limits:Limits):Promise<Readonly<{providerVerified:true;webhookVerified:true}>>;
  inspectRemainder(value:RemainderIdentity,limits:Limits):Promise<Readonly<{providerVerified:true;webhookVerified:true}>>;
  stopListener(cleanupLimits:Limits):Promise<true>;
}
export function createManualRefundRemainderCliAdapter(input:RemainderCliOptions,deps?:Record<string,unknown>):Promise<Readonly<RemainderCli>>;
