import { beforeEach, expect, it, vi } from 'vitest';
import * as sdk from '../index.js';
import { saveCustomerSession } from '../customer/session-storage.js';
import { base, json } from '../test/customer-fixtures.js';
import { actionWire, mutationWire, operationId, subscriptionId, subscriptionSession } from '../test/subscription-fixtures.js';

const provider = vi.hoisted(() => ({ load: vi.fn() }));
vi.mock('../providers/stripe-loader.js', () => ({ loadStripeClient: provider.load }));
const token = 'bga1_' + 'M'.repeat(43);
const tick = async () => { for (let n = 0; n < 40; n++) await Promise.resolve(); };
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(done => { resolve = done; });
  return { promise, resolve };
}
function fixture(owner = document) {
  const events = new Map<string, () => void>();
  const destroy = vi.fn(), mount = vi.fn(), onRetire = vi.fn();
  const element = { on: vi.fn((name: string, handler: () => void) => events.set(name, handler)), off: vi.fn(), mount, destroy };
  const confirmSetup = vi.fn(async () => ({})), confirmPayment = vi.fn(), createConfirmationToken = vi.fn();
  provider.load.mockResolvedValue({ elements: vi.fn(() => ({ create: vi.fn(() => element) })), confirmSetup, confirmPayment, createConfirmationToken });
  const calls: Request[] = [];
  const expiry = new Date(Date.now() + 60000).toISOString();
  let action: Record<string, unknown> = { ...actionWire(), kind: 'PROVIDER_CLIENT', secret: token, expires_at: expiry };
  let selection: Record<string, unknown> = { protocol: 'BROWSER_CONFIRM_V1', provider: 'stripe', flow: 'SETUP', publishable_key: 'pk_test_fixture', client_secret: 'seti_original_secret_fixture', action_token: token, expires_at: expiry };
  let receipt: Record<string, unknown> = { ...mutationWire(), status: 'RESERVED', browser_confirm_allowed: true };
  let pause: 'action' | 'selection' | 'preflight' | undefined;
  const gate = deferred<Response>();
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
    const r = new Request(url, init); calls.push(r);
    if (r.method === 'DELETE' && r.url === base + '/api/v1/customer/session') return new Response(null, { status: 204 });
    const prefix = base + `/api/v1/customer/subscriptions/${subscriptionId}/operations/${operationId}`;
    if (r.method === 'GET' && r.url === prefix + '/action') return pause === 'action' ? gate.promise : json(action);
    if (r.method === 'POST' && r.url === prefix + '/confirmation-session') return pause === 'selection' ? gate.promise : json(selection);
    if (r.method === 'POST' && r.url === prefix + '/action-confirmations') return pause === 'preflight' ? gate.promise : json(receipt, 202);
    throw Error('Unplanned inert request');
  });
  const container = owner.createElement('div'), button = owner.createElement('button');
  button.textContent = 'Confirm saved method'; button.disabled = true;
  owner.body.append(container, button);
  vi.spyOn(container, 'getClientRects').mockReturnValue([{ width: 300, height: 100 }] as unknown as DOMRectList);
  const paymentClient = sdk.createGenericPaymentClient({ apiBaseUrl: base });
  const client = paymentClient.subscriptions, session = subscriptionSession(), controller = new AbortController();
  let current = true;
  function start() {
    expect(sdk).toHaveProperty('mountStripeSubscriptionMethod', expect.any(Function));
    const pending = sdk.mountStripeSubscriptionMethod({ client, session, subscriptionId, operationId, container, isCurrent: () => current, signal: controller.signal, onRetire });
    void pending.catch(() => {});
    return pending;
  }
  async function ready() {
    const pending = start(); await tick();
    expect(mount).toHaveBeenCalledTimes(1); expect(button).toBeDisabled();
    events.get('ready')!();
    const handle = await pending; button.disabled = false;
    return handle;
  }
  function response() { return pause === 'action' ? json(action) : pause === 'selection' ? json(selection) : json(receipt, 202); }
  return { start, ready, calls, events, mount, destroy, onRetire, confirmSetup, confirmPayment, createConfirmationToken, container, button, client, paymentClient, session, controller, gate,
    pause: (phase: typeof pause) => { pause = phase; }, response,
    action: (patch: Record<string, unknown>) => { action = { ...action, ...patch }; },
    selection: (patch: Record<string, unknown>) => { selection = { ...selection, ...patch }; },
    receipt: (patch: Record<string, unknown>) => { receipt = { ...receipt, ...patch }; },
    revoke: () => { current = false; },
  };
}
beforeEach(() => { provider.load.mockReset(); });

it('exports a real-client SETUP mount and waits for ready before the site enables explicit confirmation', async () => {
  const f = fixture(), handle = await f.ready();
  let submitted: ReturnType<typeof handle.confirm> | undefined;
  f.button.addEventListener('click', () => { f.button.disabled = true; submitted = handle.confirm(); void submitted.catch(() => {}); });
  expect(f.calls).toHaveLength(2); expect(f.confirmSetup).not.toHaveBeenCalled();
  f.button.click();
  const result = await submitted!; handle.destroy();
  expect(result).toEqual({ operationId, subscriptionId, status: 'RESERVED', actionAvailable: false });
  expect(f.calls.map(r => r.method + ' ' + new URL(r.url).pathname)).toEqual([
    `GET /api/v1/customer/subscriptions/${subscriptionId}/operations/${operationId}/action`,
    `POST /api/v1/customer/subscriptions/${subscriptionId}/operations/${operationId}/confirmation-session`,
    `POST /api/v1/customer/subscriptions/${subscriptionId}/operations/${operationId}/action-confirmations`,
  ]);
  for (const request of f.calls.slice(1)) {
    expect(await request.json()).toEqual({ action_token: token });
    expect(request.headers.get('Idempotency-Key')).toMatch(/^[a-f0-9-]{36}$/);
  }
  expect(f.confirmSetup).toHaveBeenCalledTimes(1); expect(f.confirmPayment).not.toHaveBeenCalled();
  expect(f.createConfirmationToken).not.toHaveBeenCalled(); expect(f.destroy).toHaveBeenCalledTimes(1); expect(f.onRetire).toHaveBeenCalledTimes(1);
  expect(JSON.stringify(result) + document.body.innerHTML).not.toMatch(/bga1_|seti_|pk_test_/);
});

it('spends once before preflight so concurrent and later submits cannot dispatch again', async () => {
  const f = fixture(), handle = await f.ready(); f.pause('preflight');
  const pending = handle.confirm(); void pending.catch(() => {}); await tick();
  await expect(handle.confirm()).rejects.toMatchObject({ code: 'PAYMENT_STATE_CONFLICT' });
  expect(f.calls.filter(r => r.url.endsWith('/action-confirmations'))).toHaveLength(1); expect(f.confirmSetup).not.toHaveBeenCalled();
  f.gate.resolve(f.response()); await pending;
  await expect(handle.confirm()).rejects.toMatchObject({ code: 'PAYMENT_STATE_CONFLICT' });
  expect(f.confirmSetup).toHaveBeenCalledTimes(1); expect(f.destroy).toHaveBeenCalledTimes(1); expect(f.onRetire).toHaveBeenCalledTimes(1);
});

const fabricatedPermission = () => ({ operationId, subscriptionId, status: 'RESERVED' as const, actionAvailable: false, browserConfirmAllowed: true });
it.each(['before-mount', 'after-ready'])('uses original server preflight after public confirmation replacement %s', async replacement => {
  const f = fixture(), replacementCommand = vi.fn(async () => fabricatedPermission());
  if (replacement === 'before-mount') f.client.confirmAction = replacementCommand;
  const handle = await f.ready();
  if (replacement === 'after-ready') f.client.confirmAction = replacementCommand;
  f.receipt({ browser_confirm_allowed: false });

  await expect(handle.confirm()).rejects.toMatchObject({ code: 'PROVIDER_RESULT_UNKNOWN' });
  await expect(handle.confirm()).rejects.toMatchObject({ code: 'PAYMENT_STATE_CONFLICT' });

  expect(replacementCommand).not.toHaveBeenCalled();
  const requests = f.calls.filter(r => r.url.endsWith('/action-confirmations'));
  expect(requests).toHaveLength(1); expect(await requests[0]!.json()).toEqual({ action_token: token });
  expect(requests[0]!.headers.get('Idempotency-Key')).toMatch(/^[a-f0-9-]{36}$/);
  expect(f.confirmSetup).not.toHaveBeenCalled(); expect(f.confirmPayment).not.toHaveBeenCalled();
  expect(f.destroy).toHaveBeenCalledTimes(1); expect(f.onRetire).toHaveBeenCalledTimes(1);
});
it.each([false, true])('waits for original server preflight despite an immediately fabricated public receipt; allowed=%s', async allowed => {
  const f = fixture(), handle = await f.ready();
  const replacement = vi.fn(async () => fabricatedPermission()); f.client.confirmAction = replacement;
  f.pause('preflight'); f.receipt({ browser_confirm_allowed: allowed });
  const pending = handle.confirm(); void pending.catch(() => {}); await tick();

  expect(f.calls.filter(r => r.url.endsWith('/action-confirmations'))).toHaveLength(1);
  expect(f.confirmSetup).not.toHaveBeenCalled(); expect(replacement).not.toHaveBeenCalled();
  f.gate.resolve(f.response());
  if (allowed) expect(await pending).toEqual({ operationId, subscriptionId, status: 'RESERVED', actionAvailable: false });
  else await expect(pending).rejects.toMatchObject({ code: 'PROVIDER_RESULT_UNKNOWN' });

  await expect(handle.confirm()).rejects.toMatchObject({ code: 'PAYMENT_STATE_CONFLICT' });
  expect(f.calls).toHaveLength(3); expect(f.confirmSetup).toHaveBeenCalledTimes(allowed ? 1 : 0);
  expect(f.confirmPayment).not.toHaveBeenCalled(); expect(f.destroy).toHaveBeenCalledTimes(1); expect(f.onRetire).toHaveBeenCalledTimes(1);
});

it.each([{ browser_confirm_allowed: false }, { browser_confirm_allowed: null }, { status: 'UNKNOWN' }, { operation_id: subscriptionId }, { subscription_id: operationId }])('rejects denied, unknown or stale preflight %j', async patch => {
  const f = fixture(), handle = await f.ready(); f.receipt(patch);
  await expect(handle.confirm()).rejects.toBeInstanceOf(sdk.PaymentSdkError);
  expect(f.confirmSetup).not.toHaveBeenCalled(); expect(f.confirmPayment).not.toHaveBeenCalled();
  expect(f.destroy).toHaveBeenCalledTimes(1); expect(f.onRetire).toHaveBeenCalledTimes(1);
});
it.each(['SUCCEEDED', 'FAILED', 'PROCESSING'])('rejects owned %s permission before Stripe and permanently spends the handle', async status => {
  const f = fixture(), handle = await f.ready(); f.receipt({ status });

  await expect(handle.confirm()).rejects.toMatchObject({ code: 'PROVIDER_RESULT_UNKNOWN' });
  await expect(handle.confirm()).rejects.toMatchObject({ code: 'PAYMENT_STATE_CONFLICT' });

  expect(f.confirmSetup).not.toHaveBeenCalled(); expect(f.confirmPayment).not.toHaveBeenCalled();
  expect(f.calls.filter(r => r.url.endsWith('/action-confirmations'))).toHaveLength(1);
  expect(f.destroy).toHaveBeenCalledTimes(1); expect(f.onRetire).toHaveBeenCalledTimes(1);
});

it('rejects PAYMENT/v2 before provider load and never follows the payment protocol', async () => {
  const f = fixture(); f.selection({ protocol: 'SERVER_CONFIRM_V2', flow: 'PAYMENT', client_secret: 'pi_original_secret_fixture', action_token: undefined, attempt_id: operationId, capability: 'bca2_' + 'X'.repeat(43) });
  await expect(f.start()).rejects.toBeInstanceOf(sdk.PaymentSdkError);
  expect(provider.load).not.toHaveBeenCalled(); expect(f.calls).toHaveLength(2); expect(f.onRetire).toHaveBeenCalledTimes(1);
});
it('rejects a live publishable key from a genuinely branded SETUP before loader work', async () => {
  const f = fixture(); f.selection({ publishable_key: 'pk_live_incompatible' });
  const pending = f.start(); void pending.catch(() => {}); await tick(); f.events.get('ready')?.();

  await expect(pending).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });

  expect(provider.load).not.toHaveBeenCalled(); expect(f.calls).toHaveLength(2);
  expect(f.confirmSetup).not.toHaveBeenCalled(); expect(f.onRetire).toHaveBeenCalledTimes(1);
});
it('rejects a non-provider original action without selecting or loading', async () => {
  const f = fixture(); f.action({ kind: 'FAKE_CONFIRM_SETUP' });
  await expect(f.start()).rejects.toBeInstanceOf(sdk.PaymentSdkError);
  expect(f.calls).toHaveLength(1); expect(provider.load).not.toHaveBeenCalled(); expect(f.onRetire).toHaveBeenCalledTimes(1);
});
it.each(['action', 'selection'])('rejects a copied %s object despite the real registered client', async kind => {
  const f = fixture();
  if (kind === 'action') { const original = f.client.action; f.client.action = async i => ({ ...await original(i) }); }
  else { const original = f.client.confirmationSession!; f.client.confirmationSession = async i => ({ ...await original(i) }); }
  await expect(f.start()).rejects.toMatchObject({ code: 'INVALID_SESSION' });
  expect(provider.load).not.toHaveBeenCalled(); expect(f.onRetire).toHaveBeenCalledTimes(1);
});
it('refuses unavailable selection without a legacy fallback', async () => {
  const f = fixture(); f.client.confirmationSession = undefined;
  await expect(f.start()).rejects.toBeInstanceOf(sdk.PaymentSdkError);
  expect(provider.load).not.toHaveBeenCalled(); expect(f.calls.every(r => r.url.endsWith('/action'))).toBe(true);
});

async function loss(f: ReturnType<typeof fixture>, reason: string) {
  if (reason === 'hidden-visible') {
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden'); document.dispatchEvent(new Event('visibilitychange'));
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible'); document.dispatchEvent(new Event('visibilitychange'));
  } else if (reason === 'logout') await f.paymentClient.logoutCustomerSession(f.session);
  else if (reason === 'replacement') saveCustomerSession({ ...f.session, sessionToken: 'R'.repeat(43) });
  else if (reason === 'abort') f.controller.abort();
  else if (reason === 'pagehide') window.dispatchEvent(new Event('pagehide'));
  else if (reason === 'removal') f.container.remove();
  else if (reason === 'owner') { f.revoke(); f.container.setAttribute('class', 'changed'); }
  await tick();
}
it.each(['action', 'selection', 'preflight'] as const)('latches every authority loss through deferred %s', async phase => {
  for (const reason of ['hidden-visible', 'logout', 'replacement', 'abort', 'pagehide', 'removal', 'owner']) {
    const f = fixture(); saveCustomerSession(f.session);
    let handle: sdk.StripeSubscriptionHandle | undefined;
    if (phase === 'preflight') handle = await f.ready();
    f.pause(phase);
    const pending = handle ? handle.confirm() : f.start(); void pending.catch(() => {});
    await tick(); const callsBefore = f.calls.length;
    await loss(f, reason);
    await expect(pending).rejects.toThrow();
    f.gate.resolve(f.response()); await tick(); handle?.destroy();
    expect(f.calls.length).toBe(callsBefore + (reason === 'logout' ? 1 : 0));
    expect(f.confirmSetup).not.toHaveBeenCalled(); expect(f.confirmPayment).not.toHaveBeenCalled();
    expect(f.onRetire).toHaveBeenCalledTimes(1); expect(f.destroy).toHaveBeenCalledTimes(phase === 'preflight' ? 1 : 0);
    document.body.replaceChildren(); sessionStorage.clear(); vi.restoreAllMocks();
  }
});
function removalTarget(f: ReturnType<typeof fixture>, kind: string): HTMLElement {
  const owner = f.container.ownerDocument, ancestor = owner.createElement('section');
  if (kind.startsWith('shadow') || kind === 'slot-ancestor') {
    const shell = owner.createElement('div'); owner.body.prepend(shell);
    const root = shell.attachShadow({ mode: 'open' }); root.append(ancestor);
    if (kind === 'slot-ancestor') {
      const slot = owner.createElement('slot'); slot.name = 'method'; ancestor.append(slot);
      f.container.slot = 'method'; shell.append(f.container);
      expect(f.container.assignedSlot).toBe(slot);
    } else ancestor.append(f.container);
  } else { owner.body.prepend(ancestor); ancestor.append(f.container); }
  return kind.endsWith('ancestor') ? ancestor : f.container;
}
it.each(['selection', 'preflight'] as const)('latches same-turn host and composed-ancestor reinsertion during %s', async phase => {
  for (const kind of ['host', 'ancestor', 'shadow-host', 'shadow-ancestor', 'slot-ancestor']) {
    const f = fixture(), target = removalTarget(f, kind);
    let handle: sdk.StripeSubscriptionHandle | undefined;
    if (phase === 'preflight') handle = await f.ready();
    f.pause(phase); const pending = handle ? handle.confirm() : f.start(); void pending.catch(() => {}); await tick();
    const parent = target.parentNode!;

    try {
      target.remove(); parent.appendChild(target); f.gate.resolve(f.response());
      await tick(); f.events.get('ready')?.();
      await expect(pending).rejects.toThrow();

      expect(f.confirmSetup).not.toHaveBeenCalled(); expect(f.confirmPayment).not.toHaveBeenCalled();
      expect(f.onRetire).toHaveBeenCalledTimes(1); expect(f.destroy).toHaveBeenCalledTimes(phase === 'preflight' ? 1 : 0);
    } finally { f.controller.abort(); handle?.destroy(); document.body.replaceChildren(); vi.restoreAllMocks(); }
  }
});
it.each(['selection', 'preflight'] as const)('allows unrelated and provider-child DOM edits during %s', async phase => {
  const f = fixture(); let handle: sdk.StripeSubscriptionHandle | undefined;
  if (phase === 'preflight') handle = await f.ready();
  f.pause(phase); const pending = handle ? handle.confirm() : f.start(); void pending.catch(() => {}); await tick();
  const unrelated = document.createElement('aside'); document.body.append(unrelated); unrelated.remove();
  const child = document.createElement('span'); f.container.append(child); child.remove();
  f.gate.resolve(f.response()); await tick(); f.events.get('ready')?.();

  if (phase === 'selection') {
    handle = await pending as sdk.StripeSubscriptionHandle;
    expect(f.onRetire).not.toHaveBeenCalled(); await handle.confirm();
  } else await pending;
  expect(f.confirmSetup).toHaveBeenCalledTimes(1); expect(f.destroy).toHaveBeenCalledTimes(1); expect(f.onRetire).toHaveBeenCalledTimes(1);
});
function iframeOwner(): Document {
  // No src, URL, resource loader or network: an inert same-origin about:blank realm.
  const frame = document.createElement('iframe'); document.body.append(frame);
  const owner = frame.contentDocument!;
  vi.spyOn(owner, 'visibilityState', 'get').mockReturnValue('visible');
  return owner;
}
it.each(['selection', 'preflight'] as const)('latches cross-realm shadow and slot removal during %s', async phase => {
  for (const kind of ['shadow-host', 'shadow-ancestor', 'slot-ancestor']) {
    const owner = iframeOwner(), f = fixture(owner), target = removalTarget(f, kind);
    expect(f.container instanceof HTMLElement).toBe(false);
    expect(target.getRootNode() instanceof ShadowRoot).toBe(false);
    let handle: sdk.StripeSubscriptionHandle | undefined;
    if (phase === 'preflight') handle = await f.ready();
    f.pause(phase); const pending = handle ? handle.confirm() : f.start(); void pending.catch(() => {}); await tick();
    const parent = target.parentNode!;

    try {
      target.remove(); parent.appendChild(target); f.gate.resolve(f.response());
      await tick(); f.events.get('ready')?.();
      await expect(pending).rejects.toThrow();

      expect(f.confirmSetup).not.toHaveBeenCalled(); expect(f.confirmPayment).not.toHaveBeenCalled();
      expect(f.onRetire).toHaveBeenCalledTimes(1); expect(f.destroy).toHaveBeenCalledTimes(phase === 'preflight' ? 1 : 0);
    } finally { f.controller.abort(); handle?.destroy(); document.body.replaceChildren(); vi.restoreAllMocks(); }
  }
});
it.each(['selection', 'preflight'] as const)('allows unrelated cross-realm shadow edits during %s', async phase => {
  const owner = iframeOwner(), f = fixture(owner); removalTarget(f, 'shadow-host');
  let handle: sdk.StripeSubscriptionHandle | undefined;
  if (phase === 'preflight') handle = await f.ready();
  f.pause(phase); const pending = handle ? handle.confirm() : f.start(); void pending.catch(() => {}); await tick();
  const root = f.container.getRootNode() as ShadowRoot;
  const unrelated = owner.createElement('aside'); root.append(unrelated); unrelated.remove();
  const child = owner.createElement('span'); f.container.append(child); child.remove();
  f.gate.resolve(f.response()); await tick(); f.events.get('ready')?.();

  if (phase === 'selection') { handle = await pending as sdk.StripeSubscriptionHandle; expect(f.onRetire).not.toHaveBeenCalled(); await handle.confirm(); }
  else await pending;
  expect(f.confirmSetup).toHaveBeenCalledTimes(1); expect(f.onRetire).toHaveBeenCalledTimes(1); expect(f.destroy).toHaveBeenCalledTimes(1);
});
it.each(['before', 'selection', 'mounted'] as const)('refuses expiry %s without dispatch', async phase => {
  const f = fixture();
  if (phase === 'before') { f.session.expiresAt = new Date(Date.now() - 1).toISOString(); await expect(f.start()).rejects.toBeInstanceOf(sdk.PaymentSdkError); }
  else if (phase === 'selection') { f.pause('selection'); const pending = f.start(); void pending.catch(() => {}); await tick(); vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 120000); f.gate.resolve(f.response()); await expect(pending).rejects.toThrow(); }
  else { const handle = await f.ready(); vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 120000); await expect(handle.confirm()).rejects.toThrow(); }
  expect(f.confirmSetup).not.toHaveBeenCalled(); expect(f.confirmPayment).not.toHaveBeenCalled();
});
it.each(['loaderror', 'destroy', 'invalid-host'] as const)('cleans readiness/retirement exactly once for %s', async reason => {
  const f = fixture();
  if (reason === 'invalid-host') f.container.remove();
  const pending = f.start(); void pending.catch(() => {}); await tick();
  if (reason === 'loaderror') f.events.get('loaderror')!();
  if (reason === 'destroy') f.controller.abort();
  await expect(pending).rejects.toThrow();
  expect(f.onRetire).toHaveBeenCalledTimes(1); expect(f.destroy).toHaveBeenCalledTimes(reason === 'invalid-host' ? 0 : 1);
});
it('retires an explicitly destroyed ready handle once and cannot confirm afterward', async () => {
  const f = fixture(), handle = await f.ready(); handle.destroy(); handle.destroy();
  await expect(handle.confirm()).rejects.toThrow();
  expect(f.destroy).toHaveBeenCalledTimes(1); expect(f.onRetire).toHaveBeenCalledTimes(1); expect(f.calls).toHaveLength(2);
});
