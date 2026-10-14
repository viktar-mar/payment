import type { Locator } from '@playwright/test';

// Shared input boundary: covered with a non-sensitive failure-channel canary.
export async function enterPrivateText(locator: Locator, value: string): Promise<void> {
  // Protocol debugging can serialize arguments even for evaluate. Fail before dispatch.
  if (process.env.DEBUG || (process.env.PWDEBUG && process.env.PWDEBUG !== '0')) {
    throw new Error('Private entry requires browser debug logging off');
  }
  // Installed Playwright's evaluate title is static and its failure path does not
  // progress-log the argument. Keep selectors, function source and errors payload-free.
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
  }, value, { timeout: 12_000 });
  if (!entered) throw new Error('Private entry requires an enabled editable visible text control');
}
