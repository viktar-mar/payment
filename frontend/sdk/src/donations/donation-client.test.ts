import { expect, it, vi } from 'vitest';
import { createGenericPaymentClient } from '../client.js';
import { base, authInput, customerWire, customerToken, sessionKey, json } from '../test/customer-fixtures.js';
import { campaignWire, donationInput, donationWire, donationReadWire, donationHistoryWire } from '../test/donation-fixtures.js';
const client = (): any => createGenericPaymentClient({ apiBaseUrl: base });
it.each([
  ['decomposed accents', 'e\u0301'.repeat(251), 'é'.repeat(251)],
  ['exact normalized limit', 'e\u0301'.repeat(500), 'é'.repeat(500)],
  ['astral code points', '😀'.repeat(500), '😀'.repeat(500)],
])('submits NFC message using code points: %s', async (_name, message, expected) => {
  let body: unknown;
  vi.stubGlobal('fetch', async (_url: string, init: RequestInit) => { body = JSON.parse(init.body as string); return json(donationWire(), 201); });
  const input = { ...donationInput, message };
  await client().createDonationCheckout(input);
  expect(body).toEqual({ amount_minor: 4999, message: expected });
  expect(input.message).toBe(message);
});
it('rejects 501 NFC code points before networking', async () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch);
  await expect(client().createDonationCheckout({ ...donationInput, message: 'e\u0301'.repeat(501) })).rejects.toMatchObject({ code: 'INVALID_CONFIGURATION' });
  expect(fetch).not.toHaveBeenCalled();
});
it.each(['INVALID_SESSION', 'SESSION_EXPIRED'])('discards generated retry key but not caller input on readable %s', async code => {
  vi.stubGlobal('fetch', async () => json(customerWire()));
  const c = client(); await c.exchangeSiteToken(authInput);
  const keys: string[] = [], authorities: boolean[] = [];
  vi.stubGlobal('fetch', async (_url: string, init: RequestInit) => {
    keys.push(new Headers(init.headers).get('Idempotency-Key')!); authorities.push(new Headers(init.headers).has('Authorization'));
    return keys.length === 1 ? json({ code: code === 'INVALID_SESSION' ? 'AUTHENTICATION_FAILED' : code }, code === 'INVALID_SESSION' ? 401 : 400) : json(donationWire(), 201);
  });
  const input = { ...donationInput, message: '<b>caller-owned</b>\nplain text' };
  await expect(c.createDonationCheckout(input)).rejects.toMatchObject({ code });
  expect(keys).toHaveLength(1); expect(sessionStorage.getItem(sessionKey())).toBeNull();
  await c.createDonationCheckout(input);
  expect(keys[1]).not.toBe(keys[0]); expect(authorities).toEqual([true, false]);
  expect(input.message).toBe('<b>caller-owned</b>\nplain text');
});
it('accepts the current financial state on an exact completed creation replay',async()=>{
  vi.stubGlobal('fetch',async()=>json({...donationWire(),status:'PROCESSING'},201));
  await expect(client().createDonationCheckout({...donationInput,idempotencyKey:'same-live-intent'})).resolves.toMatchObject({state:{status:'PROCESSING'}});
});
it.each(['x','k'.repeat(255)])('accepts server-valid explicit idempotency key length', async idempotencyKey => {
  vi.stubGlobal('fetch', async () => json(donationWire(),201));
  await expect(client().createDonationCheckout({...donationInput,idempotencyKey})).resolves.toBeDefined();
});
it('keeps Donation checkout memory-only while restored Customer login and catalog storage remain distinct',async()=>{
  sessionStorage.setItem('generic-payments:checkout:unrelated','catalog-owned');
  vi.stubGlobal('fetch',async(url:string)=>json(url.includes('customer-auth')?customerWire():url.includes('/sites/')?donationWire():donationReadWire(),url.includes('/sites/')?201:200));
  const c=client();await c.exchangeSiteToken(authInput);const s=await c.createDonationCheckout(donationInput);await c.get(s);c.clear(s);
  expect(sessionStorage.getItem('generic-payments:checkout:unrelated')).toBe('catalog-owned');
  expect(Object.keys(sessionStorage)).toHaveLength(2);expect(sessionStorage.getItem(sessionKey())).not.toBeNull();
});
it('accepts server-valid policies without invented string or option-count limits', async () => {
  vi.stubGlobal('fetch', async () => json({ ...campaignWire(), display_name: 'x'.repeat(201), purpose_description: 'x'.repeat(2001), suggested_amounts: Array.from({ length: 21 }, (_, i) => 100 + i) }));
  expect((await client().getDonationCampaign({ siteKey: 'test-site', campaignKey: 'help-campaign' })).suggestedAmounts).toHaveLength(21);
});
it('rejects a create response for a different amount', async () => {
  vi.stubGlobal('fetch', async () => json({ ...donationWire(), amount_minor: 100 }, 201));
  await expect(client().createDonationCheckout(donationInput)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
it('loads ordered public policy without sending Customer authority', async () => {
  const sent: Request[] = []; vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { sent.push(new Request(url, init)); return json(campaignWire()); });
  const c = await client().getDonationCampaign({ siteKey: 'test-site', campaignKey: 'help-campaign' });
  expect(c).toEqual({ campaignKey: 'help-campaign', displayName: 'Help <everyone>', purposeDescription: '<b>Plain text</b>', currency: 'EUR', suggestedAmounts: [4999, 1000], minimumAmount: 100, maximumAmount: 10000, customAmountAllowed: true, anonymousAllowed: true });
  expect(sent[0].url).toBe(base + '/api/v1/public/sites/test-site/donation-campaigns/help-campaign'); expect(sent[0].headers.has('authorization')).toBe(false);
});
it.each([{ secret: 'bad' }, { suggested_amounts: [1000, 1000] }, { minimum_amount: 0 }, { maximum_amount: 99 }, { currency: 'eur' }, { custom_amount_allowed: false, suggested_amounts: [] }, { key: 'other-campaign' }, { display_name: 3 }])('rejects malformed or unexpected public policy %j', async patch => {
  vi.stubGlobal('fetch', async () => json({ ...campaignWire(), ...patch })); await expect(client().getDonationCampaign({ siteKey: 'test-site', campaignKey: 'help-campaign' })).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
it('sends exact body and reuses generated intent key after ambiguous transport', async () => {
  const sent: Request[] = []; vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { sent.push(new Request(url, init)); if (sent.length === 1) throw new TypeError('offline'); return json(donationWire(), 201); });
  const c = client(); const intent = { ...donationInput, message: '😀'.repeat(500) }; await expect(c.createDonationCheckout(intent)).rejects.toMatchObject({ code: 'TRANSPORT_UNAVAILABLE' });
  const s = await c.createDonationCheckout(intent);
  expect(sent[1].url).toBe(base + '/api/v1/public/sites/test-site/donation-campaigns/help-campaign/checkouts'); expect(await sent[1].json()).toEqual({ amount_minor: 4999, message: '😀'.repeat(500) });
  expect(sent[0].headers.get('Idempotency-Key')).toMatch(/^[a-f0-9-]{36}$/); expect(sent[1].headers.get('Idempotency-Key')).toBe(sent[0].headers.get('Idempotency-Key'));
  expect(s.donation).toEqual({ donationId: '44444444-4444-4444-8444-444444444444', campaignKey: 'help-campaign', campaignVersion: 2 }); expect(s.state.status).toBe('CREATED'); expect(JSON.stringify(s)).not.toContain('😀');
});
it('uses explicit idempotency and leaves input ownership to caller', async () => {
  const sent: Request[] = []; vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { sent.push(new Request(url, init)); return json(donationWire(), 201); });
  const i = { ...donationInput, message: '', idempotencyKey: 'donation-intent-001' }; await client().createDonationCheckout(i); expect(sent[0].headers.get('Idempotency-Key')).toBe('donation-intent-001'); expect(await sent[0].json()).toEqual({ amount_minor: 4999, message: '' }); expect(i.message).toBe('');
});
it.each([{ amountMinor: 0 }, { amountMinor: 1.5 }, { amountMinor: Number.MAX_SAFE_INTEGER + 1 }, { message: '😀'.repeat(501) }, { currency: 'EUR' }, { customerId: 'bad' }, { provider: 'FAKE' }, { tax: 0 }, { customerSession: 'invalid' }, { idempotencyKey: 'bad\nkey' }, { signal: {} }])('rejects unsafe create input before fetch %j', async patch => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); await expect(client().createDonationCheckout({ ...donationInput, ...patch })).rejects.toMatchObject({ code: 'INVALID_CONFIGURATION' }); expect(fetch).not.toHaveBeenCalled();
});
it('attaches same-context Customer and intentionally omits it before anonymous creation', async () => {
  const sent: Request[] = []; vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { sent.push(new Request(url, init)); return json(url.includes('customer-auth') ? customerWire() : donationWire(), url.includes('checkouts') ? 201 : 200); });
  const c = client(); await c.exchangeSiteToken(authInput); await c.createDonationCheckout({ ...donationInput, customerSession: 'auto' }); await c.createDonationCheckout({ ...donationInput, customerSession: 'omit' }); expect(sent.map(r => r.headers.get('authorization'))).toEqual([null, `Bearer ${customerToken}`, null]);
});
it.each(['corrupt', 'expired', 'cross-site', 'origin', 'anonymized'])('fails closed on %s stored authority with zero anonymous retries', async kind => {
  vi.stubGlobal('fetch', async () => json(customerWire())); const c = client(); await c.exchangeSiteToken(authInput); const raw = JSON.parse(sessionStorage.getItem(sessionKey())!);
  if (kind === 'expired') raw.expiresAt = '2000-01-01T00:00:00Z'; if (kind === 'cross-site') raw.siteKey = 'other-site'; if (kind === 'origin') raw.origin = 'https://other.test'; if (kind === 'anonymized') raw.customer.status = 'ANONYMIZED'; sessionStorage.setItem(sessionKey(), kind === 'corrupt' ? '{}' : JSON.stringify(raw));
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); await expect(c.createDonationCheckout(donationInput)).rejects.toMatchObject({ code: kind === 'expired' ? 'SESSION_EXPIRED' : 'INVALID_SESSION' }); expect(fetch).not.toHaveBeenCalled();
});
it('returns real strict donation history and opaque cursor with authenticated scope', async () => {
  vi.stubGlobal('fetch', async () => json(customerWire())); const c = client(); const s = await c.exchangeSiteToken(authInput); const sent: Request[] = [];
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { sent.push(new Request(url, init)); return json({ items: [donationHistoryWire()], next_cursor: 'opaque+cursor' }); });
  expect(await c.listCustomerDonations(s, { limit: 100, cursor: 'a+b' })).toEqual({ items: [{ donationId: '44444444-4444-4444-8444-444444444444', paymentId: '22222222-2222-4222-8222-222222222222', campaignId: '55555555-5555-4555-8555-555555555555', campaignKey: 'help-campaign', campaignVersion: 2, amountMinor: 4999, currency: 'EUR', status: 'SUCCEEDED', messageState: 'PRESENT', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-02T00:00:00Z' }], nextCursor: 'opaque+cursor' }); expect(sent[0].url).toBe(base + '/api/v1/customer/donations?limit=100&cursor=a%2Bb'); expect(sent[0].headers.get('authorization')).toBe(`Bearer ${customerToken}`);
  vi.stubGlobal('fetch', async () => json({ items: [{ ...donationHistoryWire(), message: 'secret' }], next_cursor: null })); await expect(c.listCustomerDonations(s)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
});
it('continues Donation through common confirm/read/watch and aborts watcher', async () => {
  vi.stubGlobal('fetch', async (url: string) => json(url.includes('/sites/') ? donationWire() : donationReadWire(), url.includes('/sites/') ? 201 : 200)); const c = client(); const s = await c.createDonationCheckout(donationInput); expect((await c.confirm(s)).status).toBe('PROCESSING'); const a = new AbortController(); a.abort(); await expect(c.watch(s, { signal: a.signal })[Symbol.asyncIterator]().next()).rejects.toMatchObject({ name: 'AbortError' }); c.clear(s);
});
it.each(['oversize', 'redirect', 'shape', '401'])('rejects %s without retry or echoed private error', async kind => {
  let count = 0; vi.stubGlobal('fetch', async () => { ++count; return kind === 'oversize' ? new Response(' '.repeat(32769)) : kind === 'redirect' ? new Response('', { status: 302 }) : kind === '401' ? json({ message: 'private text' }, 401) : json({ ...donationWire(), message: 'private text' }, 201); });
  await expect(client().createDonationCheckout(donationInput)).rejects.toBeInstanceOf(Error); expect(count).toBe(1);
});
