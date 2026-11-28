import type { CheckoutSession } from '../contracts.js';
import { normalizeApiBase } from '../contracts.js';
import { PaymentSdkError } from '../errors.js';
import type { CustomerSession } from './contracts.js';
import { browserOrigin } from './contracts.js';
interface Binding { namespace: string; token?: string; invalidated: boolean; callbacks: Set<() => void>; }
const bindings = new WeakMap<CheckoutSession, Binding | null>();
const registry = new Map<string, Set<WeakRef<Binding>>>();
const namespace = (identity: { apiBaseUrl: string; siteKey: string }) => JSON.stringify([normalizeApiBase(identity.apiBaseUrl), identity.siteKey, browserOrigin()]);
function remove(name: string, reference: WeakRef<Binding>): void {
  const entries = registry.get(name); entries?.delete(reference); if (!entries?.size) registry.delete(name);
}
// Held metadata never owns a session, Binding, token or callback. Correctness
// relies on live weak bindings, never on finalizer timing.
const finalizer = new FinalizationRegistry<{ name: string; reference: WeakRef<Binding> }>(entry => remove(entry.name, entry.reference));
function register(binding: Binding): () => void {
  const entries = registry.get(binding.namespace) ?? new Set<WeakRef<Binding>>();
  for (const entry of entries) if (!entry.deref()) entries.delete(entry);
  const reference = new WeakRef(binding); entries.add(reference); registry.set(binding.namespace, entries);
  finalizer.register(binding, { name: binding.namespace, reference }, reference);
  return () => { remove(binding.namespace, reference); finalizer.unregister(reference); binding.token = undefined; binding.callbacks.clear(); };
}
// Capture before HTTP dispatch, including the client's accepted memory-only fallback.
// A failed request releases its weak metadata when creation settles.
export function captureCheckoutCustomer(customer?: CustomerSession): { bind: (session: CheckoutSession) => void; release: () => void } {
  const binding: Binding | null = customer ? { namespace: namespace(customer), token: customer.sessionToken, invalidated: false, callbacks: new Set() } : null;
  let bound = false; const discard = binding ? register(binding) : () => {};
  return { bind: session => { if (binding?.invalidated) throw new PaymentSdkError('INVALID_SESSION'); bindings.set(session, binding); bound = true; }, release: () => { if (!bound) discard(); } };
}
export function subscribeCheckoutCustomer(session: CheckoutSession, callback: () => void): () => void {
  let binding = bindings.get(session);
  if (binding === null) return () => {}; // Originally anonymous, even after later login.
  // Recovery cannot reconstruct Customer ownership. Conservative namespace cleanup
  // conveys no financial authority and does not create/restart a checkout.
  if (!binding) { binding = { namespace: namespace(session), invalidated: false, callbacks: new Set() }; bindings.set(session, binding); register(binding); }
  if (binding.invalidated) { callback(); return () => {}; }
  const callbacks = binding.callbacks; let activeCallback: (() => void) | undefined = callback; callbacks.add(callback);
  return () => { if (activeCallback) callbacks.delete(activeCallback); activeCallback = undefined; };
}
/** Subscription forms share accepted Customer replacement/logout notifications. */
export function subscribeSubscriptionCustomer(session: CustomerSession, callback: () => void): () => void {
  const binding: Binding = { namespace: namespace(session), token: session.sessionToken, invalidated: false, callbacks: new Set([callback]) };
  return register(binding);
}
function notify(session: CustomerSession, replaced: boolean): void {
  if (session.origin !== browserOrigin()) return;
  const name = namespace(session), entries = registry.get(name);
  for (const reference of [...(entries ?? [])]) {
    const binding = reference.deref(); if (!binding) { remove(name, reference); continue; }
    if (!binding.invalidated && (!binding.token || (replaced ? binding.token !== session.sessionToken : binding.token === session.sessionToken))) {
      binding.invalidated = true; binding.token = undefined; const callbacks = [...binding.callbacks]; binding.callbacks.clear();
      for (const callback of callbacks) { try { callback(); } catch { /* Cleanup must not disrupt accepted authentication/logout. */ } }
    }
  }
}
export function replaceCheckoutCustomer(session: CustomerSession): void { notify(session, true); }
export function clearCheckoutCustomer(session: CustomerSession): void { notify(session, false); }
