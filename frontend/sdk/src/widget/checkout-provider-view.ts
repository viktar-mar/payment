import type { CheckoutSession, CheckoutState, GenericPaymentClient } from '../contracts.js';
import { object } from '../contracts.js';
import { PaymentSdkError } from '../errors.js';
import { mountStripePayment } from '../providers/stripe-driver.js';
import type { StripePaymentHandle, StripePresentation } from '../providers/stripe-driver.js';
import { strictAppearance } from '../providers/stripe-appearance.js';
const checkAbort = (signal: AbortSignal) => { if (signal.aborted) throw new DOMException('Aborted', 'AbortError'); };

// Only finite supported presentation values cross into the provider iframe.
export function checkoutPresentation(host: HTMLElement, donation = false): StripePresentation {
  const css = getComputedStyle(host), prefix = donation ? '--generic-donation-' : '--gp-';
  const color = (name: string, fallback: string) => { const value = css.getPropertyValue(prefix + name).trim(); return /^#(?:[a-f\d]{3}|[a-f\d]{6})$/i.test(value) ? value : fallback; };
  const radius = css.getPropertyValue(prefix + 'radius').trim(); const match = /^(\d+(?:\.\d+)?)(px|rem)$/.exec(radius);
  const px = match ? Number(match[1]) * (match[2] === 'rem' ? 16 : 1) : 12;
  return { locale: host.getAttribute('locale')?.toLowerCase().startsWith('pt') ? 'pt' : 'en', appearance: { variables: {
    colorPrimary: color('accent', donation ? '#2351a0' : '#1554ad'), colorBackground: color('background', '#fff'), colorText: color('text', donation ? '#17243a' : '#17202a'), colorDanger: color(donation ? 'error' : 'danger', '#9d1821'), fontFamily: 'system-ui', spacingUnit: '4px', borderRadius: `${Number.isInteger(px) && px >= 0 && px <= 32 ? px : 12}px`,
  } } };
}

export class CheckoutProviderView {
  #session?: CheckoutSession;
  #controller?: AbortController;
  #handle?: StripePaymentHandle;
  #ready = false;
  #presentation: StripePresentation = { appearance: {}, locale: 'en' };
  #detach?: () => void;
  constructor(private readonly client: GenericPaymentClient, private readonly host: HTMLElement) {}
  async mount(session: CheckoutSession, signal: AbortSignal): Promise<void> {
    this.destroy(); checkAbort(signal);
    const controller = new AbortController(); this.#controller = controller; this.#session = session;
    const abort = () => this.destroy(); signal.addEventListener('abort', abort, { once: true });
    this.#detach = () => signal.removeEventListener('abort', abort);
    try {
      if (session.providerType === 'STRIPE') {
        const capability = await this.client.prepareClientPayment(session, { signal: controller.signal }); checkAbort(controller.signal);
        const initial = this.#presentation;
        const handle = await mountStripePayment({ container: this.host, capability, signal: controller.signal, ...initial, refresh: () => this.client.get(session, controller.signal) });
        checkAbort(controller.signal); this.#handle = handle;
        // A locale/theme change during loader/readiness uses this same capability.
        if (initial !== this.#presentation) {
          try { await handle.updatePresentation(this.#presentation); }
          catch { checkAbort(controller.signal); } // A visual failure cannot discard a ready payment form.
        }
      }
      checkAbort(controller.signal); this.#ready = true;
    } catch (error) { if (this.#controller === controller) this.destroy(); throw error; }
  }
  async confirm(): Promise<CheckoutState> {
    const session = this.#session;
    if (session && Date.parse(session.expiresAt) <= Date.now()) { this.destroy(); throw new PaymentSdkError('CHECKOUT_EXPIRED'); }
    if (!this.#ready || !session) throw new PaymentSdkError('PAYMENT_STATE_CONFLICT');
    return session.providerType === 'STRIPE' ? this.#handle!.confirm() : this.client.confirm(session);
  }
  async updatePresentation(value: StripePresentation): Promise<void> {
    if (!object(value) || !Reflect.ownKeys(value).every(key => key === 'appearance' || key === 'locale')) throw new PaymentSdkError('INVALID_CONFIGURATION');
    const locale = value.locale;
    if (locale !== undefined && locale !== 'en' && locale !== 'pt') throw new PaymentSdkError('INVALID_CONFIGURATION');
    this.#presentation = { appearance: strictAppearance(value.appearance), ...(locale ? { locale } : {}) };
    if (this.#handle) await this.#handle.updatePresentation(this.#presentation);
  }
  destroy(): void {
    this.#ready = false; this.#controller?.abort(); this.#controller = undefined; this.#detach?.(); this.#detach = undefined;
    this.#handle?.destroy(); this.#handle = undefined; this.#session = undefined;
  }
}
