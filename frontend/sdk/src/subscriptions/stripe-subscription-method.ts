import type { CustomerSession } from '../customer/contracts.js';
import { subscribeSubscriptionCustomer } from '../customer/session-lifecycle.js';
import { isAbort, PaymentSdkError } from '../errors.js';
import { assertSubscriptionAction } from './action-driver.js';
import { captureSubscriptionAuthority } from './authority.js';
import { assertSelectedSetup } from './confirmation-v2.js';
import type { SubscriptionClient } from './contracts.js';
import { guardSubscriptionHandoff, type SubscriptionHandoff } from './subscription-handoff.js';
import { mountStripeSubscription, type StripeSubscriptionHandle } from './stripe-subscription-driver.js';

export interface StripeSubscriptionMethodOptions {
  client: SubscriptionClient;
  session: CustomerSession;
  subscriptionId: string;
  operationId: string;
  container: HTMLElement;
  isCurrent: () => boolean;
  signal?: AbortSignal;
  locale?: 'en' | 'pt';
  onRetire?: () => void;
}

/** Prepare one original SETUP form. The site enables its explicit button after ready.
 * Browser confirmation returns the server's pending operation, never a method fact. */
export async function mountStripeSubscriptionMethod(options: StripeSubscriptionMethodOptions): Promise<StripeSubscriptionHandle> {
  const { client, session, subscriptionId, operationId, container } = options;
  const controller = new AbortController();
  const signal = options.signal ? AbortSignal.any([options.signal, controller.signal]) : controller.signal;
  let handoff: SubscriptionHandoff | undefined;
  let releaseCustomer: (() => void) | undefined;
  let assertAuthority: (() => void) | undefined;
  let retired = false;
  function retire(): void {
    if (retired) return;
    retired = true;
    handoff?.release();
    releaseCustomer?.(); releaseCustomer = undefined;
    assertAuthority = undefined;
    try { options.onRetire?.(); } catch { /* owner cleanup cannot revive permission */ }
  }
  function currentOwner(): boolean {
    if (retired) return false;
    try { assertAuthority!(); return options.isCurrent(); } catch { return false; }
  }
  try {
    // Capture the real client's authentication/storage generations before any await.
    assertAuthority = captureSubscriptionAuthority(client, session, signal);
    handoff = guardSubscriptionHandoff({ container, signal, expiresAt: session.expiresAt, isCurrent: currentOwner, onRetire: retire });
    releaseCustomer = subscribeSubscriptionCustomer(session, () => { controller.abort(); retire(); });
    if (typeof client.confirmationSession !== 'function') throw new PaymentSdkError('INVALID_CONFIGURATION');
    const input = { session, subscriptionId, operationId, signal: handoff.signal };
    handoff.assertCurrent();
    const action = await handoff.wait(client.action(input));
    handoff.assertCurrent(); assertSubscriptionAction(client, input, action);
    if (action.kind !== 'PROVIDER_CLIENT') throw new PaymentSdkError('INVALID_RESPONSE');
    const selected = await handoff.wait(client.confirmationSession({ ...input, action, idempotencyKey: crypto.randomUUID() }));
    handoff.assertCurrent(); assertSelectedSetup(client, input, action, selected);
    if (selected.protocol !== 'BROWSER_CONFIRM_V1' || selected.flow !== 'SETUP' || !selected.publishableKey.startsWith('pk_test_')) throw new PaymentSdkError('INVALID_RESPONSE');
    // Keep the outer visibility/host guard alive during the driver's preflight and
    // confirmation. Only driver retirement releases it; release never aborts a result.
    return await handoff.wait(mountStripeSubscription({ client, input, action, selectedSession: selected,
      container, isCurrent: () => { handoff!.assertCurrent(); return true; }, locale: options.locale, onRetire: retire,
      receiptConstraint: 'OWNED_METHOD_RESERVED' }));
  } catch (error) {
    controller.abort(); retire();
    if (isAbort(error) || error instanceof PaymentSdkError) throw error;
    throw new PaymentSdkError('PROVIDER_TEMPORARILY_UNAVAILABLE');
  }
}
