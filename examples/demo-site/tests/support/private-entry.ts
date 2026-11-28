import type { Locator } from '@playwright/test';

// Shared input boundary: covered with a non-sensitive failure-channel canary.
export async function enterPrivateText(locator: Locator, value: string): Promise<void> {
  // Protocol debugging can serialize arguments even for evaluate. Fail before dispatch.
  if (process.env.DEBUG || (process.env.PWDEBUG && process.env.PWDEBUG !== '0')) {
    throw new Error('Private entry requires browser debug logging off');
  }
  const deadline = Date.now() + 12_000;
  const remaining = (): number => {
    const timeout = deadline - Date.now();
    if (timeout <= 0) throw new Error('Private entry requires an enabled editable visible text control');
    return timeout;
  };
  // Customer login remounts Donation while its campaign request is still pending.
  // Evaluate waits for attachment only. Probe readiness without the private value,
  // resolving the locator anew each time, under one attachment/readiness deadline.
  for (;;) {
    const ready = await locator.evaluate(node =>
      (node instanceof HTMLInputElement || node instanceof HTMLTextAreaElement) &&
      node.isConnected && !node.matches(':disabled') && !node.readOnly && !!node.getClientRects().length,
    undefined, { timeout: remaining() });
    if (ready) break;
    await new Promise(resolve => setTimeout(resolve, Math.min(50, remaining())));
  }
  // Installed Playwright's evaluate title is static and its failure path does not
  // progress-log the argument. Keep selectors, function source and errors payload-free.
  // Recheck atomically with the single write: readiness may change between calls.
  const entered = await locator.evaluate((node, text) => {
    if (!(node instanceof HTMLInputElement || node instanceof HTMLTextAreaElement) ||
        !node.isConnected || node.matches(':disabled') || node.readOnly || !node.getClientRects().length) return false;
    const prototype = node instanceof HTMLInputElement ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
    const setter = Object.getOwnPropertyDescriptor(prototype, 'value')?.set;
    if (!setter) return false;
    node.focus();
    setter.call(node, text);
    node.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
    node.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
    return true;
  }, value, { timeout: remaining() });
  if (!entered) throw new Error('Private entry requires an enabled editable visible text control');
}
