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
