import { expect, test } from '@playwright/test';
import { enterPrivateText } from './support/private-entry';
import { entryCanary } from './support/entry-canary';

test('private entry waits for a remounted disabled control to become editable', async ({ page }) => {
  await page.setContent('<label>Private input<textarea disabled></textarea></label>');
  await page.getByLabel('Private input').evaluate(node => {
    const facts = { originalInspectedDisabled: false, replaced: false, enabled: false };
    (window as any).privateEntryReadiness = facts;
    const matches = node.matches.bind(node);
    let armed = false;
    node.matches = selector => {
      const matched = matches(selector);
      // Start transitions only once the helper actually inspects the disabled
      // original, so a delayed worker cannot miss the state this test covers.
      if (!armed && selector === ':disabled') {
        armed = true;
        facts.originalInspectedDisabled = matched;
        setTimeout(() => {
          const replacement = node.cloneNode(false) as HTMLTextAreaElement;
          node.replaceWith(replacement);
          facts.replaced = true;
          setTimeout(() => { replacement.disabled = false; facts.enabled = true; }, 50);
        }, 0);
      }
      return matched;
    };
  });
  await enterPrivateText(page.getByLabel('Private input'), entryCanary);
  expect(await page.evaluate(() => (window as any).privateEntryReadiness), 'helper observes the disabled original before replacement becomes ready').toEqual({ originalInspectedDisabled: true, replaced: true, enabled: true });
  expect(await page.getByLabel('Private input').evaluate((node, value) => (node as HTMLTextAreaElement).value === value, entryCanary), 'ready replacement receives private entry').toBe(true);
});

for (const state of ['disabled', 'readonly'] as const) {
  test(`private entry ${state} failure excludes payload from error and call log`, async ({ page }) => {
    await page.setContent(`<label>Private input<textarea ${state}></textarea></label>`);
    let rejected = false;
    let errorContainsPayload = false;
    let callLogContainsPayload = false;
    try {
      await enterPrivateText(page.getByLabel('Private input'), entryCanary);
    } catch (error) {
      rejected = true;
      errorContainsPayload = String(error).includes(entryCanary);
      callLogContainsPayload = JSON.stringify((error as { log?: unknown }).log ?? []).includes(entryCanary);
    }
    expect(rejected, 'uneditable entry rejects').toBe(true);
    expect(errorContainsPayload, 'failure message excludes private argument').toBe(false);
    expect(callLogContainsPayload, 'automatic call log excludes private argument').toBe(false);
    expect(await page.getByLabel('Private input').evaluate(node => (node as HTMLTextAreaElement).value === ''), 'failed entry does not modify input').toBe(true);
  });
}

test('private entry updates input and textarea with bubbling input/change semantics', async ({ page }) => {
  await page.setContent('<label>Private input<input></label><label>Private textarea<textarea></textarea></label>');
  await page.evaluate(() => {
    (window as any).entryEvents = [];
    for (const name of ['input', 'change']) document.addEventListener(name, event => {
      (window as any).entryEvents.push({ type: event.type, target: (event.target as HTMLElement).tagName });
    });
  });
  await enterPrivateText(page.getByLabel('Private input', { exact: true }), entryCanary);
  await enterPrivateText(page.getByLabel('Private textarea'), entryCanary);
  expect(await page.evaluate(value => [...document.querySelectorAll('input,textarea')].every(node => (node as HTMLInputElement).value === value), entryCanary), 'both controls receive private input').toBe(true);
  expect(await page.evaluate(() => (window as any).entryEvents), 'input/change events reach owning form listeners').toEqual([
    { type: 'input', target: 'INPUT' }, { type: 'change', target: 'INPUT' },
    { type: 'input', target: 'TEXTAREA' }, { type: 'change', target: 'TEXTAREA' },
  ]);
});

test('private entry evaluator failure excludes payload from API error and call log', async ({ page }) => {
  await page.setContent('<label>Private input<textarea></textarea></label>');
  await page.getByLabel('Private input').evaluate(node => {
    // Focus runs only in the value-carrying evaluation, after readiness probes.
    (node as HTMLTextAreaElement).focus = () => { throw new Error('Controlled non-sensitive evaluator failure'); };
  });
  let controlledFailureObserved = false;
  let errorContainsPayload = false;
  let callLogContainsPayload = false;
  try {
    await enterPrivateText(page.getByLabel('Private input'), entryCanary);
  } catch (error) {
    controlledFailureObserved = (error as Error).message.includes('Controlled non-sensitive evaluator failure');
    errorContainsPayload = String(error).includes(entryCanary);
    callLogContainsPayload = JSON.stringify((error as { log?: unknown }).log ?? []).includes(entryCanary);
  }
  expect(controlledFailureObserved, 'the fixed controlled error reaches the value-carrying API failure boundary').toBe(true);
  expect(errorContainsPayload, 'API error excludes private argument').toBe(false);
  expect(callLogContainsPayload, 'API call log excludes private argument').toBe(false);
});
