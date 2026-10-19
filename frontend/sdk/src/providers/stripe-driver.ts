import type { Stripe, StripeElements, StripeElementsOptions, StripePaymentElement } from '@stripe/stripe-js';
import type { CheckoutState, ClientPaymentSession } from '../contracts.js';
import { object, safeState } from '../contracts.js';
import { PaymentSdkError } from '../errors.js';
import { strictAppearance } from './stripe-appearance.js';
import { loadStripeClient } from './stripe-loader.js';
import { formLoadBinding, markFormLoad, markFormLoadLoaderStart, failFormLoad, denyFormLoadConfirmation } from '../diagnostics/form-load-observer.js';
import { manualPaymentBinding, consumeManualPaymentConfirmation, assertManualPaymentConfirmation } from '../diagnostics/manual-payment-authorization.js';
export interface StripePresentation { appearance: StripeElementsOptions['appearance']; locale?: 'en' | 'pt'; }
export interface MountStripePaymentOptions extends StripePresentation {
  container: HTMLElement; capability: ClientPaymentSession; signal: AbortSignal;
  refresh: () => Promise<CheckoutState>;
}
export interface StripePaymentHandle {
  confirm(): Promise<CheckoutState>;
  updatePresentation(presentation: StripePresentation): Promise<void>;
  destroy(): void;
}
interface PresentationUpdate {
  value?: StripePresentation;
  resolve: () => void;
  reject: (error: Error) => void;
}
function presentation(value: StripePresentation): StripePresentation {
  try {
    if (!object(value) || !Reflect.ownKeys(value).every(key => key === 'appearance' || key === 'locale')) throw 0;
    const locale = value.locale;
    if (locale !== undefined && locale !== 'en' && locale !== 'pt') throw 0;
    return { appearance: strictAppearance(value.appearance), ...(locale === undefined ? {} : { locale }) };
  } catch { throw new PaymentSdkError('INVALID_CONFIGURATION'); }
}
const aborted = () => new DOMException('Aborted', 'AbortError');

export async function mountStripePayment(options: MountStripePaymentOptions): Promise<StripePaymentHandle> {
  const { container, signal } = options;
  const diagnostic = formLoadBinding(container);
  const manual = manualPaymentBinding(container);
  if (signal.aborted) throw aborted();
  const initialPresentation = presentation({ appearance: options.appearance, locale: options.locale });
  let capability: ClientPaymentSession | undefined = options.capability;
  const checkoutId = capability.checkoutId; const paymentId = capability.paymentId;
  let refresh: (() => Promise<CheckoutState>) | undefined = options.refresh;
  let stripe: Stripe | undefined;
  let elements: StripeElements | undefined;
  let element: StripePaymentElement | undefined;
  let returnUrl: string | undefined;
  let active = true;
  let confirming = false;
  let currentUpdate: PresentationUpdate | undefined;
  let pendingUpdate: PresentationUpdate | undefined;
  let cancel!: (reason: DOMException) => void;
  const cancellation = new Promise<never>((_, reject) => { cancel = reject; });
  // The handle may be destroyed while idle, with no pending race consumer.
  void cancellation.catch(() => {});
  let ready!: () => void;
  let loadFailed!: () => void;
  let loaderStarted!: () => void;
  function detachReadyListeners(): void {
    if (!element) return;
    try { element.off('ready', ready); } catch { /* best-effort provider cleanup */ }
    try { element.off('loaderror', loadFailed); } catch { /* best-effort provider cleanup */ }
    try { element.off('loaderstart', loaderStarted); } catch { /* best-effort provider cleanup */ }
  }
  function destroy(): void {
    const wasActive = active;
    active = false;
    signal.removeEventListener('abort', destroy);
    detachReadyListeners();
    const mounted = element;
    element = undefined; elements = undefined; stripe = undefined;
    capability = undefined; returnUrl = undefined; refresh = undefined;
    rejectUpdate(currentUpdate); rejectUpdate(pendingUpdate);
    currentUpdate = undefined; pendingUpdate = undefined;
    if (wasActive) cancel(aborted());
    try { mounted?.destroy(); } catch { /* never expose raw cleanup errors */ }
  }
  function checkActive(): void { if (!active || signal.aborted) throw aborted(); }
  async function guarded<T>(operation: Promise<T>): Promise<T> {
    const result = await Promise.race([operation, cancellation]);
    checkActive(); return result;
  }
  function rejectUpdate(update: PresentationUpdate | undefined): void {
    if (!update) return;
    update.value = undefined; update.reject(aborted());
  }
  function startUpdate(update: PresentationUpdate): void {
    currentUpdate = update;
    void (async () => {
      try {
        checkActive();
        await guarded(Promise.resolve(elements!.update(update.value!)));
        update.resolve();
      } catch {
        update.reject(!active || signal.aborted ? aborted() : new PaymentSdkError('PROVIDER_TEMPORARILY_UNAVAILABLE'));
      } finally {
        update.value = undefined;
        if (currentUpdate === update) {
          currentUpdate = undefined;
          const latest = pendingUpdate; pendingUpdate = undefined;
          if (latest && active && !signal.aborted) startUpdate(latest);
          else rejectUpdate(latest);
        }
      }
    })();
  }
  signal.addEventListener('abort', destroy, { once: true });
  try {
    checkActive();
    markFormLoad(diagnostic, 'loader_started');
    const loaded = await guarded(loadStripeClient(capability.publishableKey));
    if (!loaded) throw 0;
    markFormLoad(diagnostic, 'loader_ready');
    stripe = loaded;
    elements = stripe.elements({ clientSecret: capability.clientSecret, ...initialPresentation });
    returnUrl = capability.returnUrl;
    capability = undefined;
    checkActive();
    element = elements.create('payment', { wallets: { applePay: 'never', googlePay: 'never', link: 'never' } });
    markFormLoad(diagnostic, 'element_created');
    checkActive();
    const readiness = new Promise<void>((resolve, reject) => {
      ready = () => { if (active && !signal.aborted) { markFormLoad(diagnostic, 'ready_seen'); resolve(); } };
      loadFailed = () => { if (active && !signal.aborted) { markFormLoad(diagnostic, 'loaderror_seen'); reject(new PaymentSdkError('PROVIDER_TEMPORARILY_UNAVAILABLE')); } };
    });
    // Attach before mount: the provider may synchronously signal readiness.
    loaderStarted = () => { if (active && !signal.aborted) markFormLoadLoaderStart(diagnostic); };
    element.on('ready', ready); element.on('loaderror', loadFailed); element.on('loaderstart', loaderStarted);
    void readiness.catch(() => {});
    markFormLoad(diagnostic, 'mount_called'); element.mount(container); markFormLoad(diagnostic, 'mount_returned');
    await guarded(readiness);
    detachReadyListeners();
  } catch {
    const wasAborted = !active || signal.aborted;
    failFormLoad(diagnostic, wasAborted);
    destroy();
    if (wasAborted) throw aborted();
    throw new PaymentSdkError('PROVIDER_TEMPORARILY_UNAVAILABLE');
  }
  return {
    async confirm(): Promise<CheckoutState> {
      denyFormLoadConfirmation(diagnostic);
      checkActive();
      if (confirming) throw new PaymentSdkError('PAYMENT_STATE_CONFLICT');
      confirming = true;
      try {
        if (manual) { await guarded(consumeManualPaymentConfirmation(manual)); checkActive(); }
        // No await between this authority recheck and the sole provider dispatch.
        assertManualPaymentConfirmation(manual);
        let category: 'none' | 'actionable' | 'unknown' = 'unknown';
        try {
          category = await guarded(stripe!.confirmPayment({
            elements: elements!, confirmParams: { return_url: returnUrl! }, redirect: 'if_required',
          }).then(result => {
            // Retain only a safe category, never provider result/error fields.
            if (!result.error) return 'none' as const;
            return result.error.type === 'card_error' || result.error.type === 'validation_error' ? 'actionable' as const : 'unknown' as const;
          }, () => 'unknown' as const));
        } catch { checkActive(); }
        checkActive();
        let state: CheckoutState;
        try {
          state = await guarded(refresh!());
          safeState(state);
          if (state.checkoutId !== checkoutId || state.paymentId !== paymentId) throw 0;
        } catch {
          checkActive();
          throw new PaymentSdkError('PROVIDER_RESULT_UNKNOWN');
        }
        checkActive();
        if (['CREATED', 'REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION'].includes(state.status) && category !== 'none') {
          if (category === 'actionable') {
            try { element?.focus(); } catch { /* preserve fixed user-action error */ }
            checkActive();
            throw new PaymentSdkError('PROVIDER_DECLINED');
          }
          throw new PaymentSdkError('PROVIDER_RESULT_UNKNOWN');
        }
        return state;
      } finally { confirming = false; }
    },
    async updatePresentation(value): Promise<void> {
      checkActive();
      const update = presentation(value);
      checkActive();
      return new Promise<void>((resolve, reject) => {
        const request: PresentationUpdate = { value: update, resolve, reject };
        if (currentUpdate) {
          rejectUpdate(pendingUpdate);
          pendingUpdate = request;
        } else startUpdate(request);
      });
    },
    destroy,
  };
}
