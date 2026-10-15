import { exact, key, normalizeApiBase, object, safeState, validateSession } from "./contracts.js";
import type { CheckoutSession, RecoveryIdentity } from "./contracts.js";
import { PaymentSdkError } from "./errors.js";
const recoveryCandidates = new WeakSet<CheckoutSession>();
/** Internal workflow markers; neither persisted authority nor public SDK API. */
export function needsRecoveryRead(session: CheckoutSession): boolean { return recoveryCandidates.has(session); }
export function markRecoveryRead(session: CheckoutSession): void { recoveryCandidates.delete(session); }
export function storageKey(identity: RecoveryIdentity): string {
  if (!key(identity.siteKey) || !key(identity.priceKey)) throw new PaymentSdkError("INVALID_CONFIGURATION");
  return `generic-payments:v1:${encodeURIComponent(normalizeApiBase(identity.apiBaseUrl))}:${identity.siteKey}:${identity.priceKey}`;
}
function storage(): Storage | undefined { try { return globalThis.sessionStorage; } catch { return undefined; } }
export function saveCheckout(session: CheckoutSession): void {
  validateSession(session);
  if (session.donation) return;
  if (Date.parse(session.expiresAt) <= Date.now()) { clearCheckout(session); return; }
  const record = { version: 2, checkoutId: session.checkoutId, checkoutToken: session.checkoutToken, expiresAt: session.expiresAt, apiBaseUrl: session.apiBaseUrl, siteKey: session.siteKey, priceKey: session.priceKey, state: safeState(session.state), providerType: session.providerType, confirmationMode: session.confirmationMode };
  try { storage()?.setItem(storageKey(session), JSON.stringify(record)); } catch { /* Memory capability still works when storage is blocked. */ }
}
export function recoverCheckout(identity: RecoveryIdentity): CheckoutSession | null {
  const recordKey = storageKey(identity); const target = storage();
  try {
    const raw = target?.getItem(recordKey); if (raw == null) return null;
    if (raw.length > 8192) throw 0;
    const record: unknown = JSON.parse(raw);
    if (!object(record) || ![1, 2].includes(record.version as number) || !exact(record, ["version", "checkoutId", "checkoutToken", "expiresAt", "apiBaseUrl", "siteKey", "priceKey", "state", ...(record.version === 2 ? ['providerType', 'confirmationMode'] : [])]) || record.apiBaseUrl !== normalizeApiBase(identity.apiBaseUrl) || record.siteKey !== identity.siteKey || record.priceKey !== identity.priceKey) throw 0;
    // A stored record is only a candidate; authorized get must validate its binding before action.
    const session = { checkoutId: record.checkoutId, checkoutToken: record.checkoutToken, expiresAt: record.expiresAt, apiBaseUrl: record.apiBaseUrl, siteKey: record.siteKey, priceKey: record.priceKey, state: safeState(record.state), providerType: record.version === 1 ? 'FAKE' : record.providerType, confirmationMode: record.version === 1 ? 'SERVER' : record.confirmationMode, providerClientPayload: {} } as CheckoutSession;
    validateSession(session); if (Date.parse(session.expiresAt) <= Date.now()) throw 0;
    recoveryCandidates.add(session); return session;
  } catch { try { target?.removeItem(recordKey); } catch { /* unavailable storage */ } return null; }
}
export function clearCheckout(session: CheckoutSession): void {
  validateSession(session);
  const target = storage(); const ownedKey = storageKey(session);
  try { const raw = target?.getItem(ownedKey); if (raw && raw.length <= 8192) { const record: unknown = JSON.parse(raw); if (object(record) && record.checkoutId === session.checkoutId && record.checkoutToken === session.checkoutToken) target?.removeItem(ownedKey); } } catch { /* Do not touch another capability. */ }
}
