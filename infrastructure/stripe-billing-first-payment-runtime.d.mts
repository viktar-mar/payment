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
export function billingFirstPaymentPrepareCheckpoint(runtime:unknown):'PREPARE_REQUEST_AUTHORITY'|'PREPARE_REQUEST_TRANSPORT'|'PREPARE_REQUEST_RESPONSE'|null;
export function isBillingFirstPaymentDiagnostic(code:unknown):boolean;
export function isFailedRenewalRuntimeCheckpoint(code:unknown):boolean;
export function billingFailedRenewalRuntimeCheckpoint(runtime:unknown,limits:BillingLimits):string|null;

type FailedIdentity=Omit<FirstPaymentIdentity,'phase'>&{source_sha:string;original_frozen_time:number};
type Boundary=FailedIdentity&Record<'renewal_admission_id'|'initial_bill_id'|'initial_payment_id'|'boundary_id'|'boundary_ready_id'|'boundary_advance_id'|'boundary_operation_id'|'pending_graph_invocation_id'|'initial_invoice_id'|'initial_allocation_id'|'initial_payment_intent_id'|'renewal_invoice_id'|'boundary_time'|'period_start'|'period_end'|'pending_graph_accepted_at',string>;
type Armed=Boundary&Record<'arm_admission_id'|'arm_boundary_id'|'arm_run_id'|'arm_payment_method_id'|'arm_acknowledged_at',string>;
type Failed=Armed&Record<'failed_admission_id'|'finalization_ready_id'|'finalization_advance_id'|'finalization_operation_id'|'failure_claim_id'|'failed_bill_id'|'failed_allocation_id'|'failed_payment_intent_id'|'grace_deadline'|'failed_recorded_at',string>&{finalization_time:number};
type Last=Failed&Record<'grace_preparation_id'|'grace_ready_id'|'grace_operation_id'|'grace_invocation_id',string>&{grace_target:number;grace_frozen_time:number};
type At=Last&Record<'grace_at_ready_id'|'grace_at_operation_id'|'grace_at_invocation_id',string>&{grace_at_frozen_time:number};
type After=At&Record<'grace_after_ready_id'|'grace_after_operation_id'|'grace_after_invocation_id',string>&{grace_after_frozen_time:number};
type Reserved=After&Record<'method_reservation_id'|'method_operation_id',string>;
type MethodReady=Reserved&Record<'method_claim_id'|'method_setup_step_id'|'method_update_step_id'|'recovery_operation_id'|'recovery_bill_id'|'method_completed_at'|'setup_intent_id'|'replacement_method_id'|'recovery_allocation_id'|'recovery_payment_intent_id',string>;
type RecoveredAck=MethodReady&Record<'recovery_confirm_operation_id'|'recovery_confirm_resource_id'|'recovery_acknowledged_at'|'recovery_confirm_payment_intent_id'|'recovery_charge_id',string>&{recovery_confirm_operation_version:2;recovery_confirm_status:'ACKNOWLEDGED'};
type Published=RecoveredAck&Record<'recovery_publication_claim_id'|'recovery_publication_admission_id'|'recovery_publication_operation_id'|'recovery_publication_bill_id'|'recovery_publication_reservation_id'|'recovery_payment_id'|'recovery_published_at',string>&{recovery_revision:number;recovery_observation_sequence:number};
type Access<R extends 'GRACE'|'EXPIRED'|'PAID'>={access_reason:R;access_evaluated_at:string;access_valid_until:R extends 'EXPIRED'?null:string;access_allowed:R extends 'EXPIRED'?false:true};
type Stage<P extends string,T>=(limits:BillingLimits)=>Promise<Readonly<{phase:P}&T>>;
export interface FailedRenewalRuntime extends FirstPaymentRuntime {
 boundary:Stage<'RENEWAL_BOUNDARY',Boundary>;
 armDecline:Stage<'DECLINE_ARMED',Armed>;
 failedRenewal:Stage<'FAILED_RENEWAL',Failed>;
 graceLastSecond:Stage<'GRACE_LAST_SECOND',Last&Access<'GRACE'>>;
 graceAt:Stage<'GRACE_AT',At&Access<'EXPIRED'>>;
 graceAfter:Stage<'GRACE_AFTER',After&Access<'EXPIRED'>>;
 prepareMethod:Stage<'METHOD_PREPARED',Reserved&{action:import('./stripe-billing-first-payment.mjs').FailedMethodAction}>;
 selectMethod:Stage<'METHOD_SELECTED',Reserved&{session:import('./stripe-billing-first-payment.mjs').FailedMethodSession}>;
 confirmMethod:Stage<'METHOD_CONSUMED',Reserved&import('./stripe-billing-first-payment.mjs').FailedMethodPermit>;
 applyMethod:Stage<'METHOD_READY',MethodReady>;
 confirmRecovery:Stage<'RECOVERY_ACKNOWLEDGED',RecoveredAck>;
 observeRecovery:Stage<'RECOVERED_SETTLED_LOCAL',Published&Access<'PAID'>>;
}
export function createBillingFailedRenewalRuntime(options:{configuration:unknown;bootstrap:unknown},dependencies?:Record<string,unknown>):Readonly<FailedRenewalRuntime>;
