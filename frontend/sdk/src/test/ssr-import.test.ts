// @vitest-environment node
import { expect, it, vi } from 'vitest';
vi.mock('@stripe/stripe-js/pure/index.js', () => { throw new Error('Stripe pure runtime imported eagerly'); });
vi.mock('@stripe/stripe-js', () => { throw new Error('Stripe runtime imported eagerly'); });
it('imports the SDK and constructs its headless client in Node without browser globals', async () => {
  // Node 25 exposes experimental Web Storage without a DOM. Remove that
  // optional built-in for this check; do not supply browser shims to the SDK.
  const storage = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage');
  Reflect.deleteProperty(globalThis, 'sessionStorage');
  try {
    for (const name of ['window', 'document', 'location', 'HTMLElement', 'customElements', 'sessionStorage']) expect(name in globalThis, name).toBe(false);
    const sdk = await import('../index.js');
    const loader = await import('../providers/stripe-loader.js');
    const driver = await import('../providers/stripe-driver.js');
    expect(loader.loadStripeClient).toBeTypeOf('function');
    expect(driver.mountStripePayment).toBeTypeOf('function');
    const client = sdk.createGenericPaymentClient({ apiBaseUrl: 'https://api.test' });
    expect(client.subscriptions.get).toBeTypeOf('function');
    expect(client.prepareClientPayment).toBeTypeOf('function');
    expect(sdk.watchSubscriptionOperation).toBeTypeOf('function');
    expect(sdk.confirmSubscriptionAction).toBeTypeOf('function');
  } finally { if (storage) Object.defineProperty(globalThis, 'sessionStorage', storage); }
});
