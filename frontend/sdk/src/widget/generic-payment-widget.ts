import { createGenericPaymentClient } from "../client.js";
import type { CheckoutSession, CheckoutState, GenericPaymentClient } from "../contracts.js";
import { isAbort, PaymentSdkError } from "../errors.js";
import { recoverCheckout } from "../storage.js";
import { widgetStyles } from "./styles.js";
import { CheckoutProviderView, checkoutPresentation } from './checkout-provider-view.js';
import { subscribeCheckoutCustomer } from '../customer/session-lifecycle.js';
import { createProviderMount, type OwnedProviderMount } from './provider-mount.js';
import { registerFormLoadWidget, configureFormLoadWidget, disconnectFormLoadWidget, resumeFormLoadWidget, bindFormLoadDiagnostic, markFormLoad } from '../diagnostics/form-load-observer.js';
export type WidgetState = "idle" | "loading" | "ready" | "checking" | "processing" | "requires_action" | "succeeded" | "failed" | "unknown" | "expired";
// Importing ESM in non-DOM build tooling does not evaluate a missing HTMLElement.
const ElementBase = (typeof HTMLElement === "undefined" ? class {} : HTMLElement) as typeof HTMLElement;
export class GenericPaymentWidget extends ElementBase {
  static observedAttributes = ["site-key", "price-key", "api-base-url", "locale"];
  #state: WidgetState = "idle";
  #session?: CheckoutSession;
  #client?: GenericPaymentClient;
  #controller?: AbortController;
  #generation = 0;
  #busy = false;
  #providerView?: CheckoutProviderView;
  #providerMount?: OwnedProviderMount;
  #providerReady = false;
  #checking = false;
  #actionReady = false;
  #resumeAvailable = false;
  #unresolvedCheckout?: string;
  #expiry?: ReturnType<typeof setTimeout>;
  #unsubscribe?: () => void;
  #presentation = () => { void this.#providerView?.updatePresentation(checkoutPresentation(this)).catch(() => {}); };
  #status!: HTMLParagraphElement;
  #button!: HTMLButtonElement;
  #resumeButton!: HTMLButtonElement;
  get state(): WidgetState { return this.#state; }
  get siteKey(): string { return this.getAttribute("site-key") ?? ""; }
  set siteKey(value: string) { this.setAttribute("site-key", value); }
  get priceKey(): string { return this.getAttribute("price-key") ?? ""; }
  set priceKey(value: string) { this.setAttribute("price-key", value); }
  get apiBaseUrl(): string { return this.getAttribute("api-base-url") ?? ""; }
  set apiBaseUrl(value: string) { this.setAttribute("api-base-url", value); }
  get locale(): string { return this.getAttribute("locale") ?? "en"; }
  set locale(value: string) { this.setAttribute("locale", value); }
  constructor() {
    super();
    registerFormLoadWidget(this);
    const root = this.attachShadow({ mode: "open" });
    const style = document.createElement("style"); style.textContent = widgetStyles;
    const section = document.createElement("section"); section.setAttribute("aria-label", "Payment checkout");
    this.#status = document.createElement("p"); this.#status.setAttribute("role", "status"); this.#status.setAttribute("aria-live", "polite"); this.#status.tabIndex = -1;
    this.#button = document.createElement("button"); this.#button.type = "button";
    this.#button.addEventListener("click", () => { void this.#act(); });
    this.#resumeButton = document.createElement('button'); this.#resumeButton.type = 'button'; this.#resumeButton.textContent = 'Resume existing payment'; this.#resumeButton.addEventListener('click', () => { void this.#act(true); });
    this.addEventListener('payment-theme-change', this.#presentation);
    section.append(this.#status, this.#button, this.#resumeButton); root.append(style, section); this.#render();
  }
  connectedCallback(): void { this.#configure(false); }
  disconnectedCallback(): void { disconnectFormLoadWidget(this); this.#generation++; this.#controller?.abort(); this.#cleanup(); this.#busy = false; this.#client = undefined; this.#session = undefined; }
  attributeChangedCallback(name: string, oldValue: string | null, newValue: string | null): void {
    if (oldValue !== newValue && this.isConnected) {
      if (name === "locale") { this.#render(); this.#presentation(); }
      else this.#configure(true);
    }
  }
  #current(generation: number): boolean { return generation === this.#generation && this.isConnected && !this.#controller?.signal.aborted; }
  #configure(reset: boolean): void {
    configureFormLoadWidget(this);
    this.#generation++; this.#controller?.abort(); this.#cleanup(); this.#controller = new AbortController(); this.#busy = false; this.#session = undefined; this.#client = undefined;
    if (reset) this.#state = "idle";
    const generation = this.#generation;
    try {
      this.#client = createGenericPaymentClient({ apiBaseUrl: this.apiBaseUrl, signal: this.#controller.signal });
      this.#session = recoverCheckout({ apiBaseUrl: this.apiBaseUrl, siteKey: this.siteKey, priceKey: this.priceKey }) ?? undefined;
    } catch { this.#state = "idle"; }
    if (!this.#session) this.#state = "idle";
    else { this.#checking = this.#session.checkoutId === this.#unresolvedCheckout; this.#bindSession(this.#session, generation); }
    // Gate the public action before the queued recovery read can start.
    this.#busy = !!this.#session;
    this.#render();
    if (this.#session) queueMicrotask(() => { if (this.#current(generation)) void this.#resume(generation); });
  }
  #cleanup(): void { this.#providerView?.destroy(); this.#providerView = undefined; this.#providerMount?.destroy(); this.#providerMount = undefined; this.#providerReady = false; this.#checking = false; this.#actionReady = false; this.#resumeAvailable = false; clearTimeout(this.#expiry); this.#unsubscribe?.(); this.#unsubscribe = undefined; }
  #bindSession(session: CheckoutSession, generation: number): void {
    const unsubscribe = subscribeCheckoutCustomer(session, () => { if (!this.#current(generation)) return; this.#client?.clear(session); this.#configure(true); });
    if (!this.#current(generation)) { unsubscribe(); return; } this.#unsubscribe = unsubscribe;
    this.#expiry = setTimeout(() => { if (!this.#current(generation) || this.#session !== session) return; this.#apply({ ...session.state, status: 'EXPIRED' }); }, Math.min(2147483647, Math.max(0, Date.parse(session.expiresAt) - Date.now())));
  }
  async #mountProvider(generation: number): Promise<void> {
    if (!this.#session || (this.#session.providerType === 'STRIPE' && !['CREATED', 'REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION'].includes(this.#session.state.status))) return;
    this.#providerView?.destroy(); this.#providerMount?.destroy(); this.#providerReady = false;
    this.#providerMount = createProviderMount(this, this.#button, this.#session.providerType);
    const diagnostic = bindFormLoadDiagnostic(this, this.#providerMount.host, generation);
    this.#providerView = new CheckoutProviderView(this.#client!, this.#providerMount.host); this.#presentation();
    await this.#providerView.mount(this.#session, this.#controller!.signal);
    if (this.#current(generation)) { this.#providerReady = true; markFormLoad(diagnostic, 'widget_completed'); }
  }
  #transition(state: WidgetState, safe?: CheckoutState, focus = false): void {
    const changed = this.#state !== state; this.#state = state; this.#render();
    if (focus) (this.#button.hidden || this.#button.disabled ? this.#status : this.#button).focus();
    if (changed && !["idle", "loading", "checking"].includes(state)) {
      const name = state === "requires_action" ? "requires-action" : state;
      const detail = Object.freeze(safe ? { paymentId: safe.paymentId, status: safe.status } : {});
      this.dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
    }
  }
  #apply(state: CheckoutState, focus = false): void {
    if (['SUCCEEDED', 'FAILED', 'CANCELLED', 'REFUNDED', 'PARTIALLY_REFUNDED', 'EXPIRED'].includes(state.status)) { this.#cleanup(); this.#unresolvedCheckout = undefined; }
    if (this.#checking && ['CREATED', 'REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION'].includes(state.status)) { this.#transition('checking', undefined, focus); return; }
    const mapped: WidgetState = this.#actionReady && state.status === 'REQUIRES_ACTION' ? 'ready' : ({ CREATED: "ready", REQUIRES_PAYMENT_METHOD: "ready", REQUIRES_ACTION: "requires_action", PROCESSING: "processing", SUCCEEDED: "succeeded", FAILED: "failed", CANCELLED: "failed", PARTIALLY_REFUNDED: "succeeded", REFUNDED: "succeeded", UNKNOWN: "unknown", EXPIRED: "expired" } as const)[state.status];
    this.#transition(mapped ?? "unknown", state, focus);
    if (state.status === "EXPIRED" && this.#session) { this.#client?.clear(this.#session); this.#session = undefined; }
  }
  #error(error: unknown, generation: number, confirming = false): void {
    if (!this.#current(generation) || isAbort(error)) return;
    if (error instanceof PaymentSdkError && error.code === "CHECKOUT_EXPIRED") {
      if (this.#session) this.#apply({ ...this.#session.state, status: 'EXPIRED' }, true);
      else this.#transition("expired");
    } else if (confirming && this.#session?.providerType === 'STRIPE') {
      if (error instanceof PaymentSdkError && error.code === 'PROVIDER_DECLINED') { this.#checking = false; this.#apply(this.#session.state); }
      else { this.#checking = true; this.#unresolvedCheckout = this.#session.checkoutId; this.#transition('checking', undefined, true); }
    } else if (confirming && error instanceof PaymentSdkError && ["PROVIDER_DECLINED", "PROVIDER_TEMPORARILY_UNAVAILABLE"].includes(error.code)
      && this.#session && ["CREATED", "REQUIRES_PAYMENT_METHOD"].includes(this.#session.state.status)) this.#apply(this.#session.state, true);
    else if (this.#session) { if (this.#session.providerType === 'STRIPE') this.#unresolvedCheckout = this.#session.checkoutId; this.#transition("unknown", { ...this.#session.state, status: "UNKNOWN" }, true); }
    else this.#transition("failed");
  }
  async #watch(generation: number): Promise<void> {
    if (!this.#client || !this.#session) return;
    for await (const safe of this.#client.watch(this.#session)) {
      if (!this.#current(generation)) return;
      if (this.#session?.providerType === 'STRIPE' && ['CREATED', 'REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION'].includes(safe.status)) {
        this.#checking = safe.status === 'CREATED' || !this.#providerReady;
        this.#resumeAvailable = this.#checking; this.#actionReady = !this.#checking && safe.status === 'REQUIRES_ACTION';
      }
      this.#apply(safe); if (this.#resumeAvailable || this.#actionReady) return;
    }
  }
  async #resume(generation: number): Promise<void> {
    this.#busy = true; this.#render();
    try {
      const safe = await this.#client!.get(this.#session!); if (!this.#current(generation)) return;
      if (this.#session!.providerType === 'STRIPE' && ['CREATED', 'REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION'].includes(safe.status)) { this.#checking = true; this.#resumeAvailable = true; }
      else await this.#mountProvider(generation);
      if (!this.#current(generation)) return; this.#apply(safe);
      if (!this.#resumeAvailable && ["PROCESSING", "REQUIRES_ACTION", "UNKNOWN"].includes(safe.status)) await this.#watch(generation);
    }
    catch (error) { this.#error(error, generation); }
    finally { if (this.#current(generation)) { this.#busy = false; this.#render(); } }
  }
  async #act(resume = false): Promise<void> {
    if (resume) resumeFormLoadWidget(this);
    if (this.#busy || !this.isConnected || (resume && !this.#resumeAvailable)) return;
    const generation = this.#generation; this.#busy = true;
    let confirming = false;
    try {
      if (!this.#client) throw new PaymentSdkError("INVALID_CONFIGURATION");
      if (resume && this.#session) {
        this.#resumeAvailable = false; this.#transition('loading'); await this.#mountProvider(generation); if (!this.#current(generation)) return;
        this.#checking = false; this.#actionReady = this.#session.state.status === 'REQUIRES_ACTION'; this.#unresolvedCheckout = undefined; this.#busy = false; this.#apply(this.#session.state, true); return;
      }
      if (!this.#session) {
        this.#transition("loading");
        const session = await this.#client.createCatalogCheckout({ siteKey: this.siteKey, priceKey: this.priceKey });
        if (!this.#current(generation)) return; this.#session = session; this.#bindSession(session, generation); await this.#mountProvider(generation);
        if (!this.#current(generation)) return; this.#busy = false; this.#apply(session.state, true);
      } else {
        confirming = this.#state === "ready" && this.#providerReady && !this.#checking;
        this.#actionReady = false;
        this.#transition("loading");
        const safe = confirming ? await this.#providerView!.confirm() : await this.#client.get(this.#session);
        if (!this.#current(generation)) return;
        if (this.#session.providerType === 'STRIPE') {
          if (confirming && safe.status === 'CREATED') { this.#checking = true; this.#unresolvedCheckout = this.#session.checkoutId; }
          if (!confirming && ['REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION'].includes(safe.status)) { this.#checking = false; this.#unresolvedCheckout = undefined; }
          if (!confirming && safe.status === 'REQUIRES_ACTION' && this.#providerReady) this.#actionReady = true;
          if (!this.#providerReady && ['CREATED', 'REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION'].includes(safe.status)) this.#checking = true;
          this.#resumeAvailable = !confirming && this.#checking && ['CREATED', 'REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION'].includes(safe.status);
        }
        if (!this.#current(generation)) return; this.#busy = false; this.#apply(safe, true);
        if (!this.#actionReady && !this.#resumeAvailable && ["PROCESSING", "REQUIRES_ACTION", "UNKNOWN"].includes(safe.status)) { this.#busy = true; this.#render(); await this.#watch(generation); }
      }
    } catch (error) { this.#error(error, generation, confirming || resume); }
    finally { if (this.#current(generation)) { this.#busy = false; this.#render(); } }
  }
  #render(): void {
    const messages: Record<WidgetState, string> = { idle: "Ready to start checkout", loading: "Preparing checkout", ready: "Ready to pay", checking: this.locale.toLowerCase().startsWith('pt') ? 'A verificar pagamento' : 'Checking payment', processing: "Payment is processing", requires_action: "Payment requires further action. Awaiting confirmation.", succeeded: "Payment succeeded", failed: "Payment could not be completed", unknown: "Payment result unknown. Check status before trying again.", expired: "Checkout expired" };
    let amount = "";
    if (this.#state === "ready" && this.#session) {
      const safe = this.#session.state;
      try { amount = ` — ${new Intl.NumberFormat(this.locale, { style: "currency", currency: safe.currency }).format(safe.amountMinor / 10 ** new Intl.NumberFormat(this.locale, { style: "currency", currency: safe.currency }).resolvedOptions().maximumFractionDigits!)} (${safe.currency})`; } catch { amount = ` — ${safe.amountMinor} minor units ${safe.currency}`; }
    }
    this.#status.textContent = messages[this.#state] + amount;
    this.#button.textContent = this.#state === "ready" && this.#providerReady ? "Pay" : ['unknown', 'checking'].includes(this.#state) ? "Check status" : "Start checkout";
    this.#button.hidden = ["succeeded", "expired", "processing", "requires_action"].includes(this.#state) || (!!this.#session && this.#session.providerType === 'STRIPE' && !this.#providerReady && ['loading', 'ready'].includes(this.#state));
    this.#button.disabled = this.#busy || this.#state === "failed";
    this.#button.setAttribute("aria-label", this.#button.textContent);
    this.#resumeButton.textContent = this.locale.toLowerCase().startsWith('pt') ? 'Retomar pagamento existente' : 'Resume existing payment'; this.#resumeButton.hidden = !this.#resumeAvailable; this.#resumeButton.disabled = this.#busy;
  }
}
export function defineGenericPaymentWidget(): void {
  if (typeof customElements !== "undefined" && !customElements.get("generic-payment-widget")) customElements.define("generic-payment-widget", GenericPaymentWidget);
}
