import { visibleSubscriptionHost } from './stripe-subscription-driver.js';

/** One preparation action owns this guard until the mounted driver takes over.
 * Retirement latches across hidden→visible transitions; releasing a successful
 * handoff removes observers without aborting the driver's inherited signal. */
export interface SubscriptionHandoff {
  readonly signal: AbortSignal;
  assertCurrent(): void;
  wait<T>(pending: Promise<T>): Promise<T>;
  release(): void;
}
export function guardSubscriptionHandoff(options: {
  container: HTMLElement;
  signal: AbortSignal;
  expiresAt: string;
  isCurrent: () => boolean;
  onRetire?: () => void;
}): SubscriptionHandoff {
  const controller = new AbortController();
  const signal = AbortSignal.any([options.signal, controller.signal]);
  let active = true;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let cancel!: (error: DOMException) => void;
  const cancelled = new Promise<never>((_, reject) => { cancel = reject; });
  void cancelled.catch(() => {});
  const document = options.container.ownerDocument;
  const window = document.defaultView!;
  const observer = new MutationObserver(() => { try { assertCurrent(); } catch { /* retirement is latched */ } });
  function release(): void {
    signal.removeEventListener('abort', retire);
    window.removeEventListener('pagehide', retire);
    document.removeEventListener('visibilitychange', visibility);
    observer.disconnect();
    if (timer !== undefined) clearTimeout(timer);
  }
  function retire(): void {
    if (!active) return;
    active = false;
    release();
    controller.abort();
    cancel(new DOMException('Aborted', 'AbortError'));
    options.onRetire?.();
  }
  function visibility(): void { if (document.visibilityState === 'hidden') retire(); }
  function assertCurrent(): void {
    if (!active || signal.aborted || !options.isCurrent() || document.visibilityState === 'hidden'
      || !visibleSubscriptionHost(options.container) || Date.parse(options.expiresAt) <= Date.now()) {
      retire();
      throw new DOMException('Aborted', 'AbortError');
    }
  }
  signal.addEventListener('abort', retire, { once: true });
  window.addEventListener('pagehide', retire, { once: true });
  document.addEventListener('visibilitychange', visibility);
  const observation = { childList: true, subtree: true, attributes: true, attributeFilter: ['hidden', 'style', 'class', 'name', 'slot'] };
  observer.observe(document, observation);
  const projection = options.container.assignedSlot?.getRootNode();
  if (projection instanceof ShadowRoot) observer.observe(projection, observation);
  timer = setTimeout(retire, Math.max(0, Math.min(Date.parse(options.expiresAt) - Date.now(), 2147483647)));
  assertCurrent();
  return {
    signal, assertCurrent, release,
    async wait<T>(pending: Promise<T>): Promise<T> {
      const result = await Promise.race([pending, cancelled]);
      assertCurrent();
      return result;
    },
  };
}
