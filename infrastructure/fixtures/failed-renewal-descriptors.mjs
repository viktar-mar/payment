// Inert test values only. No runtime/owner imports or external capabilities.
export const id=n=>'11111111-2222-4333-8444-'+String(n).padStart(12,'0');
export const F=1790000000,E=1792592000,N=1795270400,T=E+3600,G=E+259200,iso=n=>new Date(n*1000).toISOString();
export const opts=()=>({env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_BILLING_FAILED_RENEWAL:'true'},runId:id(1),expectedSource:'e'.repeat(40),daemon:'unix:///Users/test/docker.sock',ports:{backend:18080,demo:18081,receiver:18082,postgres:15432,mailpitHttp:18025,mailpitSmtp:11025},controlPort:18083,frozenTime:F,approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},selectedCase:'initial_failed_renewal_grace_recovery'});
export const scope=()=>({runId:id(1),expectedSource:'e'.repeat(40),accountId:id(2),siteId:id(3),customerId:id(4),priceId:id(5),productId:id(6),subscriptionId:id(7),operationId:id(8),attemptId:id(9),createKeyHash:'a'.repeat(64),confirmKeyHash:'b'.repeat(64),endpoints:{demo:'http://127.0.0.1:18081'}});
export const original=()=>({code:'OK',run_id:id(1),account_id:id(2),site_id:id(3),customer_id:id(4),price_id:id(5),subscription_id:id(7),operation_id:id(8)});
export const prepared=()=>({...original(),phase:'PREPARED',action:{kind:'PROVIDER_CLIENT',secret:'original',expires_at:iso(N)},session:{protocol:'SERVER_CONFIRM_V2',provider:'stripe',flow:'PAYMENT',publishable_key:'pk_test_inert',client_secret:'pi_original_secret_inert',action_token:null,attempt_id:id(9),capability:'selected',expires_at:iso(N)}});
export const confirmRequest=()=>({attempt_id:id(9),capability:'selected',confirmation_token:'ctoken_inert',idempotency_key:'sdk-original-confirm'});
export const confirmed=()=>({...original(),phase:'OBSERVE',protocol:'SERVER_CONFIRM_V2',attempt_id:id(9),state:'OBSERVE',retry_allowed:false,next_action_allowed:false,successor_attempt_id:null,expires_at:iso(N)});
export const initial=()=>({...original(),phase:'SETTLED_LOCAL',period_start:iso(F),paid_through:iso(E),access_evaluated_at:iso(F),access_valid_until:iso(F+300)});
export const routes=['boundary','arm-decline','failed','grace-last-second','grace-at','grace-after','method/prepare','method/select','method/confirm','method/apply','recovery/confirm','recovery/observe'];
export const methods=['boundary','armDecline','failedRenewal','graceLastSecond','graceAt','graceAfter','prepareMethod','selectMethod','confirmMethod','applyMethod','confirmRecovery','observeRecovery'];
export function responses(){
 let retained={...original(),source_sha:opts().expectedSource,original_frozen_time:F};
 const step=(phase,more={},local={})=>({...Object.assign(retained,more),phase,...local});
 const access=(reason,at,until)=>({access_reason:reason,access_allowed:until!==null,access_evaluated_at:iso(at),access_valid_until:until===null?null:iso(until)});
 return [
 step('RENEWAL_BOUNDARY',{renewal_admission_id:id(10),initial_bill_id:id(11),initial_payment_id:id(12),boundary_id:id(13),boundary_ready_id:id(14),boundary_advance_id:id(15),boundary_operation_id:id(16),pending_graph_invocation_id:id(17),initial_invoice_id:'in_original',initial_allocation_id:'inpay_original',initial_payment_intent_id:'pi_original',renewal_invoice_id:'in_debt',boundary_time:iso(E),period_start:iso(E),period_end:iso(N),pending_graph_accepted_at:iso(F+1)}),
 step('DECLINE_ARMED',{arm_admission_id:id(10),arm_boundary_id:id(13),arm_run_id:id(1),arm_payment_method_id:'pm_armed',arm_acknowledged_at:iso(F+2)}),
 step('FAILED_RENEWAL',{failed_admission_id:id(18),finalization_ready_id:id(19),finalization_advance_id:id(20),finalization_operation_id:id(21),failure_claim_id:id(22),failed_bill_id:id(23),finalization_time:T,failed_allocation_id:'inpay_debt',failed_payment_intent_id:'pi_debt',grace_deadline:iso(G),failed_recorded_at:iso(F+3)}),
 step('GRACE_LAST_SECOND',{grace_preparation_id:id(24),grace_ready_id:id(25),grace_operation_id:id(26),grace_invocation_id:id(27),grace_target:G-1,grace_frozen_time:G-1},access('GRACE',G-1,G)),
 step('GRACE_AT',{grace_at_ready_id:id(28),grace_at_operation_id:id(29),grace_at_invocation_id:id(30),grace_at_frozen_time:G},access('EXPIRED',G,null)),
 step('GRACE_AFTER',{grace_after_ready_id:id(31),grace_after_operation_id:id(32),grace_after_invocation_id:id(33),grace_after_frozen_time:G+1},access('EXPIRED',G+1,null)),
 step('METHOD_PREPARED',{method_reservation_id:id(34),method_operation_id:id(35)},{action:{kind:'PROVIDER_CLIENT',secret:'method-inert',expires_at:iso(N)}}),
 step('METHOD_SELECTED',{},{session:{protocol:'BROWSER_CONFIRM_V1',provider:'stripe',flow:'SETUP',publishable_key:'pk_test_inert',client_secret:'seti_inert_secret_example',action_token:'method-inert',attempt_id:null,capability:null,expires_at:iso(N)}}),
 step('METHOD_CONSUMED',{},{browser_confirm_allowed:true,state:'RESERVED'}),
 step('METHOD_READY',{method_claim_id:id(36),method_setup_step_id:id(37),method_update_step_id:id(38),recovery_operation_id:id(39),recovery_bill_id:id(23),method_completed_at:iso(F+4),setup_intent_id:'seti_inert',replacement_method_id:'pm_replacement',recovery_allocation_id:'inpay_debt',recovery_payment_intent_id:'pi_debt'}),
 step('RECOVERY_ACKNOWLEDGED',{recovery_confirm_operation_id:id(40),recovery_confirm_resource_id:id(39),recovery_confirm_operation_version:2,recovery_confirm_status:'ACKNOWLEDGED',recovery_acknowledged_at:iso(F+5),recovery_confirm_payment_intent_id:'pi_debt',recovery_charge_id:'ch_debt'}),
 step('RECOVERED_SETTLED_LOCAL',{recovery_publication_claim_id:id(41),recovery_publication_admission_id:id(18),recovery_publication_operation_id:id(39),recovery_publication_bill_id:id(23),recovery_publication_reservation_id:id(34),recovery_payment_id:id(42),recovery_published_at:iso(F+6),recovery_revision:4,recovery_observation_sequence:2},access('PAID',G+1,G+301))];
}
