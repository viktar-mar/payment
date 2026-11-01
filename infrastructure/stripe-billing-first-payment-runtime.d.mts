import type {BillingLimits,FirstPaymentPrepared,FirstPaymentConfirmRequest,FirstPaymentConfirmed,FirstPaymentIdentity} from './stripe-billing-first-payment.mjs';
export interface FirstPaymentSettlement extends FirstPaymentIdentity {phase:'SETTLED_LOCAL';period_start:string;paid_through:string;access_valid_until:string;access_evaluated_at:string}
export interface FirstPaymentRuntime {
 ready(limits:BillingLimits):Promise<true>;
 prepare(scope:Record<string,unknown>,limits:BillingLimits):Promise<FirstPaymentPrepared>;
 confirm(original:FirstPaymentConfirmRequest,limits:BillingLimits):Promise<FirstPaymentConfirmed>;
 observe(limits:BillingLimits):Promise<FirstPaymentIdentity|FirstPaymentSettlement>;
 close():Promise<true>;
}
export interface FirstRenewalBoundary extends FirstPaymentIdentity {phase:'RENEWAL_BOUNDARY';renewal_admission_id:string;boundary_time:string;period_start:string;period_end:string}
export interface FirstRenewalSettlement extends FirstPaymentIdentity {phase:'RENEWAL_SETTLED_LOCAL';renewal_admission_id:string;publication_id:string;period_start:string;paid_through:string;access_evaluated_at:string;access_valid_until:string}
export interface FirstRenewalRuntime extends FirstPaymentRuntime {boundary(limits:BillingLimits):Promise<FirstRenewalBoundary>;finalization(limits:BillingLimits):Promise<FirstRenewalSettlement>}
export function createBillingFirstPaymentRuntime(options:{configuration:unknown;bootstrap:unknown},dependencies?:Record<string,unknown>):Readonly<FirstPaymentRuntime>;
export function createBillingFirstRenewalRuntime(options:{configuration:unknown;bootstrap:unknown},dependencies?:Record<string,unknown>):Readonly<FirstRenewalRuntime>;
export function billingFirstPaymentDiagnostic(error:unknown):string|null;
export function isBillingFirstPaymentDiagnostic(code:unknown):boolean;
