import { createGenericPaymentClient } from "../client.js";
import type { CheckoutSession, CheckoutState, GenericPaymentClient } from "../contracts.js";
import { isAbort, PaymentSdkError } from "../errors.js";
import { recoverCheckout } from "../storage.js";
import { widgetStyles } from "./styles.js";
export type WidgetState = "idle" | "loading" | "ready" | "processing" | "requires_action" | "succeeded" | "failed" | "unknown" | "expired";
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
  #status!: HTMLParagraphElement;
  #button!: HTMLButtonElement;
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
    const root = this.attachShadow({ mode: "open" });
    const style = document.createElement("style"); style.textContent = widgetStyles;
    const section = document.createElement("section"); section.setAttribute("aria-label", "Payment checkout");
    this.#status = document.createElement("p"); this.#status.setAttribute("role", "status"); this.#status.setAttribute("aria-live", "polite"); this.#status.tabIndex = -1;
    this.#button = document.createElement("button"); this.#button.type = "button";
    this.#button.addEventListener("click", () => { void this.#act(); });
    section.append(this.#status, this.#button); root.append(style, section); this.#render();
  }
  connectedCallback(): void { this.#configure(false); }
  disconnectedCallback(): void { this.#generation++; this.#controller?.abort(); this.#busy = false; this.#client = undefined; this.#session = undefined; }
  attributeChangedCallback(name: string, oldValue: string | null, newValue: string | null): void {
    if (oldValue !== newValue && this.isConnected) {
      if (name === "locale") this.#render();
      else this.#configure(true);
    }
  }
  #current(generation: number): boolean { return generation === this.#generation && this.isConnected && !this.#controller?.signal.aborted; }
  #configure(reset: boolean): void {
    this.#generation++; this.#controller?.abort(); this.#controller = new AbortController(); this.#busy = false; this.#session = undefined; this.#client = undefined;
    if (reset) this.#state = "idle";
    const generation = this.#generation;
    try {
      this.#client = createGenericPaymentClient({ apiBaseUrl: this.apiBaseUrl, signal: this.#controller.signal });
      this.#session = recoverCheckout({ apiBaseUrl: this.apiBaseUrl, siteKey: this.siteKey, priceKey: this.priceKey }) ?? undefined;
    } catch { this.#state = "idle"; }
    if (!this.#session) this.#state = "idle";
    // Gate the public action before the queued recovery read can start.
    this.#busy = !!this.#session;
    this.#render();
    if (this.#session) queueMicrotask(() => { if (this.#current(generation)) void this.#resume(generation); });
  }
  #transition(state: WidgetState, safe?: CheckoutState, focus = false): void {
    const changed = this.#state !== state; this.#state = state; this.#render();
    if (focus) (this.#button.hidden || this.#button.disabled ? this.#status : this.#button).focus();
    if (changed && !["idle", "loading"].includes(state)) {
      const name = state === "requires_action" ? "requires-action" : state;
      const detail = Object.freeze(safe ? { paymentId: safe.paymentId, status: safe.status } : {});
      this.dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
    }
  }
  #apply(state: CheckoutState, focus = false): void {
    const mapped: WidgetState = ({ CREATED: "ready", REQUIRES_PAYMENT_METHOD: "ready", REQUIRES_ACTION: "requires_action", PROCESSING: "processing", SUCCEEDED: "succeeded", FAILED: "failed", CANCELLED: "failed", PARTIALLY_REFUNDED: "succeeded", REFUNDED: "succeeded", UNKNOWN: "unknown", EXPIRED: "expired" } as const)[state.status];
    this.#transition(mapped ?? "unknown", state, focus);
    if (state.status === "EXPIRED" && this.#session) { this.#client?.clear(this.#session); this.#session = undefined; }
  }
  #error(error: unknown, generation: number, confirming = false): void {
    if (!this.#current(generation) || isAbort(error)) return;
    if (error instanceof PaymentSdkError && error.code === "CHECKOUT_EXPIRED") {
      if (this.#session) { this.#client?.clear(this.#session); this.#transition("expired", { ...this.#session.state, status: "EXPIRED" }, true); this.#session = undefined; }
      else this.#transition("expired");
    } else if (confirming && error instanceof PaymentSdkError && ["PROVIDER_DECLINED", "PROVIDER_TEMPORARILY_UNAVAILABLE"].includes(error.code)
      && this.#session && ["CREATED", "REQUIRES_PAYMENT_METHOD"].includes(this.#session.state.status)) this.#apply(this.#session.state, true);
    else if (this.#session) this.#transition("unknown", { ...this.#session.state, status: "UNKNOWN" }, true);
    else this.#transition("failed");
  }
  async #watch(generation: number): Promise<void> {
    if (!this.#client || !this.#session) return;
    for await (const safe of this.#client.watch(this.#session)) { if (!this.#current(generation)) return; this.#apply(safe); }
  }
  async #resume(generation: number): Promise<void> {
    this.#busy = true; this.#render();
    try { const safe = await this.#client!.get(this.#session!); if (!this.#current(generation)) return; this.#apply(safe); if (["PROCESSING", "REQUIRES_ACTION", "UNKNOWN"].includes(safe.status)) await this.#watch(generation); }
    catch (error) { this.#error(error, generation); }
    finally { if (this.#current(generation)) { this.#busy = false; this.#render(); } }
  }
  async #act(): Promise<void> {
    if (this.#busy || !this.isConnected) return;
    const generation = this.#generation; this.#busy = true;
    let confirming = false;
    try {
      if (!this.#client) throw new PaymentSdkError("INVALID_CONFIGURATION");
      if (!this.#session) {
        this.#transition("loading");
        const session = await this.#client.createCatalogCheckout({ siteKey: this.siteKey, priceKey: this.priceKey });
        if (!this.#current(generation)) return; this.#session = session; this.#busy = false; this.#apply(session.state, true);
      } else {
        confirming = this.#state === "ready";
        this.#transition("loading");
        const safe = confirming ? await this.#client.confirm(this.#session) : await this.#client.get(this.#session);
        if (!this.#current(generation)) return; this.#busy = false; this.#apply(safe, true);
        if (["PROCESSING", "REQUIRES_ACTION", "UNKNOWN"].includes(safe.status)) { this.#busy = true; this.#render(); await this.#watch(generation); }
      }
    } catch (error) { this.#error(error, generation, confirming); }
    finally { if (this.#current(generation)) { this.#busy = false; this.#render(); } }
  }
  #render(): void {
    const messages: Record<WidgetState, string> = { idle: "Ready to start checkout", loading: "Preparing checkout", ready: "Ready to pay", processing: "Payment is processing", requires_action: "Payment requires further action. Awaiting confirmation.", succeeded: "Payment succeeded", failed: "Payment could not be completed", unknown: "Payment result unknown. Check status before trying again.", expired: "Checkout expired" };
    let amount = "";
    if (this.#state === "ready" && this.#session) {
      const safe = this.#session.state;
      try { amount = ` — ${new Intl.NumberFormat(this.locale, { style: "currency", currency: safe.currency }).format(safe.amountMinor / 10 ** new Intl.NumberFormat(this.locale, { style: "currency", currency: safe.currency }).resolvedOptions().maximumFractionDigits!)} (${safe.currency})`; } catch { amount = ` — ${safe.amountMinor} minor units ${safe.currency}`; }
    }
    this.#status.textContent = messages[this.#state] + amount;
    this.#button.textContent = this.#state === "ready" ? "Pay" : this.#state === "unknown" ? "Check status" : "Start checkout";
    this.#button.hidden = ["succeeded", "expired", "processing", "requires_action"].includes(this.#state);
    this.#button.disabled = this.#busy || this.#state === "failed";
    this.#button.setAttribute("aria-label", this.#button.textContent);
  }
}
export function defineGenericPaymentWidget(): void {
  if (typeof customElements !== "undefined" && !customElements.get("generic-payment-widget")) customElements.define("generic-payment-widget", GenericPaymentWidget);
}
