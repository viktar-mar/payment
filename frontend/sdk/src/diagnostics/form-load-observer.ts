// Internal only. No SDK entry-point export, provider data, events, callbacks or I/O.
import { ownedProviderHost } from '../widget/provider-mount.js';
import { hasManualPaymentAuthorization } from './manual-payment-authorization.js';
export interface FormLoadSnapshot {
  version: 1;
  binding: 'ARMED' | 'BOUND' | 'LOST' | 'CLOSED';
  capability_started: boolean; capability_ready: boolean;
  loader_started: boolean; loader_ready: boolean; element_created: boolean;
  mount_called: boolean; mount_returned: boolean; ready_seen: boolean; loaderror_seen: boolean;
  catchup: 'NOT_STARTED' | 'PENDING' | 'RESOLVED' | 'REJECTED' | 'ABORTED';
  view_completed: boolean; widget_completed: boolean; confirmation_blocked: boolean;
  operation: 'PENDING' | 'COMPLETED' | 'FAILED' | 'ABORTED';
}
export interface FormLoadBinding {
  readonly widget: HTMLElement;
  readonly state: FormLoadSnapshot;
  host?: HTMLElement;
  generation?: number;
  resumed?: boolean;
}
type Flag = 'capability_started' | 'capability_ready' | 'loader_started' | 'loader_ready' | 'element_created' | 'mount_called' | 'mount_returned' | 'ready_seen' | 'loaderror_seen' | 'view_completed' | 'widget_completed';
const known = new WeakSet<HTMLElement>();
const used = new WeakSet<HTMLElement>();
const widgets = new WeakMap<HTMLElement, FormLoadBinding>();
const hosts = new WeakMap<HTMLElement, FormLoadBinding>();
const loaderStarts = new WeakSet<FormLoadBinding>();
let armed: FormLoadBinding | undefined;

export function registerFormLoadWidget(widget: HTMLElement): void { known.add(widget); }
export function hasFormLoadDiagnostic(widget: HTMLElement): boolean { return widgets.has(widget); }
function lose(binding: FormLoadBinding): void {
  if (binding.state.binding === 'CLOSED' || binding.state.binding === 'LOST') return;
  binding.state.binding = 'LOST';
  if (binding.state.catchup === 'PENDING') binding.state.catchup = 'ABORTED';
  if (binding.state.operation === 'PENDING') binding.state.operation = 'ABORTED';
}
export function configureFormLoadWidget(widget: HTMLElement): void {
  const binding = widgets.get(widget);
  if (binding && used.has(widget)) lose(binding);
  used.add(widget);
}
export function disconnectFormLoadWidget(widget: HTMLElement): void {
  used.add(widget); const binding = widgets.get(widget); if (binding) lose(binding);
}
export function resumeFormLoadWidget(widget: HTMLElement): void {
  const binding = widgets.get(widget); if (!binding || binding.state.binding === 'CLOSED') return;
  if (binding.resumed) lose(binding);
  binding.resumed = true;
}
function current(binding: FormLoadBinding | undefined): binding is FormLoadBinding {
  if (!binding || binding.state.binding !== 'BOUND') return false;
  if (!binding.host || ownedProviderHost(binding.widget) !== binding.host) lose(binding);
  return binding.state.binding === 'BOUND';
}
export function armFormLoadDiagnostic(widget: HTMLElement): { snapshot(): Readonly<FormLoadSnapshot>; readinessSnapshot(): Readonly<{ loaderstart_seen: boolean }>; close(): void } {
  if (!known.has(widget) || used.has(widget) || widget.isConnected || widgets.has(widget) || armed || hasManualPaymentAuthorization(widget)) throw new Error('INSTRUMENTATION_UNAVAILABLE');
  const binding: FormLoadBinding = { widget, state: {
    version: 1, binding: 'ARMED', capability_started: false, capability_ready: false,
    loader_started: false, loader_ready: false, element_created: false, mount_called: false,
    mount_returned: false, ready_seen: false, loaderror_seen: false, catchup: 'NOT_STARTED',
    view_completed: false, widget_completed: false, confirmation_blocked: false, operation: 'PENDING',
  } };
  widgets.set(widget, binding); armed = binding;
  return Object.freeze({
    snapshot(): Readonly<FormLoadSnapshot> { current(binding); return Object.freeze({ ...binding.state }); },
    readinessSnapshot(): Readonly<{ loaderstart_seen: boolean }> { current(binding); return Object.freeze({ loaderstart_seen: loaderStarts.has(binding) }); },
    close(): void { binding.state.binding = 'CLOSED'; if (armed === binding) armed = undefined; },
  });
}
export function bindFormLoadDiagnostic(widget: HTMLElement, host: HTMLElement, generation: number): FormLoadBinding | undefined {
  const binding = widgets.get(widget); if (!binding) return;
  // Keep the deny association even for an invalid repeated mount or a closed observer.
  hosts.set(host, binding);
  if (binding.state.binding !== 'ARMED' || binding.host) { lose(binding); return binding; }
  binding.host = host; binding.generation = generation; binding.state.binding = 'BOUND'; current(binding);
  return binding;
}
export function formLoadBinding(host: HTMLElement): FormLoadBinding | undefined { return hosts.get(host); }
export function markFormLoad(binding: FormLoadBinding | undefined, flag: Flag): void {
  if (!current(binding) || binding.state.operation !== 'PENDING') return;
  binding.state[flag] = true;
  if (flag === 'widget_completed') binding.state.operation = 'COMPLETED';
}
export function markFormLoadLoaderStart(binding: FormLoadBinding | undefined): void {
  if (current(binding) && binding.state.operation === 'PENDING') loaderStarts.add(binding);
}
export function markFormLoadCatchup(binding: FormLoadBinding | undefined, state: FormLoadSnapshot['catchup']): void {
  if (current(binding) && binding.state.operation === 'PENDING') binding.state.catchup = state;
}
export function failFormLoad(binding: FormLoadBinding | undefined, wasAborted: boolean): void {
  if (!current(binding) || binding.state.operation !== 'PENDING') return;
  if (binding.state.catchup === 'PENDING') binding.state.catchup = wasAborted ? 'ABORTED' : 'REJECTED';
  binding.state.operation = wasAborted ? 'ABORTED' : 'FAILED';
}
export function denyFormLoadConfirmation(binding: FormLoadBinding | undefined): void {
  if (!binding) return;
  // Captured binding identity is the immutable deny; close never removes authority checks.
  binding.state.confirmation_blocked = true; binding.state.operation = 'FAILED';
  const error = new Error('CONFIRMATION_BLOCKED');
  Object.defineProperty(error, 'code', { value: 'CONFIRMATION_BLOCKED', enumerable: true }); throw error;
}
