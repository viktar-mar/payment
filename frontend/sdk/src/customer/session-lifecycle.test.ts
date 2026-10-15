import { expect, it, vi } from 'vitest';
import { createGenericPaymentClient } from '../client.js';
import { subscribeCheckoutCustomer } from './session-lifecycle.js';
import { authInput, customerWire } from '../test/customer-fixtures.js';
import { json, wire } from '../test/fixtures.js';
import { donationWire } from '../test/donation-fixtures.js';
async function authenticated() {
  const client = createGenericPaymentClient({ apiBaseUrl: 'https://api.test' }); vi.stubGlobal('fetch', async () => json(customerWire()));
  return { client, customer: await client.exchangeSiteToken(authInput) };
}
it.each(['logout', 'replacement'])('remembers %s between client resolution and delayed UI subscription', async kind => {
  const f = await authenticated(); vi.stubGlobal('fetch', async () => json(wire(), 201));
  const session = await f.client.createCatalogCheckout({ siteKey: 'test-site', priceKey: 'lifetime' });
  vi.stubGlobal('fetch', async () => kind === 'logout' ? new Response(null, { status: 204 }) : json({ ...customerWire(), session_token: 'N'.repeat(43) }));
  if (kind === 'logout') await f.client.logoutCustomerSession(f.customer); else await f.client.exchangeSiteToken(authInput);
  const callback = vi.fn(); const unsubscribe = subscribeCheckoutCustomer(session, callback); expect(callback).toHaveBeenCalledOnce(); unsubscribe();
});
it('invalidated delayed subscription can reenter cleanup and does not keep callbacks', async () => {
  const f = await authenticated(); vi.stubGlobal('fetch', async () => json(wire(), 201)); const session = await f.client.createCatalogCheckout({ siteKey: 'test-site', priceKey: 'lifetime' });
  vi.stubGlobal('fetch', async () => new Response(null, { status: 204 })); await f.client.logoutCustomerSession(f.customer);
  let calls = 0; const unsubscribe = subscribeCheckoutCustomer(session, () => { calls++; subscribeCheckoutCustomer(session, () => {}).call(undefined); });
  expect(calls).toBe(1); unsubscribe(); await f.client.logoutCustomerSession(f.customer); expect(calls).toBe(1);
});
it.each(['omit', 'absent'])('originally anonymous Donation %s remains independent of later login/logout', async kind => {
  const client = createGenericPaymentClient({ apiBaseUrl: 'https://api.test' }); vi.stubGlobal('fetch', async () => json(donationWire(), 201));
  const session = await client.createDonationCheckout({ siteKey: 'test-site', campaignKey: 'help-campaign', amountMinor: 4999, customerSession: kind === 'omit' ? 'omit' : 'auto' });
  const callback = vi.fn(); const unsubscribe = subscribeCheckoutCustomer(session, callback);
  vi.stubGlobal('fetch', async () => json(customerWire())); const customer = await client.exchangeSiteToken(authInput); vi.stubGlobal('fetch', async () => new Response(null, { status: 204 })); await client.logoutCustomerSession(customer);
  expect(callback).not.toHaveBeenCalled(); unsubscribe();
});
it('identified Donation creation cannot publish after logout while its request is pending', async () => {
  const f = await authenticated(); let release!: (r: Response) => void; vi.stubGlobal('fetch', () => new Promise<Response>(r => { release = r; }));
  const pending = f.client.createDonationCheckout({ siteKey: 'test-site', campaignKey: 'help-campaign', amountMinor: 4999 }); const outcome = pending.catch(e => e);
  vi.stubGlobal('fetch', async () => new Response(null, { status: 204 })); await f.client.logoutCustomerSession(f.customer); release(json(donationWire(), 201)); expect(await outcome).toMatchObject({ code: 'INVALID_SESSION' }); expect(sessionStorage.length).toBe(0);
});
