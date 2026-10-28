import type { CustomerSession } from '../customer/contracts.js';
import { PaymentSdkError } from '../errors.js';
import type { SubscriptionClient } from './contracts.js';
export type SubscriptionAuthorityCapture = (session: CustomerSession, signal?: AbortSignal) => () => void;
const captures = new WeakMap<SubscriptionClient, SubscriptionAuthorityCapture>();
/** Internal bridge, preserving the Customer client's captured epochs and storage owner. */
export function registerSubscriptionAuthority(client: SubscriptionClient, capture: SubscriptionAuthorityCapture): void { captures.set(client,capture); }
export function captureSubscriptionAuthority(client: SubscriptionClient, session: CustomerSession, signal?: AbortSignal): () => void {
  const capture=captures.get(client); if (!capture) throw new PaymentSdkError('INVALID_SESSION');
  return capture(session,signal);
}
