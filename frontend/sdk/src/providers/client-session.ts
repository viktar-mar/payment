import { exact, instant, object, uuid } from '../contracts.js';
import type { ClientPaymentSession, StripeCheckoutSession } from '../contracts.js';
import { PaymentSdkError } from '../errors.js';
import { browserOrigin } from '../customer/contracts.js';

const fields = ['checkout_id', 'payment_id', 'attempt_id', 'provider_type', 'confirmation_mode', 'publishable_key', 'client_secret', 'expires_at', 'return_url'];
// The endpoint has a flat string-only object. Check its grammar and pair count
// before JSON.parse can discard duplicate (including escaped) identity keys.
export function parseClientSessionJson(raw: string): unknown {
  const string = '"(?:[^"\\\\\\u0000-\\u001f]|\\\\(?:["\\\\/bfnrt]|u[0-9a-fA-F]{4}))*"';
  const pair = `${string}\\s*:\\s*${string}`;
  if (!new RegExp(`^\\s*\\{\\s*${pair}(?:\\s*,\\s*${pair})*\\s*\\}\\s*$`).test(raw) || [...raw.matchAll(new RegExp(pair, 'g'))].length !== fields.length) throw new PaymentSdkError('INVALID_RESPONSE');
  return JSON.parse(raw);
}
function returnUrl(value: unknown, secret: string): value is string {
  if (typeof value !== 'string' || value.length > 2048 || /[\\\s\u0000-\u001f\u007f-\u009f]/u.test(value)) return false;
  try {
    const decoded = decodeURIComponent(value);
    if (/[\u0000-\u001f\u007f-\u009f]/u.test(decoded) || decoded.includes(secret)) return false;
    const url = new URL(value); const local = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname);
    if ('location' in globalThis && url.origin !== browserOrigin()) return false;
    return (url.protocol === 'https:' || (url.protocol === 'http:' && local)) && !url.username && !url.password && !value.includes('#') && ![...url.searchParams.keys()].some(key => /(?:secret|token|publishable_key)/i.test(key));
  } catch { return false; }
}
export function parseClientPaymentSession(raw: unknown, session: StripeCheckoutSession): ClientPaymentSession {
  if (!object(raw) || !exact(raw, fields) || raw.provider_type !== 'STRIPE' || raw.confirmation_mode !== 'CLIENT_PROVIDER' || !uuid(raw.checkout_id) || raw.checkout_id !== session.checkoutId || !uuid(raw.payment_id) || raw.payment_id !== session.state.paymentId || !uuid(raw.attempt_id) || typeof raw.publishable_key !== 'string' || raw.publishable_key.length > 255 || !/^pk_(?:test|live)_[A-Za-z0-9_]+$/.test(raw.publishable_key) || typeof raw.client_secret !== 'string' || raw.client_secret.length > 512 || !/^pi_[A-Za-z0-9_]+_secret_[A-Za-z0-9_]+$/.test(raw.client_secret) || !instant(raw.expires_at) || raw.expires_at !== session.expiresAt || Date.parse(raw.expires_at) <= Date.now() || !returnUrl(raw.return_url, raw.client_secret)) throw new PaymentSdkError('INVALID_RESPONSE');
  const result: ClientPaymentSession = { checkoutId: raw.checkout_id, paymentId: raw.payment_id, attemptId: raw.attempt_id, publishableKey: raw.publishable_key, clientSecret: raw.client_secret, expiresAt: raw.expires_at, returnUrl: raw.return_url };
  Object.defineProperties(result, {
    clientSecret: { enumerable: false },
    toString: { value: () => '[ClientPaymentSession redacted]' },
    toJSON: { value: () => '[ClientPaymentSession redacted]' },
  });
  return Object.freeze(result);
}
