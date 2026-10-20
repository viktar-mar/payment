import type {RemainderOptions,Limits,RemainderState,RemainderTarget} from './stripe-manual-refund-remainder-state.mjs';
export interface RemainderRuntime {
  resources():Readonly<RemainderTarget>;
  restore(limits:Limits):Promise<true>;
  verify(limits:Limits):Promise<true>;
  withAdminCredentials(callback:(credentials:Readonly<{username:string;password:string}>)=>true|Promise<true>,limits:Limits):Promise<true>;
  verifyForwardTarget(limits:Limits):Promise<true>;
  verifyWebhookSecret(candidate:string,limits:Limits):Promise<true>;
  stop(cleanupLimits:Limits):Promise<true>;
}
export function createOwnedManualRefundRemainderRuntime(input:Readonly<{options:RemainderOptions;state:RemainderState}>,dependencies?:Record<string,unknown>):Promise<Readonly<RemainderRuntime>>;
