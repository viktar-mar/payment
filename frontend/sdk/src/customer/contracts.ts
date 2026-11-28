import { exact, instant, key, normalizeApiBase, object, statuses, token, uuid } from "../contracts.js";
import type { PaymentStatus } from "../contracts.js";
import { PaymentSdkError } from "../errors.js";
import type { CustomerDonationHistory } from '../donations/contracts.js';
export type { CustomerDonationHistory } from '../donations/contracts.js';
export type AuthenticationIntent = "AUTHENTICATE" | "LINK";
export type CustomerScope = "CUSTOMER_READ" | "CHECKOUT_CREATE" | "IDENTITY_LINK" | "SUBSCRIPTION_READ" | "SUBSCRIPTION_MANAGE";
export type CustomerAuthenticationMode = "SITE_TOKEN" | "OIDC_TOKEN" | "EMAIL_OTP";
export interface SafeCustomer { id: string; status: "ACTIVE"; maskedEmail: string | null; }
export interface CustomerSessionState { customer: SafeCustomer; expiresAt: string; scopes: readonly CustomerScope[]; }
/** Secret-bearing capability: never put in events, DOM, logs or URLs. */
export interface CustomerSession extends CustomerSessionState { sessionToken: string; apiBaseUrl: string; siteKey: string; origin: string; /** Required for subscription authority; absent on legacy sessions. */ siteId?: string; }
/** Ephemeral secret-bearing proof. Never persisted. */
export interface CustomerLinkProof { proofToken: string; expiresAt: string; }
export interface CustomerRecoveryIdentity { apiBaseUrl: string; siteKey: string; }
export interface CustomerInput { siteKey: string; signal?: AbortSignal; }
export interface SiteTokenInput<I extends AuthenticationIntent = AuthenticationIntent> extends CustomerInput { providerKey: string; token: string; intent: I; }
export interface OidcBeginInput extends CustomerInput { providerKey: string; intent: AuthenticationIntent; }
/** Ephemeral nonce-bearing result. */
export interface OidcAttempt { attemptId: string; nonce: string; expiresAt: string; }
export interface OidcExchangeInput<I extends AuthenticationIntent = AuthenticationIntent> extends SiteTokenInput<I>, OidcAttempt {}
export interface EmailOtpInput extends CustomerInput { email: string; intent: AuthenticationIntent; }
export interface EmailOtpChallenge { challengeId: string; expiresAt: string; }
export interface EmailOtpVerifyInput<I extends AuthenticationIntent = AuthenticationIntent> extends CustomerInput { challengeId: string; code: string; intent: I; }
export type AuthenticationResult<I extends AuthenticationIntent> = I extends "AUTHENTICATE" ? CustomerSession : CustomerLinkProof;
export interface CurrentCustomer extends SafeCustomer { authenticationModes: CustomerAuthenticationMode[]; sessionExpiresAt: string; }
export interface CustomerHistoryOptions { limit?: number; cursor?: string; signal?: AbortSignal; }
export interface CustomerPayment { paymentId: string; source: "CATALOG" | "DYNAMIC" | "DONATION" | "SUBSCRIPTION"; amountMinor: number; currency: string; status: Exclude<PaymentStatus, "UNKNOWN" | "EXPIRED">; createdAt: string; updatedAt: string; catalog: { priceId: string; priceKey: string; version: number } | null; }
export interface CustomerPaymentHistory { items: CustomerPayment[]; nextCursor: string | null; }
export interface IdentityLinkInput { proofToken: string; signal?: AbortSignal; }
export interface IdentityLinkResult { identityId: string; outcome: "LINKED" | "ALREADY_LINKED"; }
export interface CustomerClient {
  exchangeSiteToken<I extends AuthenticationIntent>(input: SiteTokenInput<I>): Promise<AuthenticationResult<I>>;
  beginOidcAuthentication(input: OidcBeginInput): Promise<OidcAttempt>;
  exchangeOidcToken<I extends AuthenticationIntent>(input: OidcExchangeInput<I>): Promise<AuthenticationResult<I>>;
  requestEmailOtp(input: EmailOtpInput): Promise<EmailOtpChallenge>;
  verifyEmailOtp<I extends AuthenticationIntent>(input: EmailOtpVerifyInput<I>): Promise<AuthenticationResult<I>>;
  getCurrentCustomer(session: CustomerSession, signal?: AbortSignal): Promise<CurrentCustomer>;
  listCustomerPayments(session: CustomerSession, options?: CustomerHistoryOptions): Promise<CustomerPaymentHistory>;
  listCustomerDonations(session: CustomerSession, options?: CustomerHistoryOptions): Promise<CustomerDonationHistory>;
  linkIdentity(session: CustomerSession, input: IdentityLinkInput): Promise<IdentityLinkResult>;
  logoutCustomerSession(session: CustomerSession, signal?: AbortSignal): Promise<void>;
}
export type RequestCapability = { kind: "none" } | { kind: "checkout"; token: string } | { kind: "customer"; token: string };
export type ResponseRule = (status: number, data: unknown) => boolean;
export type CustomerRequest = (path: string, method: "GET" | "POST" | "DELETE", body?: object, capability?: RequestCapability, idempotency?: string, signal?: AbortSignal, statuses?: readonly number[], cap?: number, rule?: ResponseRule, parseSuccess?: (raw: string) => unknown) => Promise<unknown>;
const scopes = ["CUSTOMER_READ", "CHECKOUT_CREATE", "IDENTITY_LINK", "SUBSCRIPTION_READ", "SUBSCRIPTION_MANAGE"] as const;
export function browserOrigin(): string {
  try { const origin = globalThis.location.origin; const url = new URL(origin); if (origin === "null" || !["http:", "https:"].includes(url.protocol) || url.origin !== origin) throw 0; return origin; }
  catch { throw new PaymentSdkError("INVALID_CONFIGURATION"); }
}
export function safeCustomer(raw: unknown, wire = false): SafeCustomer {
  const emailKey = wire ? "masked_email" : "maskedEmail";
  // The current backend exposes only this uniform mask; reject raw email in safe metadata.
  if (!object(raw) || !exact(raw, ["id", "status", emailKey]) || !uuid(raw.id) || raw.status !== "ACTIVE" || (raw[emailKey] !== null && raw[emailKey] !== "***@***")) throw new PaymentSdkError("INVALID_RESPONSE");
  return { id: raw.id, status: "ACTIVE", maskedEmail: raw[emailKey] as string | null };
}
export function validScopes(raw: unknown): raw is CustomerScope[] { return Array.isArray(raw) && raw.length > 0 && raw.length <= scopes.length && new Set(raw).size === raw.length && raw.every(s => scopes.includes(s)); }
function subscriptionScopes(scopes: readonly CustomerScope[]): boolean { return scopes.includes('SUBSCRIPTION_READ') || scopes.includes('SUBSCRIPTION_MANAGE'); }
export function validateCustomerSession(raw: unknown, base?: string): asserts raw is CustomerSession {
  try {
    if (!object(raw) || !exact(raw, ["sessionToken", "apiBaseUrl", "siteKey", "origin", "customer", "expiresAt", "scopes", ...(Object.hasOwn(raw, 'siteId') ? ['siteId'] : [])]) || !token(raw.sessionToken) || !instant(raw.expiresAt) || !key(raw.siteKey) || !validScopes(raw.scopes) || ((Object.hasOwn(raw, 'siteId') || subscriptionScopes(raw.scopes)) && !uuid(raw.siteId)) || raw.apiBaseUrl !== normalizeApiBase(raw.apiBaseUrl as string) || (base && raw.apiBaseUrl !== base) || raw.origin !== browserOrigin()) throw 0;
    safeCustomer(raw.customer);
  } catch { throw new PaymentSdkError("INVALID_SESSION"); }
  if (Date.parse(raw.expiresAt) <= Date.now()) throw new PaymentSdkError("SESSION_EXPIRED");
}
export function parseAuthentication(raw: unknown, intent: AuthenticationIntent, base: string, siteKey: string): CustomerSession | CustomerLinkProof {
  if (!object(raw) || !instant(raw.expires_at) || Date.parse(raw.expires_at) <= Date.now()) throw new PaymentSdkError("INVALID_RESPONSE");
  if (intent === "LINK") { if (!exact(raw, ["proof_token", "expires_at"]) || !token(raw.proof_token)) throw new PaymentSdkError("INVALID_RESPONSE"); return { proofToken: raw.proof_token, expiresAt: raw.expires_at }; }
  if (!exact(raw, ["session_token", "expires_at", "scopes", "customer", ...(Object.hasOwn(raw, 'site_id') ? ['site_id'] : [])]) || !token(raw.session_token) || !validScopes(raw.scopes) || ((Object.hasOwn(raw, 'site_id') || subscriptionScopes(raw.scopes)) && !uuid(raw.site_id))) throw new PaymentSdkError("INVALID_RESPONSE");
  return { sessionToken: raw.session_token, expiresAt: raw.expires_at, scopes: [...raw.scopes], customer: safeCustomer(raw.customer, true), apiBaseUrl: base, siteKey, origin: browserOrigin(), ...(Object.hasOwn(raw, 'site_id') ? { siteId: raw.site_id as string } : {}) };
}
export function parseHistory(raw: unknown, limit: number): CustomerPaymentHistory {
  if (!object(raw) || !exact(raw, ["items", "next_cursor"]) || !Array.isArray(raw.items) || raw.items.length > limit || (raw.next_cursor !== null && !validCursor(raw.next_cursor))) throw new PaymentSdkError("INVALID_RESPONSE");
  const items = raw.items.map(item => {
    if (!object(item) || !exact(item, ["payment_id", "source", "amount_minor", "currency", "status", "created_at", "updated_at", "catalog"]) || !uuid(item.payment_id) || !["CATALOG", "DYNAMIC", "DONATION", "SUBSCRIPTION"].includes(item.source as string) || (item.source === "SUBSCRIPTION" && item.catalog !== null) || !Number.isSafeInteger(item.amount_minor) || (item.amount_minor as number) <= 0 || typeof item.currency !== "string" || !/^[A-Z]{3}$/.test(item.currency) || !statuses.includes(item.status as string) || !instant(item.created_at) || !instant(item.updated_at) || Date.parse(item.updated_at) < Date.parse(item.created_at)) throw new PaymentSdkError("INVALID_RESPONSE");
    const p = item.catalog;
    if (p !== null && (!object(p) || !exact(p, ["price_id", "price_key", "version"]) || !uuid(p.price_id) || !key(p.price_key) || !Number.isSafeInteger(p.version) || (p.version as number) < 1)) throw new PaymentSdkError("INVALID_RESPONSE");
    if (["UNKNOWN", "EXPIRED"].includes(item.status as string)) throw new PaymentSdkError("INVALID_RESPONSE");
    return { paymentId: item.payment_id, source: item.source as CustomerPayment["source"], amountMinor: item.amount_minor as number, currency: item.currency, status: item.status as CustomerPayment["status"], createdAt: item.created_at, updatedAt: item.updated_at, catalog: p === null ? null : { priceId: (p as Record<string, unknown>).price_id as string, priceKey: (p as Record<string, unknown>).price_key as string, version: (p as Record<string, unknown>).version as number } };
  });
  return { items, nextCursor: raw.next_cursor as string | null };
}
export function validCursor(raw: unknown): raw is string { return typeof raw === "string" && raw.trim().length > 0 && raw.length <= 4096; }
