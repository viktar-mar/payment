import { expect, it, vi } from 'vitest';
import { fireEvent, getByLabelText, getByRole, queryByRole, waitFor } from '@testing-library/dom';
import userEvent from '@testing-library/user-event';
import * as sdk from '../index.js';
import type { GenericPaymentClient } from '../contracts.js';
import type { CustomerSession } from '../customer/contracts.js';
import { base, json } from '../test/customer-fixtures.js';
import { actionWire, billingWire, eventWire, mutationWire, offersWire, operationId, operationWire, productId, quoteWire, subscriptionId, subscriptionPriceId, subscriptionSession, subscriptionWire } from '../test/subscription-fixtures.js';

interface Manager extends HTMLElement { client?: GenericPaymentClient; customerSession?: CustomerSession; productId?: string; priceId?: string; locale: 'en' | 'pt'; }
function mount(session: CustomerSession | null = subscriptionSession()): Manager {
  (sdk as unknown as { registerGenericSubscriptionManager?: () => void }).registerGenericSubscriptionManager?.();
  const element = document.createElement('generic-subscription-manager') as Manager;
  element.client = sdk.createGenericPaymentClient({ apiBaseUrl: base }); element.customerSession = session ?? undefined;
  element.productId = productId; element.priceId = subscriptionPriceId; document.body.append(element); return element;
}
function root(element: Manager): HTMLElement { return (element.shadowRoot ?? element) as unknown as HTMLElement; }
function server(view: unknown = subscriptionWire()) {
  const seen: Request[] = [];
  const fetch = vi.fn(async (url: string, init: RequestInit) => {
    const request = new Request(url, init); seen.push(request);
    if (url.includes('/offers')) return json(view === null ? { items: offersWire().items.map(offer => ({ ...offer, direction: null, allowed_timings: [] })) } : offersWire());
    if (url.includes('/billing-records')) return json({ items: [billingWire()], next_cursor: null });
    if (url.includes('/events')) return json({ items: [eventWire()], next_cursor: null });
    if (url.includes('/action-confirmations')) return json({ ...mutationWire(), status: 'SUCCEEDED' }, 202);
    if (url.endsWith('/action')) return json(actionWire());
    if (url.includes('/operations/')) return json(operationWire());
    if (url.includes('/change-preview')) return json(quoteWire());
    if (request.method === 'POST') return json({ ...mutationWire(), status: 'REQUIRES_ACTION', action_available: true, next_action: actionWire() }, 202);
    if (url.includes('/subscriptions?')) return json({ items: view ? [view] : [], next_cursor: null });
    return json(view);
  }); vi.stubGlobal('fetch', fetch); return { seen, fetch };
}
async function ready(element: Manager) { await waitFor(() => expect(getByRole(root(element), 'button', { name: 'Refresh status' })).toBeEnabled()); }

it.each(['local validation', 'allowlist rejection', 'transport failure', 'navigation failure'] as const)(
  'restores authoritative controls after Portal %s without inventing financial UNKNOWN', async failure => {
    const { seen, fetch } = server(); const respond = fetch.getMockImplementation()!;
    const assign = vi.fn(() => { throw new Error('navigation unavailable'); });
    vi.stubGlobal('location', { origin: location.origin, href: failure === 'local validation' ? 'http://external.test/manage' : 'https://shop.test/manage', assign });
    fetch.mockImplementation(async (url: string, init: RequestInit) => {
      if (!url.endsWith('/portal-sessions')) return respond(url, init);
      seen.push(new Request(url, init));
      if (failure === 'allowlist rejection') return json({ code: 'CONFLICT' }, 409);
      if (failure === 'transport failure') throw new Error('network unavailable');
      return json({ navigation_url: `https://shop.test/manage?subscriptionPortal=simulated&operationId=${operationId}`, expires_at: new Date(Date.now() + 240_000).toISOString(), simulated: true }, 201);
    });
    const element = mount(); await ready(element); const states: { state: string; operationId: string | null }[] = []; const errors: unknown[] = [];
    element.addEventListener('generic-subscription-state', event => states.push((event as CustomEvent).detail));
    element.addEventListener('generic-subscription-error', event => errors.push((event as CustomEvent).detail));
    fireEvent.click(getByRole(root(element), 'button', { name: 'Open customer portal' }));
    await waitFor(() => expect(errors).toHaveLength(1)); await ready(element);
    fireEvent.click(getByRole(root(element), 'button', { name: 'Refresh status' })); await ready(element);
    expect(queryByRole(root(element), 'button', { name: 'Update payment method' })).not.toBeNull();
    expect(queryByRole(root(element), 'button', { name: 'Cancel at period end' })).not.toBeNull();
    expect(states.every(state => state.state !== 'unknown' && state.operationId === null)).toBe(true);
    expect(seen.filter(request => request.method === 'POST').map(request => new URL(request.url).pathname.split('/').at(-1)))
      .toEqual(failure === 'local validation' ? [] : ['portal-sessions']);
    expect(assign).toHaveBeenCalledTimes(failure === 'navigation failure' ? 1 : 0);
  });

it('shows login requirement and sends no requests without explicit authority', async () => {
  const { seen } = server(); const element = mount(null);
  expect(root(element).textContent).toContain('Sign in to manage subscriptions.'); expect(seen).toHaveLength(0);
});
it('ignores a late list completion after logout and aborts the old request', async () => {
  let finish!: (r: Response) => void; let signal: AbortSignal | null | undefined;
  vi.stubGlobal('fetch', (url: string, init: RequestInit) => url.includes('/offers') ? Promise.resolve(json(offersWire())) : (signal = init.signal, new Promise<Response>(r => { finish = r; })));
  const element = mount(); await waitFor(() => expect(finish).toBeTypeOf('function'));
  element.customerSession = undefined; finish(json({ items: [subscriptionWire()], next_cursor: null }));
  await waitFor(() => expect(root(element).textContent).toContain('Sign in to manage subscriptions.'));
  expect(signal?.aborted).toBe(true); expect(root(element).textContent).not.toContain(subscriptionId);
});
it('loads exact Product offers, billing records and lifecycle history separately', async () => {
  const { seen } = server(); const element = mount(); await ready(element);
  expect(seen.some(r => r.url.includes(`/subscription-products/${productId}/offers`))).toBe(true);
  expect(seen.some(r => r.url.includes('/billing-records'))).toBe(true); expect(seen.some(r => r.url.includes('/events'))).toBe(true);
  expect(root(element).textContent).toContain('Billing tier'); expect(root(element).textContent).toContain('Entitled tier');
  expect(root(element).textContent).toContain('CONFIRMED_CASH'); expect(root(element).textContent).toContain('PERIOD_PAID');
});
it('requires consent before create and emits only frozen safe operation metadata', async () => {
  const { seen } = server(null); const element = mount(); await ready(element); const events: unknown[] = [];
  element.addEventListener('generic-subscription-state', e => events.push((e as CustomEvent).detail));
  fireEvent.click(getByRole(root(element), 'button', { name: 'Create subscription' }));
  expect(seen.filter(r => r.method === 'POST')).toHaveLength(0);
  expect(element.shadowRoot?.activeElement).toBe(getByLabelText(root(element), 'I consent to recurring payments'));
  fireEvent.click(getByLabelText(root(element), 'I consent to recurring payments'));
  fireEvent.click(getByRole(root(element), 'button', { name: 'Create subscription' }));
  await waitFor(() => expect(getByRole(root(element), 'button', { name: 'Complete Fake action' })).toBeEnabled());
  expect(seen.filter(r => r.method === 'POST')).toHaveLength(1);
  expect(events.every(e => Object.isFrozen(e) && Object.keys(e as object).sort().join(',') === 'operationId,revision,state,subscriptionId')).toBe(true);
  expect(JSON.stringify(events)).not.toContain('secret'); expect(element.shadowRoot?.innerHTML).not.toContain(actionWire().secret);
});
it('reopens an unresolved operation through safe status/action reads without a financial POST', async () => {
  const { seen } = server({ ...subscriptionWire(), pending_operation: { operation_id: operationId, kind: 'SETUP', status: 'REQUIRES_ACTION' } });
  const element = mount(); await ready(element);
  expect(seen.some(r => r.url.endsWith(`/operations/${operationId}`))).toBe(true);
  expect(seen.filter(r => r.method === 'POST')).toHaveLength(0);
  expect(queryByRole(root(element), 'button', { name: 'Update payment method' })).toBeNull();
});
it('keeps UNKNOWN on read-only refresh and does not offer another financial command', async () => {
  const { seen, fetch } = server({ ...subscriptionWire(), pending_operation: { operation_id: operationId, kind: 'CREATE', status: 'UNKNOWN' } });
  fetch.mockImplementation(async (url: string, init: RequestInit) => { seen.push(new Request(url, init)); if (url.includes('/offers')) return json(offersWire()); if (url.includes('/operations/')) return json({ ...operationWire(), status: 'UNKNOWN' }); if (url.includes('/subscriptions?')) return json({ items: [{ ...subscriptionWire(), pending_operation: { operation_id: operationId, kind: 'CREATE', status: 'UNKNOWN' } }], next_cursor: null }); return json({ items: [], next_cursor: null }); });
  const element = mount(); await ready(element); expect(root(element).textContent).toContain('The result is not confirmed.');
  fireEvent.click(getByRole(root(element), 'button', { name: 'Refresh status' })); await ready(element);
  expect(seen.every(r => r.method === 'GET')).toBe(true); expect(queryByRole(root(element), 'button', { name: 'Create subscription' })).toBeNull();
});
it('reconciles the original UNKNOWN create operation even before the list exposes its pointer', async () => {
  const { seen, fetch } = server(null);
  fetch.mockImplementation(async (url: string, init: RequestInit) => {
    const request = new Request(url, init); seen.push(request);
    if (url.includes('/offers')) return json(offersWire());
    if (url.includes('/operations/')) return json({ ...operationWire(), status: 'UNKNOWN' });
    if (request.method === 'POST') return json({ ...mutationWire(), status: 'UNKNOWN' }, 202);
    return json({ items: [], next_cursor: null });
  });
  const element = mount(); await ready(element);
  fireEvent.click(getByLabelText(root(element), 'I consent to recurring payments'));
  fireEvent.click(getByRole(root(element), 'button', { name: 'Create subscription' }));
  await waitFor(() => expect(root(element).textContent).toContain('The result is not confirmed.'));
  fireEvent.click(getByRole(root(element), 'button', { name: 'Refresh status' })); await ready(element);
  expect(seen.some(r => r.method === 'GET' && r.url.endsWith(`/operations/${operationId}`))).toBe(true);
  expect(seen.filter(r => r.method === 'POST')).toHaveLength(1);
  expect(queryByRole(root(element), 'button', { name: 'Create subscription' })).toBeNull();
});
it('does not offer create when a failed list read has not established that the Product slot is empty', async () => {
  const { fetch } = server(null);
  fetch.mockImplementation(async (url: string) => { if (url.includes('/offers')) return json(offersWire()); throw new Error('network unavailable'); });
  const element = mount(); await ready(element);
  expect(queryByRole(root(element), 'button', { name: 'Create subscription' })).toBeNull();
  expect(element.shadowRoot?.activeElement).toBe(getByRole(root(element), 'alert'));
});
it('changes locale without requests, losing confirmation or adding a financial intent', async () => {
  const { seen } = server(null); const element = mount(); await ready(element);
  fireEvent.click(getByLabelText(root(element), 'I consent to recurring payments')); const count = seen.length; element.locale = 'pt';
  expect(root(element).textContent).toContain('Simulação Fake local'); expect(getByLabelText(root(element), 'Autorizo pagamentos recorrentes')).toBeChecked(); expect(seen).toHaveLength(count);
});
it('previews by keyboard and applies only after separate explicit confirmation', async () => {
  const { seen } = server(); const element = mount(); await ready(element); const user = userEvent.setup();
  fireEvent.change(getByLabelText(root(element), 'Change timing'), { target: { value: 'NEXT_PERIOD' } });
  const button = getByRole(root(element), 'button', { name: 'Preview plan change' }); button.focus(); await user.keyboard('{Enter}');
  await waitFor(() => expect(getByRole(root(element), 'button', { name: 'Apply plan change' })).toBeEnabled());
  expect(element.shadowRoot?.activeElement).toBe(getByLabelText(root(element), 'I confirm this plan change'));
  expect(seen.filter(r => r.method === 'POST').map(r => new URL(r.url).pathname.split('/').at(-1))).toEqual(['change-preview']);
  fireEvent.click(getByRole(root(element), 'button', { name: 'Apply plan change' })); expect(seen.filter(r => r.url.endsWith('/changes'))).toHaveLength(0);
  fireEvent.click(getByLabelText(root(element), 'I confirm this plan change')); fireEvent.click(getByRole(root(element), 'button', { name: 'Apply plan change' }));
  await waitFor(() => expect(seen.filter(r => r.url.endsWith('/changes'))).toHaveLength(1));
});
it('explains cancellation date/refund and requires a separate confirmation', async () => {
  const { seen } = server(); const element = mount(); await ready(element); const user = userEvent.setup();
  getByRole(root(element), 'button', { name: 'Cancel at period end' }).focus(); await user.keyboard('{Enter}');
  expect(root(element).textContent).toContain('Cancellation does not automatically refund earlier payments.'); expect(root(element).textContent).toContain('2026');
  fireEvent.click(getByRole(root(element), 'button', { name: 'Confirm cancellation' })); expect(seen.filter(r => r.method === 'POST')).toHaveLength(0);
  fireEvent.click(getByLabelText(root(element), 'I confirm cancellation')); fireEvent.click(getByRole(root(element), 'button', { name: 'Confirm cancellation' }));
  await waitFor(() => expect(seen.filter(r => r.url.endsWith('/cancellation'))).toHaveLength(1));
});
it.each([
  ['SUCCEEDED', '/offers'], ['SUCCEEDED', '/subscriptions?'], ['SUCCEEDED', '/billing-records'], ['SUCCEEDED', '/events'],
  ['FAILED', '/offers'], ['FAILED', '/subscriptions?'], ['FAILED', '/billing-records'], ['FAILED', '/events'],
] as const)('restores permitted controls after known terminal %s and a transient %s read failure without retrying the command', async (status, readPath) => {
  const { seen, fetch } = server(); const respond = fetch.getMockImplementation()!;
  let posted = false, failedRead = false;
  fetch.mockImplementation(async (url: string, init: RequestInit) => {
    const request = new Request(url, init);
    if (request.method === 'POST') { seen.push(request); posted = true; return json({ ...mutationWire(), status }, 202); }
    if (posted && !failedRead && url.includes(readPath)) { seen.push(request); failedRead = true; throw new Error('transient projection read failure'); }
    return respond(url, init);
  });
  const element = mount(); await ready(element);
  fireEvent.click(getByRole(root(element), 'button', { name: 'Cancel at period end' }));
  fireEvent.click(getByLabelText(root(element), 'I confirm cancellation'));
  fireEvent.click(getByRole(root(element), 'button', { name: 'Confirm cancellation' }));
  await waitFor(() => expect(failedRead).toBe(true)); await ready(element);
  expect(root(element).textContent).toContain(status);
  fireEvent.click(getByRole(root(element), 'button', { name: 'Refresh status' })); await ready(element);
  expect(queryByRole(root(element), 'button', { name: 'Update payment method' })).not.toBeNull();
  expect(queryByRole(root(element), 'button', { name: 'Preview plan change' })).not.toBeNull();
  expect(root(element).textContent).not.toContain('The result is not confirmed.');
  expect(seen.filter(r => r.method === 'POST').map(r => new URL(r.url).pathname.split('/').at(-1))).toEqual(['cancellation']);
});
it('discards a cancellation confirmation when refreshed server permissions withdraw the action', async () => {
  const { seen, fetch } = server(); const element = mount(); await ready(element);
  fireEvent.click(getByRole(root(element), 'button', { name: 'Cancel at period end' }));
  fireEvent.click(getByLabelText(root(element), 'I confirm cancellation'));
  fetch.mockImplementation(async (url: string, init: RequestInit) => {
    seen.push(new Request(url, init));
    if (url.includes('/offers')) return json(offersWire());
    if (url.includes('/subscriptions?')) return json({ items: [{ ...subscriptionWire(), permitted_actions: [] }], next_cursor: null });
    return json({ items: [], next_cursor: null });
  });
  fireEvent.click(getByRole(root(element), 'button', { name: 'Refresh status' })); await ready(element);
  expect(queryByRole(root(element), 'button', { name: 'Confirm cancellation' })).toBeNull();
  expect(queryByRole(root(element), 'checkbox', { name: 'I confirm cancellation' })).toBeNull();
  expect(seen.filter(r => r.method === 'POST')).toHaveLength(0);
});
it('keeps setup distinct from debt and clears a consumed action after confirmation', async () => {
  const { seen } = server(); const element = mount(); await ready(element); const user = userEvent.setup();
  fireEvent.click(getByLabelText(root(element), 'I consent to recurring payments')); getByRole(root(element), 'button', { name: 'Update payment method' }).focus(); await user.keyboard('{Enter}');
  await waitFor(() => expect(getByRole(root(element), 'button', { name: 'Complete Fake action' })).toBeEnabled());
  expect(root(element).textContent?.toLowerCase()).not.toContain('pay debt');
  fireEvent.click(getByRole(root(element), 'button', { name: 'Complete Fake action' }));
  await waitFor(() => expect(queryByRole(root(element), 'button', { name: 'Complete Fake action' })).toBeNull());
  expect(seen.filter(r => r.url.includes('action-confirmations'))).toHaveLength(1);
});
it('clears an available action when the live Customer session expires', async () => {
  const { seen } = server(); const element = mount({ ...subscriptionSession(), expiresAt: new Date(Date.now() + 1000).toISOString() }); await ready(element);
  fireEvent.click(getByLabelText(root(element), 'I consent to recurring payments')); fireEvent.click(getByRole(root(element), 'button', { name: 'Update payment method' }));
  await waitFor(() => expect(getByRole(root(element), 'button', { name: 'Complete Fake action' })).toBeEnabled());
  const old = getByRole(root(element), 'button', { name: 'Complete Fake action' });
  await waitFor(() => expect(root(element).textContent).toContain('Session expired.'), { timeout: 2000 });
  fireEvent.click(old); expect(seen.filter(r => r.url.includes('action-confirmations'))).toHaveLength(0);
  expect(queryByRole(root(element), 'button', { name: 'Complete Fake action' })).toBeNull();
});
it('emits a frozen whitelisted error code and never a raw response message', async () => {
  server(); const element = mount(null); const events: unknown[] = [];
  element.addEventListener('generic-subscription-error', event => events.push((event as CustomEvent).detail));
  element.client!.subscriptions.offers = vi.fn().mockRejectedValue(new sdk.PaymentSdkError('untrusted response text'));
  element.customerSession = subscriptionSession(); await ready(element);
  expect(events).toEqual([{ code: 'REQUEST_FAILED' }]); expect(Object.isFrozen(events[0])).toBe(true);
  expect(root(element).textContent).not.toContain('untrusted response text');
});
it.each(['disconnect', 'customer', 'bearer', 'site', 'api', 'expiry'] as const)('invalidates authority and action after %s', async kind => {
  const { seen } = server(); const element = mount(); await ready(element);
  fireEvent.click(getByLabelText(root(element), 'I consent to recurring payments')); fireEvent.click(getByRole(root(element), 'button', { name: 'Update payment method' }));
  await waitFor(() => expect(getByRole(root(element), 'button', { name: 'Complete Fake action' })).toBeEnabled());
  const old = getByRole(root(element), 'button', { name: 'Complete Fake action' }); const before = seen.filter(r => r.method === 'POST').length;
  if (kind === 'disconnect') element.remove();
  if (kind === 'customer') element.customerSession = { ...subscriptionSession(), customer: { ...subscriptionSession().customer, id: operationId } };
  if (kind === 'bearer') element.customerSession = { ...subscriptionSession(), sessionToken: 'R'.repeat(43) };
  if (kind === 'site') element.customerSession = { ...subscriptionSession(), siteId: operationId, siteKey: 'other-site' };
  if (kind === 'api') element.client = sdk.createGenericPaymentClient({ apiBaseUrl: 'https://other.test' });
  if (kind === 'expiry') element.customerSession = { ...subscriptionSession(), expiresAt: '2000-01-01T00:00:00Z' };
  fireEvent.click(old); await Promise.resolve(); expect(seen.filter(r => r.method === 'POST')).toHaveLength(before);
  expect(queryByRole(root(element), 'button', { name: 'Complete Fake action' })).toBeNull();
  if (kind === 'disconnect') { document.body.append(element); await ready(element); }
});
it('uses labelled native options, polite status, inherited theme tokens and reduced motion', async () => {
  server(); const element = mount(); element.style.setProperty('--gp-subscription-accent', '#123456'); await ready(element);
  expect(getByRole(root(element), 'group', { name: 'Plan options' }).tagName).toBe('FIELDSET');
  expect(getByRole(root(element), 'status')).toHaveAttribute('aria-live', 'polite');
  expect(element.shadowRoot?.querySelector('style')?.textContent).toContain('var(--gp-subscription-accent');
  expect(element.shadowRoot?.querySelector('style')?.textContent).toContain('prefers-reduced-motion');
});
