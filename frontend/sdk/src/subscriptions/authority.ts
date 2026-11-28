import type { CustomerSession } from '../customer/contracts.js';
import { PaymentSdkError } from '../errors.js';
import type { SubscriptionClient } from './contracts.js';
export type SubscriptionAuthorityCapture = (session: CustomerSession, signal?: AbortSignal) => () => void;
const captures = new WeakMap<SubscriptionClient, SubscriptionAuthorityCapture>();
const originalConfirmations = new WeakMap<SubscriptionClient, SubscriptionClient['confirmAction']>();
/** Internal bridge, preserving the Customer client's captured epochs and storage owner. */
export function registerSubscriptionAuthority(client: SubscriptionClient, capture: SubscriptionAuthorityCapture): void {
  // The real factory registers before exposing the client. Retain the first
  // original command independently of later public-method decoration.
  if (!originalConfirmations.has(client)) originalConfirmations.set(client, client.confirmAction);
  captures.set(client,capture);
}
export function captureSubscriptionAuthority(client: SubscriptionClient, session: CustomerSession, signal?: AbortSignal): () => void {
  const capture=captures.get(client); if (!capture) throw new PaymentSdkError('INVALID_SESSION');
  return capture(session,signal);
}
/** Internal owned-entry command; never resolves a replaceable public method. */
export function captureOriginalSubscriptionConfirmation(client: SubscriptionClient): SubscriptionClient['confirmAction'] {
  const command = originalConfirmations.get(client);
  if (!command) throw new PaymentSdkError('INVALID_SESSION');
  return command;
}
