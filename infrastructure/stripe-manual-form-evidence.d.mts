import type {FormDiagnosticSample} from './stripe-form-diagnostic-schema.mjs';
export interface ManualFormReadinessEvidence {
  readonly loaderstart_seen:boolean;
  readonly host_box:'ABSENT'|'ZERO_AREA'|'POSITIVE_AREA'|'UNAVAILABLE';
  readonly frame_box:ManualFormReadinessEvidence['host_box']|'MULTIPLE';
  readonly host_visibility:'ABSENT'|'VISIBLE'|'HIDDEN'|'UNAVAILABLE';
  readonly frame_visibility:ManualFormReadinessEvidence['host_visibility'];
  readonly frame_load:'NO_FRAME'|'NOT_SEEN'|'SEEN'|'UNAVAILABLE';
  readonly csp_observation:'OBSERVED'|'UNAVAILABLE';
  readonly csp_enforced_mask:number;readonly csp_report_only_mask:number;
}
export interface ManualFormObservation {readonly version:2;readonly sample:Readonly<FormDiagnosticSample>;readonly evidence:Readonly<ManualFormReadinessEvidence>;}
export function unavailableManualFormEvidence():Readonly<ManualFormReadinessEvidence>;
export function validateManualFormEvidence(value:unknown):Readonly<ManualFormReadinessEvidence>|null;
export function validateManualFormObservation(value:unknown):Readonly<ManualFormObservation>|null;
export function parseManualFormObservation(wire:unknown):Readonly<ManualFormObservation>|null;
