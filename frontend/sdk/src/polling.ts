import type { CheckoutState, WatchOptions } from "./contracts.js";
import { checkAbort, isAbort } from "./errors.js";
interface PollSeams { now: () => number; wallNow: () => number; random: () => number; sleep: (ms: number, signal?: AbortSignal) => Promise<void>; jitter: boolean; }
export function abortableSleep(ms: number, signal?: AbortSignal): Promise<void> {
  checkAbort(signal);
  return new Promise((resolve, reject) => {
    const cleanup = () => { clearTimeout(timer); signal?.removeEventListener("abort", abort); };
    const abort = () => { cleanup(); reject(signal?.reason ?? new DOMException("Aborted", "AbortError")); };
    const timer = setTimeout(() => { cleanup(); resolve(); }, ms);
    signal?.addEventListener("abort", abort, { once: true });
  });
}
export async function* pollCheckout(read: (signal?: AbortSignal) => Promise<CheckoutState>, session: { expiresAt: string; state?: CheckoutState }, options: WatchOptions = {}, seams: Partial<PollSeams> = {}): AsyncIterable<CheckoutState> {
  const clock: PollSeams = { now: () => performance.now(), wallNow: Date.now, random: Math.random, sleep: abortableSleep, jitter: true, ...seams };
  const started = clock.now(); let count = 0; let last: CheckoutState | undefined = session.state;
  const stopStatuses = ["SUCCEEDED", "FAILED", "CANCELLED", "REFUNDED", "PARTIALLY_REFUNDED", "EXPIRED", "REQUIRES_PAYMENT_METHOD"];
  for (;;) {
    checkAbort(options.signal);
    const expiry = Date.parse(session.expiresAt) - clock.wallNow(); const remaining = 600000 - (clock.now() - started);
    if (expiry <= 0 || remaining <= 0) { if (last) yield { ...last, status: expiry <= 0 ? "EXPIRED" : "UNKNOWN" }; return; }
    const controller = new AbortController();
    const forward = () => controller.abort(options.signal?.reason); options.signal?.addEventListener("abort", forward, { once: true });
    const timer = setTimeout(() => controller.abort(), Math.min(expiry, remaining));
    try { last = await read(controller.signal); checkAbort(options.signal); checkAbort(controller.signal); }
    catch (error) {
      if (options.signal?.aborted || (!controller.signal.aborted && isAbort(error))) throw error;
      if (controller.signal.aborted && last) { yield { ...last, status: Date.parse(session.expiresAt) <= clock.wallNow() ? "EXPIRED" : "UNKNOWN" }; return; }
      throw error;
    } finally { clearTimeout(timer); options.signal?.removeEventListener("abort", forward); }
    yield last;
    if (stopStatuses.includes(last.status)) return;
    const base = [500, 1000, 2000, 4000, 5000][Math.min(count++, 4)];
    const delay = clock.jitter ? Math.min(5000, base * (0.8 + Math.max(0, Math.min(1, clock.random())) * 0.2)) : base;
    const wait = Math.min(delay, Date.parse(session.expiresAt) - clock.wallNow(), 600000 - (clock.now() - started));
    if (wait > 0) await clock.sleep(wait, options.signal);
  }
}
