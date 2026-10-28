import type { Stripe, StripeElements, StripePaymentElement } from '@stripe/stripe-js';
import { browserOrigin, validateCustomerSession } from '../customer/contracts.js';
import { PaymentSdkError, isAbort } from '../errors.js';
import { loadStripeClient } from '../providers/stripe-loader.js';
import { subscribeSubscriptionCustomer } from '../customer/session-lifecycle.js';
import { captureSubscriptionAuthority } from './authority.js';
import { assertSubscriptionAction, consumeSubscriptionAction } from './action-driver.js';
import type { SubscriptionAction, SubscriptionClient, SubscriptionClientSession, SubscriptionIdRequest, SubscriptionMutationReceipt, SubscriptionOperation } from './contracts.js';

export interface StripeSubscriptionOptions {
  client: SubscriptionClient; input: SubscriptionIdRequest & { operationId: string }; action: SubscriptionAction;
  container: HTMLElement; isCurrent: () => boolean; locale?: 'en' | 'pt';
  onRetire?: () => void;
}
export interface StripeSubscriptionHandle { confirm(): Promise<SubscriptionOperation>; destroy(): void; }
const fingerprint = (s: StripeSubscriptionOptions['input']['session']) => JSON.stringify([s.apiBaseUrl,s.siteKey,s.siteId,s.origin,s.customer.id,s.customer.status,s.customer.maskedEmail,s.sessionToken,s.expiresAt,[...s.scopes].sort()]);
const aborted = () => new DOMException('Aborted', 'AbortError');
function visible(host: HTMLElement): boolean {
  if (!host.isConnected || ![...host.getClientRects()].some(r => r.width > 0 && r.height > 0)) return false;
  let node: HTMLElement | null = host;
  while (node) {
    const style = getComputedStyle(node);
    if (node.hidden || style.display === 'none' || ['hidden','collapse'].includes(style.visibility) || style.opacity === '0') return false;
    node = node.assignedSlot ?? node.parentElement ?? ((node.getRootNode() as ShadowRoot).host as HTMLElement | undefined) ?? null;
  }
  return true;
}
/** One memory-only original grant, one explicit preflight, at most one browser dispatch. */
export async function mountStripeSubscription(options: StripeSubscriptionOptions): Promise<StripeSubscriptionHandle> {
  const { client, input, container, isCurrent, locale, onRetire } = options;
  let action: SubscriptionAction | undefined = options.action;
  const authority = fingerprint(input.session);
  let assertAuthority: (() => void) | undefined = captureSubscriptionAuthority(client,input.session,input.signal);
  let actionIdentity: string | undefined = JSON.stringify(action), token: string | undefined = action.secret;
  let expires = Math.min(Date.parse(input.session.expiresAt), Date.parse(action.expiresAt));
  let capability: SubscriptionClientSession | undefined;
  let stripe: Stripe | undefined, elements: StripeElements | undefined, element: StripePaymentElement | undefined;
  let active = true, spent = false, ready = false;
  let expiry: ReturnType<typeof setTimeout> | undefined, readinessTimer: ReturnType<typeof setTimeout> | undefined;
  let cancel!: (error: DOMException) => void;
  const cancelled = new Promise<never>((_, reject) => { cancel = reject; }); void cancelled.catch(() => {});
  let readyListener: (() => void) | undefined, loadErrorListener: (() => void) | undefined;
  let releaseCustomer: (() => void) | undefined;
  const observer = new MutationObserver(() => { if (active) { try { current(); } catch { destroy(); } } });
  function destroy(): void {
    const wasActive = active; active = false; ready = false;
    if (expiry !== undefined) clearTimeout(expiry); if (readinessTimer !== undefined) clearTimeout(readinessTimer);
    input.signal?.removeEventListener('abort', destroy); window.removeEventListener('pagehide', destroy); observer.disconnect();
    releaseCustomer?.(); releaseCustomer=undefined; assertAuthority=undefined;
    try { if (readyListener) element?.off('ready',readyListener); if (loadErrorListener) element?.off('loaderror',loadErrorListener); } catch { /* fixed cleanup only */ }
    const retired = element; element = undefined; elements = undefined; stripe = undefined; capability = undefined; action = undefined; actionIdentity = undefined; token = undefined;
    try { retired?.destroy(); } catch { /* never expose provider details */ }
    if (wasActive) { cancel(aborted()); try { onRetire?.(); } catch { /* owner cleanup cannot revive this form */ } }
  }
  function current(): void {
    if (!active || input.signal?.aborted || !isCurrent() || !visible(container)) throw aborted();
    assertAuthority!();
    validateCustomerSession(input.session);
    if (fingerprint(input.session) !== authority || input.session.origin !== browserOrigin() || Date.now() >= expires || !input.session.scopes.includes('SUBSCRIPTION_MANAGE') || (action && JSON.stringify(action) !== actionIdentity)) throw new PaymentSdkError('INVALID_SESSION');
  }
  async function guarded<T>(pending: Promise<T>): Promise<T> { const result = await Promise.race([pending,cancelled]); current(); return result; }
  function armExpiry(): void { if (expiry !== undefined) clearTimeout(expiry); expiry = setTimeout(destroy,Math.max(0,Math.min(expires-Date.now(),2147483647))); }
  try {
    current(); assertSubscriptionAction(client,input,action);
    input.signal?.addEventListener('abort',destroy,{ once:true }); window.addEventListener('pagehide',destroy,{ once:true });
    releaseCustomer=subscribeSubscriptionCustomer(input.session,destroy);
    const observation={ childList:true,subtree:true,attributes:true,attributeFilter:['hidden','style','class','name','slot'] };
    observer.observe(container.ownerDocument,observation);
    const projection=container.assignedSlot?.getRootNode();if(projection instanceof ShadowRoot)observer.observe(projection,observation);
    armExpiry();
    capability = await guarded(client.clientSession({ ...input, action, idempotencyKey:crypto.randomUUID() }));
    expires = Math.min(expires,Date.parse(capability.expiresAt)); armExpiry();
    const loaded = await guarded(loadStripeClient(capability.publishableKey)); if (!loaded) throw new PaymentSdkError('PROVIDER_TEMPORARILY_UNAVAILABLE');
    stripe = loaded; elements = stripe.elements({ clientSecret:capability.clientSecret, locale:locale ?? 'en' });
    const flow = capability.flow; capability = undefined;
    current(); element = elements.create('payment',{ wallets:{ applePay:'never',googlePay:'never',link:'never' } });
    const readiness = new Promise<void>((resolve,reject) => {
      readyListener = () => { try { current(); ready=true; resolve(); } catch { reject(aborted()); } };
      loadErrorListener = () => reject(new PaymentSdkError('PROVIDER_TEMPORARILY_UNAVAILABLE'));
      readinessTimer = setTimeout(() => reject(new PaymentSdkError('PROVIDER_TEMPORARILY_UNAVAILABLE')),30000);
    }); void readiness.catch(() => {});
    element.on('ready',readyListener!); element.on('loaderror',loadErrorListener!);
    current(); element.mount(container); await guarded(readiness);
    if (readinessTimer !== undefined) clearTimeout(readinessTimer);
    return {
      async confirm() {
        if (spent) throw new PaymentSdkError('PAYMENT_STATE_CONFLICT');
        try {
          current(); if (!ready || !action) throw new PaymentSdkError('PAYMENT_STATE_CONFLICT');
          // Permanent local latch precedes the very first asynchronous preflight.
          spent=true; consumeSubscriptionAction(client,input,action); action=undefined;
          const receipt = await guarded(client.confirmAction({ ...input, actionToken:token!, idempotencyKey:crypto.randomUUID() }));
          if ((receipt as SubscriptionMutationReceipt).browserConfirmAllowed !== true || receipt.status === 'UNKNOWN') throw new PaymentSdkError('PROVIDER_RESULT_UNKNOWN');
          current();
          // There is no await between the final authority/mount check and dispatch.
          const confirmation = flow === 'PAYMENT'
            ? stripe!.confirmPayment({ elements:elements!, redirect:'if_required' })
            : stripe!.confirmSetup({ elements:elements!, redirect:'if_required' });
          let failed = false;
          try { const result = await guarded<{ error?: unknown }>(confirmation); failed=!!result.error; }
          catch (error) { if (isAbort(error)) throw error; throw new PaymentSdkError('PROVIDER_RESULT_UNKNOWN'); }
          if (failed) throw new PaymentSdkError('PROVIDER_RESULT_UNKNOWN');
          return { operationId:receipt.operationId,subscriptionId:receipt.subscriptionId,status:receipt.status,actionAvailable:receipt.actionAvailable };
        } catch (error) {
          if (isAbort(error) || error instanceof PaymentSdkError) throw error;
          throw new PaymentSdkError('PROVIDER_RESULT_UNKNOWN');
        } finally { destroy(); }
      }, destroy,
    };
  } catch (error) {
    destroy();
    if (isAbort(error) || error instanceof PaymentSdkError) throw error;
    throw new PaymentSdkError('PROVIDER_TEMPORARILY_UNAVAILABLE');
  }
}
