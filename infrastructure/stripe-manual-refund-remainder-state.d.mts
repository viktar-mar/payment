import type {Limits,RemainderOptions} from './stripe-manual-refund-remainder-options.mjs';
import type {OriginalRefundTarget,RetainedContainer,RestoreService} from './stripe-manual-refund-retained.mjs';
export type {Limits,RemainderOptions} from './stripe-manual-refund-remainder-options.mjs';
export type {OriginalRefundTarget,RetainedContainer,RestoreService} from './stripe-manual-refund-retained.mjs';
export interface RemainderTarget {readonly original:OriginalRefundTarget;readonly priorRunId:'34941d87-12d3-4c74-8c5a-b62b7c914390';readonly priorSource:string;readonly priorRefundId:string;readonly remainderKey:string;readonly callbackVersionBefore:2;readonly callbackVersionAfter:3;}
export interface PriorPartialIdentity {readonly refundId:string;readonly providerReference:string;readonly providerEventId:string;}
export interface PriorPartialProof extends PriorPartialIdentity {readonly cashEventId:string;readonly amountMinor:700;readonly totalMinor:700;readonly providerVerified:true;readonly webhookVerified:true;readonly journalVerified:true;readonly callbackDeliveryVerified:true;}
export interface RemainderIdentity {readonly refundId:string;readonly providerReference:string;readonly providerEventId:string;readonly amountMinor:1300;readonly totalMinor:2000;}
export interface RemainderProof extends RemainderIdentity {readonly cashEventId:string;readonly providerVerified:true;readonly webhookVerified:true;readonly journalVerified:true;readonly callbackSignatureVerified:true;}
export interface FinalRemainderProof {readonly priorPartialVerified:true;readonly priorCallbackDeliveryVerified:true;readonly remainderConfirmed:true;readonly providerVerified:true;readonly webhookVerified:true;readonly journalVerified:true;readonly callbackSignatureVerified:true;readonly finalTotalVerified:true;}
export type StoppedContainers=Readonly<Record<RestoreService,Readonly<RetainedContainer>>>;
export interface RemainderState {
 admit(limits:Limits):Promise<'READY'|'DENIED'|'PENDING'>;
 target():Readonly<RemainderTarget>;
 recordStopped(value:StoppedContainers,limits:Limits):Promise<true>;
 beginRestore(service:RestoreService,limits:Limits):Promise<true>;
 ackRestore(service:RestoreService,startedAt:string,limits:Limits):Promise<true>;
 confirmBaseline(proof:PriorPartialProof,limits:Limits):Promise<true>;
 beginRotation(limits:Limits):Promise<true>;
 ackRotation(version:3,limits:Limits):Promise<true>;
 beginConfigure(version:3,limits:Limits):Promise<true>;
 ackConfigure(version:3,limits:Limits):Promise<true>;
 beginRemainder(limits:Limits):Promise<Readonly<{amountMinor:1300;key:string;phase:'DISPATCHING';refundId:null;providerReference:null}>>;
 ackRemainder(value:Readonly<{refundId:string}>,limits:Limits):Promise<true>;
 confirmRemainder(proof:RemainderProof,limits:Limits):Promise<true>;
 confirmFinal(proof:FinalRemainderProof,limits:Limits):Promise<true>;
 withRetainedAdmin(callback:(value:Readonly<{username:string;password:string}>)=>true|Promise<true>,limits:Limits):Promise<true>;
 verifyRetainedWebhookSecret(candidate:string,limits:Limits):Promise<true>;
 close(cleanupLimits:Limits):Promise<true>;
}
/** Internal IO seams are for synthetic offline fixtures; construction is inert. */
export function createManualRefundRemainderState(options:RemainderOptions,dependencies?:Record<string,unknown>):Readonly<RemainderState>;
