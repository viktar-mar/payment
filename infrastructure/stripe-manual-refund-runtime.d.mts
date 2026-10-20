import type {RefundOptions,Limits,StateCapability,OriginalRefundTarget} from './stripe-manual-refund-state.mjs';
export interface RefundRuntime {
  resources():Readonly<OriginalRefundTarget>;
  restore(limits:Limits):Promise<true>;
  verify(limits:Limits):Promise<true>;
  withAdminCredentials(callback:(credentials:Readonly<{username:string;password:string}>)=>true|Promise<true>,limits:Limits):Promise<true>;
  verifyForwardTarget(limits:Limits):Promise<true>;
  verifyWebhookSecret(candidate:string,limits:Limits):Promise<true>;
  stop(cleanupLimits:Limits):Promise<true>;
}
export function createOwnedManualRefundRuntime(input:Readonly<{options:RefundOptions;state:StateCapability}>,dependencies?:Record<string,unknown>):Promise<Readonly<RefundRuntime>>;
