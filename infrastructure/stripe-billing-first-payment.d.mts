import type {BillingBinding,BillingLimits} from './stripe-billing-acceptance.mjs';
export type {BillingLimits};
export interface FirstPaymentIdentity {phase:string;code:'OK';run_id:string;account_id:string;site_id:string;customer_id:string;price_id:string;subscription_id:string;operation_id:string}
export interface FirstPaymentPrepared extends FirstPaymentIdentity {
 phase:'PREPARED';
 action:{kind:'PROVIDER_CLIENT';secret:string;expires_at:string};
 session:{protocol:'SERVER_CONFIRM_V2';provider:'stripe';flow:'PAYMENT';publishable_key:string;client_secret:string;action_token:null;attempt_id:string;capability:string;expires_at:string};
}
/** Values come from the original SDK request body/path/header, never a harness-generated key. */
export interface FirstPaymentConfirmRequest {attempt_id:string;capability:string;confirmation_token:string;idempotency_key:string}
export interface FirstPaymentConfirmed extends FirstPaymentIdentity {phase:'OBSERVE';protocol:'SERVER_CONFIRM_V2';attempt_id:string;state:'OBSERVE';retry_allowed:false;successor_attempt_id:null;next_action_allowed:false;expires_at:string}
export interface BillingFirstPaymentPort {
 resources():BillingBinding;
 withOtpCode(callback:(code:string)=>true|Promise<true>,limits:BillingLimits):Promise<true>;
 acknowledgeCustomer(value:{customerId:string;siteId:string}):Promise<true>;
 prepare(customerSession:{token:string;origin:string},createKey:string,limits:BillingLimits):Promise<FirstPaymentPrepared>;
 confirm(originalSDKRequest:FirstPaymentConfirmRequest,limits:BillingLimits):Promise<FirstPaymentConfirmed>;
}
export interface BillingFirstPaymentResult {runId:string;expectedSource:string;scope:'OWNED_INITIAL_PAYMENT';initialAccepted:boolean;full5bAccepted:false;cleanupConfirmed:boolean;status:string;phase:string;code:string|null}
export function createOwnedBillingFirstPayment(options:unknown,dependencies?:Record<string,unknown>):Promise<{resources():BillingFirstPaymentResult;run(journey:(port:BillingFirstPaymentPort,limits:BillingLimits)=>Promise<unknown>,limits:BillingLimits):Promise<BillingFirstPaymentResult>;close():Promise<BillingFirstPaymentResult>}>;
