import { PaymentSdkError } from "./errors.js";
import type { CustomerClient } from "./customer/contracts.js";
import type { SubscriptionClient } from './subscriptions/contracts.js';
import type { DonationCampaign, DonationCampaignInput, DonationCheckoutInput, DonationCheckoutSession, DonationContext } from './donations/contracts.js';
export type PaymentStatus = "CREATED" | "REQUIRES_PAYMENT_METHOD" | "REQUIRES_ACTION" | "PROCESSING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "PARTIALLY_REFUNDED" | "REFUNDED" | "UNKNOWN" | "EXPIRED";
export interface PriceSnapshot { priceId: string; priceKey: string; version: number; amountMinor: number; currency: string; taxMode: "NONE" | "INCLUSIVE"; }
export interface CheckoutState { checkoutId: string; paymentId: string; amountMinor: number; currency: string; price: PriceSnapshot | null; status: PaymentStatus; expiresAt: string; }
/** Secret-bearing capability. Keep out of events, logs, URLs and DOM. */
export interface CheckoutSession { checkoutId: string; checkoutToken: string; expiresAt: string; apiBaseUrl: string; siteKey: string; priceKey: string; state: CheckoutState; providerType: "FAKE"; confirmationMode: "SERVER"; providerClientPayload: Record<string, never>; donation?: DonationContext; }
export interface CatalogCheckoutInput { siteKey: string; priceKey: string; signal?: AbortSignal; customerSession?: "omit"; }
export interface HandoffInput extends Pick<CatalogCheckoutInput, "siteKey" | "priceKey" | "signal"> { handoffToken: string; }
export interface WatchOptions { signal?: AbortSignal; }
export interface ClientOptions { apiBaseUrl: string; signal?: AbortSignal; }
export interface RecoveryIdentity { apiBaseUrl: string; siteKey: string; priceKey: string; }
export interface GenericPaymentClient extends CustomerClient {
  subscriptions: SubscriptionClient;
  getDonationCampaign(input: DonationCampaignInput): Promise<DonationCampaign>;
  createDonationCheckout(input: DonationCheckoutInput): Promise<DonationCheckoutSession>;
  createCatalogCheckout(input: CatalogCheckoutInput): Promise<CheckoutSession>;
  exchangeHandoff(input: HandoffInput): Promise<CheckoutSession>;
  confirm(session: CheckoutSession): Promise<CheckoutState>;
  get(session: CheckoutSession, signal?: AbortSignal): Promise<CheckoutState>;
  watch(session: CheckoutSession, options?: WatchOptions): AsyncIterable<CheckoutState>;
  clear(session: CheckoutSession): void;
}
export const statuses: readonly string[] = ["CREATED", "REQUIRES_PAYMENT_METHOD", "REQUIRES_ACTION", "PROCESSING", "SUCCEEDED", "FAILED", "CANCELLED", "PARTIALLY_REFUNDED", "REFUNDED", "UNKNOWN", "EXPIRED"];
export function object(value: unknown): value is Record<string, unknown> { return !!value && typeof value === "object" && !Array.isArray(value) && Object.getPrototypeOf(value) === Object.prototype; }
export function exact(value: Record<string, unknown>, keys: string[]): boolean { return Object.keys(value).length === keys.length && keys.every(k => Object.hasOwn(value, k)); }
export function key(value: unknown): value is string { return typeof value === "string" && /^[a-z0-9][a-z0-9-]{2,62}$/.test(value); }
export function uuid(value: unknown): value is string { return typeof value === "string" && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value); }
export function token(value: unknown): value is string { return typeof value === "string" && /^[A-Za-z0-9_-]{43}$/.test(value); }
export function instant(value: unknown): value is string {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)) return false;
  const date = new Date(value); return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 19) === value.slice(0, 19);
}
export function normalizeApiBase(value: string): string {
  try {
    if (typeof value !== "string" || value.length > 2048 || /[%\\\s]/.test(value) || /(?:^|\/)\.{1,2}(?:\/|$)/.test(value)) throw 0;
    const url = new URL(value);
    const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
    if ((url.protocol !== "https:" && !(url.protocol === "http:" && loopback)) || url.username || url.password || url.search || url.hash || value.includes("?") || value.includes("#")) throw 0;
    return url.origin + url.pathname.replace(/\/+$/, "");
  } catch { throw new PaymentSdkError("INVALID_CONFIGURATION"); }
}
export function safeState(value: unknown): CheckoutState {
  if (!object(value) || !exact(value, ["checkoutId", "paymentId", "amountMinor", "currency", "price", "status", "expiresAt"]) || !uuid(value.checkoutId) || !uuid(value.paymentId) || !Number.isSafeInteger(value.amountMinor) || (value.amountMinor as number) <= 0 || typeof value.currency !== "string" || !/^[A-Z]{3}$/.test(value.currency) || !statuses.includes(value.status as string) || !instant(value.expiresAt)) throw new PaymentSdkError("INVALID_RESPONSE");
  const p = value.price;
  if (p !== null && (!object(p) || !exact(p, ["priceId", "priceKey", "version", "amountMinor", "currency", "taxMode"]) || !uuid(p.priceId) || !key(p.priceKey) || !Number.isSafeInteger(p.version) || (p.version as number) < 1 || p.amountMinor !== value.amountMinor || p.currency !== value.currency || !["NONE", "INCLUSIVE"].includes(p.taxMode as string))) throw new PaymentSdkError("INVALID_RESPONSE");
  return { checkoutId: value.checkoutId, paymentId: value.paymentId, amountMinor: value.amountMinor as number, currency: value.currency, price: p === null ? null : { ...(p as unknown as PriceSnapshot) }, status: value.status as PaymentStatus, expiresAt: value.expiresAt };
}
export function validateSession(value: CheckoutSession, base?: string): void {
  if (!object(value) || !uuid(value.checkoutId) || !token(value.checkoutToken) || !instant(value.expiresAt) || !key(value.siteKey) || !key(value.priceKey) || normalizeApiBase(value.apiBaseUrl) !== value.apiBaseUrl || (base && base !== value.apiBaseUrl)) throw new PaymentSdkError("INVALID_CONFIGURATION");
  const state = safeState(value.state);
  if (state.checkoutId !== value.checkoutId || state.expiresAt !== value.expiresAt || (state.price && state.price.priceKey !== value.priceKey)) throw new PaymentSdkError("INVALID_RESPONSE");
}
export function parseWire(value: unknown, creation: boolean): { state: CheckoutState; checkoutToken?: string } {
  const keys = ["checkout_id", "payment_id", "amount_minor", "currency", "price", "provider_type", "confirmation_mode", "provider_client_payload", "status", "expires_at"];
  if (creation) keys.push("checkout_token");
  if (!object(value) || !exact(value, keys) || (creation && !token(value.checkout_token))) throw new PaymentSdkError("INVALID_RESPONSE");
  if (value.provider_type !== "FAKE" || value.confirmation_mode !== "SERVER" || !object(value.provider_client_payload) || Object.keys(value.provider_client_payload).length) throw new PaymentSdkError("UNSUPPORTED_PROVIDER");
  let price: unknown = null;
  if (value.price !== null) {
    const p = value.price;
    if (!object(p) || !exact(p, ["price_id", "price_key", "version", "amount_minor", "currency", "tax_mode"])) throw new PaymentSdkError("INVALID_RESPONSE");
    price = { priceId: p.price_id, priceKey: p.price_key, version: p.version, amountMinor: p.amount_minor, currency: p.currency, taxMode: p.tax_mode };
  }
  const state = safeState({ checkoutId: value.checkout_id, paymentId: value.payment_id, amountMinor: value.amount_minor, currency: value.currency, price, status: value.status, expiresAt: value.expires_at });
  return { state, checkoutToken: creation ? value.checkout_token as string : undefined };
}
