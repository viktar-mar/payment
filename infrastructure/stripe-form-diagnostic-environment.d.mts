import type {FormFetchDestination,FormFetchFailureCode} from './stripe-form-diagnostic-network.mjs';
export interface FormCspEvidence {
  readonly csp_observation:'OBSERVED'|'UNAVAILABLE';
  readonly csp_enforced_mask:number;
  readonly csp_report_only_mask:number;
}
export interface FormResourceEvidence {
  readonly resource_observation:'OBSERVED'|'UNAVAILABLE';
  readonly resource_transport_failure_mask:number;
  readonly resource_http_4xx_mask:number;
  readonly resource_http_5xx_mask:number;
  readonly fetch_failure_destination:FormFetchDestination;
  readonly fetch_failure_code:FormFetchFailureCode;
}
export type FormEnvironmentEvidence = FormCspEvidence & FormResourceEvidence & {
  readonly environment_freshness:'SAMPLED'|'CACHED'|'UNAVAILABLE';
};
export function validateFormEnvironmentEvidence(value:unknown):Readonly<FormEnvironmentEvidence>|null;
export function unavailableFormEnvironmentEvidence():Readonly<FormEnvironmentEvidence>;
