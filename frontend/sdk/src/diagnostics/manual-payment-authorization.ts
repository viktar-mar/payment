// Internal only: this lease gates dispatch, not financial proof or card contents.
import { ownedProviderHost } from '../widget/provider-mount.js';
import { hasFormLoadDiagnostic } from './form-load-observer.js';
import type { CheckoutSession } from '../contracts.js';
import { bindManualPaymentSession } from './manual-payment-session.js';

declare const identity: unique symbol;
export interface ManualPaymentBinding { readonly [identity]: true; }
interface Lease {
  widget: HTMLElement;
  binding: ManualPaymentBinding;
  deadline: number;
  authorize: () => Promise<true>;
  host?: HTMLElement;
  generation?: number;
  consumed: boolean;
  granted: boolean;
  invalid: boolean;
  controller: AbortController;
}
const known = new WeakSet<HTMLElement>();
const used = new WeakSet<HTMLElement>();
const widgets = new WeakMap<HTMLElement, Lease>();
const hosts = new WeakMap<HTMLElement, ManualPaymentBinding>();
const bindings = new WeakMap<ManualPaymentBinding, Lease>();
let armed: Lease | undefined;

export function registerManualPaymentWidget(widget: HTMLElement): void { known.add(widget); }
export function hasManualPaymentAuthorization(widget: HTMLElement): boolean { return widgets.has(widget); }
export function configureManualPaymentWidget(widget: HTMLElement): void {
  if (used.has(widget)) invalidateManualPaymentWidget(widget);
  used.add(widget);
}
export function invalidateManualPaymentWidget(widget: HTMLElement): void {
  used.add(widget);
  const lease = widgets.get(widget); if (lease) invalidate(lease);
}
function invalidate(lease: Lease): void { lease.invalid = true; lease.controller.abort(); }
function blocked(): Error {
  const error = new Error('CONFIRMATION_BLOCKED');
  Object.defineProperty(error, 'code', { value: 'CONFIRMATION_BLOCKED', enumerable: true });
  return error;
}
function current(lease: Lease): boolean {
  if (!lease.invalid && (!Number.isFinite(Date.now()) || Date.now() >= lease.deadline
    || !lease.host || lease.generation === undefined || !lease.widget.isConnected
    || ownedProviderHost(lease.widget) !== lease.host)) invalidate(lease);
  return !lease.invalid;
}
export function armManualPayment(widget: HTMLElement, options: { deadline: number; authorize: () => Promise<true>; session?: CheckoutSession }): { close(): void } {
  if (!known.has(widget) || used.has(widget) || widget.isConnected || widgets.has(widget) || armed
    || hasFormLoadDiagnostic(widget) || !Number.isFinite(options.deadline) || options.deadline <= Date.now()
    || typeof options.authorize !== 'function') throw new Error('INSTRUMENTATION_UNAVAILABLE');
  const binding = Object.freeze({}) as ManualPaymentBinding;
  const lease: Lease = { widget, binding, deadline: Math.min(options.deadline, options.session ? Date.parse(options.session.expiresAt) : Infinity), authorize: options.authorize, consumed: false, granted: false, invalid: false, controller: new AbortController() };
  if (options.session) bindManualPaymentSession(widget, options.session, {
    deadline: lease.deadline, signal: lease.controller.signal, invalidate: () => invalidate(lease),
    current: () => {
      if (!lease.invalid && (!Number.isFinite(Date.now()) || Date.now() >= lease.deadline || !widget.isConnected
        || (lease.host && ownedProviderHost(widget) !== lease.host))) invalidate(lease);
      return !lease.invalid;
    },
  });
  widgets.set(widget, lease); bindings.set(binding, lease); armed = lease;
  return Object.freeze({ close(): void { invalidate(lease); if (armed === lease) armed = undefined; } });
}
export function bindManualPayment(widget: HTMLElement, host: HTMLElement, generation: number): void {
  const lease = widgets.get(widget); if (!lease) return;
  // A replacement host still captures a permanent deny, never an ordinary handle.
  hosts.set(host, lease.binding);
  if (lease.host || lease.invalid || !Number.isSafeInteger(generation) || generation < 0) { invalidate(lease); return; }
  lease.host = host; lease.generation = generation; current(lease);
}
export function manualPaymentBinding(host: HTMLElement): ManualPaymentBinding | undefined { return hosts.get(host); }
export async function consumeManualPaymentConfirmation(binding: ManualPaymentBinding | undefined): Promise<void> {
  if (!binding) return;
  const lease = bindings.get(binding);
  if (!lease || lease.consumed) throw blocked();
  // Latch before any callback or await. Rejection and uncertainty never restore it.
  lease.consumed = true;
  if (!current(lease)) throw blocked();
  let permitted: unknown;
  try { permitted = await lease.authorize(); } catch { invalidate(lease); throw blocked(); }
  if (permitted !== true || !current(lease)) { invalidate(lease); throw blocked(); }
  lease.granted = true;
}
export function assertManualPaymentConfirmation(binding: ManualPaymentBinding | undefined): void {
  if (!binding) return;
  const lease = bindings.get(binding);
  if (!lease || !lease.consumed || !lease.granted || !current(lease)) throw blocked();
}
