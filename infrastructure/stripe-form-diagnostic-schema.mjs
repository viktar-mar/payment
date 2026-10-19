/** Pure finite data boundary shared by the browser, owner and safe reporter. */
import {validateFormEnvironmentEvidence,unavailableFormEnvironmentEvidence} from './stripe-form-diagnostic-environment.mjs';
const FLAGS = ['capability_started','capability_ready','loader_started','loader_ready','element_created',
  'mount_called','mount_returned','ready_seen','loaderror_seen','view_completed','widget_completed','confirmation_blocked'];
const DOMAINS = {
  version:[1], binding:['ARMED','BOUND','LOST','CLOSED'],
  catchup:['NOT_STARTED','PENDING','RESOLVED','REJECTED','ABORTED'], operation:['PENDING','COMPLETED','FAILED','ABORTED'],
  widget_state:['idle','loading','ready','checking','processing','requires_action','succeeded','failed','unknown','expired','UNAVAILABLE'],
  element_state:['UNAVAILABLE','NO_HOST','EMPTY','FRAME'], pay_state:['UNAVAILABLE','ABSENT','HIDDEN','DISABLED','ENABLED'],
  bucket:['BEFORE_15S','AFTER_15S','AT_LIMIT'], pay_visible:[false,true],
};
for (const key of FLAGS) DOMAINS[key] = [false,true];
const SAMPLE_KEYS = ['version','binding',...FLAGS,'catchup','operation','widget_state','element_state','pay_state','pay_visible','bucket'];
const STAGE_KEYS = SAMPLE_KEYS.filter(key=>key!=='version');
const ENVIRONMENT_KEYS = Object.keys(unavailableFormEnvironmentEvidence());
const PROOF_KEYS = ['no_confirmation','browser_closed','context_closed','runtime_closed'];
const FAILURES = ['OPERATION_FAILED','ABORTED','DEADLINE_REACHED','INSTRUMENTATION_UNAVAILABLE','BINDING_LOST','CONFIRMATION_BLOCKED','UNEXPECTED_ACTIVITY'];
const RESULTS = ['FORM_READY_BEFORE_15S','FORM_READY_AFTER_15S','WAITING_CLIENT_SESSION','WAITING_STRIPE_LOADER',
  'ELEMENT_SETUP_INCOMPLETE','WAITING_ELEMENT_READY','WAITING_PRESENTATION_CATCHUP','WIDGET_HANDOFF_INCOMPLETE','ELEMENT_LOADERROR',...FAILURES];
const OUTCOMES = {FAILED:'OPERATION_FAILED',ABORTED:'ABORTED',DEADLINE_REACHED:'DEADLINE_REACHED',
  INSTRUMENTATION_UNAVAILABLE:'INSTRUMENTATION_UNAVAILABLE',UNEXPECTED_ACTIVITY:'UNEXPECTED_ACTIVITY'};
const encoder = new TextEncoder();

function exactData(value, keys) {
  try {
    if (value === null || typeof value !== 'object') return null;
    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) return null;
    const descriptors = Object.getOwnPropertyDescriptors(value);
    const names = Reflect.ownKeys(descriptors);
    if (names.length !== keys.length || names.some(key => typeof key !== 'string' || !keys.includes(key))) return null;
    const copy = {};
    for (const key of keys) {
      const descriptor = descriptors[key];
      if (!descriptor || !Object.hasOwn(descriptor,'value') || !descriptor.enumerable) return null;
      copy[key] = descriptor.value;
    }
    return copy;
  } catch { return null; }
}

export function validateFormDiagnosticSample(value) {
  const copy = exactData(value,SAMPLE_KEYS);
  if (!copy || SAMPLE_KEYS.some(key => !DOMAINS[key].includes(copy[key]))) return null;
  return Object.freeze(copy);
}

export function validateFormDiagnosticObservation(value) {
  const data=exactData(value,['sample','environment']);
  if(!data)return null;
  const sample=data.sample===null?null:validateFormDiagnosticSample(data.sample);
  const environment=validateFormEnvironmentEvidence(data.environment);
  if((data.sample!==null&&!sample)||!environment)return null;
  return Object.freeze({sample,environment});
}

function sampledEnvironment(environment) {
  return environment.environment_freshness==='SAMPLED'&&environment.csp_observation==='OBSERVED'
    &&environment.resource_observation==='OBSERVED';
}

function cause(outcome) {
  return typeof outcome === 'string' && Object.hasOwn(OUTCOMES,outcome) ? OUTCOMES[outcome] : null;
}

export function classifyFormDiagnosticSample(value, outcome = 'OBSERVING') {
  const failure = cause(outcome);
  if (failure) return failure;
  if (outcome !== 'OBSERVING') return 'INSTRUMENTATION_UNAVAILABLE';
  const s = validateFormDiagnosticSample(value);
  if (!s) return 'INSTRUMENTATION_UNAVAILABLE';
  if (s.confirmation_blocked) return 'CONFIRMATION_BLOCKED';
  if (s.binding === 'LOST' || s.binding === 'CLOSED') return 'BINDING_LOST';
  if (s.operation === 'ABORTED' || s.catchup === 'ABORTED') return 'ABORTED';
  if (s.operation === 'FAILED' || s.catchup === 'REJECTED') return 'OPERATION_FAILED';
  if (s.loaderror_seen) return 'ELEMENT_LOADERROR';
  // Independent flags allow a synchronous ready during mount, but impossible
  // completion claims cannot be promoted to a more specific diagnosis.
  if ((s.capability_ready && !s.capability_started) || (s.loader_started && !s.capability_ready)
    || (s.loader_ready && !s.loader_started) || (s.element_created && !s.loader_ready)
    || (s.mount_called && !s.element_created) || (s.mount_returned && !s.mount_called)
    || (s.ready_seen && !s.mount_called) || (s.catchup !== 'NOT_STARTED' && !s.ready_seen)
    || (s.view_completed && (!s.mount_returned || !s.ready_seen || s.catchup === 'PENDING'))
    || (s.widget_completed && !s.view_completed)) return 'INSTRUMENTATION_UNAVAILABLE';
  const complete = s.binding === 'BOUND' && s.capability_ready && s.loader_ready && s.element_created
    && s.mount_returned && s.ready_seen && s.view_completed && s.widget_completed
    && s.operation === 'COMPLETED' && s.widget_state === 'ready' && s.element_state === 'FRAME'
    && s.pay_state === 'ENABLED' && s.pay_visible;
  if (complete) return s.bucket === 'BEFORE_15S' ? 'FORM_READY_BEFORE_15S' : 'FORM_READY_AFTER_15S';
  if (s.binding !== 'BOUND') return 'INSTRUMENTATION_UNAVAILABLE';
  if (s.view_completed) return 'WIDGET_HANDOFF_INCOMPLETE';
  if (s.operation !== 'PENDING') return 'INSTRUMENTATION_UNAVAILABLE';
  if (!s.capability_ready) return 'WAITING_CLIENT_SESSION';
  if (!s.loader_ready) return 'WAITING_STRIPE_LOADER';
  if (!s.element_created || !s.mount_returned) return 'ELEMENT_SETUP_INCOMPLETE';
  if (!s.ready_seen) return 'WAITING_ELEMENT_READY';
  if (s.catchup === 'PENDING' && !s.view_completed) return 'WAITING_PRESENTATION_CATCHUP';
  return 'INSTRUMENTATION_UNAVAILABLE';
}

function safeProofs(value) {
  const copy = exactData(value,PROOF_KEYS);
  return Object.fromEntries(PROOF_KEYS.map(key => [key, copy !== null && copy[key] === true]));
}

export function buildFormDiagnosticFrame(value, outcome, proofs, evidence) {
  const sample = validateFormDiagnosticSample(value);
  const environment=validateFormEnvironmentEvidence(evidence)??unavailableFormEnvironmentEvidence();
  let result = classifyFormDiagnosticSample(sample,outcome);
  const sampled=sampledEnvironment(environment);
  if(!sampled&&!FAILURES.includes(result))result='INSTRUMENTATION_UNAVAILABLE';
  const stage=sample&&sampled?Object.fromEntries(STAGE_KEYS.map(key=>[key,sample[key]])):{};
  return Object.freeze({version:2,...stage,result,...safeProofs(proofs),...environment});
}

function validateFrame(value) {
  const shortKeys = ['version','result',...PROOF_KEYS,...ENVIRONMENT_KEYS];
  const longKeys = ['version',...STAGE_KEYS,'result',...PROOF_KEYS,...ENVIRONMENT_KEYS];
  const data = exactData(value,shortKeys) ?? exactData(value,longKeys);
  if (!data || data.version !== 2 || !RESULTS.includes(data.result) || PROOF_KEYS.some(key => typeof data[key] !== 'boolean')) return null;
  const environment=validateFormEnvironmentEvidence(Object.fromEntries(ENVIRONMENT_KEYS.map(key=>[key,data[key]])));
  if(!environment)return null;
  if (!Object.hasOwn(data,'bucket')) return FAILURES.includes(data.result) ? Object.freeze(data) : null;
  const sample = validateFormDiagnosticSample({version:1,...Object.fromEntries(STAGE_KEYS.map(key => [key,data[key]]))});
  if (!sample||!sampledEnvironment(environment)) return null;
  // Failure provenance is an opaque discriminator. Stage and readiness results
  // must be independently reproducible from the finite snapshot.
  if (!FAILURES.includes(data.result) && classifyFormDiagnosticSample(sample) !== data.result) return null;
  return Object.freeze(data);
}

export function formatFormDiagnosticFrame(value) {
  const frame = validateFrame(value);
  if (!frame) return null;
  const wire = JSON.stringify(frame)+'\n';
  return encoder.encode(wire).byteLength <= 4096 ? wire : null;
}

export function parseFormDiagnosticFrame(wire) {
  if (typeof wire !== 'string' || !wire.endsWith('\n') || wire.slice(0,-1).includes('\n')
    || encoder.encode(wire).byteLength > 4096) return null;
  try {
    const value = JSON.parse(wire);
    const frame = validateFrame(value);
    if (!frame) return null;
    // Every accepted value is flat finite scalar data. Values and allowed
    // decoded keys contain no colon, so each colon must delimit one key.
    // Counting before decoding also rejects escaped duplicate key spellings.
    if ((wire.match(/:/g) ?? []).length !== Object.keys(frame).length) return null;
    return frame;
  } catch { return null; }
}
