import { afterEach, expect, it, vi } from 'vitest';
vi.mock('../providers/stripe-loader.js', () => ({ loadStripeClient: vi.fn() }));
import { defineGenericPaymentWidget } from './generic-payment-widget.js';
import { defineGenericDonation } from '../donations/generic-donation.js';
import { stripeUiProvider, stripeCapability, stripeWire } from '../test/stripe-ui-fixtures.js';
import { campaignWire, donationWire } from '../test/donation-fixtures.js';
import { json } from '../test/fixtures.js';
import { armFormLoadDiagnostic, bindFormLoadDiagnostic, registerFormLoadWidget } from '../diagnostics/form-load-observer.js';

defineGenericPaymentWidget(); defineGenericDonation();
const observations = new Set<{ close(): void }>();
afterEach(() => { for (const o of observations) o.close(); observations.clear(); });
async function mountApi() {
  const modules = import.meta.glob('./provider-mount.ts');
  expect(modules['./provider-mount.ts'], 'internal owned mount implementation').toBeTypeOf('function');
  return await modules['./provider-mount.ts']!() as typeof import('./provider-mount.js');
}
async function widgetFixture(kind: 'Catalog' | 'Donation', provider = 'STRIPE', nested = false) {
  const p = stripeUiProvider(), requests: string[] = [];
  vi.stubGlobal('fetch', async (url: string) => { requests.push(url); return json(url.endsWith('/client-session') ? stripeCapability() : url.endsWith('/checkouts') ? { ...(kind === 'Catalog' ? stripeWire() : donationWire()), provider_type: provider, confirmation_mode: provider === 'STRIPE' ? 'CLIENT_PROVIDER' : 'SERVER' } : campaignWire(), url.endsWith('/checkouts') ? 201 : 200); });
  const widget = document.createElement(kind === 'Catalog' ? 'generic-payment-widget' : 'generic-donation');
  for (const [k, v] of Object.entries({ 'api-base-url': 'https://api.test', 'site-key': 'test-site', ...(kind === 'Catalog' ? { 'price-key': 'lifetime' } : { 'campaign-key': 'help-campaign' }) })) widget.setAttribute(k, v);
  if (nested) { const outer = document.createElement('div'); document.body.append(outer); outer.attachShadow({ mode: 'open' }).append(widget); } else document.body.append(widget);
  const button = () => kind === 'Catalog' ? widget.shadowRoot!.querySelector('button')! : widget.shadowRoot!.querySelector<HTMLButtonElement>('button[type=submit]')!;
  await vi.waitFor(() => expect(button()).toBeEnabled()); button().click();
  return { widget, p, requests, button };
}
// Break: the actual widgets passing a ShadowRoot container to Stripe.
it.each(['Catalog', 'Donation'] as const)('%s supplies a connected document Light DOM host projected in its shadow slot', async kind => {
  const f = await widgetFixture(kind);
  await vi.waitFor(() => expect(f.p.frame.isConnected).toBe(true));
  const host = f.p.frame.parentElement!;
  expect(host.isConnected).toBe(true);
  expect(host.ownerDocument.documentElement.contains(host)).toBe(true);
  expect(host.getRootNode()).toBe(host.ownerDocument);
  expect(host.parentElement).toBe(f.widget);
  const slot = f.widget.shadowRoot!.querySelector('slot')!;
  expect(slot.assignedElements()).toEqual([host]);
  expect(host.assignedSlot).toBe(slot);
  f.p.emit('ready'); await vi.waitFor(() => expect(f.button()).toBeEnabled());
  f.widget.setAttribute('locale', 'pt'); f.widget.dispatchEvent(new Event('payment-theme-change'));
  expect(f.p.frame.parentElement).toBe(host);
});
// Break: issuing a Stripe capability for an unsupported external ShadowRoot.
it.each(['Catalog', 'Donation'] as const)('%s rejects nested-shadow Stripe before capability preparation', async kind => {
  const f = await widgetFixture(kind, 'STRIPE', true);
  await vi.waitFor(() => expect(f.button()).toBeEnabled());
  expect(f.requests.filter(url => url.endsWith('/client-session'))).toHaveLength(0);
  expect(f.p.frame.isConnected).toBe(false);
});
// Break: accidentally applying Stripe document mounting constraints to Fake.
it.each(['Catalog', 'Donation'] as const)('%s keeps Fake in its legacy shadow position even when nested', async kind => {
  const f = await widgetFixture(kind, 'FAKE', true);
  await vi.waitFor(() => expect(f.widget.shadowRoot!.querySelector('.payment-element')).toBeTruthy());
  expect(f.widget.shadowRoot!.querySelector('.payment-element')!.getRootNode()).toBe(f.widget.shadowRoot);
  expect(f.widget.shadowRoot!.querySelector('slot')).toBeNull();
});
// Break: widget cleanup removing a sibling widget's provider lease or leaving its own projection behind.
it.each(['Catalog', 'Donation'] as const)('%s instances keep separate provider hosts and clean only their own projection', async kind => {
  const a = await widgetFixture(kind); await vi.waitFor(() => expect(a.p.frame.isConnected).toBe(true));
  // A new Catalog instance must create a separate checkout rather than recover the first fixture's record.
  sessionStorage.clear();
  const b = await widgetFixture(kind); await vi.waitFor(() => expect(b.p.frame.isConnected).toBe(true));
  const ah = a.p.frame.parentElement!, bh = b.p.frame.parentElement!;
  expect(ah).not.toBe(bh); a.widget.remove();
  expect(ah.isConnected).toBe(false); expect(a.widget.shadowRoot!.querySelector('slot')).toBeNull();
  expect(bh.isConnected).toBe(true); expect(b.widget.shadowRoot!.querySelector('slot')!.assignedElements()).toEqual([bh]);
});
function leaseWidget() {
  const widget = document.createElement('div'), anchor = document.createElement('button');
  widget.attachShadow({ mode: 'open' }).append(anchor); document.body.append(widget);
  return { widget, anchor };
}
// Break: accepting forged lookalikes or allowing stale cleanup to remove a successor.
it('keeps instances and generations independent and destroys only exact owned nodes', async () => {
  const api = await mountApi(), a = leaseWidget(), b = leaseWidget();
  const first = api.createProviderMount(a.widget, a.anchor, 'STRIPE'), other = api.createProviderMount(b.widget, b.anchor, 'STRIPE');
  const forged = first.host.cloneNode(true) as HTMLElement; forged.slot = 'foreign'; a.widget.append(forged);
  expect(api.ownedProviderHost(a.widget)).toBe(first.host);
  const next = api.createProviderMount(a.widget, a.anchor, 'STRIPE'); first.destroy(); first.destroy();
  expect(api.ownedProviderHost(a.widget)).toBe(next.host); expect(api.ownedProviderHost(b.widget)).toBe(other.host);
  expect(forged.isConnected).toBe(true); next.destroy();
  expect(a.widget.shadowRoot!.querySelector('slot')).toBeNull(); expect(next.host.isConnected).toBe(false); expect(forged.isConnected).toBe(true);
  expect(a.widget.shadowRoot!.querySelector('.payment-element')).toBeNull();
  expect(api.ownedProviderHost(a.widget)).toBeUndefined(); expect(api.ownedProviderHost(forged)).toBeUndefined();
});
// Break: DOM tampering, including queued same-turn mutations, reviving diagnostic authority.
it.each(['host-remove', 'host-replace', 'host-reinsert', 'slot-remove', 'slot-replace', 'slot-reinsert', 'wrapper-remove', 'wrapper-replace', 'wrapper-reinsert', 'slot-reassign', 'slot-rename', 'slot-reassign-revert', 'slot-rename-revert', 'widget-reinsert'])('permanently revokes the owned binding after %s', async action => {
  const api = await mountApi(), widget = document.createElement('div'), anchor = document.createElement('button');
  widget.attachShadow({ mode: 'open' }).append(anchor); registerFormLoadWidget(widget);
  const observation = armFormLoadDiagnostic(widget); observations.add(observation); document.body.append(widget);
  const lease = api.createProviderMount(widget, anchor, 'STRIPE'); bindFormLoadDiagnostic(widget, lease.host, 1);
  expect(observation.snapshot().binding).toBe('BOUND');
  const slot = widget.shadowRoot!.querySelector('slot')!, wrapper = slot.parentElement!;
  const target = action.startsWith('slot-') ? slot : action.startsWith('wrapper-') ? wrapper : action.startsWith('widget-') ? widget : lease.host;
  const parent = target.parentNode!, sibling = target.nextSibling;
  if (action.startsWith('slot-reassign')) { lease.host.slot = 'foreign'; if (action.endsWith('revert')) lease.host.slot = slot.name; }
  else if (action.startsWith('slot-rename')) { const name = slot.name; slot.name = 'foreign'; if (action.endsWith('revert')) slot.name = name; }
  else if (action.endsWith('replace')) target.replaceWith(target.cloneNode(true));
  else { target.remove(); if (action.endsWith('reinsert')) parent.insertBefore(target, sibling); }
  expect(api.ownedProviderHost(widget)).toBeUndefined();
  expect(observation.snapshot().binding).toBe('LOST');
  if (action === 'slot-reassign') lease.host.slot = slot.name;
  expect(api.ownedProviderHost(widget)).toBeUndefined();
  lease.destroy();
});
// Break: exposing the private current-lease callback makes authority forgeable by replacing a property.
it('keeps authority private when callers tamper with the returned mount handle', async () => {
  const api = await mountApi(), { widget, anchor } = leaseWidget();
  const lease = api.createProviderMount(widget, anchor, 'STRIPE');
  Reflect.set(lease, 'current', () => true); Reflect.set(lease, 'destroy', () => {});
  lease.host.remove(); expect(api.ownedProviderHost(widget)).toBeUndefined();
  const replacement = api.createProviderMount(widget, anchor, 'STRIPE');
  expect(widget.shadowRoot!.querySelectorAll('slot')).toHaveLength(1);
  replacement.destroy();
});
// Break: a competing slot stealing and returning the host within one turn revives a revoked association.
it('revokes ownership when a temporary competing slot reassigns the host', async () => {
  const api = await mountApi(), { widget, anchor } = leaseWidget();
  const lease = api.createProviderMount(widget, anchor, 'STRIPE');
  const ownSlot = widget.shadowRoot!.querySelector('slot')!, competing = document.createElement('slot'); competing.name = ownSlot.name;
  ownSlot.before(competing); expect(lease.host.assignedSlot).toBe(competing); competing.remove();
  expect(api.ownedProviderHost(widget)).toBeUndefined(); lease.destroy();
});
// Break: classifying historical slot mutations by their target's current root misses detached ancestors.
it.each(['resolver', 'observer'] as const)('revokes a competing slot in a detached ancestor through the %s path', async path => {
  const api = await mountApi(), widget = document.createElement('div'), anchor = document.createElement('button'), foreign = document.createElement('div');
  const root = widget.attachShadow({ mode: 'open' }); root.append(foreign, anchor); registerFormLoadWidget(widget);
  const observation = armFormLoadDiagnostic(widget); observations.add(observation); document.body.append(widget);
  const lease = api.createProviderMount(widget, anchor, 'STRIPE'); bindFormLoadDiagnostic(widget, lease.host, 1);
  expect(observation.snapshot().binding).toBe('BOUND');
  const ownSlot = root.querySelector('slot')!, competing = document.createElement('slot'); competing.name = ownSlot.name;
  foreign.append(competing); expect(lease.host.assignedSlot).toBe(competing);
  foreign.remove(); expect(lease.host.assignedSlot).toBe(ownSlot);
  if (path === 'observer') await Promise.resolve();
  expect(api.ownedProviderHost(widget)).toBeUndefined();
  expect(observation.snapshot().binding).toBe('LOST');
  root.prepend(foreign); foreign.remove();
  expect(api.ownedProviderHost(widget)).toBeUndefined(); lease.destroy();
});
// Break: arbitrary shadow nodes receiving a positive diagnostic binding.
it('rejects a forged diagnostic host while retaining its captured deny association', async () => {
  const { formLoadBinding, denyFormLoadConfirmation } = await import('../diagnostics/form-load-observer.js');
  const widget = document.createElement('div'), host = document.createElement('div'); widget.attachShadow({ mode: 'open' }).append(host);
  registerFormLoadWidget(widget); const observation = armFormLoadDiagnostic(widget); observations.add(observation); document.body.append(widget);
  const binding = bindFormLoadDiagnostic(widget, host, 1);
  expect(observation.snapshot().binding).toBe('LOST'); expect(formLoadBinding(host)).toBe(binding);
  observation.close(); expect(() => denyFormLoadConfirmation(binding)).toThrow('CONFIRMATION_BLOCKED');
});
