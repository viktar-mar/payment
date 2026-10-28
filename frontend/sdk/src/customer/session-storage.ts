import { exact, key, normalizeApiBase, object } from "../contracts.js";
import { PaymentSdkError } from "../errors.js";
import { browserOrigin, validateCustomerSession } from "./contracts.js";
import type { CustomerRecoveryIdentity, CustomerSession } from "./contracts.js";
import { clearCheckoutCustomer, replaceCheckoutCustomer } from './session-lifecycle.js';
// Safe failure markers retain fail-closed intent after public recovery removes a bad record.
// They contain only a namespace and fixed code, never a credential or response body.
const invalidRecords = new Map<string, "INVALID_SESSION" | "SESSION_EXPIRED">();
// Internal receipts are held by the owning client's failed write, not by storage.
// Weak references never retain a discarded client's snapshot or capability.
export interface CustomerRecordCleanup { readonly record: string | null | undefined; removed: boolean; changed: boolean }
const trackedRecords = new Map<string, Set<WeakRef<CustomerRecordCleanup>>>();
export function trackCustomerRecordCleanup(identity: CustomerRecoveryIdentity, record: string | null | undefined): CustomerRecordCleanup {
  const storageKey = customerStorageKey(identity); const refs = trackedRecords.get(storageKey) ?? new Set<WeakRef<CustomerRecordCleanup>>();
  for (const ref of refs) if (!ref.deref()) refs.delete(ref);
  const receipt = { record, removed: false, changed: false }; refs.add(new WeakRef(receipt)); trackedRecords.set(storageKey, refs); return receipt;
}
function noteRecordObservation(storageKey: string, raw: string, removed: boolean): void {
  const refs = trackedRecords.get(storageKey); if (!refs) return;
  for (const ref of refs) {
    const receipt = ref.deref(); if (!receipt) { refs.delete(ref); continue; }
    if (receipt.record !== raw) receipt.changed = true;
    receipt.removed = !receipt.changed && receipt.record === raw && removed;
  }
  if (!refs.size) trackedRecords.delete(storageKey);
}
export function customerStorageKey(identity: CustomerRecoveryIdentity): string {
  if (!object(identity) || !key(identity.siteKey)) throw new PaymentSdkError("INVALID_CONFIGURATION");
  return `generic-payments:customer:v1:${encodeURIComponent(normalizeApiBase(identity.apiBaseUrl))}:${identity.siteKey}:${encodeURIComponent(browserOrigin())}`;
}
export function readCustomerRecord(identity: CustomerRecoveryIdentity): string | null | undefined {
  const storageKey = customerStorageKey(identity);
  try { return globalThis.sessionStorage?.getItem(storageKey); } catch { return undefined; }
}
export type CustomerResolution = { kind: "absent" | "unavailable" } | { kind: "valid"; session: CustomerSession } | { kind: "invalid"; code: "INVALID_SESSION" | "SESSION_EXPIRED" };
export function resolveCustomerSession(identity: CustomerRecoveryIdentity): CustomerResolution {
  const raw = readCustomerRecord(identity);
  const storageKey = customerStorageKey(identity); const invalid = invalidRecords.get(storageKey);
  if (raw === undefined || raw === null) return invalid ? { kind: "invalid", code: invalid } : { kind: raw === undefined ? "unavailable" : "absent" };
  try {
    if (raw.length > 8192) throw 0;
    const record: unknown = JSON.parse(raw);
    if (!object(record) || !exact(record, ["version", "sessionToken", "apiBaseUrl", "siteKey", "origin", "customer", "expiresAt", "scopes", ...(Object.hasOwn(record, 'siteId') ? ['siteId'] : [])]) || record.version !== 1) throw 0;
    const { version: _, ...session } = record;
    if (session.apiBaseUrl !== normalizeApiBase(identity.apiBaseUrl) || session.siteKey !== identity.siteKey) throw 0;
    validateCustomerSession(session); noteRecordObservation(storageKey, raw, false); invalidRecords.delete(storageKey); return { kind: "valid", session };
  } catch (error) {
    let removed = false;
    try { const target = globalThis.sessionStorage; if (target?.getItem(storageKey) === raw) { target.removeItem(storageKey); removed = target.getItem(storageKey) === null; } } catch { /* Unavailable storage has no fallback. */ }
    noteRecordObservation(storageKey, raw, removed);
    const code = error instanceof PaymentSdkError && error.code === "SESSION_EXPIRED" ? "SESSION_EXPIRED" : "INVALID_SESSION";
    invalidRecords.set(storageKey, code); return { kind: "invalid", code };
  }
}
export function recoverCustomerSession(identity: CustomerRecoveryIdentity): CustomerSession | null { const result = resolveCustomerSession(identity); return result.kind === "valid" ? result.session : null; }
export function saveCustomerSession(session: CustomerSession): boolean {
  validateCustomerSession(session); const storageKey = customerStorageKey(session); invalidRecords.delete(storageKey);
  replaceCheckoutCustomer(session);
  try { const target = globalThis.sessionStorage; if (!target) return false; const raw = JSON.stringify({ version: 1, ...session }); target.setItem(storageKey, raw); noteRecordObservation(storageKey, raw, false); return true; } catch { return false; }
}
export function clearCustomerSession(session: CustomerSession): void {
  clearCheckoutCustomer(session);
  const storageKey = customerStorageKey(session); const raw = readCustomerRecord(session);
  // Even a nonmatching exact-token clear has observed a replacement record.
  if (typeof raw === "string") noteRecordObservation(storageKey, raw, false);
  if (!raw || raw.length > 8192) return;
  try {
    const record: unknown = JSON.parse(raw);
    if (object(record) && record.sessionToken === session.sessionToken && record.origin === session.origin) {
      const target = globalThis.sessionStorage; target.removeItem(storageKey); noteRecordObservation(storageKey, raw, target.getItem(storageKey) === null);
    }
  } catch { /* Never remove another session. */ }
}
