import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { matchesGlob } from 'node:path';
import type { Locator } from '@playwright/test';
// @ts-expect-error Native Node type erasure requires the source extension.
import { enterPrivateText } from './private-entry.ts';
// @ts-expect-error Native Node type erasure requires the source extension.
import ordinary from '../../playwright.config.ts';

const canary = 'synthetic-private-entry-canary';
const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); };

// Only the browser/locator boundary is synthetic: run the real helper's callbacks
// against controls with native-style value setters and observable input events.
function harness(kind = 'textarea', beforeProbe?: () => void) {
  const context = vm.createContext({ Event });
  vm.runInContext(`
    class Control {
      isConnected = true; disabled = false; readOnly = false; visible = true;
      stored = ''; events = []; focused = false;
      get value() { return this.stored; }
      set value(value) { this.stored = value; }
      matches(selector) { if (selector !== ':disabled') throw Error('unexpected selector'); return this.disabled; }
      getClientRects() { return this.visible ? [{}] : []; }
      focus() { this.focused = true; }
      dispatchEvent(event) { this.events.push({ type: event.type, bubbles: event.bubbles, composed: event.composed }); }
    }
    class HTMLInputElement extends Control {
      get value() { return this.stored; } set value(value) { this.stored = value; }
    }
    class HTMLTextAreaElement extends Control {
      get value() { return this.stored; } set value(value) { this.stored = value; }
    }
    globalThis.HTMLInputElement = HTMLInputElement;
    globalThis.HTMLTextAreaElement = HTMLTextAreaElement;
    globalThis.node = new ${kind === 'input' ? 'HTMLInputElement' : 'HTMLTextAreaElement'}();
  `, context);
  const calls: unknown[] = [];
  let beforeEntry: (() => void) | undefined;
  const locator = {
    evaluate: async (callback: Function, arg: unknown, options: { timeout: number }) => {
      assert.ok(options.timeout > 0 && options.timeout <= 12_000, 'every locator wait shares the bounded deadline');
      calls.push(arg);
      if (arg === undefined) beforeProbe?.();
      if (arg !== undefined) beforeEntry?.();
      context.arg = arg;
      return vm.runInContext(`(${callback.toString()})(node, arg)`, context);
    },
  } as unknown as Locator;
  return { locator, calls, context, get node() { return context.node; },
    beforeEntry(callback: () => void) { beforeEntry = callback; } };
}

// Break: checking attachment once rejects a freshly remounted campaign control
// while its independent campaign request is still pending after customer login.
for (const state of ['disabled', 'readOnly', 'visible'] as const) {
  test(`waits without private payload until ${state} becomes ready`, async t => {
    t.mock.timers.enable({ apis: ['Date', 'setTimeout'] });
    const h = harness();
    h.node[state] = state === 'visible' ? false : true;
    const outcome = enterPrivateText(h.locator, canary).then(() => 'entered', () => 'rejected');
    await flush();
    assert.equal(h.calls.includes(canary), false, 'readiness dispatches no private payload');
    assert.equal(h.node.value, '');
    h.node[state] = state === 'visible' ? true : false;
    t.mock.timers.tick(50);
    await flush();
    assert.equal(await outcome, 'entered');
    assert.equal(h.node.value === canary, true, 'the ready control receives private input');
    assert.equal(h.node.focused, true);
    assert.deepEqual(Array.from(h.node.events, (event: any) => ({ ...event })), [
      { type: 'input', bubbles: true, composed: true }, { type: 'change', bubbles: true, composed: true },
    ]);
    assert.equal(h.calls.filter(arg => arg !== undefined).length, 1, 'private entry is dispatched once');
  });
}

test('resolves a replacement control while waiting instead of retaining the old node', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'] });
  const h = harness();
  const old = h.node;
  old.disabled = true;
  const outcome = enterPrivateText(h.locator, canary).then(() => 'entered', () => 'rejected');
  await flush();
  vm.runInContext('node.isConnected = false; node = new HTMLInputElement()', h.context);
  t.mock.timers.tick(50);
  await flush();
  assert.equal(await outcome, 'entered');
  assert.equal(old.value, '');
  assert.equal(h.node.value === canary, true);
});

test('permanently disabled control reaches the bounded deadline without dispatching private input', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'] });
  const h = harness();
  h.node.disabled = true;
  let finished = false;
  const pending = enterPrivateText(h.locator, canary).catch(error => { finished = true; return error; });
  await flush();
  assert.equal(finished, false, 'an attached disabled control waits for readiness');
  t.mock.timers.tick(12_000);
  await flush();
  const error = await pending;
  assert.equal(finished, true);
  assert.equal(String(error).includes(canary), false);
  assert.equal(h.calls.includes(canary), false);
  assert.equal(h.node.value, '');
});

test('atomic entry still rejects a control disabled after readiness and emits no input events', async () => {
  const h = harness('input');
  h.beforeEntry(() => { h.node.disabled = true; });
  await assert.rejects(enterPrivateText(h.locator, canary), (error: Error) => !String(error).includes(canary));
  assert.equal(h.node.value, '');
  assert.equal(h.node.events.length, 0);
});

test('attachment/readiness cannot consume the deadline and then dispatch a private write', async t => {
  t.mock.timers.enable({ apis: ['Date', 'setTimeout'] });
  const h = harness('input', () => { t.mock.timers.tick(12_000); });
  await assert.rejects(enterPrivateText(h.locator, canary), (error: Error) => !String(error).includes(canary));
  assert.equal(h.calls.includes(canary), false);
  assert.equal(h.node.value, '');
});

test('debug guard rejects before any browser dispatch', async t => {
  const debug = process.env.DEBUG;
  t.after(() => { if (debug === undefined) delete process.env.DEBUG; else process.env.DEBUG = debug; });
  process.env.DEBUG = 'synthetic-debug';
  const h = harness();
  await assert.rejects(enterPrivateText(h.locator, canary), (error: Error) => !String(error).includes(canary));
  assert.equal(h.calls.length, 0);
});

test('ordinary browser discovery excludes the Node regression and retains private entry and donation journeys', () => {
  for (const [file, excluded] of [
    ['tests/support/private-entry.test.ts', true],
    ['tests/private-entry.spec.ts', false],
    ['tests/donations.spec.ts', false],
  ] as const) {
    assert.equal((ordinary.testIgnore as string[]).some(pattern => matchesGlob(file, pattern)), excluded, file);
  }
});
