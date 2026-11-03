// Internal, process-local branch provenance. Never inspect a handle or rejected value.
export type StripePaymentAttemptFailure = 'GUARD' | 'STATE' | 'SUBMIT_VALIDATION' | 'SUBMIT' | 'TOKEN_VALIDATION' | 'TOKEN' | 'CLIENT' | 'NEXT_ACTION' | 'RETIRED';
const failures = new WeakMap<object, StripePaymentAttemptFailure>();
const codes = new Set<unknown>(['GUARD','STATE','SUBMIT_VALIDATION','SUBMIT','TOKEN_VALIDATION','TOKEN','CLIENT','NEXT_ACTION','RETIRED']);
const suitable = (handle: unknown): handle is object => handle !== null && (typeof handle === 'object' || typeof handle === 'function');
export function retainStripePaymentAttemptFailure(handle: unknown, code: unknown): void {
  if (suitable(handle) && typeof code === 'string' && codes.has(code) && !failures.has(handle)) failures.set(handle, code as StripePaymentAttemptFailure);
}
export function readStripePaymentAttemptFailure(handle: unknown): StripePaymentAttemptFailure | null {
  return suitable(handle) ? failures.get(handle) ?? null : null;
}

// Pending observations are independent of immutable terminal failure provenance.
export type StripePaymentAttemptPending = 'SUBMIT' | 'TOKEN';
const pending = new WeakMap<object, StripePaymentAttemptPending>();
const listeners = new WeakMap<object, (stage: StripePaymentAttemptPending | null) => void>();
export function setStripePaymentAttemptPending(handle: unknown, value: unknown): void {
  if (!suitable(handle) || (value !== 'SUBMIT' && value !== 'TOKEN' && value !== null)) return;
  if ((pending.get(handle) ?? null) === value) return;
  if (value === null) pending.delete(handle); else pending.set(handle, value);
  try { listeners.get(handle)?.(value); } catch { /* observation cannot control payment */ }
}
export function observeStripePaymentAttemptPending(handle: unknown, listener: (stage: StripePaymentAttemptPending | null) => void): () => void {
  if (!suitable(handle) || typeof listener !== 'function' || listeners.has(handle)) return () => {};
  listeners.set(handle, listener);
  let disposed = false;
  return () => {
    if (disposed) return;
    disposed = true;
    if (listeners.get(handle) === listener) listeners.delete(handle);
  };
}
