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
