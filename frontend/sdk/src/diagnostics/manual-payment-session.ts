// Internal manual capability boundary. No public SDK options or storage bridge.
import type { CheckoutSession, ClientOptions } from '../contracts.js';
import { safeState, validateSession } from '../contracts.js';
import { PaymentSdkError } from '../errors.js';

interface Authority {
  readonly deadline: number;
  readonly signal: AbortSignal;
  current(): boolean;
  invalidate(): void;
}
interface Handoff { session?: CheckoutSession; authority: Authority; }
const handoffs = new WeakMap<HTMLElement, Handoff>();
const clients = new WeakMap<ClientOptions, Authority>();
// Keep the tombstone after closure: copied/refreshed capabilities must never persist
// or regain ordinary transport authority for the lifetime of this module graph.
const capabilities = new Map<string, Authority>();
const denied = () => new PaymentSdkError('TRANSPORT_UNAVAILABLE');

export function bindManualPaymentSession(widget: HTMLElement, session: CheckoutSession, authority: Authority): void {
  validateSession(session);
  if (handoffs.has(widget) || capabilities.has(session.checkoutToken) || session.providerType !== 'STRIPE'
    || session.donation || session.apiBaseUrl !== widget.getAttribute('api-base-url')
    || session.siteKey !== widget.getAttribute('site-key') || session.priceKey !== widget.getAttribute('price-key')) throw denied();
  const snapshot = { ...session, state: safeState(session.state) };
  capabilities.set(session.checkoutToken, authority);
  handoffs.set(widget, { session: snapshot, authority });
}

export function takeManualPaymentSession(widget: HTMLElement): CheckoutSession | undefined {
  const handoff = handoffs.get(widget);
  if (!handoff) return undefined;
  const session = handoff.session; handoff.session = undefined;
  if (!session || !handoff.authority.current() || session.apiBaseUrl !== widget.getAttribute('api-base-url')
    || session.siteKey !== widget.getAttribute('site-key') || session.priceKey !== widget.getAttribute('price-key')) {
    handoff.authority.invalidate(); throw denied();
  }
  return session;
}

export function isManualPaymentSession(session: CheckoutSession): boolean { return capabilities.has(session.checkoutToken); }

export function bindManualPaymentClient(widget: HTMLElement, options: ClientOptions): ClientOptions {
  const handoff = handoffs.get(widget);
  if (handoff) clients.set(options, handoff.authority);
  return options;
}

export async function manualPaymentRequest<T>(options: ClientOptions, token: string | undefined, signal: AbortSignal | undefined, work: (signal?: AbortSignal) => Promise<T>): Promise<T> {
  const capability = token === undefined ? undefined : capabilities.get(token);
  const authority = clients.get(options) ?? capability;
  if (!authority) return work(signal);
  const deadline = Math.min(Date.now() + 10000, authority.deadline);
  const controller = new AbortController();
  const active = AbortSignal.any([controller.signal, authority.signal, ...(signal ? [signal] : [])]);
  const check = () => {
    if (active.aborted || !Number.isFinite(Date.now()) || Date.now() >= deadline || !authority.current()) throw denied();
  };
  let timer: ReturnType<typeof setTimeout> | undefined;
  let cancel: (() => void) | undefined;
  try {
    // A bound manual client cannot create a replacement Checkout or exchange any
    // unrelated capability, even if widget recovery/configuration has failed.
    if (capability !== authority || clients.get(options) !== authority) throw denied();
    check();
    const cancelled = new Promise<never>((_, reject) => {
      cancel = () => { authority.invalidate(); reject(denied()); };
      active.addEventListener('abort', cancel, { once: true });
      timer = setTimeout(() => controller.abort(), Math.max(0, deadline - Date.now()));
    });
    // The race settles even if fetch/body ignore abort; late completion cannot
    // return a capability, refresh state, persist a copy or start the next request.
    const result = await Promise.race([work(active), cancelled]);
    check(); return result;
  } catch {
    authority.invalidate(); controller.abort(); throw denied();
  } finally {
    clearTimeout(timer); if (cancel) active.removeEventListener('abort', cancel);
  }
}
