import type {RefundOptions,Limits} from './stripe-manual-refund-options.mjs';
export type {RefundOptions,Limits} from './stripe-manual-refund-options.mjs';
export type RestoreService='postgres'|'mailpit'|'callback-receiver'|'demo-site'|'backend';
export interface RetainedContainer {
  readonly id:string;readonly name:string;readonly image:string;readonly service:RestoreService|'issuer-installer'|'issuer-probe';
  readonly status:'exited';readonly startedAt:string;readonly networkId:string|null;
  readonly mounts:readonly Readonly<{Type:'bind'|'volume';Source?:string;Name?:string;Destination:string;RW:boolean}>[];
  readonly ports:Readonly<Record<string,readonly Readonly<{HostIp:'127.0.0.1';HostPort:string}>[]>>;
}
export interface OriginalRefundTarget {
  readonly originalRunId:'031b5ae7-6603-4339-b1b3-77231291ff34';
  readonly originalSource:'f0620e40004162d2b3eededa60512f50a492f4cc';
  readonly project:string;
  readonly containers:Readonly<Record<RestoreService|'issuer-installer'|'issuer-probe',Readonly<RetainedContainer>>>;
  readonly images:Readonly<Record<RestoreService,Readonly<{tag:string;id:string;user:string}>>>;
  readonly network:Readonly<{Id:string;Name:string;Labels:Readonly<Record<string,string>>;Driver:'bridge';Scope:'local'}>;
  readonly volumes:Readonly<Record<'postgres'|'issuer',Readonly<{Name:string;CreatedAt:string;Driver:'local';Scope:'local';Labels:Readonly<Record<string,string>>}>>>;
  readonly ports:Readonly<Record<'backend'|'demo'|'receiver'|'postgres'|'mailpitHttp'|'mailpitSmtp',number>>;
  readonly endpoints:Readonly<Record<'backend'|'demo'|'receiver'|'postgres'|'mailpitHttp'|'mailpitSmtp',string>>;
  readonly account:Readonly<{id:string;key:'stripe-sandbox-default';externalId:'acct_1UPiMpELswAd8tpR'}>;
  readonly site:Readonly<{id:string;key:string}>;
  readonly price:Readonly<{id:string;key:string;version:1;providerPriceId:string}>;
  readonly paymentId:string;readonly operationId:string;readonly intentId:string;
  readonly callbackVersion:1;readonly amountMinor:2000;readonly currency:'EUR';readonly source:'CATALOG';
}
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
