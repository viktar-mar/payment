import type {ManualRefundDiagnostic} from './stripe-manual-refund-schema.mjs';
export interface ManualRefundRemainderReport {
  readonly version:1;
  readonly mode:'MANUAL_REFUND_REMAINDER';
  readonly result:'FAILED'|'PENDING'|'SUCCEEDED';
  readonly phase:'ADMISSION'|'RESTORE'|'BINDING'|'LISTENER'|'BASELINE'|'ROTATION'|'REMAINDER'|'VERIFY'|'CLEANUP';
  readonly target_verified:boolean;
  readonly prior_partial_verified:boolean;
  readonly prior_callback_delivery_verified:boolean;
  readonly callback_rotated:boolean;
  readonly remainder_confirmed:boolean;
  readonly provider_verified:boolean;
  readonly webhook_verified:boolean;
  readonly journal_verified:boolean;
  readonly callback_signature_verified:boolean;
  readonly final_total_verified:boolean;
  readonly listener_closed:boolean;
  readonly runtime_closed:boolean;
  readonly provenance:'CONTROLLER_REPORTED';
  readonly diagnostic?:ManualRefundDiagnostic;
}
export function validateManualRefundRemainderReport(value:unknown):Readonly<ManualRefundRemainderReport>|null;
export function formatManualRefundRemainderReport(value:unknown):string|null;
