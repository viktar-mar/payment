import { createGenericPaymentClient } from '../client.js';
import type { CheckoutState, GenericPaymentClient, PaymentStatus } from '../contracts.js';
import { isAbort, PaymentSdkError } from '../errors.js';
import type { DonationCampaign, DonationCheckoutInput, DonationCheckoutSession } from './contracts.js';
import { donationStyles } from './styles.js';
const copy = {
  en: { amount: 'Donation amount', custom: 'Custom amount (minor units)', message: 'Optional message', create: 'Create donation', pay: 'Pay', check: 'Check status', loading: 'Loading campaign', ready: 'Choose a donation amount.', pending: 'Please wait.', local: 'Donation checkout created. Ready to pay.', processing: 'Payment is processing', succeeded: 'Payment succeeded', unknown: 'Payment result unknown. Check status.', failed: 'Payment could not be completed.', expired: 'Checkout expired.', signIn: 'Sign in required', anonymous: 'Anonymous donations allowed', invalidAmount: 'Choose a permitted amount.', invalidMessage: 'Use at most 500 characters.', auth: 'Sign in is required for this campaign.', error: 'Unable to complete donation. Please try again.', session: 'Customer session unavailable. Sign in again.' },
  pt: { amount: 'Valor do donativo', custom: 'Valor personalizado (unidades mínimas)', message: 'Mensagem opcional', create: 'Criar donativo', pay: 'Pagar', check: 'Verificar estado', loading: 'A carregar campanha', ready: 'Escolha o valor do donativo.', pending: 'Aguarde.', local: 'Donativo criado. Pronto para pagar.', processing: 'Pagamento em processamento', succeeded: 'Pagamento concluído', unknown: 'Resultado desconhecido. Verifique o estado.', failed: 'Não foi possível concluir o pagamento.', expired: 'Pagamento expirado.', signIn: 'Inicie sessão', anonymous: 'Donativos anónimos permitidos', invalidAmount: 'Escolha um valor permitido.', invalidMessage: 'Use no máximo 500 caracteres.', auth: 'Inicie sessão para esta campanha.', error: 'Não foi possível concluir o donativo. Tente novamente.', session: 'Sessão indisponível. Inicie sessão novamente.' },
};
export interface DonationEventDetail { donationId: string; paymentId: string; checkoutId: string; status: PaymentStatus; }
const ElementBase = (typeof HTMLElement === 'undefined' ? class {} : HTMLElement) as typeof HTMLElement;
export class GenericDonation extends ElementBase {
  static observedAttributes = ['api-base-url', 'site-key', 'campaign-key', 'locale', 'customer-session'];
  #client?: GenericPaymentClient;
  #controller?: AbortController;
  #generation = 0;
  #campaign?: DonationCampaign;
  #session?: DonationCheckoutSession;
  #intent?: DonationCheckoutInput;
  #busy = false;
  #custom = false;
  #amount = 0;
  #error?: keyof typeof copy.en;
  #invalid?: 'amount' | 'message';
  #status?: PaymentStatus;
  #form: HTMLFormElement;
  #title: HTMLHeadingElement;
  #purpose: HTMLParagraphElement;
  #fieldset: HTMLFieldSetElement;
  #legend: HTMLLegendElement;
  #suggestions: HTMLDivElement;
  #customLabel: HTMLLabelElement;
  #customInput: HTMLInputElement;
  #hint: HTMLParagraphElement;
  #policy: HTMLParagraphElement;
  #messageLabel: HTMLLabelElement;
  #message: HTMLTextAreaElement;
  #counter: HTMLParagraphElement;
  #alert: HTMLParagraphElement;
  #live: HTMLParagraphElement;
  #submit: HTMLButtonElement;
  get apiBaseUrl(): string { return this.getAttribute('api-base-url') ?? ''; }
  set apiBaseUrl(v: string) { this.setAttribute('api-base-url', v); }
  get siteKey(): string { return this.getAttribute('site-key') ?? ''; }
  set siteKey(v: string) { this.setAttribute('site-key', v); }
  get campaignKey(): string { return this.getAttribute('campaign-key') ?? ''; }
  set campaignKey(v: string) { this.setAttribute('campaign-key', v); }
  get locale(): string { return this.getAttribute('locale') ?? 'en-GB'; }
  set locale(v: string) { this.setAttribute('locale', v); }
  constructor() {
    super(); const r = this.attachShadow({ mode: 'open' }); const style = document.createElement('style'); style.textContent = donationStyles;
    this.#form = document.createElement('form'); this.#form.noValidate = true; this.#title = document.createElement('h2'); this.#purpose = document.createElement('p');
    this.#fieldset = document.createElement('fieldset'); this.#legend = document.createElement('legend'); this.#suggestions = document.createElement('div');
    this.#customLabel = document.createElement('label'); this.#customLabel.htmlFor = 'donation-amount'; this.#customInput = document.createElement('input'); this.#customInput.id = 'donation-amount'; this.#customInput.type = 'number'; this.#customInput.step = '1'; this.#customInput.inputMode = 'numeric'; this.#customInput.setAttribute('aria-describedby', 'donation-hint donation-error');
    this.#hint = document.createElement('p'); this.#hint.id = 'donation-hint'; this.#policy = document.createElement('p');
    this.#messageLabel = document.createElement('label'); this.#messageLabel.htmlFor = 'donation-message'; this.#message = document.createElement('textarea'); this.#message.id = 'donation-message'; this.#message.autocomplete = 'off'; this.#message.setAttribute('aria-describedby', 'donation-count donation-error');
    this.#counter = document.createElement('p'); this.#counter.id = 'donation-count'; this.#alert = document.createElement('p'); this.#alert.id = 'donation-error'; this.#alert.setAttribute('role', 'alert');
    this.#live = document.createElement('p'); this.#live.setAttribute('role', 'status'); this.#live.setAttribute('aria-live', 'polite'); this.#live.tabIndex = -1;
    this.#submit = document.createElement('button'); this.#submit.type = 'submit';
    this.#fieldset.append(this.#legend, this.#suggestions, this.#customLabel, this.#customInput, this.#hint);
    this.#form.append(this.#title, this.#purpose, this.#policy, this.#fieldset, this.#messageLabel, this.#message, this.#counter, this.#alert, this.#live, this.#submit); r.append(style, this.#form);
    this.#form.addEventListener('submit', e => { e.preventDefault(); void this.#act(); });
    this.#customInput.addEventListener('input', () => { this.#custom = true; this.#changed(); this.#render(); });
    this.#message.addEventListener('input', () => { this.#changed(); this.#render(); }); this.#render();
  }
  connectedCallback(): void { void this.#configure(); }
  disconnectedCallback(): void { this.#reset(); this.#render(); }
  attributeChangedCallback(name: string, old: string | null, value: string | null): void { if (old === value || !this.isConnected) return; if (name === 'locale') this.#render(); else void this.#configure(); }
  #changed(): void { if (this.#intent) delete this.#intent.message; this.#intent = undefined; this.#error = undefined; this.#invalid = undefined; }
  #reset(): void { ++this.#generation; this.#controller?.abort(); this.#controller = undefined; this.#client = undefined; this.#campaign = undefined; this.#session = undefined; this.#changed(); this.#message.value = ''; this.#customInput.value = ''; this.#custom = false; this.#amount = 0; this.#busy = false; this.#status = undefined; }
  #current(g: number): boolean { return this.isConnected && g === this.#generation && !this.#controller?.signal.aborted; }
  async #configure(): Promise<void> {
    this.#reset(); this.#controller = new AbortController(); const g = this.#generation; this.#busy = true; this.#render();
    try { this.#client = createGenericPaymentClient({ apiBaseUrl: this.apiBaseUrl, signal: this.#controller.signal }); if (![null, 'auto', 'omit'].includes(this.getAttribute('customer-session'))) throw new PaymentSdkError('INVALID_CONFIGURATION'); const c = await this.#client.getDonationCampaign({ siteKey: this.siteKey, campaignKey: this.campaignKey }); if (!this.#current(g)) return; this.#campaign = c; this.#amount = c.suggestedAmounts[0] ?? 0; }
    catch (e) { if (this.#current(g) && !isAbort(e)) this.#error = 'error'; }
    finally { if (this.#current(g)) { this.#busy = false; this.#render(); } }
  }
  #event(name: string, status: PaymentStatus): void {
    if (!this.#session) return; const detail: DonationEventDetail = Object.freeze({ donationId: this.#session.donation.donationId, paymentId: this.#session.state.paymentId, checkoutId: this.#session.checkoutId, status }); this.dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
  }
  #apply(state: CheckoutState, financial = true): void { const changed = this.#status !== state.status; this.#status = state.status; this.#render(); if (changed) { this.#event('donation-state', state.status); if (financial && state.status === 'SUCCEEDED') this.#event('donation-succeeded', state.status); } }
  async #watch(session: DonationCheckoutSession, g: number): Promise<void> {
    for await (const next of this.#client!.watch(session)) { if (!this.#current(g)) return; this.#apply(next); }
  }
  async #act(): Promise<void> {
    if (this.#busy || !this.#campaign || !this.#client || !this.isConnected) return;
    const g = this.#generation, c = this.#campaign;
    if (!this.#session) {
      const amount = this.#custom ? Number(this.#customInput.value) : this.#amount;
      if (!Number.isSafeInteger(amount) || amount < c.minimumAmount || amount > c.maximumAmount || (!c.customAmountAllowed && !c.suggestedAmounts.includes(amount))) { this.#error = 'invalidAmount'; this.#invalid = 'amount'; this.#render(); (c.customAmountAllowed ? this.#customInput : this.#suggestions.querySelector('button'))?.focus(); return; }
      this.#message.value = this.#message.value.normalize('NFC');
      if ([...this.#message.value].length > 500) { this.#error = 'invalidMessage'; this.#invalid = 'message'; this.#render(); this.#message.focus(); return; }
      if (!c.anonymousAllowed && this.getAttribute('customer-session') === 'omit') { this.#error = 'auth'; this.#render(); this.#live.focus(); return; }
      if (!this.#intent) this.#intent = { siteKey: this.siteKey, campaignKey: this.campaignKey, amountMinor: amount, ...(this.#message.value ? { message: this.#message.value } : {}), customerSession: this.getAttribute('customer-session') === 'omit' ? 'omit' : 'auto', idempotencyKey: crypto.randomUUID() };
    }
    this.#busy = true; this.#error = undefined; this.#render();
    try {
      if (!this.#session) {
        const intent = this.#intent!; const session = await this.#client.createDonationCheckout(intent);
        // Scrub even an obsolete successful request. Never touch replacement B's draft.
        delete intent.message;
        if (!this.#current(g)) return;
        this.#message.value = ''; this.#intent = undefined; this.#session = session; this.#busy = false; this.#apply(session.state, false); this.#event('donation-created', session.state.status); this.#submit.focus();
        if (['PROCESSING', 'REQUIRES_ACTION', 'UNKNOWN'].includes(session.state.status)) { this.#busy = true; this.#render(); await this.#watch(session, g); }
      } else {
        const client = this.#client, session = this.#session;
        const state = ['CREATED', 'REQUIRES_PAYMENT_METHOD'].includes(this.#status!) ? await client.confirm(session) : await client.get(session);
        if (!this.#current(g)) return; this.#apply(state);
        if (['PROCESSING', 'REQUIRES_ACTION', 'UNKNOWN'].includes(state.status)) await this.#watch(session, g);
        if (!this.#current(g)) return; this.#live.focus();
      }
    } catch (e) {
      if (!this.#current(g) || isAbort(e)) return;
      if (this.#session) { this.#status = e instanceof PaymentSdkError && e.code === 'CHECKOUT_EXPIRED' ? 'EXPIRED' : 'UNKNOWN'; this.#event('donation-state', this.#status); }
      else if (e instanceof PaymentSdkError && ['INVALID_SESSION', 'SESSION_EXPIRED', 'AUTHENTICATION_FAILED'].includes(e.code)) { this.#changed(); this.#message.value = ''; this.#error = 'session'; }
      else this.#error = 'error';
      this.#live.focus();
    } finally { if (this.#current(g)) { this.#busy = false; this.#render(); } }
  }
  #format(n: number, currency: string): string { try { const f = new Intl.NumberFormat(this.locale, { style: 'currency', currency }); return f.format(n / 10 ** f.resolvedOptions().maximumFractionDigits!); } catch { return `${n} ${currency}`; } }
  #render(): void {
    const t = copy[this.locale.toLowerCase().startsWith('pt') ? 'pt' : 'en'], c = this.#campaign;
    this.#form.lang = this.locale.toLowerCase().startsWith('pt') ? 'pt' : 'en'; this.#form.setAttribute('aria-busy', String(this.#busy)); this.#title.textContent = c?.displayName ?? ''; this.#purpose.textContent = c?.purposeDescription ?? ''; this.#legend.textContent = t.amount;
    const existing = Array.from(this.#suggestions.querySelectorAll<HTMLButtonElement>('button'));
    if (existing.length !== (c?.suggestedAmounts.length ?? 0) || existing.some((b, i) => b.dataset.amount !== String(c?.suggestedAmounts[i]))) {
      this.#suggestions.replaceChildren(...(c?.suggestedAmounts ?? []).map(amount => { const b = document.createElement('button'); b.type = 'button'; b.dataset.amount = String(amount); b.addEventListener('click', () => { if (this.#busy || this.#session) return; this.#amount = amount; this.#custom = false; this.#customInput.value = ''; this.#changed(); this.#render(); }); return b; }));
    }
    for (const b of this.#suggestions.querySelectorAll<HTMLButtonElement>('button')) { b.textContent = this.#format(Number(b.dataset.amount), c!.currency); b.setAttribute('aria-pressed', String(!this.#custom && this.#amount === Number(b.dataset.amount))); }
    this.#customLabel.textContent = t.custom; this.#customLabel.hidden = this.#customInput.hidden = !c?.customAmountAllowed; this.#customInput.min = String(c?.minimumAmount ?? 1); this.#customInput.max = String(c?.maximumAmount ?? 1); this.#customInput.setAttribute('aria-invalid', String(this.#invalid === 'amount'));
    this.#hint.textContent = c ? `${c.minimumAmount}–${c.maximumAmount} ${c.currency} (${t.custom})` : ''; this.#policy.textContent = c ? c.anonymousAllowed ? t.anonymous : t.signIn : '';
    this.#messageLabel.textContent = t.message; this.#counter.textContent = `${[...this.#message.value.normalize('NFC')].length} / 500`; this.#message.setAttribute('aria-invalid', String(this.#invalid === 'message'));
    this.#fieldset.disabled = this.#message.disabled = this.#busy || !!this.#session || !c;
    this.#alert.textContent = this.#error ? t[this.#error] : ''; this.#alert.hidden = !this.#error;
    const s = this.#status; this.#live.textContent = this.#busy && !['PROCESSING', 'REQUIRES_ACTION', 'UNKNOWN'].includes(s ?? '') ? t.pending : !c ? t.loading : !s ? t.ready : ['CREATED', 'REQUIRES_PAYMENT_METHOD'].includes(s) ? t.local : s === 'SUCCEEDED' ? t.succeeded : ['PROCESSING', 'REQUIRES_ACTION'].includes(s) ? t.processing : s === 'UNKNOWN' ? t.unknown : s === 'EXPIRED' ? t.expired : t.failed;
    this.#submit.textContent = !s ? t.create : ['CREATED', 'REQUIRES_PAYMENT_METHOD'].includes(s) ? t.pay : t.check; this.#submit.disabled = this.#busy || !c; this.#submit.hidden = !!s && !['CREATED', 'REQUIRES_PAYMENT_METHOD', 'UNKNOWN'].includes(s);
  }
}
export function defineGenericDonation(): void { if (typeof customElements !== 'undefined' && !customElements.get('generic-donation')) customElements.define('generic-donation', GenericDonation); }
