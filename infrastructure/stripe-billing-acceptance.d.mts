export interface BillingLimits {deadline:number;signal?:AbortSignal}
export interface BillingBinding {runId:string;expectedSource:string;endpoints:{backend:string;demo:string;receiver:string};siteKey:string;email:string;accountId:string;siteId:string;productId:string;priceId:string;customerId?:string;subscriptionId?:string;operationId?:string;attemptId?:string}
export interface BillingPort {resources():BillingBinding;beforeDispatch(kind:'create'|'confirm',key:string,identity?:Record<string,string>):Promise<true>;acknowledge(kind:'create'|'confirm',identity:Record<string,unknown>):Promise<true>;withOtpCode(callback:(code:string)=>true|Promise<true>,limits:BillingLimits):Promise<true>;acknowledgeCustomer(value:{customerId:string;siteId:string}):Promise<true>}
export interface BillingCancellationPort extends BillingPort {
  verifyInitial():Promise<true>;
  beforeCancellation(kind:'schedule'|'withdraw',key:string):Promise<true>;
  verifyCancellation(kind:'schedule'|'withdraw',operationId:string):Promise<true>;
}
export interface BillingCancellationResult extends Omit<BillingResult,'scope'> {
  scope:'INITIAL_CANCEL_WITHDRAW';
  cancellationAccepted:boolean;
}
export function createOwnedBillingCancellation(options:unknown,dependencies?:Record<string,unknown>):Promise<{resources():BillingCancellationResult;run(journey:(port:BillingCancellationPort,limits:BillingLimits)=>Promise<unknown>,limits:BillingLimits):Promise<BillingCancellationResult>;close(limits?:BillingLimits):Promise<BillingCancellationResult>}>;
export interface BillingResult {scope:'INITIAL_NON_CLOCK';initialAccepted:boolean;full5bAccepted:false;cleanupConfirmed:boolean;status:string;phase:string;code:string|null}
export function createOwnedBillingAcceptance(options:unknown,dependencies?:Record<string,unknown>):Promise<{resources():BillingResult;run(journey:(port:BillingPort,limits:BillingLimits)=>Promise<unknown>,limits:BillingLimits):Promise<BillingResult>;close(limits?:BillingLimits):Promise<BillingResult>}>;

export interface BillingRetryAttempt {subscriptionId:string;operationId:string;attemptId:string}
export interface BillingDeclineRetryPort extends BillingPort {
  beforeRetry(key:string,original:BillingRetryAttempt):Promise<true>;
  acknowledgeRetry(value:{attemptId:string;successorAttemptId:string}):Promise<true>;
  beforeSuccessorConfirm(key:string,successor:BillingRetryAttempt):Promise<true>;
  acknowledgeSuccessor(value:{attemptId:string;state:'OBSERVE';nextActionAllowed:false}):Promise<true>;
}
export interface BillingDeclineRetryResult extends Omit<BillingResult,'scope'> {
  scope:'INITIAL_DECLINE_RETRY';
  declineRetryAccepted:boolean;
}
export function createOwnedBillingDeclineRetry(options:unknown,dependencies?:Record<string,unknown>):Promise<{
  resources():BillingDeclineRetryResult;
  run(journey:(port:BillingDeclineRetryPort,limits:BillingLimits)=>Promise<unknown>,limits:BillingLimits):Promise<BillingDeclineRetryResult>;
  close(limits?:BillingLimits):Promise<BillingDeclineRetryResult>;
}>;

export interface BillingInitial3dsPort extends BillingPort {
  beforeChallenge(value:BillingRetryAttempt):Promise<true>;
  acknowledgeChallenge(value:BillingRetryAttempt):Promise<true>;
}
export interface BillingInitial3dsResult extends Omit<BillingResult,'scope'> {
  scope:'INITIAL_3DS';
  initial3dsAccepted:boolean;
}
export function createOwnedBillingInitial3ds(options:unknown,dependencies?:Record<string,unknown>):Promise<{
  resources():BillingInitial3dsResult;
  run(journey:(port:BillingInitial3dsPort,limits:BillingLimits)=>Promise<unknown>,limits:BillingLimits):Promise<BillingInitial3dsResult>;
  close(limits?:BillingLimits):Promise<BillingInitial3dsResult>;
}>;

export interface BillingInvoiceWebhooksPort extends BillingPort {releaseOriginalConfirm():true}
export interface BillingInvoiceWebhooksResult extends Omit<BillingResult,'scope'> {
  scope:'INITIAL_MISSED_REORDERED';
  invoiceWebhooksAccepted:boolean;
}
export function createOwnedBillingInvoiceWebhooks(options:unknown,dependencies?:Record<string,unknown>):Promise<{
  resources():BillingInvoiceWebhooksResult;
  run(journey:(port:BillingInvoiceWebhooksPort,limits:BillingLimits)=>Promise<unknown>,limits:BillingLimits):Promise<BillingInvoiceWebhooksResult>;
  close(limits?:BillingLimits):Promise<BillingInvoiceWebhooksResult>;
}>;
