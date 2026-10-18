export type FormDiagnosticOutcome = 'OBSERVING'|'FAILED'|'ABORTED'|'DEADLINE_REACHED'|'INSTRUMENTATION_UNAVAILABLE'|'UNEXPECTED_ACTIVITY';
export type FormDiagnosticResult = 'FORM_READY_BEFORE_15S'|'FORM_READY_AFTER_15S'|'WAITING_CLIENT_SESSION'|'WAITING_STRIPE_LOADER'|
  'ELEMENT_SETUP_INCOMPLETE'|'WAITING_ELEMENT_READY'|'WAITING_PRESENTATION_CATCHUP'|'WIDGET_HANDOFF_INCOMPLETE'|'ELEMENT_LOADERROR'|
  'OPERATION_FAILED'|'ABORTED'|'DEADLINE_REACHED'|'INSTRUMENTATION_UNAVAILABLE'|'BINDING_LOST'|'CONFIRMATION_BLOCKED'|'UNEXPECTED_ACTIVITY';
export interface FormDiagnosticSample {
  readonly version:1;
  readonly binding:'ARMED'|'BOUND'|'LOST'|'CLOSED';
  readonly capability_started:boolean;
  readonly capability_ready:boolean;
  readonly loader_started:boolean;
  readonly loader_ready:boolean;
  readonly element_created:boolean;
  readonly mount_called:boolean;
  readonly mount_returned:boolean;
  readonly ready_seen:boolean;
  readonly loaderror_seen:boolean;
  readonly view_completed:boolean;
  readonly widget_completed:boolean;
  readonly confirmation_blocked:boolean;
  readonly catchup:'NOT_STARTED'|'PENDING'|'RESOLVED'|'REJECTED'|'ABORTED';
  readonly operation:'PENDING'|'COMPLETED'|'FAILED'|'ABORTED';
  readonly widget_state:'idle'|'loading'|'ready'|'checking'|'processing'|'requires_action'|'succeeded'|'failed'|'unknown'|'expired'|'UNAVAILABLE';
  readonly element_state:'UNAVAILABLE'|'NO_HOST'|'EMPTY'|'FRAME';
  readonly pay_state:'UNAVAILABLE'|'ABSENT'|'HIDDEN'|'DISABLED'|'ENABLED';
  readonly pay_visible:boolean;
  readonly bucket:'BEFORE_15S'|'AFTER_15S'|'AT_LIMIT';
}
export interface FormDiagnosticProofs {
  readonly no_confirmation:boolean;
  readonly browser_closed:boolean;
  readonly context_closed:boolean;
  readonly runtime_closed:boolean;
}
export type FormDiagnosticFrame = Readonly<{version:1;result:FormDiagnosticResult} & FormDiagnosticProofs> |
  Readonly<FormDiagnosticSample & {result:FormDiagnosticResult} & FormDiagnosticProofs>;
export function validateFormDiagnosticSample(value:unknown):Readonly<FormDiagnosticSample>|null;
export function classifyFormDiagnosticSample(value:unknown,outcome?:FormDiagnosticOutcome):FormDiagnosticResult;
export function buildFormDiagnosticFrame(sample:FormDiagnosticSample|null,outcome:FormDiagnosticOutcome,proofs:FormDiagnosticProofs):FormDiagnosticFrame;
export function formatFormDiagnosticFrame(value:unknown):string|null;
export function parseFormDiagnosticFrame(wire:string):FormDiagnosticFrame|null;
