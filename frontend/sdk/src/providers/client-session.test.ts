import { describe, expect, it, vi } from 'vitest';
import { createGenericPaymentClient } from '../client.js';
import { recoverCheckout } from '../storage.js';
import { input, json, token, wire } from '../test/fixtures.js';
import { authInput, customerToken, customerWire } from '../test/customer-fixtures.js';
import { donationInput, donationWire } from '../test/donation-fixtures.js';
import { assertProviderBinding } from '../contracts.js';

const secret = 'pi_fixture_secret_ephemeral_sentinel';
const stripeWire = () => ({ ...wire(), provider_type: 'STRIPE', confirmation_mode: 'CLIENT_PROVIDER' });
const capability = () => ({ checkout_id: wire().checkout_id, payment_id: wire().payment_id,
  attempt_id: '44444444-4444-4444-8444-444444444444', provider_type: 'STRIPE', confirmation_mode: 'CLIENT_PROVIDER',
  publishable_key: 'pk_test_fixture', client_secret: secret, expires_at: wire().expires_at, return_url: 'https://merchant.test/payment/return' });
async function setup() {
  vi.stubGlobal('location', { origin: 'https://merchant.test' });
  const requests: Request[] = [];
  const fetch = vi.fn(async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(stripeWire()); });
  vi.stubGlobal('fetch', fetch);
  const client = createGenericPaymentClient({ apiBaseUrl: 'https://api.test' });
  const session = await client.createCatalogCheckout(input);
  return { client, session, fetch, requests };
}
describe('ephemeral Stripe client session', () => {
  it('parses Stripe checkout and uses isolated empty-body capability POST without persisting the secret', async () => {
    const { client, session, fetch, requests } = await setup();
    expect(session.providerType).toBe('STRIPE'); expect(session.providerClientPayload).toEqual({});
    fetch.mockImplementation(async (url, init) => { requests.push(new Request(url, init)); return json(capability()); });
    const result = await client.prepareClientPayment(session as never, { idempotencyKey: 'owned-attempt-key' });
    expect({ ...result, clientSecret: result.clientSecret }).toEqual({ checkoutId: wire().checkout_id, paymentId: wire().payment_id, attemptId: '44444444-4444-4444-8444-444444444444', publishableKey: 'pk_test_fixture', clientSecret: secret, expiresAt: wire().expires_at, returnUrl: 'https://merchant.test/payment/return' });
    const request = requests[1];
    expect(request.url).toBe(`https://api.test/api/v1/public/checkouts/${session.checkoutId}/client-session`);
    expect(request.method).toBe('POST'); expect(await request.text()).toBe('');
    expect(request.headers.get('authorization')).toBe(`Bearer ${token}`); expect(request.headers.get('idempotency-key')).toBe('owned-attempt-key');
    expect(request.headers.has('content-type')).toBe(false); expect(request.redirect).toBe('error'); expect(request.cache).toBe('no-store'); expect(request.credentials).toBe('omit');
    expect(String(result)).not.toContain(secret); expect(JSON.stringify(result)).not.toContain(secret);
    expect(sessionStorage.getItem(sessionStorage.key(0)!)).not.toContain(secret);
    expect(recoverCheckout({ ...input, apiBaseUrl: 'https://api.test' })?.providerType).toBe('STRIPE');
  });
  const invalid = [
    ...Object.keys(capability()).map(field => ({ name: `missing ${field}`, mutate: (raw: Record<string, unknown>) => { delete raw[field]; } })),
    ...Object.entries({ extra: true, provider_type: 'FAKE', confirmation_mode: 'SERVER', checkout_id: '55555555-5555-4555-8555-555555555555', payment_id: '55555555-5555-4555-8555-555555555555', attempt_id: '../evil', publishable_key: 'sk_test_private', client_secret: '', expires_at: '2000-01-01T00:00:00Z', return_url: 'javascript:evil' }).map(([field, value]) => ({ name: `invalid ${field}`, mutate: (raw: Record<string, unknown>) => { raw[field] = value; } })),
    ...['http://merchant.test/return', 'https://user:pass@merchant.test/return', 'https://merchant.test/return#secret', 'https://merchant.test/%C2%85', 'https://merchant.test/return?client_secret=synthetic'].map(url => ({ name: url, mutate: (raw: Record<string, unknown>) => { raw.return_url = url; } })),
  ];
  it.each(invalid)('rejects exact-wire violation $name without disclosing raw capability', async ({ mutate }) => {
    const { client, session, fetch } = await setup(); const raw = capability(); mutate(raw);
    fetch.mockImplementation(async () => json(raw));
    try { await client.prepareClientPayment(session as never); expect.fail('invalid capability accepted'); }
    catch (error) { expect(error).toMatchObject({ code: 'INVALID_RESPONSE' }); expect(String(error)).not.toContain(secret); expect(JSON.stringify(error)).not.toContain(secret); }
  });
  it.each(['oversized', 'utf8', 'duplicate', 'escaped-duplicate', 'redirect'])('rejects bounded transport %s', async kind => {
    const { client, session, fetch } = await setup();
    fetch.mockImplementation(async () => kind === 'oversized' ? new Response(' '.repeat(32769)) : kind === 'utf8' ? new Response(new Uint8Array([0xc3, 0x28])) : kind === 'redirect' ? new Response(null, { status: 302 }) : new Response(JSON.stringify(capability()).replace('"checkout_id":', `${kind === 'escaped-duplicate' ? '"checkout_\\u0069d"' : '"checkout_id"'}:"55555555-5555-4555-8555-555555555555","checkout_id":`)));
    await expect(client.prepareClientPayment(session as never)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });
  it('rejects stale response after checkout state changes in flight', async () => {
    const { client, session, fetch } = await setup();
    fetch.mockImplementation(async () => { session.state = { ...session.state, status: 'SUCCEEDED' }; return json(capability()); });
    await expect(client.prepareClientPayment(session as never)).rejects.toMatchObject({ code: 'PAYMENT_STATE_CONFLICT' });
  });
  it('rejects capability when the checkout expires during transport', async () => {
    const { client, session, fetch } = await setup();
    vi.useFakeTimers(); fetch.mockImplementation(async () => { vi.setSystemTime(Date.parse(session.expiresAt) + 1); return json(capability()); });
    await expect(client.prepareClientPayment(session as never)).rejects.toMatchObject({ code: 'CHECKOUT_EXPIRED' });
  });
  it('rejects an earlier capability superseded by a newer request on the same Payment', async () => {
    const { client, session, fetch } = await setup();
    let deliver!: (response: Response) => void;
    fetch.mockImplementationOnce(() => new Promise(resolve => { deliver = resolve; })).mockImplementationOnce(async () => json(capability()));
    const old = client.prepareClientPayment(session as never, { idempotencyKey: 'old-key' });
    const rejection = expect(old).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
    expect((await client.prepareClientPayment(session as never, { idempotencyKey: 'new-key' })).paymentId).toBe(session.state.paymentId);
    deliver(json(capability())); await rejection;
  });
  it('never substitutes Customer session authority for the checkout bearer', async () => {
    vi.stubGlobal('location', { origin: 'https://merchant.test' });
    const sent: Request[] = [];
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { sent.push(new Request(url, init)); return json(url.includes('customer-auth') ? customerWire() : url.endsWith('/client-session') ? capability() : stripeWire(), url.includes('/sites/') ? 201 : 200); });
    const client = createGenericPaymentClient({ apiBaseUrl: 'https://api.test' }); const customer = await client.exchangeSiteToken(authInput);
    const session = await client.createCatalogCheckout(input); await client.prepareClientPayment(session as never);
    expect(sent.map(request => request.headers.get('authorization'))).toEqual([null, `Bearer ${customerToken}`, `Bearer ${token}`]);
    await expect(client.prepareClientPayment(customer as never)).rejects.toMatchObject({ code: 'INVALID_CONFIGURATION' }); expect(sent).toHaveLength(3);
  });
  it('keeps Stripe Donation checkout and capability entirely out of recovery storage', async () => {
    vi.stubGlobal('location', { origin: 'https://merchant.test' });
    vi.stubGlobal('fetch', async (url: string) => json(url.endsWith('/client-session') ? capability() : { ...donationWire(), provider_type: 'STRIPE', confirmation_mode: 'CLIENT_PROVIDER' }, url.includes('/sites/') ? 201 : 200));
    const client = createGenericPaymentClient({ apiBaseUrl: 'https://api.test' }); const session = await client.createDonationCheckout(donationInput);
    expect(session.providerType).toBe('STRIPE'); expect(session.donation.campaignKey).toBe('help-campaign');
    expect((await client.prepareClientPayment(session as never)).clientSecret).toBe(secret); expect(sessionStorage.length).toBe(0);
  });
  it.each(['', 'k'.repeat(256), 'bad\nkey', '\u0085'])('refuses invalid idempotency key before transport %#', async idempotencyKey => {
    const { client, session, requests } = await setup();
    await expect(client.prepareClientPayment(session as never, { idempotencyKey })).rejects.toMatchObject({ code: 'INVALID_CONFIGURATION' }); expect(requests).toHaveLength(1);
  });
  it('honors cancellation without a capability request or secret disclosure', async () => {
    const { client, session, requests } = await setup(); const controller = new AbortController(); const reason = new DOMException('fixture abort', 'AbortError'); controller.abort(reason);
    await expect(client.prepareClientPayment(session as never, { signal: controller.signal })).rejects.toBe(reason); expect(requests).toHaveLength(1);
  });
  it('checks provider binding independently of the read parser', () => {
    expect(() => assertProviderBinding('STRIPE', 'CLIENT_PROVIDER', 'FAKE', 'SERVER')).toThrowError();
    expect(() => assertProviderBinding('STRIPE', 'CLIENT_PROVIDER', 'STRIPE', 'CLIENT_PROVIDER')).not.toThrow();
  });
  it('refuses server confirm for Stripe without changing state or issuing POST', async () => {
    const { client, session, requests } = await setup();
    await expect(client.confirm(session as never)).rejects.toMatchObject({ code: 'UNSUPPORTED_PROVIDER' });
    expect(session.state.status).toBe('REQUIRES_PAYMENT_METHOD'); expect(requests).toHaveLength(1);
  });
  it('refuses a substituted provider during authorized recovery read', async () => {
    const { client, session, fetch } = await setup();
    fetch.mockImplementation(async () => { const { checkout_token: ignored, ...read } = wire(); return json(read); });
    await expect(client.get(session)).rejects.toMatchObject({ code: 'UNSUPPORTED_PROVIDER' });
    expect(session.providerType).toBe('STRIPE'); expect(session.state.status).toBe('REQUIRES_PAYMENT_METHOD');
  });
  it('authorizes headless recovered Stripe with GET before capability POST, once per candidate', async () => {
    const { client } = await setup(); const recovered = recoverCheckout({ ...input, apiBaseUrl: 'https://api.test' })!; const requests: Request[] = [];
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); const { checkout_token: ignored, ...read } = stripeWire(); return json(init.method === 'GET' ? read : capability()); });
    await client.prepareClientPayment(recovered as never); await client.prepareClientPayment(recovered as never);
    expect(requests.map(request => request.method)).toEqual(['GET', 'POST', 'POST']);
    expect(requests.every(request => request.headers.get('authorization') === `Bearer ${token}`)).toBe(true);
  });
  it.each(['foreign', 'opaque', 'malformed'])('rejects %s browser return origin before exposing capability', async kind => {
    const { client, session, fetch } = await setup(); const raw = capability();
    if (kind === 'foreign') raw.return_url = 'https://foreign.test/payment/return';
    else vi.stubGlobal('location', { origin: kind === 'opaque' ? 'null' : 'https://user@merchant.test/path' });
    fetch.mockImplementation(async () => json(raw));
    await expect(client.prepareClientPayment(session as never)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });
  it('supports structurally valid return URLs with no global location in headless use', async () => {
    const { client, session, fetch } = await setup(); vi.stubGlobal('location', undefined); Reflect.deleteProperty(globalThis, 'location');
    fetch.mockImplementation(async () => json(capability()));
    expect((await client.prepareClientPayment(session as never)).clientSecret).toBe(secret);
  });
});
