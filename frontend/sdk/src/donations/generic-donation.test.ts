import { expect, it, vi } from 'vitest';
vi.mock('../providers/stripe-loader.js', () => ({ loadStripeClient: vi.fn() }));
import { stripeUiProvider, stripeCapability } from '../test/stripe-ui-fixtures.js';
import { fireEvent, getByRole, getByLabelText, queryByRole, waitFor } from '@testing-library/dom';
import userEvent from '@testing-library/user-event';
import * as sdk from '../index.js';
import { campaignWire, donationWire, donationReadWire } from '../test/donation-fixtures.js';
import { json } from '../test/fixtures.js';
import { authInput, customerWire, sessionKey } from '../test/customer-fixtures.js';
function mount(attributes: Record<string, string> = {}) {
  (sdk as any).defineGenericDonation?.(); const e = document.createElement('generic-donation');
  for (const [k, v] of Object.entries({ 'api-base-url': 'https://api.test', 'site-key': 'test-site', 'campaign-key': 'help-campaign', ...attributes })) e.setAttribute(k, v);
  document.body.append(e); return e;
}
const root = (e: HTMLElement) => { expect(e.shadowRoot, 'Donation must expose accessible isolated controls').not.toBeNull(); return e.shadowRoot! as unknown as HTMLElement; };
const ready = async (e: HTMLElement) => { await waitFor(() => expect(getByRole(root(e), 'button', { name: 'Create donation' })).toBeEnabled()); return root(e); };
const api = () => vi.stubGlobal('fetch', async (url: string) => json(url.endsWith('/checkouts') ? donationWire() : url.includes('/checkouts/') ? donationReadWire() : campaignWire(), url.endsWith('/checkouts') ? 201 : 200));
it.each(['PROCESSING', 'UNKNOWN'])('Fake Donation created %s regains original explicit confirmation after becoming actionable', async pending => {
  const p = stripeUiProvider(), requests: Request[] = [];
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(url.endsWith('/checkouts') ? { ...donationWire(), status: pending } : url.endsWith('/confirm') ? donationReadWire('SUCCEEDED') : url.includes('/checkouts/') ? donationReadWire('REQUIRES_PAYMENT_METHOD') : campaignWire(), url.endsWith('/checkouts') ? 201 : 200); });
  const e = mount(), r = await ready(e); fireEvent.click(getByRole(r, 'button', { name: 'Create donation' }));
  await waitFor(() => expect(getByRole(r, 'button', { name: 'Pay' })).toBeEnabled()); expect(requests.map(r => r.method)).toEqual(['GET', 'POST', 'GET']);
  fireEvent.click(getByRole(r, 'button', { name: 'Pay' })); await waitFor(() => expect(getByRole(r, 'status')).toHaveTextContent('Payment succeeded'));
  expect(requests.map(r => r.method)).toEqual(['GET', 'POST', 'GET', 'POST']); expect(requests[3].url).toBe('https://api.test/api/v1/public/checkouts/11111111-1111-4111-8111-111111111111/confirm'); expect(p.elements.create).not.toHaveBeenCalled();
});
const stripeDonationWire = (status = 'CREATED') => ({ ...donationWire(), status, provider_type: 'STRIPE', confirmation_mode: 'CLIENT_PROVIDER' });
const stripeDonationRead = (status = 'CREATED') => ({ ...donationReadWire(status), provider_type: 'STRIPE', confirmation_mode: 'CLIENT_PROVIDER' });
function stripeApi(backend = 'CREATED') {
  const requests: Request[] = [];
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(url.endsWith('/client-session') ? stripeCapability() : url.endsWith('/checkouts') ? stripeDonationWire() : url.includes('/checkouts/') ? stripeDonationRead(backend) : campaignWire(), url.endsWith('/checkouts') ? 201 : 200); }); return requests;
}
async function stripeReady(e: HTMLElement, p: ReturnType<typeof stripeUiProvider>) {
  const r = await ready(e); fireEvent.click(getByRole(r, 'button', { name: 'Create donation' })); await waitFor(() => expect(r.querySelector('iframe')).toBe(p.frame)); p.emit('ready'); await waitFor(() => expect(getByRole(r, 'button', { name: 'Pay' })).toBeEnabled()); return r;
}
it.each(['reject', 'throw'])('Donation initial Stripe presentation %s retains ready Pay without financial error events', async failure => {
  const p = stripeUiProvider(), requests = stripeApi('SUCCEEDED'), e = mount(), r = await ready(e), states: string[] = []; e.addEventListener('donation-state', event => states.push((event as CustomEvent).detail.status));
  fireEvent.click(getByRole(r, 'button', { name: 'Create donation' })); await waitFor(() => expect(r.querySelector('iframe')).toBe(p.frame)); const host = p.frame.parentElement;
  p.elements.update.mockImplementationOnce(() => { if (failure === 'throw') throw Error('sensitive'); return Promise.reject(Error('sensitive')); });
  e.setAttribute('locale', 'en-US'); e.dispatchEvent(new Event('payment-theme-change')); p.emit('ready');
  await waitFor(() => expect(getByRole(r, 'button', { name: 'Pay' })).toBeEnabled()); expect(p.frame.parentElement).toBe(host); expect(p.element.destroy).not.toHaveBeenCalled(); expect(states).toEqual(['CREATED']); expect(requests).toHaveLength(3);
  fireEvent.click(getByRole(r, 'button', { name: 'Pay' })); await waitFor(() => expect(getByRole(r, 'status')).toHaveTextContent('Payment succeeded')); expect(requests).toHaveLength(4);
});
it('Donation disconnect cancels initial presentation catch-up without late financial events', async () => {
  const p = stripeUiProvider(), requests = stripeApi(), e = mount(), r = await ready(e); let reject!: (reason: unknown) => void;
  fireEvent.click(getByRole(r, 'button', { name: 'Create donation' })); await waitFor(() => expect(r.querySelector('iframe')).toBe(p.frame));
  p.elements.update.mockImplementationOnce(() => new Promise<void>((_, no) => { reject = no; })); e.setAttribute('locale', 'pt-PT'); p.emit('ready');
  await waitFor(() => expect(reject).toBeTypeOf('function')); const states = vi.fn(); e.addEventListener('donation-state', states); e.remove(); reject(Error('late sensitive')); await Promise.resolve(); await Promise.resolve();
  expect(r.querySelector('iframe')).toBeNull(); expect(p.element.destroy).toHaveBeenCalledOnce(); expect(states).not.toHaveBeenCalled(); expect(requests).toHaveLength(3);
});
it('Donation expiry removes the live capability without another request', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true }); const p = stripeUiProvider(), requests = stripeApi(), e = mount(), r = await stripeReady(e, p);
  await vi.advanceTimersByTimeAsync(Date.parse(stripeCapability().expires_at) - Date.now() + 1); expect(getByRole(r, 'status')).toHaveTextContent('Checkout expired'); expect(p.element.destroy).toHaveBeenCalledOnce(); expect(requests).toHaveLength(3);
});
it('Donation later verified REQUIRES_ACTION permits only explicit same-handle action', async () => {
  const p = stripeUiProvider(); stripeApi(); const e = mount(), r = await stripeReady(e, p); fireEvent.click(getByRole(r, 'button', { name: 'Pay' })); await waitFor(() => expect(getByRole(r, 'button', { name: 'Check status' })).toBeEnabled());
  vi.stubGlobal('fetch', async () => json(stripeDonationRead('REQUIRES_ACTION'))); fireEvent.click(getByRole(r, 'button', { name: 'Check status' })); await waitFor(() => expect(getByRole(r, 'button', { name: 'Pay' })).toBeEnabled()); expect(p.stripe.confirmPayment).toHaveBeenCalledOnce();
});
it('Donation theme supersession keeps bounded presentation updates and changes no money or events', async () => {
  const p = stripeUiProvider(), requests = stripeApi(), e = mount(), r = await stripeReady(e, p); let release!: () => void; p.elements.update.mockImplementationOnce(() => new Promise<void>(resolve => { release = resolve; }));
  const states = vi.fn(); e.addEventListener('donation-state', states);
  for (const color of ['#111111', '#222222', '#333333']) { e.style.setProperty('--generic-donation-accent', color); e.dispatchEvent(new Event('payment-theme-change')); }
  expect(p.elements.update).toHaveBeenCalledOnce(); release(); await waitFor(() => expect(p.elements.update).toHaveBeenCalledTimes(2));
  expect(p.elements.update).toHaveBeenLastCalledWith(expect.objectContaining({ appearance: expect.objectContaining({ variables: expect.objectContaining({ colorPrimary: '#333333' }) }) })); expect(requests).toHaveLength(3); expect(states).not.toHaveBeenCalled(); expect(r.querySelector('iframe')).toBe(p.frame);
});
it.each(['CREATED', 'REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION'])('failed Donation capability preparation can explicitly resume only the existing checkout after a safe read %s', async backend => {
  const p = stripeUiProvider(), requests: Request[] = []; let fail = true;
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); if (url.endsWith('/client-session') && fail) throw Error('offline'); return json(url.endsWith('/client-session') ? stripeCapability() : url.endsWith('/checkouts') ? stripeDonationWire() : url.includes('/checkouts/') ? stripeDonationRead(backend) : campaignWire(), url.endsWith('/checkouts') ? 201 : 200); });
  const e = mount(), r = await ready(e); fireEvent.click(getByRole(r, 'button', { name: 'Create donation' })); await waitFor(() => expect(getByRole(r, 'button', { name: 'Check status' })).toBeEnabled());
  fireEvent.click(getByRole(r, 'button', { name: 'Check status' })); await waitFor(() => expect(getByRole(r, 'button', { name: 'Resume existing payment' })).toBeEnabled()); expect(getByRole(r, 'button', { name: 'Check status' })).toBeEnabled(); expect(requests).toHaveLength(4); fail = false;
  const resume = getByRole(r, 'button', { name: 'Resume existing payment' }); resume.focus(); await userEvent.setup().keyboard('{Enter}'); await waitFor(() => expect(r.querySelector('iframe')).toBe(p.frame)); expect(p.stripe.confirmPayment).not.toHaveBeenCalled(); p.emit('ready'); await waitFor(() => expect(getByRole(r, 'button', { name: 'Pay' })).toBeEnabled()); expect(requests).toHaveLength(5); expect(e.shadowRoot!.activeElement).toBe(getByRole(r, 'button', { name: 'Pay' }));
});
it('failed explicit Donation recovery stays checking and requires another safe read', async () => {
  const p = stripeUiProvider(), requests = stripeApi(), e = mount(), r = await stripeReady(e, p); fireEvent.click(getByRole(r, 'button', { name: 'Pay' })); await waitFor(() => expect(getByRole(r, 'button', { name: 'Check status' })).toBeEnabled()); fireEvent.click(getByRole(r, 'button', { name: 'Check status' }));
  await waitFor(() => expect(getByRole(r, 'button', { name: 'Resume existing payment' })).toBeEnabled());
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); throw Error('offline'); }); fireEvent.click(getByRole(r, 'button', { name: 'Resume existing payment' })); await waitFor(() => expect(getByRole(r, 'button', { name: 'Check status' })).toBeEnabled()); expect(getByRole(r, 'status')).toHaveTextContent('Checking payment'); expect(requests).toHaveLength(6);
});
it.each(['omit', 'auto'])('mounts anonymous/identified Stripe Donation %s only after authorization and actual ready', async mode => {
  if (mode === 'auto') { vi.stubGlobal('fetch', async () => json(customerWire())); await sdk.createGenericPaymentClient({ apiBaseUrl: 'https://api.test' }).exchangeSiteToken(authInput); }
  const p = stripeUiProvider(), requests = stripeApi('SUCCEEDED'), e = mount({ 'customer-session': mode }), r = await ready(e);
  fireEvent.input(getByLabelText(r, 'Optional message'), { target: { value: 'private donor message' } }); fireEvent.click(getByRole(r, 'button', { name: 'Create donation' }));
  await waitFor(() => expect(r.querySelector('iframe')).toBe(p.frame)); expect(queryByRole(r, 'button', { name: 'Pay' })).toBeNull(); expect(getByLabelText(r, 'Optional message')).toHaveValue(''); const host = p.frame.parentElement;
  p.emit('ready'); await waitFor(() => expect(getByRole(r, 'button', { name: 'Pay' })).toBeEnabled());
  expect(new Headers(requests[1].headers).has('authorization')).toBe(mode === 'auto'); expect(await requests[1].clone().json()).toEqual({ amount_minor: 4999, message: 'private donor message' }); expect(await requests[2].clone().text()).toBe('');
  e.setAttribute('locale', 'pt-PT'); (e as HTMLElement).style.setProperty('--generic-donation-accent', '#fedcba'); e.dispatchEvent(new Event('payment-theme-change'));
  await waitFor(() => expect(p.elements.update).toHaveBeenLastCalledWith(expect.objectContaining({ locale: 'pt', appearance: expect.objectContaining({ variables: expect.objectContaining({ colorPrimary: '#fedcba' }) }) }))); expect(p.frame.parentElement).toBe(host); expect(requests).toHaveLength(3);
  if (mode === 'auto') p.stripe.confirmPayment.mockResolvedValue({ error: { type: 'card_error' } });
  fireEvent.click(getByRole(r, 'button', { name: 'Pagar' })); await waitFor(() => expect(getByRole(r, 'status')).toHaveTextContent('Pagamento concluído')); expect(requests).toHaveLength(4); expect(p.element.destroy).toHaveBeenCalledOnce();
  expect(JSON.stringify({ session: { ...sessionStorage }, local: { ...localStorage } })).not.toMatch(/pi_fixture|pk_test_fixture|private donor/);
});
it.each(['unknown', 'refresh-failure', 'unsettled'])('Donation %s checks backend without automatic confirmation or preparation replay', async kind => {
  const p = stripeUiProvider(), requests = stripeApi(), e = mount(), r = await stripeReady(e, p);
  if (kind === 'unknown') p.stripe.confirmPayment.mockRejectedValue(new Error('sensitive'));
  if (kind === 'refresh-failure') vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); throw Error('raw sensitive'); });
  fireEvent.click(getByRole(r, 'button', { name: 'Pay' })); r.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
  await waitFor(() => expect(getByRole(r, 'status')).toHaveTextContent('Checking payment')); expect(getByRole(r, 'button', { name: 'Check status' })).toBeEnabled(); expect(requests).toHaveLength(4); expect(p.stripe.confirmPayment).toHaveBeenCalledOnce();
  vi.stubGlobal('fetch', async () => json(stripeDonationRead('CREATED'))); fireEvent.click(getByRole(r, 'button', { name: 'Check status' })); await waitFor(() => expect(getByRole(r, 'button', { name: 'Check status' })).toBeEnabled());
  vi.stubGlobal('fetch', async () => json(stripeDonationRead('REQUIRES_PAYMENT_METHOD'))); fireEvent.click(getByRole(r, 'button', { name: 'Check status' })); await waitFor(() => expect(getByRole(r, 'button', { name: 'Pay' })).toBeEnabled());
});
it('Donation input decline preserves live input focus and safe state without financial failure', async () => {
  const p = stripeUiProvider(); stripeApi(); const e = mount(), r = await stripeReady(e, p), states: unknown[] = []; e.addEventListener('donation-state', e => states.push((e as CustomEvent).detail));
  p.stripe.confirmPayment.mockResolvedValue({ error: { type: 'card_error', message: 'sensitive' } }); fireEvent.click(getByRole(r, 'button', { name: 'Pay' }));
  await waitFor(() => expect(p.element.focus).toHaveBeenCalledOnce()); await waitFor(() => expect(getByRole(r, 'button', { name: 'Pay' })).toBeEnabled()); expect(e.shadowRoot!.activeElement).toBe(p.frame); expect(JSON.stringify(states)).not.toMatch(/FAILED|UNKNOWN|sensitive/);
});
it.each(['campaign-key', 'site-key', 'api-base-url', 'disconnect'])('Donation cleans Stripe pending readiness on %s and ignores stale ready', async change => {
  const p = stripeUiProvider(); stripeApi(); const e = mount(), r = await ready(e); fireEvent.click(getByRole(r, 'button', { name: 'Create donation' })); await waitFor(() => expect(r.querySelector('iframe')).toBeTruthy());
  if (change === 'disconnect') e.remove(); else e.setAttribute(change, change === 'api-base-url' ? 'https://other.test' : 'other-key'); expect(p.element.destroy).toHaveBeenCalledOnce(); p.emit('ready'); await Promise.resolve(); expect(r.querySelector('iframe')).toBeNull();
});
it('identified Donation destroys Stripe immediately on logout while anonymous omit remains mounted', async () => {
  const client = sdk.createGenericPaymentClient({ apiBaseUrl: 'https://api.test' }); vi.stubGlobal('fetch', async () => json(customerWire())); const customer = await client.exchangeSiteToken(authInput);
  const a = stripeUiProvider(); stripeApi(); const identified = mount(), ar = await stripeReady(identified, a);
  const b = stripeUiProvider(); stripeApi(); const anonymous = mount({ 'customer-session': 'omit' }), br = await stripeReady(anonymous, b);
  vi.stubGlobal('fetch', () => new Promise<Response>(() => {})); void client.logoutCustomerSession(customer).catch(() => {});
  expect(a.element.destroy).toHaveBeenCalledOnce(); expect(ar.querySelector('iframe')).toBeNull(); expect(b.element.destroy).not.toHaveBeenCalled(); expect(getByRole(br, 'button', { name: 'Pay' })).toBeEnabled();
});
it.each([
  ['decomposed accents', 'e\u0301'.repeat(251), 'é'.repeat(251), 251],
  ['exact normalized limit', 'e\u0301'.repeat(500), 'é'.repeat(500), 500],
  ['astral code points', '😀'.repeat(500), '😀'.repeat(500), 500],
])('counts and submits NFC message: %s', async (_name, message, expected, count) => {
  let body: unknown;
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
    if (!url.endsWith('/checkouts')) return json(campaignWire());
    body = JSON.parse(init.body as string); return json(donationWire(), 201);
  });
  const r = await ready(mount());
  fireEvent.input(getByLabelText(r, 'Optional message'), { target: { value: message } });
  expect(r.querySelector('#donation-count')).toHaveTextContent(`${count} / 500`);
  fireEvent.click(getByRole(r, 'button', { name: 'Create donation' }));
  await waitFor(() => expect(getByRole(r, 'button', { name: 'Pay' })).toBeEnabled());
  expect(body).toEqual({ amount_minor: 4999, message: expected });
  expect(getByLabelText(r, 'Optional message')).toHaveValue('');
});
it('rejects 501 normalized code points without creating a checkout', async () => {
  let creates = 0;
  vi.stubGlobal('fetch', async (url: string) => { if (url.endsWith('/checkouts')) creates++; return json(campaignWire()); });
  const r = await ready(mount());
  fireEvent.input(getByLabelText(r, 'Optional message'), { target: { value: 'e\u0301'.repeat(501) } });
  expect(r.querySelector('#donation-count')).toHaveTextContent('501 / 500');
  fireEvent.click(getByRole(r, 'button', { name: 'Create donation' }));
  expect(getByRole(r, 'alert')).toHaveTextContent('Use at most 500 characters.');
  expect(creates).toBe(0);
});
it.each(['INVALID_SESSION', 'SESSION_EXPIRED'])('discards owned draft and retry intent on readable %s', async code => {
  vi.stubGlobal('fetch', async () => json(customerWire()));
  await sdk.createGenericPaymentClient({ apiBaseUrl: 'https://api.test' }).exchangeSiteToken(authInput);
  const sent: { key: string; hasMessage: boolean }[] = [];
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
    if (!url.endsWith('/checkouts')) return json(campaignWire());
    sent.push({ key: new Headers(init.headers).get('Idempotency-Key')!, hasMessage: Object.hasOwn(JSON.parse(init.body as string), 'message') });
    return sent.length === 1 ? json({ code: code === 'INVALID_SESSION' ? 'AUTHENTICATION_FAILED' : code }, code === 'INVALID_SESSION' ? 401 : 400) : json(donationWire(), 201);
  });
  const r = await ready(mount());
  fireEvent.input(getByLabelText(r, 'Optional message'), { target: { value: 'owned ephemeral draft' } });
  fireEvent.click(getByRole(r, 'button', { name: 'Create donation' }));
  await waitFor(() => expect(r.querySelector('#donation-error')?.textContent).toBe('Customer session unavailable. Sign in again.'));
  expect(sessionStorage.getItem(sessionKey())).toBeNull();
  expect(getByLabelText(r, 'Optional message')).toHaveValue('');
  fireEvent.click(getByRole(r, 'button', { name: 'Create donation' }));
  await waitFor(() => expect(getByRole(r, 'button', { name: 'Pay' })).toBeEnabled());
  expect(sent.map(s => s.hasMessage)).toEqual([true, false]);
  expect(sent[1].key).not.toBe(sent[0].key);
});
it('announces processing while the common financial watcher remains pending',async()=>{
  let release!:(r:Response)=>void;
  vi.stubGlobal('fetch',async(url:string)=>url.includes('/checkouts/')&&!url.endsWith('/confirm')?new Promise<Response>(r=>release=r):json(url.endsWith('/checkouts')?donationWire():url.endsWith('/confirm')?donationReadWire():campaignWire(),url.endsWith('/checkouts')?201:200));
  const r=await ready(mount());fireEvent.click(getByRole(r,'button',{name:'Create donation'}));await waitFor(()=>expect(getByRole(r,'button',{name:'Pay'})).toBeEnabled());fireEvent.click(getByRole(r,'button',{name:'Pay'}));
  await waitFor(()=>expect(release).toBeDefined());expect(getByRole(r,'status')).toHaveTextContent('Payment is processing');release(json(donationReadWire('SUCCEEDED')));
});
it('a replay already processing clears local draft then continues the common watcher',async()=>{
  let reads=0;
  vi.stubGlobal('fetch',async(url:string)=>json(url.endsWith('/checkouts')?{...donationWire(),status:'PROCESSING'}:url.includes('/checkouts/')?(++reads,donationReadWire('SUCCEEDED')):campaignWire(),url.endsWith('/checkouts')?201:200));
  const e=mount(),r=await ready(e);const completed:Event[]=[];e.addEventListener('donation-succeeded',event=>completed.push(event));
  fireEvent.input(getByLabelText(r,'Optional message'),{target:{value:'discard after local replay'}});
  fireEvent.click(getByRole(r,'button',{name:'Create donation'}));
  await waitFor(()=>expect(getByRole(r,'status')).toHaveTextContent('Payment succeeded'));
  expect(reads).toBe(1);expect(completed).toHaveLength(1);expect(getByLabelText(r,'Optional message')).toHaveValue('');
});
it('a completed creation replay does not emit the financial-success event',async()=>{
  vi.stubGlobal('fetch',async(url:string)=>json(url.endsWith('/checkouts')?{...donationWire(),status:'SUCCEEDED'}:campaignWire(),url.endsWith('/checkouts')?201:200));
  const e=mount(),r=await ready(e);const created:Event[]=[],completed:Event[]=[];e.addEventListener('donation-created',event=>created.push(event));e.addEventListener('donation-succeeded',event=>completed.push(event));
  fireEvent.click(getByRole(r,'button',{name:'Create donation'}));await waitFor(()=>expect(created).toHaveLength(1));expect(completed).toHaveLength(0);
});
it('loads safe policy as plain text and preserves suggested order in labelled fieldset', async () => {
  api(); const e = mount(); const r = await ready(e); expect(getByRole(r, 'heading')).toHaveTextContent('Help <everyone>'); expect(r.querySelector('b')).toBeNull(); expect(getByRole(r, 'group', { name: 'Donation amount' })).toBeVisible(); expect(Array.from(r.querySelectorAll('button[data-amount]')).map(b => b.textContent)).toEqual(['€49.99', '€10.00']); expect(getByLabelText(r, 'Custom amount (minor units)')).toBeVisible(); expect(r.textContent).toContain('100–10000'); expect(getByRole(r, 'status')).toHaveAttribute('aria-live', 'polite');
});
it('enforces custom-forbidden policy and explicit anonymous policy', async () => {
  vi.stubGlobal('fetch', async () => json({ ...campaignWire(), custom_amount_allowed: false, anonymous_allowed: false })); const e = mount({ 'customer-session': 'omit' }); const r = root(e); await waitFor(() => expect(r.textContent).toContain('Sign in required')); expect(queryByRole(r, 'spinbutton')).toBeNull(); fireEvent.click(getByRole(r, 'button', { name: 'Create donation' })); await waitFor(() => expect(getByRole(r, 'alert')).toHaveTextContent('Sign in is required for this campaign.'));
});
it.each(['0', '10100', '1.5', ''])('announces invalid amount %s and focuses its labelled input', async value => {
  api(); const e = mount(); const r = await ready(e); const amount = getByLabelText(r, 'Custom amount (minor units)'); fireEvent.input(amount, { target: { value } }); fireEvent.click(getByRole(r, 'button', { name: 'Create donation' })); await waitFor(() => expect(getByRole(r, 'alert')).toHaveTextContent('Choose a permitted amount.')); expect(e.shadowRoot!.activeElement).toBe(amount); expect(amount).toHaveAttribute('aria-invalid', 'true');
});
it('counts Unicode code points, rejects 501 and focuses message', async () => {
  api(); const e = mount(); const r = await ready(e); const message = getByLabelText(r, 'Optional message'); fireEvent.input(message, { target: { value: '😀'.repeat(501) } }); expect(r.textContent).toContain('501 / 500'); fireEvent.click(getByRole(r, 'button', { name: 'Create donation' })); expect(getByRole(r, 'alert')).toHaveTextContent('Use at most 500 characters.'); expect(e.shadowRoot!.activeElement).toBe(message);
});
it('clears message immediately at LOCAL creation and emits only IDs/status before financial success', async () => {
  api(); const e = mount(); const r = await ready(e); const created: CustomEvent[] = [], states: CustomEvent[] = [], succeeded: CustomEvent[] = [];
  e.addEventListener('donation-created', ev => created.push(ev as CustomEvent)); e.addEventListener('donation-state', ev => states.push(ev as CustomEvent)); e.addEventListener('donation-succeeded', ev => succeeded.push(ev as CustomEvent));
  fireEvent.input(getByLabelText(r, 'Optional message'), { target: { value: 'distinctive <b>donor</b> message' } }); fireEvent.click(getByRole(r, 'button', { name: 'Create donation' })); await waitFor(() => expect(getByRole(r, 'button', { name: 'Pay' })).toBeEnabled());
  expect(getByLabelText(r, 'Optional message')).toHaveValue(''); expect(e.shadowRoot!.activeElement).toBe(getByRole(r, 'button', { name: 'Pay' })); expect(created).toHaveLength(1); expect(succeeded).toHaveLength(0); expect(created[0].detail).toEqual({ donationId: '44444444-4444-4444-8444-444444444444', paymentId: '22222222-2222-4222-8222-222222222222', checkoutId: '11111111-1111-4111-8111-111111111111', status: 'CREATED' }); expect(created[0].bubbles && created[0].composed).toBe(true); expect(Object.isFrozen(created[0].detail)).toBe(true);
  expect(JSON.stringify({ session: { ...sessionStorage }, local: { ...localStorage }, events: states.map(e => e.detail) })).not.toMatch(/distinctive|donor|checkoutToken|sessionToken/);
  vi.stubGlobal('fetch', async () => json(donationReadWire('SUCCEEDED'))); fireEvent.click(getByRole(r, 'button', { name: 'Pay' })); await waitFor(() => expect(succeeded).toHaveLength(1)); expect(getByRole(r, 'status')).toHaveTextContent('Payment succeeded');
});
it('suppresses pending duplicates and reuses intent key on transport retry', async () => {
  let release!: () => void; const pending = new Promise<void>(r => release = r); const keys: string[] = [];
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { if (!url.endsWith('/checkouts')) return json(campaignWire()); keys.push(new Headers(init.headers).get('Idempotency-Key')!); if (keys.length === 1) { await pending; throw new TypeError('offline'); } return json(donationWire(), 201); });
  const r = await ready(mount()); const button = getByRole(r, 'button', { name: 'Create donation' }); fireEvent.click(button); expect(button).toBeDisabled(); fireEvent.click(button); expect(keys).toHaveLength(1); release(); await waitFor(() => expect(button).toBeEnabled()); fireEvent.click(button); await waitFor(() => expect(getByRole(r, 'button', { name: 'Pay' })).toBeEnabled()); expect(keys).toHaveLength(2); expect(keys[1]).toBe(keys[0]);
});
it('supports keyboard selection, submission, locale and isolated design tokens', async () => {
  let sentAmount:number|undefined;
  vi.stubGlobal('fetch',async(url:string,init:RequestInit)=>{if(!url.endsWith('/checkouts'))return json(campaignWire());sentAmount=JSON.parse(init.body as string).amount_minor;return json({...donationWire(),amount_minor:sentAmount},201);});
  const e = mount({ locale: 'pt-PT' }); const r = root(e); await waitFor(() => expect(getByRole(r, 'button', { name: 'Criar donativo' })).toBeEnabled()); expect(getByRole(r, 'group', { name: 'Valor do donativo' })).toBeVisible(); expect(r.querySelector('style')!.textContent).toContain('--generic-donation-accent');
  const user = userEvent.setup(); const amount = r.querySelectorAll<HTMLButtonElement>('button[data-amount]')[1]; amount.focus(); await user.keyboard('{Enter}'); expect(amount).toHaveAttribute('aria-pressed', 'true'); const submit = getByRole(r, 'button', { name: 'Criar donativo' }); submit.focus(); await user.keyboard('{Enter}'); await waitFor(() => expect(e.shadowRoot!.activeElement).toBe(getByRole(r, 'button', { name: 'Pagar' })));
  expect(sentAmount).toBe(1000);
});
it.each(['campaign', 'create', 'watch'])('disconnect aborts owned %s work and clears donor field', async phase => {
  let signal: AbortSignal | null | undefined; let release!: (r: Response) => void;
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { if ((phase === 'campaign' && !url.endsWith('/checkouts')) || (phase === 'create' && url.endsWith('/checkouts')) || (phase === 'watch' && url.includes('/checkouts/') && !url.endsWith('/confirm'))) { signal = init.signal; return new Promise<Response>(r => release = r); } return json(url.endsWith('/checkouts') ? donationWire() : url.endsWith('/confirm') ? donationReadWire() : campaignWire(), url.endsWith('/checkouts') ? 201 : 200); });
  const e = mount(); if (phase !== 'campaign') { const r = await ready(e); fireEvent.input(getByLabelText(r, 'Optional message'), { target: { value: 'ephemeral' } }); fireEvent.click(getByRole(r, 'button', { name: 'Create donation' })); if (phase === 'watch') { await waitFor(() => expect(getByRole(r, 'button', { name: 'Pay' })).toBeEnabled()); fireEvent.click(getByRole(r, 'button', { name: 'Pay' })); } }
  await waitFor(() => expect(signal).toBeDefined()); e.remove(); expect(signal!.aborted).toBe(true); expect(getByLabelText(root(e), 'Optional message')).toHaveValue(''); release(json(phase === 'campaign' ? campaignWire() : phase === 'create' ? donationWire() : donationReadWire(), phase === 'create' ? 201 : 200));
});
it.each(['campaign-success', 'campaign-failure', 'create-success', 'create-failure', 'create-auth-failure', 'watch-success', 'watch-failure'])('stale A %s cannot mutate current B controls or emit events', async phase => {
  let release!: (r: Response) => void, reject!: (e: Error) => void; let held = false;
  vi.stubGlobal('fetch', async (url: string) => { const take = !held && (phase.startsWith('campaign') ? url.includes('help-campaign') && !url.endsWith('checkouts') : phase.startsWith('create') ? url.endsWith('/checkouts') : url.includes('/checkouts/') && !url.endsWith('/confirm')); if (take) { held = true; return new Promise<Response>((r, j) => { release = r; reject = j; }); } return json(url.endsWith('/checkouts') ? donationWire() : url.endsWith('/confirm') ? donationReadWire() : { ...campaignWire(), key: url.includes('other-campaign') ? 'other-campaign' : 'help-campaign', display_name: url.includes('other-campaign') ? 'Campaign B' : 'Help <everyone>' }, url.endsWith('/checkouts') ? 201 : 200); });
  const e = mount(); if (!phase.startsWith('campaign')) { let r = await ready(e); fireEvent.click(getByRole(r, 'button', { name: 'Create donation' })); if (phase.startsWith('watch')) { await waitFor(() => expect(getByRole(r, 'button', { name: 'Pay' })).toBeEnabled()); fireEvent.click(getByRole(r, 'button', { name: 'Pay' })); } }
  await waitFor(() => expect(held).toBe(true)); e.setAttribute('campaign-key', 'other-campaign'); const r = await ready(e); fireEvent.input(getByLabelText(r, 'Optional message'), { target: { value: 'B draft' } }); const events: Event[] = []; e.addEventListener('donation-created', ev => events.push(ev)); e.addEventListener('donation-state', ev => events.push(ev));
  if (phase === 'create-auth-failure') release(json({code:'AUTHENTICATION_FAILED'},401));
  else if (phase.endsWith('failure')) reject(new TypeError('old failure')); else release(json(phase.startsWith('campaign') ? campaignWire() : phase.startsWith('create') ? donationWire() : donationReadWire('SUCCEEDED'), phase.startsWith('create') ? 201 : 200));
  await new Promise(r => setTimeout(r, 0)); expect(getByRole(r, 'heading')).toHaveTextContent('Campaign B'); expect(getByLabelText(r, 'Optional message')).toHaveValue('B draft'); expect(getByRole(r, 'button', { name: 'Create donation' })).toBeEnabled(); expect(events).toHaveLength(0);
});
