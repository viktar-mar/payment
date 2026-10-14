import type { components } from "../../lib/api/schema";
type Schemas = components["schemas"];
export const siteId = "10000000-0000-0000-0000-000000000001";
export const customerId = "20000000-0000-0000-0000-000000000002";
export const productId = "30000000-0000-0000-0000-000000000003";
export const subscriptionId = "40000000-0000-0000-0000-000000000004";
export const operationId = "50000000-0000-0000-0000-000000000005";
export const priceId = "60000000-0000-0000-0000-000000000006";
export const targetPriceId = "70000000-0000-0000-0000-000000000007";
export const billId = "80000000-0000-0000-0000-000000000008";
export const paymentId = "90000000-0000-0000-0000-000000000009";
export const instant = "2026-10-12T12:00:00Z";
export const price: Schemas["SubscriptionPriceResponse"] = { price_id: priceId, price_key: "monthly", version: 1, product_id: productId, amount_minor: 1000, currency: "EUR", interval: "MONTH", trial_days: 0, trial_payment_method_policy: "REQUIRED_BEFORE_TRIAL", tax_mode: "NONE" };
export function activeSubscription(): Schemas["SubscriptionResponse"] {
  return { subscription_id: subscriptionId, site_id: siteId, customer_id: customerId, product_id: productId, payment_account_id: siteId, billing_price: price, entitled_price: price, state: "ACTIVE", billing_status: "ACTIVE", revision: 3, period: { start: instant, end: "2026-11-12T12:00:00Z" }, paid_through: "2026-11-12T12:00:00Z", trial: null, cancellation: null, pending_change: null, pending_operation: null, debt: null, access: { allowed: true, reason: "PAID", site_id: siteId, customer_id: customerId, product_id: productId, subscription_id: subscriptionId, entitled_price_id: priceId, entitled_price_version: 1, revision: 3, evaluated_at: instant, valid_until: "2026-10-12T12:05:00Z" }, permitted_actions: ["CHANGE_PLAN", "CANCEL_PERIOD_END", "CANCEL_IMMEDIATE"], next_amount: { amount_minor: 1000, currency: "EUR", tax_final: true }, created_at: instant, updated_at: instant };
}
export function unknownCancellationSubscription(): Schemas["SubscriptionResponse"] {
  return { ...activeSubscription(), permitted_actions: [], pending_operation: { operation_id: operationId, kind: "CANCEL", status: "UNKNOWN" } };
}
export function unknownCancellationOperation(): Schemas["SubscriptionOperationResponse"] {
  return { operation_id: operationId, subscription_id: subscriptionId, status: "UNKNOWN", action_available: false };
}
export const policy: Schemas["SubscriptionPolicyResponse"] = { site_id: siteId, version: 4, enabled: true, grace_days: 3, allow_immediate_cancellation: true, portal_enabled: false, defer_upgrades: false, portal_return_urls: [] };
export const bill: Schemas["SubscriptionBillingResponse"] = { billing_record_id: billId, subscription_id: subscriptionId, kind: "RENEWAL", price, previous_price: null, period: { start: instant, end: "2026-11-12T12:00:00Z" }, amount_due_minor: 1000, currency: "EUR", settlement: "CONFIRMED_CASH", payment_ids: [paymentId], created_at: instant, updated_at: instant };
export function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done; }); return { promise, resolve }; }
