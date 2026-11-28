import { visibleSubscriptionHost } from './stripe-subscription-driver.js';

/** One preparation action owns this guard, optionally through driver retirement.
 * Visibility and original composed-host removal latch even after synchronous
 * restoration; release detaches observers without aborting an inherited signal. */
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
  // Preserve identities from both physical and composed ancestry. Document
  // subtree observation cannot see removals inside a shadow root or slot tree.
  const originalNodes = new Set<Node>();
  const roots = new Set<Document | ShadowRoot>([document]);
  const ancestors: Node[] = [options.container];
  while (ancestors.length) {
    const node = ancestors.pop()!;
    if (originalNodes.has(node)) continue;
    originalNodes.add(node);
    if (node.parentNode) ancestors.push(node.parentNode);
    // DOM nodes can belong to another same-origin window. Its constructors are
    // different, so use the platform node kinds rather than this realm's instanceof.
    if (node.nodeType === 1) {
      const slot = (node as Element).assignedSlot;
      if (slot) ancestors.push(slot);
    }
    if (node.nodeType === 11 && 'host' in node) {
      const root = node as ShadowRoot;
      roots.add(root); ancestors.push(root.host);
    }
  }
  const observer = new MutationObserver(records => {
    try { latchRemovals(records); assertCurrent(); } catch { /* retirement is latched */ }
  });
  function latchRemovals(records: MutationRecord[]): void {
    if (records.some(record => record.type === 'childList' && [...record.removedNodes].some(node => originalNodes.has(node)))) retire();
  }
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
    // Also drain queued records at synchronous dispatch checks, before observer
    // delivery can race an already-resolved preflight continuation.
    latchRemovals(observer.takeRecords());
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
  for (const root of roots) observer.observe(root, observation);
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
