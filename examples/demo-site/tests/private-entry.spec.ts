import { expect, test } from '@playwright/test';
import { enterPrivateText } from './support/private-entry';
import { entryCanary } from './support/entry-canary';

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
    node.matches = () => { throw new Error('Controlled non-sensitive evaluator failure'); };
  });
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
  expect(rejected, 'controlled evaluator failure reaches actual API failure boundary').toBe(true);
  expect(errorContainsPayload, 'API error excludes private argument').toBe(false);
  expect(callLogContainsPayload, 'API call log excludes private argument').toBe(false);
});
