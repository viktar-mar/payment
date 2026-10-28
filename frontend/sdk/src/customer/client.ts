import { exact, instant, key, object, token, uuid } from "../contracts.js";
import { checkAbort, PaymentSdkError } from "../errors.js";
import { browserOrigin, parseAuthentication, parseHistory, safeCustomer, validateCustomerSession, validCursor } from "./contracts.js";
import type { AuthenticationIntent, AuthenticationResult, CustomerClient, CustomerHistoryOptions, CustomerRequest, CustomerScope, CustomerSession, CurrentCustomer, ResponseRule } from "./contracts.js";
import { clearCustomerSession, readCustomerRecord, resolveCustomerSession, saveCustomerSession, trackCustomerRecordCleanup } from "./session-storage.js";
import type { CustomerRecordCleanup } from "./session-storage.js";
import { parseDonationHistory } from '../donations/contracts.js';
import type { SubscriptionAuthorizedRequest } from '../subscriptions/contracts.js';
export function createCustomerClient(base: string, request: CustomerRequest, signalFor: (signal?: AbortSignal) => AbortSignal | undefined) {
  const memory = new Map<string, CustomerSession>(); const generations = new Map<string, number>();
  // Attempts fence stale login completions; only accepted login/logout changes
  // the durable-in-memory authority epoch for future subscription requests.
  const acceptedGenerations = new Map<string, number>();
  // Subscription work belongs to one explicit authority generation at a time.
  // These snapshots are memory-only and are never projected into events or storage.
  let subscriptionIdentity: string | undefined; let subscriptionGeneration = 0;
  const revokedSubscriptionIdentities = new Set<string>();
  const subscriptionBindings = new Map<string, { record: string | null | undefined; generation: number }>();
  const fingerprint = (s: CustomerSession) => JSON.stringify([s.apiBaseUrl, s.siteKey, s.siteId, s.origin, s.customer.id, s.customer.status, s.customer.maskedEmail, s.sessionToken, s.expiresAt, [...s.scopes].sort()]);
  type SessionFailure = "INVALID_SESSION" | "SESSION_EXPIRED";
  // The exact superseded snapshot remains owned after the memory capability is cleared.
  const failedWrites = new Map<string, { record: string | null | undefined; origin: string; revoked?: SessionFailure; token?: string; cleanup?: CustomerRecordCleanup }>();
  function input(raw: unknown, required: string[], optional: string[] = []): void {
    browserOrigin();
    if (!object(raw) || !required.every(k => Object.hasOwn(raw, k)) || Object.keys(raw).some(k => ![...required, ...optional, "signal"].includes(k)) || ("siteKey" in raw && !key(raw.siteKey)) || ("intent" in raw && !["AUTHENTICATE", "LINK"].includes(raw.intent as string)) || (raw.signal !== undefined && !(raw.signal instanceof AbortSignal))) throw new PaymentSdkError("INVALID_CONFIGURATION");
  }
  function ownsRecord(raw: string | null | undefined, ownedToken: string | undefined, origin: string): boolean {
    if (!ownedToken || !raw || raw.length > 8192) return false;
    try { const record: unknown = JSON.parse(raw); return object(record) && record.sessionToken === ownedToken && record.origin === origin; } catch { return false; }
  }
  function clear(session: CustomerSession, code: SessionFailure = "INVALID_SESSION"): void {
    if (session.origin !== browserOrigin()) return;
    revokedSubscriptionIdentities.add(fingerprint(session));
    const siteKey = session.siteKey; const before = readCustomerRecord(session); const ownership = failedWrites.get(siteKey);
    const ownsMemory = memory.get(siteKey)?.sessionToken === session.sessionToken && memory.get(siteKey)?.origin === session.origin;
    clearCustomerSession(session); const remaining = readCustomerRecord(session);
    // An old request may remove the exact record superseded by a newer quota session.
    // That known local removal must not discard the newer memory capability.
    if (ownership?.origin === session.origin && ownership.record === before && before !== remaining && remaining === null) {
      if (ownership.revoked) failedWrites.delete(siteKey); else failedWrites.set(siteKey, { ...ownership, record: null });
    }
    if (ownsMemory) {
      memory.delete(siteKey);
      if (remaining !== null && ((ownership?.origin === session.origin && (ownership.record === remaining || remaining === undefined)) || ownsRecord(remaining, session.sessionToken, session.origin) || remaining === undefined)) {
        failedWrites.set(siteKey, { record: remaining === undefined ? ownership?.record : remaining, origin: session.origin, revoked: code, token: session.sessionToken });
      } else failedWrites.delete(siteKey);
    } else if (!memory.has(siteKey) && ownsRecord(remaining, session.sessionToken, session.origin)) {
      failedWrites.set(siteKey, { record: remaining, origin: session.origin, revoked: code, token: session.sessionToken });
    }
  }
  function checked(session: CustomerSession): CustomerSession {
    try { validateCustomerSession(session, base); } catch (e) { if (e instanceof PaymentSdkError && e.code === "SESSION_EXPIRED") clear(session, "SESSION_EXPIRED"); throw e; }
    return { ...session, customer: { ...session.customer }, scopes: [...session.scopes] };
  }
  function catalog(siteKey: string): CustomerSession | undefined {
    const identity = { apiBaseUrl: base, siteKey }; const raw = readCustomerRecord(identity);
    const inMemory = memory.get(siteKey); const ownership = failedWrites.get(siteKey);
    // Reconcile our failed write before parsing/removing its stale corrupt/expired record.
    // Null is owned only with a receipt for that exact snapshot's recovery cleanup.
    // A different record is still resolved strictly and may supersede this client.
    if (ownership?.origin === browserOrigin() && !ownership.cleanup?.changed && (raw === ownership.record || (raw === null && ownership.cleanup?.removed) || (ownership.revoked && (raw === undefined || ownsRecord(raw, ownership.token, ownership.origin))))) {
      if (ownership.revoked) throw new PaymentSdkError(ownership.revoked);
      if (inMemory) return checked(inMemory);
    }
    const result = resolveCustomerSession(identity);
    if (result.kind === "invalid") { memory.delete(siteKey); failedWrites.delete(siteKey); throw new PaymentSdkError(result.code); }
    if (result.kind === "valid") { failedWrites.delete(siteKey); memory.set(siteKey, result.session); return checked(result.session); }
    if (result.kind === "unavailable") { const s = memory.get(siteKey); return s ? checked(s) : undefined; }
    memory.delete(siteKey); failedWrites.delete(siteKey); return undefined;
  }
  async function authorized(session: CustomerSession, path: string, method: "GET" | "POST" | "DELETE", body?: object, signal?: AbortSignal, expected: readonly number[] = [200], idempotency?: string, scope?: CustomerScope, rule?: ResponseRule, cap = 131072, parseSuccess?: (raw: string) => unknown): Promise<unknown> {
    const owned = checked(session);
    if (scope && !owned.scopes.includes(scope)) throw new PaymentSdkError("INVALID_SESSION");
    try { return await request(path, method, body, { kind: "customer", token: owned.sessionToken }, idempotency, signal, expected, cap, rule, parseSuccess); }
    catch (e) { if (e instanceof PaymentSdkError && (e.code === "INVALID_SESSION" || e.code === "SESSION_EXPIRED")) clear(owned, e.code); throw e; }
  }
  function captureSubscriptionAuthority(session: CustomerSession, signal: AbortSignal | undefined, scope: 'SUBSCRIPTION_READ' | 'SUBSCRIPTION_MANAGE') {
    const owned = checked(session); const identity = fingerprint(owned);
    if (!uuid(owned.siteId) || !owned.scopes.includes(scope) || revokedSubscriptionIdentities.has(identity)) throw new PaymentSdkError('INVALID_SESSION');
    const current = catalog(owned.siteKey);
    if (current && fingerprint(current) !== identity) throw new PaymentSdkError('INVALID_SESSION');
    if (subscriptionIdentity !== identity) {
      if (subscriptionIdentity) revokedSubscriptionIdentities.add(subscriptionIdentity);
      subscriptionIdentity = identity; subscriptionGeneration++;
    }
    const generation = subscriptionGeneration; const authenticationGeneration = generations.get(owned.siteKey) ?? 0;
    const acceptedGeneration = acceptedGenerations.get(owned.siteKey) ?? 0;
    const record = readCustomerRecord(owned);
    const priorBinding = subscriptionBindings.get(identity);
    if (priorBinding && (priorBinding.record !== record || priorBinding.generation !== acceptedGeneration)) throw new PaymentSdkError('INVALID_SESSION');
    subscriptionBindings.set(identity, { record, generation: acceptedGeneration });
    function stillAuthorized(): void {
      checkAbort(signalFor(signal));
      if (fingerprint(checked(session)) !== identity || generation !== subscriptionGeneration || (generations.get(owned.siteKey) ?? 0) !== authenticationGeneration || (acceptedGenerations.get(owned.siteKey) ?? 0) !== acceptedGeneration || revokedSubscriptionIdentities.has(identity)) throw new PaymentSdkError('INVALID_SESSION');
      const replacement = catalog(owned.siteKey);
      if ((replacement && fingerprint(replacement) !== identity) || readCustomerRecord(owned) !== record) throw new PaymentSdkError('INVALID_SESSION');
    }
    stillAuthorized(); return { owned, stillAuthorized };
  }
  const subscriptionAuthorized: SubscriptionAuthorizedRequest = async (session, path, method, body, signal, expected, idempotency, scope, rule, cap = 32768, parseSuccess) => {
    const { owned, stillAuthorized } = captureSubscriptionAuthority(session, signal, scope);
    try { const raw = await authorized(owned, path, method, body, signal, expected, idempotency, scope, rule, cap, parseSuccess); stillAuthorized(); return raw; }
    catch (error) { stillAuthorized(); throw error; }
  };
  async function authenticate(siteKey: string, intent: AuthenticationIntent, path: string, body: object, signal?: AbortSignal) {
    const origin = browserOrigin(); const generation = (generations.get(siteKey) ?? 0) + 1;
    if (intent === "AUTHENTICATE") generations.set(siteKey, generation);
    const prior = readCustomerRecord({ apiBaseUrl: base, siteKey });
    const raw = await request(path, "POST", body, { kind: "none" }, undefined, signal, [200], 131072);
    checkAbort(signalFor(signal));
    if (browserOrigin() !== origin) throw new PaymentSdkError("INVALID_SESSION");
    const parsed = parseAuthentication(raw, intent, base, siteKey);
    if ("sessionToken" in parsed) {
      // A stale successful authentication cannot overwrite a replacement session.
      if (generations.get(siteKey) !== generation || readCustomerRecord({ apiBaseUrl: base, siteKey }) !== prior) throw new DOMException("Superseded", "AbortError");
      const session = Object.freeze({ ...parsed, customer: Object.freeze(parsed.customer), scopes: Object.freeze(parsed.scopes) });
      checkAbort(signalFor(signal)); acceptedGenerations.set(siteKey, (acceptedGenerations.get(siteKey) ?? 0) + 1); memory.set(siteKey, session);
      if (saveCustomerSession(session)) failedWrites.delete(siteKey);
      else { const identity = { apiBaseUrl: base, siteKey }; const record = readCustomerRecord(identity); failedWrites.set(siteKey, { record, origin, cleanup: trackCustomerRecordCleanup(identity, record) }); }
      return session;
    }
    return parsed;
  }
  async function history(session: CustomerSession, options: CustomerHistoryOptions = {}, donations = false) {
    input(options, [], ["limit", "cursor"]); const limit = options.limit ?? 20;
    if (!Number.isInteger(limit) || limit < 1 || limit > 100 || (options.cursor !== undefined && !validCursor(options.cursor))) throw new PaymentSdkError("INVALID_CONFIGURATION");
    const query = new URLSearchParams({ limit: String(limit) }); if (options.cursor !== undefined) query.set("cursor", options.cursor);
    const raw = await authorized(session, `/api/v1/customer/${donations ? "donations" : "payments"}?${query}`, "GET", undefined, options.signal, [200], undefined, "CUSTOMER_READ");
    return donations ? parseDonationHistory(raw, limit) : parseHistory(raw, limit);
  }
  const methods: CustomerClient = {
    exchangeSiteToken: async i => {
      input(i, ["siteKey", "providerKey", "intent", "token"]);
      if (!key(i.providerKey) || typeof i.token !== "string" || !i.token.trim() || i.token.length > 16384) throw new PaymentSdkError("INVALID_CONFIGURATION");
      return await authenticate(i.siteKey, i.intent, `/api/v1/public/customer-auth/site-token/${i.intent === "LINK" ? "link-proofs" : "exchanges"}`, { site_key: i.siteKey, provider_key: i.providerKey, token: i.token }, i.signal) as AuthenticationResult<typeof i.intent>;
    },
    beginOidcAuthentication: async i => {
      input(i, ["siteKey", "providerKey", "intent"]); if (!key(i.providerKey)) throw new PaymentSdkError("INVALID_CONFIGURATION");
      const raw = await request("/api/v1/public/customer-auth/oidc/attempts", "POST", { site_key: i.siteKey, provider_key: i.providerKey, intent: i.intent }, { kind: "none" }, undefined, i.signal, [201], 131072);
      if (!object(raw) || !exact(raw, ["attempt_id", "nonce", "expires_at"]) || !uuid(raw.attempt_id) || !token(raw.nonce) || !instant(raw.expires_at) || Date.parse(raw.expires_at) <= Date.now()) throw new PaymentSdkError("INVALID_RESPONSE");
      return { attemptId: raw.attempt_id, nonce: raw.nonce, expiresAt: raw.expires_at };
    },
    exchangeOidcToken: async i => {
      input(i, ["siteKey", "providerKey", "intent", "attemptId", "nonce", "expiresAt", "token"]);
      if (!key(i.providerKey) || !uuid(i.attemptId) || !token(i.nonce) || !instant(i.expiresAt) || Date.parse(i.expiresAt) <= Date.now() || typeof i.token !== "string" || !i.token.trim() || i.token.length > 16384) throw new PaymentSdkError("INVALID_CONFIGURATION");
      return await authenticate(i.siteKey, i.intent, "/api/v1/public/customer-auth/oidc/exchanges", { attempt_id: i.attemptId, site_key: i.siteKey, provider_key: i.providerKey, intent: i.intent, nonce: i.nonce, token: i.token }, i.signal) as AuthenticationResult<typeof i.intent>;
    },
    requestEmailOtp: async i => {
      input(i, ["siteKey", "email", "intent"]); if (typeof i.email !== "string" || i.email.length > 320 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(i.email)) throw new PaymentSdkError("INVALID_CONFIGURATION");
      const raw = await request("/api/v1/public/customer-auth/email-otp/challenges", "POST", { site_key: i.siteKey, email: i.email, intent: i.intent }, { kind: "none" }, undefined, i.signal, [202], 131072);
      if (!object(raw) || !exact(raw, ["challenge_id", "expires_at"]) || !uuid(raw.challenge_id) || !instant(raw.expires_at) || Date.parse(raw.expires_at) <= Date.now()) throw new PaymentSdkError("INVALID_RESPONSE");
      return { challengeId: raw.challenge_id, expiresAt: raw.expires_at };
    },
    verifyEmailOtp: async i => {
      input(i, ["siteKey", "challengeId", "code", "intent"]); if (!uuid(i.challengeId) || typeof i.code !== "string" || !/^\d{6}$/.test(i.code)) throw new PaymentSdkError("INVALID_CONFIGURATION");
      return await authenticate(i.siteKey, i.intent, `/api/v1/public/customer-auth/email-otp/challenges/${i.challengeId}/verify`, { site_key: i.siteKey, code: i.code }, i.signal) as AuthenticationResult<typeof i.intent>;
    },
    getCurrentCustomer: async (session, signal) => {
      const owned = checked(session);
      const raw = await authorized(owned, "/api/v1/customer/me", "GET", undefined, signal, [200], undefined, "CUSTOMER_READ");
      if (!object(raw) || !exact(raw, ["id", "status", "masked_email", "authentication_modes", "session_expires_at"])) throw new PaymentSdkError("INVALID_RESPONSE");
      if (raw.status === "ANONYMIZED") { clear(owned); throw new PaymentSdkError("INVALID_SESSION"); }
      const customer = safeCustomer({ id: raw.id, status: raw.status, masked_email: raw.masked_email }, true);
      const modes = raw.authentication_modes;
      if (customer.id !== owned.customer.id || raw.session_expires_at !== owned.expiresAt || !Array.isArray(modes) || !modes.length || modes.length > 3 || new Set(modes).size !== modes.length || modes.some(m => !["SITE_TOKEN", "OIDC_TOKEN", "EMAIL_OTP"].includes(m))) throw new PaymentSdkError("INVALID_RESPONSE");
      return { ...customer, authenticationModes: [...modes], sessionExpiresAt: owned.expiresAt } as CurrentCustomer;
    },
    listCustomerPayments: async (s, o) => await history(s, o) as import('./contracts.js').CustomerPaymentHistory,
    listCustomerDonations: async (s, o) => await history(s, o, true) as import('../donations/contracts.js').CustomerDonationHistory,
    linkIdentity: async (session, i) => {
      input(i, ["proofToken"]); if (!token(i.proofToken)) throw new PaymentSdkError("INVALID_CONFIGURATION");
      const raw = await authorized(session, "/api/v1/customer/identities", "POST", { proof_token: i.proofToken }, i.signal, [200, 201], undefined, "IDENTITY_LINK", (status, data) => object(data) && (status === 201 ? data.outcome === "LINKED" : data.outcome === "ALREADY_LINKED"));
      if (!object(raw) || !exact(raw, ["identity_id", "outcome"]) || !uuid(raw.identity_id) || !["LINKED", "ALREADY_LINKED"].includes(raw.outcome as string)) throw new PaymentSdkError("INVALID_RESPONSE");
      return { identityId: raw.identity_id, outcome: raw.outcome as "LINKED" | "ALREADY_LINKED" };
    },
    logoutCustomerSession: async (session, signal) => {
      const owned = checked(session); generations.set(owned.siteKey, (generations.get(owned.siteKey) ?? 0) + 1);
      acceptedGenerations.set(owned.siteKey, (acceptedGenerations.get(owned.siteKey) ?? 0) + 1);
      // Clear before awaiting revoke: local logout also works offline or when aborted.
      clear(owned); await authorized(owned, "/api/v1/customer/session", "DELETE", undefined, signal, [204]);
    },
  };
  return { methods, catalog, authorized, subscriptionAuthorized, subscriptionAuthority: (session: CustomerSession, signal?: AbortSignal) => captureSubscriptionAuthority(session, signal, 'SUBSCRIPTION_MANAGE').stillAuthorized };
}
