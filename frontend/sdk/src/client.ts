import { key, token, normalizeApiBase, object, parseWire, validateSession } from "./contracts.js";
import type { CatalogCheckoutInput, CheckoutSession, CheckoutState, ClientOptions, GenericPaymentClient, HandoffInput } from "./contracts.js";
import { checkAbort, isAbort, PaymentSdkError, problemError } from "./errors.js";
import { clearCheckout, saveCheckout } from "./storage.js";
import { pollCheckout } from "./polling.js";
import { fakeDriver } from "./providers/fake-driver.js";
import { createCustomerClient } from "./customer/client.js";
import type { RequestCapability, ResponseRule } from "./customer/contracts.js";
import { donationInput, parseCampaign, parseDonationContext } from './donations/contracts.js';
import type { DonationCheckoutInput, DonationCheckoutSession } from './donations/contracts.js';
import { createSubscriptionClient } from './subscriptions/client.js';
export function createGenericPaymentClient(options: ClientOptions): GenericPaymentClient {
  const base = normalizeApiBase(options.apiBaseUrl);
  const signalFor = (signal?: AbortSignal) => signal && options.signal ? AbortSignal.any([signal, options.signal]) : signal ?? options.signal;
  async function request(path: string, method: "GET" | "POST" | "DELETE", body?: object, capability: RequestCapability = { kind: "none" }, idempotency?: string, signal?: AbortSignal, expected?: readonly number[], cap = 32768, rule?: ResponseRule): Promise<unknown> {
    const activeSignal = signalFor(signal); checkAbort(activeSignal);
    const headers = new Headers({ Accept: "application/json, application/problem+json" });
    if (body) headers.set("Content-Type", "application/json");
    if (capability.kind !== "none") headers.set("Authorization", `Bearer ${capability.token}`);
    if (idempotency) headers.set("Idempotency-Key", idempotency);
    let response: Response;
    try { response = await fetch(base + path, { method, headers, body: body ? JSON.stringify(body) : undefined, signal: activeSignal, mode: "cors", credentials: "omit", cache: "no-store", redirect: "error" }); checkAbort(activeSignal); }
    catch (error) { if (isAbort(error)) throw error; throw new PaymentSdkError("TRANSPORT_UNAVAILABLE"); }
    if (response.redirected || response.type === "opaqueredirect" || (response.status >= 300 && response.status < 400)) throw new PaymentSdkError("INVALID_RESPONSE");
    if (response.ok && expected && !expected.includes(response.status)) throw new PaymentSdkError("INVALID_RESPONSE");
    if (response.status === 204 && expected?.includes(204)) { checkAbort(activeSignal); return undefined; }
    let data: unknown;
    try {
      let raw = "";
      if (response.body) {
        const reader = response.body.getReader(); const decoder = new TextDecoder("utf-8", { fatal: true }); let bytes = 0;
        const cancel = () => { void reader.cancel().catch(() => {}); }; activeSignal?.addEventListener("abort", cancel, { once: true });
        try { while (true) { checkAbort(activeSignal); const chunk = await reader.read(); checkAbort(activeSignal); if (chunk.done) break; bytes += chunk.value.byteLength; if (bytes > cap) throw 0; raw += decoder.decode(chunk.value, { stream: true }); } raw += decoder.decode(); }
        finally { activeSignal?.removeEventListener("abort", cancel); cancel(); reader.releaseLock(); }
      } else { raw = await response.text(); if (new TextEncoder().encode(raw).length > cap) throw 0; }
      checkAbort(activeSignal); data = JSON.parse(raw);
    }
    catch (error) { if (isAbort(error)) throw error; throw new PaymentSdkError(capability.kind === "customer" && response.status === 401 ? "INVALID_SESSION" : response.ok ? "INVALID_RESPONSE" : "REQUEST_FAILED"); }
    if (!response.ok) { if (capability.kind === "customer" && response.status === 401) throw new PaymentSdkError("INVALID_SESSION"); throw problemError(data); }
    if (rule && !rule(response.status, data)) throw new PaymentSdkError("INVALID_RESPONSE");
    return data;
  }
  const customer = createCustomerClient(base, request, signalFor);
  // Weak identity owns only a random key; no captured donor text or request bodies.
  const donationIntents = new WeakMap<DonationCheckoutInput, string>();
  const persist = (session: CheckoutSession): void => { if (!session.donation) saveCheckout(session); };
  async function createDonation(input: DonationCheckoutInput): Promise<DonationCheckoutSession> {
    donationInput(input, true);
    const owned = input.customerSession !== 'omit' ? customer.catalog(input.siteKey) : undefined;
    let intentKey = input.idempotencyKey ?? donationIntents.get(input);
    if (!intentKey) { intentKey = crypto.randomUUID(); donationIntents.set(input, intentKey); }
    const path = `/api/v1/public/sites/${encodeURIComponent(input.siteKey)}/donation-campaigns/${encodeURIComponent(input.campaignKey)}/checkouts`;
    const body: { amount_minor: number; message?: string } = { amount_minor: input.amountMinor };
    if (input.message !== undefined) body.message = input.message.normalize('NFC');
    let raw: unknown;
    try { raw = owned ? await customer.authorized(owned, path, 'POST', body, input.signal, [201], intentKey, 'CHECKOUT_CREATE', undefined, 32768) : await request(path, 'POST', body, { kind: 'none' }, intentKey, input.signal, [201]); }
    catch (error) { if (error instanceof PaymentSdkError && ['INVALID_SESSION', 'SESSION_EXPIRED'].includes(error.code)) donationIntents.delete(input); throw error; }
    finally { delete body.message; }
    if (!object(raw) || raw.price !== null) throw new PaymentSdkError('INVALID_RESPONSE');
    const { donation: context, ...checkout } = raw; const donation = parseDonationContext(context);
    if (donation.campaignKey !== input.campaignKey) throw new PaymentSdkError('INVALID_RESPONSE');
    const parsed = parseWire(checkout, true);
    if (parsed.state.amountMinor !== input.amountMinor) throw new PaymentSdkError('INVALID_RESPONSE');
    const session: DonationCheckoutSession = { checkoutId: parsed.state.checkoutId, checkoutToken: parsed.checkoutToken!, expiresAt: parsed.state.expiresAt, apiBaseUrl: base, siteKey: input.siteKey, priceKey: input.campaignKey, state: parsed.state, donation, providerType: 'FAKE', confirmationMode: 'SERVER', providerClientPayload: {} };
    checkAbort(signalFor(input.signal)); checkSession(session); return session;
  }
  function validInput(input: CatalogCheckoutInput | HandoffInput, handoff: boolean): void {
    const allowed = ["siteKey", "priceKey", "signal", ...(handoff ? ["handoffToken"] : ["customerSession"])];
    if (!input || !key(input.siteKey) || !key(input.priceKey) || Object.keys(input).some(k => !allowed.includes(k)) || (handoff && !token((input as HandoffInput).handoffToken))) throw new PaymentSdkError("INVALID_CONFIGURATION");
    if (!handoff && (input as CatalogCheckoutInput).customerSession !== undefined && (input as CatalogCheckoutInput).customerSession !== "omit") throw new PaymentSdkError("INVALID_CONFIGURATION");
  }
  function checkSession(session: CheckoutSession): void { validateSession(session, base); if (Date.parse(session.expiresAt) <= Date.now()) { clearCheckout(session); throw new PaymentSdkError("CHECKOUT_EXPIRED"); } }
  async function created(input: CatalogCheckoutInput | HandoffInput, handoff: boolean): Promise<CheckoutSession> {
    validInput(input, handoff);
    const owned = !handoff && (input as CatalogCheckoutInput).customerSession !== "omit" ? customer.catalog(input.siteKey) : undefined;
    const raw = owned ? await customer.authorized(owned, `/api/v1/public/sites/${encodeURIComponent(input.siteKey)}/checkouts`, "POST", { price_key: input.priceKey }, input.signal, [201], crypto.randomUUID(), "CHECKOUT_CREATE", undefined, 32768) : await request(handoff ? "/api/v1/public/handoffs" : `/api/v1/public/sites/${encodeURIComponent(input.siteKey)}/checkouts`, "POST", handoff ? { handoff_token: (input as HandoffInput).handoffToken } : { price_key: input.priceKey }, undefined, handoff ? undefined : crypto.randomUUID(), input.signal);
    const parsed = parseWire(raw, true);
    const session: CheckoutSession = { checkoutId: parsed.state.checkoutId, checkoutToken: parsed.checkoutToken!, expiresAt: parsed.state.expiresAt, apiBaseUrl: base, siteKey: input.siteKey, priceKey: input.priceKey, state: parsed.state, providerType: "FAKE", confirmationMode: "SERVER", providerClientPayload: {} };
    checkAbort(signalFor(input.signal)); checkSession(session); saveCheckout(session); return session;
  }
  async function update(session: CheckoutSession, method: "GET" | "POST", signal?: AbortSignal): Promise<CheckoutState> {
    checkSession(session);
    const data = await request(`/api/v1/public/checkouts/${encodeURIComponent(session.checkoutId)}${method === "POST" ? "/confirm" : ""}`, method, method === "POST" ? { payment_method_family: "CARD" } : undefined, { kind: "checkout", token: session.checkoutToken }, method === "POST" ? crypto.randomUUID() : undefined, signal);
    const state = parseWire(data, false).state;
    if (state.checkoutId !== session.checkoutId || state.paymentId !== session.state.paymentId || state.expiresAt !== session.expiresAt || state.amountMinor !== session.state.amountMinor || state.currency !== session.state.currency || JSON.stringify(state.price) !== JSON.stringify(session.state.price)) throw new PaymentSdkError("INVALID_RESPONSE");
    checkAbort(signalFor(signal)); session.state = state; persist(session); return { ...state, price: state.price ? { ...state.price } : null };
  }
  const client: GenericPaymentClient = {
    ...customer.methods,
    subscriptions: createSubscriptionClient(customer.subscriptionAuthorized),
    getDonationCampaign: async input => { donationInput(input); const raw = await request(`/api/v1/public/sites/${encodeURIComponent(input.siteKey)}/donation-campaigns/${encodeURIComponent(input.campaignKey)}`, 'GET', undefined, { kind: 'none' }, undefined, input.signal, [200]); return parseCampaign(raw, input.campaignKey); },
    createDonationCheckout: createDonation,
    createCatalogCheckout: input => created(input, false), exchangeHandoff: input => created(input, true),
    confirm: async session => {
      checkSession(session);
      try { return await fakeDriver.confirm(session, () => update(session, "POST")); }
      catch (error) {
        if (error instanceof PaymentSdkError && ["TRANSPORT_UNAVAILABLE", "INVALID_RESPONSE", "UNSUPPORTED_PROVIDER", "PROVIDER_RESULT_UNKNOWN", "REQUEST_FAILED"].includes(error.code)) {
          // An unusable confirmation result cannot authorize another financial attempt.
          session.state = { ...session.state, status: "UNKNOWN" }; persist(session);
        }
        throw error;
      }
    },
    get: (session, signal) => update(session, "GET", signal),
    watch: (session, watchOptions = {}) => { checkSession(session); return pollCheckout(signal => client.get(session, signal), session, { signal: signalFor(watchOptions.signal) }); },
    clear: session => { validateSession(session, base); if (!session.donation) clearCheckout(session); },
  };
  return client;
}
