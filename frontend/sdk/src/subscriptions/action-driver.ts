import type { CustomerSession } from '../customer/contracts.js';
import { checkAbort, isAbort, PaymentSdkError } from '../errors.js';
import { abortableSleep } from '../polling.js';
import type { SubscriptionAction, SubscriptionClient, SubscriptionIdRequest, SubscriptionMutation, SubscriptionOperation } from './contracts.js';
import { parseSubscriptionAction } from './wire.js';
const identity = (s: CustomerSession) => JSON.stringify([s.apiBaseUrl, s.siteKey, s.siteId, s.origin, s.customer.id, s.customer.status, s.customer.maskedEmail, s.sessionToken, s.expiresAt, [...s.scopes].sort()]);
interface ActionBinding { client: SubscriptionClient; authority: string; subscriptionId: string; operationId: string; secret: string; kind: SubscriptionAction['kind']; expiresAt: string; }
const bindings = new WeakMap<SubscriptionAction, ActionBinding>();
const spent = new WeakMap<SubscriptionClient, Set<string>>();
// A tombstone retains only the original operation identity, never the consumed grant.
const intent = (input: SubscriptionIdRequest & { operationId: string }) => JSON.stringify([input.subscriptionId, input.operationId]);
/** Internal registration; no capability or receipt is persisted. */
export function bindSubscriptionAction(client: SubscriptionClient, session: CustomerSession, operationId: string, subscriptionId: string, action: SubscriptionAction): SubscriptionAction {
  bindings.set(action, { client, authority: identity(session), subscriptionId, operationId, secret: action.secret, kind: action.kind, expiresAt: action.expiresAt }); return action;
}
/** Check the original action object, request owner and immutable Customer identity. */
export function assertSubscriptionAction(client: SubscriptionClient, input: SubscriptionIdRequest & { operationId: string }, action: SubscriptionAction): void {
  const binding = bindings.get(action);
  if (!binding || spent.get(client)?.has(intent(input)) || binding.client !== client || binding.authority !== identity(input.session) || binding.subscriptionId !== input.subscriptionId || binding.operationId !== input.operationId || action.secret !== binding.secret || action.kind !== binding.kind || action.expiresAt !== binding.expiresAt || Date.parse(binding.expiresAt) <= Date.now()) throw new PaymentSdkError('INVALID_SESSION');
  parseSubscriptionAction({ kind: action.kind, secret: action.secret, expires_at: action.expiresAt });
}
/** Consume synchronously before any preflight await; an uncertain receipt stays spent. */
export function consumeSubscriptionAction(client: SubscriptionClient, input: SubscriptionIdRequest & { operationId: string }, action: SubscriptionAction): void {
  assertSubscriptionAction(client, input, action);
  const consumed = spent.get(client) ?? new Set<string>(); consumed.add(intent(input)); spent.set(client, consumed); bindings.delete(action);
}
export { mountStripeSubscription } from './stripe-subscription-driver.js';
/** Explicit one-shot Fake confirmation. Caller creates the intent key; never auto-replayed. */
export async function confirmSubscriptionAction(client: SubscriptionClient, input: SubscriptionMutation & { operationId: string }, action: SubscriptionAction): Promise<SubscriptionOperation> {
  checkAbort(input.signal);
  const binding = bindings.get(action);
  if (!binding || binding.client !== client || binding.authority !== identity(input.session) || binding.subscriptionId !== input.subscriptionId || binding.operationId !== input.operationId || action.secret !== binding.secret || action.kind !== binding.kind || action.expiresAt !== binding.expiresAt || Date.parse(binding.expiresAt) <= Date.now()) throw new PaymentSdkError('INVALID_SESSION');
  // Validate the capability again at consumption; provider client SDKs belong to Stage5.
  parseSubscriptionAction({ kind: action.kind, secret: action.secret, expires_at: action.expiresAt });
  if (action.kind === 'PROVIDER_CLIENT') throw new PaymentSdkError('UNSUPPORTED_PROVIDER');
  bindings.delete(action);
  return client.confirmAction({ ...input, actionToken: binding.secret });
}
/** Memory-only Fake driver. Capabilities remain bound to the original Customer intent. */
export class SubscriptionFakeActionDriver {
  constructor(private readonly client: SubscriptionClient) {}
  complete(action: SubscriptionAction, input: SubscriptionMutation & { operationId: string }): Promise<SubscriptionOperation> {
    return confirmSubscriptionAction(this.client, input, action);
  }
}
/** Safe GET-only polling. Acceptance/timeout never authorizes a new financial POST. */
export async function* watchSubscriptionOperation(client: SubscriptionClient, input: SubscriptionIdRequest & { operationId: string }): AsyncIterable<SubscriptionOperation> {
  const started = performance.now(); const authority = identity(input.session); let count = 0; let last: SubscriptionOperation | undefined;
  const expires = Date.parse(input.session.expiresAt);
  const unknown = (): SubscriptionOperation | undefined => last && ({ operationId: last.operationId, subscriptionId: last.subscriptionId, status: 'UNKNOWN', actionAvailable: false });
  for (;;) {
    checkAbort(input.signal);
    if (identity(input.session) !== authority) throw new PaymentSdkError('INVALID_SESSION');
    const remaining = Math.min(600000 - (performance.now() - started), expires - Date.now());
    if (remaining <= 0) { const result = unknown(); if (result) yield result; return; }
    const controller = new AbortController(); const forward = () => controller.abort(input.signal?.reason);
    input.signal?.addEventListener('abort', forward, { once: true });
    const timer = setTimeout(() => controller.abort(), remaining);
    try {
      last = await client.operation({ ...input, signal: controller.signal }); checkAbort(input.signal); checkAbort(controller.signal);
    } catch (error) {
      if (input.signal?.aborted || (!controller.signal.aborted && isAbort(error))) throw error;
      if (controller.signal.aborted) { const result = unknown(); if (result) yield result; return; }
      throw error;
    } finally { clearTimeout(timer); input.signal?.removeEventListener('abort', forward); }
    // Polling never returns mutation capabilities, including after a timeout.
    yield last;
    if (['SUCCEEDED', 'FAILED', 'UNKNOWN', 'REQUIRES_ACTION'].includes(last.status)) return;
    const delay = [500, 1000, 2000, 4000, 5000][Math.min(count++, 4)] * (0.8 + Math.random() * 0.2);
    const wait = Math.min(delay, 600000 - (performance.now() - started), expires - Date.now());
    if (wait > 0) await abortableSleep(wait, input.signal);
  }
}
