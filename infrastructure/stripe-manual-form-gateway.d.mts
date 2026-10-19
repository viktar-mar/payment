import type {IncomingMessage,Server,ServerResponse} from 'node:http';
import type {FormDiagnosticLimits,ManualFormHandoff} from './stripe-form-diagnostic-runtime.mjs';
import type {FormDiagnosticSample} from './stripe-form-diagnostic-schema.mjs';

import type {ManualFormReadinessEvidence} from './stripe-manual-form-evidence.mjs';
export type ManualGatewayObservationKind='NOT_STARTED'|'READY_REPORTED'|'PENDING_REPORTED'|'TERMINAL_REPORTED'|'NO_REPORT'|'INVALID_REPORT';
export type ManualGatewayEnd='READY_REPORTED'|'LOCAL_LIMIT'|'NOT_STARTED'|'ABORTED'|'ACTIVE_DEADLINE'|'FAILED';
/** Finite browser reports, never independent browser/financial proof.
 * Historical READY_REPORTED may pair with FAILED after admitted malformed IO;
 * only end=READY_REPORTED can support the controller's bounded success result.
 */
export interface ManualGatewayObservation {
  readonly observation:ManualGatewayObservationKind;
  readonly end:ManualGatewayEnd;
  readonly sample:Readonly<FormDiagnosticSample>|null;
  readonly evidence:Readonly<ManualFormReadinessEvidence>|null;
}
export interface ManualFormGatewayOptions {
  readonly origin:string;
  readonly assetEndpoint:string;
  readonly session:ManualFormHandoff['session'];
}
export interface ManualFormGatewayDependencies {
  readonly createServer?:(handler:(request:IncomingMessage,response:ServerResponse)=>void)=>Server;
  readonly fetch?:typeof globalThis.fetch;
  readonly randomBytes?:(size:number)=>Buffer;
  readonly now?:()=>number;
  readonly setTimeout?:(callback:()=>void,delay:number)=>unknown;
  readonly clearTimeout?:(handle:unknown)=>void;
}
export interface ManualFormGateway {
  open():Promise<Readonly<{url:string}>>;
  /** Early READY_REPORTED retains the page through the original visual cutoff. */
  wait():Promise<Readonly<ManualGatewayObservation>>;
  /** True proves this gateway's listener, sockets, responses and work settled. */
  close(limits:FormDiagnosticLimits):Promise<boolean>;
}
export function createManualFormGateway(options:ManualFormGatewayOptions,limits:FormDiagnosticLimits,dependencies?:ManualFormGatewayDependencies):Readonly<ManualFormGateway>;
