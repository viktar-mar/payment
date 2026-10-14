import { createGenericPaymentClient } from "../client.js";
import type { GenericPaymentClient } from "../contracts.js";
import { isAbort, PaymentSdkError } from "../errors.js";
import type { CustomerSessionState, EmailOtpChallenge } from "./contracts.js";
import { emailOtpStyles } from "./styles.js";
const translations = {
  en: { title: "Email sign in", email: "Email", code: "Six-digit code", send: "Request code", verify: "Verify code", resend: "Resend code", idle: "Sign in with your email.", challenge: "If available, use the code sent to your email.", pending: "Please wait.", success: "Signed in.", invalidEmail: "Enter a valid email address.", invalidCode: "Enter a six-digit code.", error: "Unable to complete sign in. Please try again.", rate: "Please try again later." },
  pt: { title: "Entrar com email", email: "Email", code: "Código de seis dígitos", send: "Pedir código", verify: "Verificar código", resend: "Reenviar código", idle: "Entre com o seu email.", challenge: "Se disponível, use o código enviado para o seu email.", pending: "Aguarde.", success: "Sessão iniciada.", invalidEmail: "Introduza um email válido.", invalidCode: "Introduza um código de seis dígitos.", error: "Não foi possível entrar. Tente novamente.", rate: "Tente novamente mais tarde." },
};
export interface CustomerAuthenticatedDetail extends CustomerSessionState { state: "authenticated"; }
const ElementBase = (typeof HTMLElement === "undefined" ? class {} : HTMLElement) as typeof HTMLElement;
export class GenericEmailOtp extends ElementBase {
  static observedAttributes = ["api-base-url", "site-key", "locale"];
  #client?: GenericPaymentClient;
  #controller?: AbortController;
  #generation = 0;
  #pending = false;
  #challenge?: EmailOtpChallenge;
  #authenticated = false;
  #resendAt = 0;
  #timer?: ReturnType<typeof setTimeout>;
  #error?: "invalidEmail" | "invalidCode" | "error" | "rate";
  #section: HTMLElement;
  #status: HTMLParagraphElement;
  #alert: HTMLParagraphElement;
  #form: HTMLFormElement;
  #label: HTMLLabelElement;
  #input: HTMLInputElement;
  #submit: HTMLButtonElement;
  #resend: HTMLButtonElement;
  get apiBaseUrl(): string { return this.getAttribute("api-base-url") ?? ""; }
  set apiBaseUrl(value: string) { this.setAttribute("api-base-url", value); }
  get siteKey(): string { return this.getAttribute("site-key") ?? ""; }
  set siteKey(value: string) { this.setAttribute("site-key", value); }
  get locale(): string { return this.getAttribute("locale") ?? "en"; }
  set locale(value: string) { this.setAttribute("locale", value); }
  constructor() {
    super(); const root = this.attachShadow({ mode: "open" });
    const style = document.createElement("style"); style.textContent = emailOtpStyles;
    this.#section = document.createElement("section");
    this.#status = document.createElement("p"); this.#status.setAttribute("role", "status"); this.#status.setAttribute("aria-live", "polite"); this.#status.tabIndex = -1;
    this.#alert = document.createElement("p"); this.#alert.setAttribute("role", "alert"); this.#alert.id = "otp-error";
    this.#form = document.createElement("form"); this.#form.noValidate = true;
    this.#label = document.createElement("label"); this.#label.htmlFor = "otp-input";
    this.#input = document.createElement("input"); this.#input.id = "otp-input"; this.#input.required = true; this.#input.setAttribute("aria-describedby", "otp-error");
    this.#submit = document.createElement("button"); this.#submit.type = "submit";
    this.#resend = document.createElement("button"); this.#resend.type = "button";
    this.#form.append(this.#label, this.#input, this.#submit, this.#resend);
    this.#section.append(this.#status, this.#alert, this.#form); root.append(style, this.#section);
    this.#form.addEventListener("submit", e => { e.preventDefault(); void this.#act(); });
    this.#resend.addEventListener("click", () => {
      if (this.#pending || !this.#challenge || Date.now() < this.#resendAt) return;
      this.#challenge = undefined; this.#input.value = ""; this.#error = undefined; this.#render(); this.#input.focus();
    });
    this.#render();
  }
  connectedCallback(): void { this.#configure(); }
  disconnectedCallback(): void { this.#reset(); this.#render(); }
  attributeChangedCallback(name: string, oldValue: string | null, value: string | null): void {
    if (oldValue === value || !this.isConnected) return;
    if (name === "locale") this.#render(); else this.#configure();
  }
  #reset(): void {
    this.#generation++; this.#controller?.abort(); this.#controller = undefined; this.#client = undefined; this.#pending = false; this.#challenge = undefined; this.#authenticated = false; this.#input.value = ""; this.#error = undefined; this.#resendAt = 0;
    if (this.#timer !== undefined) clearTimeout(this.#timer); this.#timer = undefined;
  }
  #configure(): void {
    this.#reset(); this.#controller = new AbortController();
    try { this.#client = createGenericPaymentClient({ apiBaseUrl: this.apiBaseUrl, signal: this.#controller.signal }); } catch { this.#error = "error"; }
    this.#render();
  }
  #current(g: number): boolean { return this.isConnected && this.#generation === g && !this.#controller?.signal.aborted; }
  async #act(): Promise<void> {
    if (this.#pending || this.#authenticated || !this.isConnected) return;
    const value = this.#input.value;
    if (this.#challenge ? !/^\d{6}$/.test(value) : value.length > 320 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) { this.#error = this.#challenge ? "invalidCode" : "invalidEmail"; this.#render(); this.#input.focus(); return; }
    if (!this.#challenge && Date.now() < this.#resendAt) return;
    const g = this.#generation; this.#pending = true; this.#error = undefined; this.#input.value = ""; this.#render();
    try {
      if (!this.#client) throw new PaymentSdkError("INVALID_CONFIGURATION");
      if (this.#challenge) {
        const session = await this.#client.verifyEmailOtp({ siteKey: this.siteKey, challengeId: this.#challenge.challengeId, code: value, intent: "AUTHENTICATE" });
        if (!this.#current(g)) return;
        this.#authenticated = true; this.#challenge = undefined; this.#pending = false; if (this.#timer !== undefined) clearTimeout(this.#timer); this.#timer = undefined;
        this.#render(); this.#status.focus();
        const detail: CustomerAuthenticatedDetail = Object.freeze({ state: "authenticated", customer: Object.freeze({ ...session.customer }), scopes: Object.freeze([...session.scopes]), expiresAt: session.expiresAt });
        this.dispatchEvent(new CustomEvent("customer-authenticated", { detail, bubbles: true, composed: true }));
      } else {
        // The address exists only in this request's ephemeral stack, never the component state.
        this.#resendAt = Date.now() + 60000;
        const challenge = await this.#client.requestEmailOtp({ siteKey: this.siteKey, email: value, intent: "AUTHENTICATE" });
        if (!this.#current(g)) return; this.#challenge = challenge; this.#pending = false;
        const remaining = Math.max(0, this.#resendAt - Date.now());
        this.#timer = setTimeout(() => { this.#timer = undefined; if (this.#current(g)) this.#render(); }, remaining);
        this.#render(); this.#input.focus();
      }
    } catch (e) {
      if (!this.#current(g) || isAbort(e)) return;
      this.#error = e instanceof PaymentSdkError && e.code === "RATE_LIMIT_EXCEEDED" ? "rate" : "error";
      // Request failures still enforce the cooldown; verification can be corrected explicitly.
      if (!this.#challenge) this.#timer = setTimeout(() => { this.#timer = undefined; if (this.#current(g)) this.#render(); }, Math.max(0, this.#resendAt - Date.now()));
    } finally { if (this.#current(g)) { this.#pending = false; this.#render(); } }
  }
  #render(): void {
    const t = translations[this.locale.toLowerCase().split("-")[0] === "pt" ? "pt" : "en"];
    this.#section.setAttribute("aria-label", t.title); this.#section.lang = this.locale.toLowerCase().startsWith("pt") ? "pt" : "en";
    this.#status.textContent = this.#pending ? t.pending : this.#authenticated ? t.success : this.#challenge ? t.challenge : t.idle;
    this.#alert.textContent = this.#error ? t[this.#error] : ""; this.#alert.hidden = !this.#error;
    this.#input.setAttribute("aria-invalid", this.#error === "invalidCode" || this.#error === "invalidEmail" ? "true" : "false");
    if (this.#authenticated) { this.#form.replaceChildren(); return; }
    // Reconnect after success uses the same labelled controls, with cleared values.
    if (!this.#input.isConnected) this.#form.append(this.#label, this.#input, this.#submit, this.#resend);
    this.#label.textContent = this.#challenge ? t.code : t.email;
    this.#input.type = this.#challenge ? "text" : "email"; this.#input.inputMode = this.#challenge ? "numeric" : "email"; this.#input.autocomplete = this.#challenge ? "one-time-code" : "email"; this.#input.maxLength = this.#challenge ? 6 : 320;
    this.#input.disabled = this.#pending;
    this.#submit.textContent = this.#challenge ? t.verify : t.send; this.#submit.disabled = this.#pending || (!this.#challenge && Date.now() < this.#resendAt);
    this.#resend.textContent = t.resend; this.#resend.hidden = !this.#challenge; this.#resend.disabled = this.#pending || Date.now() < this.#resendAt;
    this.#form.setAttribute("aria-busy", String(this.#pending));
  }
}
export function defineGenericEmailOtp(): void { if (typeof customElements !== "undefined" && !customElements.get("generic-email-otp")) customElements.define("generic-email-otp", GenericEmailOtp); }
