import type {RefundOptions,Limits} from './stripe-manual-refund-options.mjs';
export type {RefundOptions,Limits} from './stripe-manual-refund-options.mjs';
import type {RestoreService,OriginalRefundTarget} from './stripe-manual-refund-retained.mjs';
export type {RestoreService,RetainedContainer,OriginalRefundTarget} from './stripe-manual-refund-retained.mjs';
export interface RefundLeg {
  readonly amountMinor:700|1300;readonly key:string;readonly phase:'READY'|'DISPATCHING'|'ACKNOWLEDGED'|'CONFIRMED';
  readonly refundId:string|null;readonly providerReference:string|null;
}
export interface RefundProof {
  readonly refundId:string;readonly providerReference:string;readonly providerEventId:string;
  readonly amountMinor:700|1300;readonly totalMinor:700|2000;
  readonly providerVerified:true;readonly webhookVerified:true;readonly journalVerified:true;readonly callbackVerified:true;
}
export interface StateCapability {
  readonly target:Readonly<OriginalRefundTarget>;
  beginRestore(service:RestoreService,limits:Limits):Promise<true>;
  ackRestore(service:RestoreService,limits:Limits):Promise<true>;
  beginRotation(limits:Limits):Promise<true>;
  ackRotation(version:2,limits:Limits):Promise<true>;
  beginConfigure(version:2,limits:Limits):Promise<true>;
  ackConfigure(version:2,limits:Limits):Promise<true>;
  beginRefund(index:0|1,limits:Limits):Promise<Readonly<RefundLeg>>;
  ackRefund(index:0|1,value:Readonly<{refundId:string}>,limits:Limits):Promise<true>;
  confirmRefund(index:0|1,proof:RefundProof,limits:Limits):Promise<true>;
  withRetainedAdmin(callback:(value:Readonly<{username:string;password:string}>)=>true|Promise<true>,limits:Limits):Promise<true>;
  verifyRetainedWebhookSecret(candidate:string,limits:Limits):Promise<true>;
  close(value:Readonly<{retain:boolean}>,cleanupLimits:Limits):Promise<true>;
}
/** Dependencies are internal IO seams; root is used solely for synthetic offline fixtures. */
export function openManualRefundState(options:RefundOptions,limits:Limits,dependencies?:Record<string,unknown>):Promise<Readonly<StateCapability>>;
