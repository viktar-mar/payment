import { exact, key, normalizeApiBase, object, safeState, validateSession } from "./contracts.js";
import type { CheckoutSession, RecoveryIdentity } from "./contracts.js";
import { PaymentSdkError } from "./errors.js";
export function storageKey(identity: RecoveryIdentity): string {
  if (!key(identity.siteKey) || !key(identity.priceKey)) throw new PaymentSdkError("INVALID_CONFIGURATION");
  return `generic-payments:v1:${encodeURIComponent(normalizeApiBase(identity.apiBaseUrl))}:${identity.siteKey}:${identity.priceKey}`;
}
function storage(): Storage | undefined { try { return globalThis.sessionStorage; } catch { return undefined; } }
export function saveCheckout(session: CheckoutSession): void {
  validateSession(session);
  if (Date.parse(session.expiresAt) <= Date.now()) { clearCheckout(session); return; }
  const record = { version: 1, checkoutId: session.checkoutId, checkoutToken: session.checkoutToken, expiresAt: session.expiresAt, apiBaseUrl: session.apiBaseUrl, siteKey: session.siteKey, priceKey: session.priceKey, state: safeState(session.state) };
  try { storage()?.setItem(storageKey(session), JSON.stringify(record)); } catch { /* Memory capability still works when storage is blocked. */ }
}
export function recoverCheckout(identity: RecoveryIdentity): CheckoutSession | null {
  const recordKey = storageKey(identity); const target = storage();
  try {
    const raw = target?.getItem(recordKey); if (raw == null) return null;
    if (raw.length > 8192) throw 0;
    const record: unknown = JSON.parse(raw);
    if (!object(record) || !exact(record, ["version", "checkoutId", "checkoutToken", "expiresAt", "apiBaseUrl", "siteKey", "priceKey", "state"]) || record.version !== 1 || record.apiBaseUrl !== normalizeApiBase(identity.apiBaseUrl) || record.siteKey !== identity.siteKey || record.priceKey !== identity.priceKey) throw 0;
    const session = { ...record, state: safeState(record.state), providerType: "FAKE", confirmationMode: "SERVER", providerClientPayload: {} } as unknown as CheckoutSession;
    validateSession(session); if (Date.parse(session.expiresAt) <= Date.now()) throw 0;
    return session;
  } catch { try { target?.removeItem(recordKey); } catch { /* unavailable storage */ } return null; }
}
export function clearCheckout(session: CheckoutSession): void {
  validateSession(session);
  const target = storage(); const ownedKey = storageKey(session);
  try { const raw = target?.getItem(ownedKey); if (raw && raw.length <= 8192) { const record: unknown = JSON.parse(raw); if (object(record) && record.checkoutId === session.checkoutId && record.checkoutToken === session.checkoutToken) target?.removeItem(ownedKey); } } catch { /* Do not touch another capability. */ }
}
