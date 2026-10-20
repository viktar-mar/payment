export interface ManualRefundReport {
  readonly version:1;
  readonly mode:'MANUAL_REFUND';
  readonly result:'FAILED'|'PENDING'|'SUCCEEDED';
  readonly phase:'ADMISSION'|'RESTORE'|'BINDING'|'LISTENER'|'BASELINE'|'ROTATION'|'PARTIAL'|'REMAINDER'|'VERIFY'|'CLEANUP';
  readonly target_verified:boolean;
  readonly callback_rotated:boolean;
  readonly partial_confirmed:boolean;
  readonly remainder_confirmed:boolean;
  readonly provider_verified:boolean;
  readonly webhook_verified:boolean;
  readonly journal_verified:boolean;
  readonly callback_verified:boolean;
  readonly listener_closed:boolean;
  readonly runtime_closed:boolean;
  readonly provenance:'CONTROLLER_REPORTED';
}
export function validateManualRefundReport(value:unknown):Readonly<ManualRefundReport>|null;
export function formatManualRefundReport(value:unknown):string|null;
