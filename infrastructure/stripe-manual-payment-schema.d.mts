interface ManualPaymentReportFields {
  readonly version:1;readonly result:'SUCCEEDED'|'NOT_STARTED'|'FAILED'|'PENDING';
  readonly phase:'ADMISSION'|'PREPARE'|'AUTHORIZATION'|'BINDING'|'LISTENER'|'START'|'PROVISION'|'HANDOFF'|'AWAITING_PAY'|'VERIFY'|'SUCCEEDED'|'CLEANUP';
  readonly confirmation_granted:boolean;readonly payment_confirmed:boolean;readonly provider_confirmed:boolean;readonly webhook_verified:boolean;readonly journal_verified:boolean;readonly callback_verified:boolean;
  readonly gateway_closed:boolean;readonly listener_closed:boolean;readonly runtime_closed:boolean;readonly browser_closure:'NOT_OWNED';readonly provenance:'CONTROLLER_REPORTED';
}
export type ManualPaymentReport=ManualPaymentReportFields&({readonly mode:'MANUAL_PAYMENT';readonly three_ds_verified?:never}|{readonly mode:'MANUAL_3DS';readonly three_ds_verified:boolean});
export function validateManualPaymentReport(value:unknown):Readonly<ManualPaymentReport>|null;
export function formatManualPaymentReport(value:unknown):string|null;
export function parseManualPaymentReport(wire:unknown):Readonly<ManualPaymentReport>|null;
