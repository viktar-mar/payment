import type { CustomerSession } from '../customer/contracts.js';
import { base, customerToken, future, id, subscriptionCustomerScopes } from './customer-fixtures.js';
export const subscriptionId = '8ad38d60-f78a-44b3-9881-722eea250289';
export const subscriptionPriceId = '98f16bdb-3f7a-4b1f-bf02-01d1ced46b75';
export const productId = '5db5813b-277e-4bb1-87e3-8625a0c294dc';
export const siteId = '08ac693f-6f49-4a8d-bd14-e5ef47a1dc18';
export const operationId = 'f1d1b7e8-4158-40d1-ae13-ea053568f314';
export const billingId = '7c9ff016-521c-47bf-9440-89bda6a8bc07';
export function subscriptionSession(): CustomerSession {
  return { apiBaseUrl: base, siteKey: 'test-site', siteId, origin: location.origin, sessionToken: customerToken, customer: { id, status: 'ACTIVE', maskedEmail: null }, expiresAt: future(), scopes: [...subscriptionCustomerScopes] };
}
export const priceWire = () => ({ price_id: subscriptionPriceId, price_key: 'monthly', version: 1, product_id: productId, amount_minor: 1000, currency: 'EUR', interval: 'MONTH', trial_days: 0, trial_payment_method_policy: 'REQUIRED_BEFORE_TRIAL', tax_mode: 'NONE' });
export const periodWire = () => ({ start: '2026-01-01T00:00:00Z', end: '2026-02-01T00:00:00Z' });
export const actionWire = () => ({ kind: 'FAKE_CONFIRM_SETUP', secret: 'S'.repeat(43), expires_at: future() });
export const operationWire = () => ({ operation_id: operationId, subscription_id: subscriptionId, status: 'PROCESSING', action_available: false });
export const mutationWire = () => ({ ...operationWire(), next_action: null });
export const quoteWire = () => ({ quote_id: operationId, subscription_id: subscriptionId, from_price_id: subscriptionPriceId, from_price_version: 1, target_price: priceWire(), timing: 'NEXT_PERIOD', due_now_minor: 0, next_amount_minor: 1000, currency: 'EUR', effective_at: '2026-02-01T00:00:00Z', expires_at: future(), tax_final: true, subscription_revision: 1, policy_version: 1 });
export const subscriptionWire = () => ({ subscription_id: subscriptionId, site_id: siteId, customer_id: id, product_id: productId, payment_account_id: 'b773932b-2075-4ead-8e9b-5cd7aab43d4c', billing_price: priceWire(), entitled_price: priceWire(), state: 'ACTIVE', billing_status: 'ACTIVE', revision: 1, period: periodWire(), paid_through: '2026-02-01T00:00:00Z', trial: null, cancellation: null, pending_change: null, pending_operation: null, debt: null, access: { allowed: true, reason: 'PAID', site_id: siteId, customer_id: id, product_id: productId, subscription_id: subscriptionId, entitled_price_id: subscriptionPriceId, entitled_price_version: 1, revision: 1, evaluated_at: '2026-01-10T00:00:00Z', valid_until: '2026-01-10T00:05:00Z' }, permitted_actions: ['CHANGE_PLAN', 'CANCEL_PERIOD_END', 'SETUP_METHOD', 'OPEN_PORTAL'], next_amount: { amount_minor: 1000, currency: 'EUR', tax_final: true }, created_at: '2026-01-01T00:00:00Z', updated_at: '2026-01-01T00:00:00Z' });
export const billingWire = () => ({ billing_record_id: billingId, subscription_id: subscriptionId, kind: 'RENEWAL', price: priceWire(), previous_price: null, period: periodWire(), amount_due_minor: 1000, currency: 'EUR', settlement: 'CONFIRMED_CASH', payment_ids: [id], created_at: '2026-01-01T00:00:00Z', updated_at: '2026-01-01T00:00:00Z' });
export const eventWire = () => ({ event_id: billingId, subscription_id: subscriptionId, sequence: 1, revision: 1, type: 'PERIOD_PAID', source: 'PROVIDER_WEBHOOK', occurred_at: '2026-01-01T00:00:00Z', received_at: '2026-01-01T00:00:01Z', operation_id: null, billing_record_id: billingId, payment_id: id });
export const offersWire = () => ({ items: [{ price: priceWire(), direction: 'UPGRADE', allowed_timings: ['IMMEDIATE', 'NEXT_PERIOD'], trial_eligible: false }] });
export const returnUrl = 'https://shop.test/subscriptions';
export const portalWire = () => ({ navigation_url: `${returnUrl}?subscriptionPortal=simulated&operationId=${operationId}`, expires_at: future(), simulated: true });
