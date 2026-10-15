// @vitest-environment node
import { expect, it } from 'vitest';
it('imports the SDK and constructs its headless client in Node without browser globals', async () => {
  // Node 25 exposes experimental Web Storage without a DOM. Remove that
  // optional built-in for this check; do not supply browser shims to the SDK.
  const storage = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage');
  Reflect.deleteProperty(globalThis, 'sessionStorage');
  try {
    for (const name of ['window', 'document', 'location', 'HTMLElement', 'customElements', 'sessionStorage']) expect(name in globalThis, name).toBe(false);
    const sdk = await import('../index.js');
    const client = sdk.createGenericPaymentClient({ apiBaseUrl: 'https://api.test' });
    expect(client.subscriptions.get).toBeTypeOf('function');
    expect(client.prepareClientPayment).toBeTypeOf('function');
    expect(sdk.watchSubscriptionOperation).toBeTypeOf('function');
    expect(sdk.confirmSubscriptionAction).toBeTypeOf('function');
  } finally { if (storage) Object.defineProperty(globalThis, 'sessionStorage', storage); }
});
