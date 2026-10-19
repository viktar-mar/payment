import type { Stripe } from '@stripe/stripe-js';
import { vi } from 'vitest';
import { wire } from './fixtures.js';
import { loadStripeClient } from '../providers/stripe-loader.js';
export const stripeWire = (status = 'CREATED') => ({ ...wire(status), provider_type: 'STRIPE', confirmation_mode: 'CLIENT_PROVIDER' });
export const stripeReadWire = (status = 'CREATED') => { const { checkout_token: _, ...safe } = stripeWire(status); return safe; };
export const stripeCapability = () => ({ checkout_id: wire().checkout_id, payment_id: wire().payment_id, attempt_id: '44444444-4444-4444-8444-444444444444', provider_type: 'STRIPE', confirmation_mode: 'CLIENT_PROVIDER', publishable_key: 'pk_test_fixture', client_secret: 'pi_fixture_secret_sensitive', expires_at: wire().expires_at, return_url: `${location.origin}/payment-return` });
// Only the external Stripe SDK is doubled. Parents, client, wrapper and driver run.
export function stripeUiProvider() {
  const listeners = new Map<string, () => void>(); const frame = document.createElement('iframe'); frame.title = 'Secure card input';
  const element = { on: (name: string, fn: () => void) => { listeners.set(name, fn); return element; }, off: (name: string) => { listeners.delete(name); return element; }, mount: (host: HTMLElement) => {
    if (!host.isConnected || host.getRootNode() !== host.ownerDocument || !host.ownerDocument.documentElement.contains(host)) throw new Error('Stripe requires a connected document Light DOM host');
    host.append(frame);
  }, destroy: vi.fn(() => frame.remove()), focus: vi.fn(() => frame.focus()) };
  const elements = { create: vi.fn(() => element), update: vi.fn<(...args: unknown[]) => Promise<void>>(() => Promise.resolve()) };
  const stripe = { elements: vi.fn(() => elements), confirmPayment: vi.fn<(...args: unknown[]) => Promise<unknown>>(() => Promise.resolve({})) };
  vi.mocked(loadStripeClient).mockResolvedValue(stripe as unknown as Stripe);
  return { stripe, elements, element, frame, emit: (name: string) => listeners.get(name)?.() };
}
