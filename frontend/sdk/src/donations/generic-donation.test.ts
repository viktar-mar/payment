import { expect, it, vi } from 'vitest';
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
