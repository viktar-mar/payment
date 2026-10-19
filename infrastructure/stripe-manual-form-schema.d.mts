import type {FormDiagnosticSample} from './stripe-form-diagnostic-schema.mjs';
import type {ManualGatewayObservationKind,ManualGatewayEnd} from './stripe-manual-form-gateway.mjs';
export interface ManualFormReport {
  readonly version:1;readonly mode:'MANUAL_FORM_DIAGNOSTIC';
  readonly observation:ManualGatewayObservationKind;readonly end:ManualGatewayEnd;
  readonly sample:Readonly<FormDiagnosticSample>|null;
  readonly no_confirmation:boolean;readonly gateway_closed:boolean;readonly runtime_closed:boolean;
  readonly browser_closure:'NOT_OWNED';readonly provenance:'BROWSER_REPORTED';
}
export function validateManualFormReport(value:unknown):Readonly<ManualFormReport>|null;
export function formatManualFormReport(value:unknown):string|null;
export function parseManualFormReport(wire:unknown):Readonly<ManualFormReport>|null;
