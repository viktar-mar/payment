import type {Limits,StateCapability,RefundProof} from './stripe-manual-refund-state.mjs';
import type {RefundRuntime} from './stripe-manual-refund-runtime.mjs';
export interface RefundCli {
  validateBinding(limits:Limits):Promise<true>;
  assertRefundListener(limits:Limits):true;
  inspectRefund(proof:Readonly<Pick<RefundProof,'refundId'|'providerReference'|'providerEventId'|'amountMinor'|'totalMinor'>>,limits:Limits):Promise<Readonly<{providerVerified:true;webhookVerified:true}>>;
}
export interface RefundSession {
  verifyBaseline(limits:Limits):Promise<true>;
  rotateCallback(limits:Limits):Promise<true>;
  refundPartial(limits:Limits):Promise<Readonly<RefundProof>>;
  refundRemainder(limits:Limits):Promise<Readonly<RefundProof>>;
  verifyFinal(limits:Limits):Promise<Readonly<{partialConfirmed:true;remainderConfirmed:true;providerVerified:true;webhookVerified:true;journalVerified:true;callbackVerified:true}>>;
  close(cleanupLimits:Limits):Promise<true>;
}
/** CLI must already have its captured payment bound; construction performs no IO. */
export function createManualRefundSession(input:Readonly<{runtime:RefundRuntime;cli:RefundCli;state:StateCapability}>,dependencies?:Record<string,unknown>):Promise<Readonly<RefundSession>>;
