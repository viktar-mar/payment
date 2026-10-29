import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import type { Page, Response as PlaywrightResponse } from '@playwright/test';
// @ts-expect-error Native Node type erasure requires the source extension.
import { submitOtpChallenge } from './otp-challenge-response.ts';

const endpoint = 'http://127.0.0.1:28080/api/v1/public/customer-auth/email-otp/challenges';
const privateValue = 'synthetic-private-value';
const tick = () => new Promise<void>(resolve => setImmediate(resolve));
function harness(options: { body?: string; status?: number; bodyFailure?: boolean; fetchGate?: Promise<void>; bodyGate?: Promise<void> } = {}) {
  const delivered = Promise.withResolvers<PlaywrightResponse>();
  const started = Promise.withResolvers<void>();
  const reading = Promise.withResolvers<void>();
  const calls: { receiver: unknown; args: unknown[] }[] = [];
  const responses: Response[] = [];
  let legacyReads = 0, cloneReads = 0, handlesDisposed = 0, observed = false;
  const browser = vm.createContext({ URL, Request, Response, location: { href: 'http://127.0.0.1:4173/' } });
  vm.runInContext('window = globalThis', browser);
  const original = function(this: unknown, ...args: unknown[]) {
    calls.push({ receiver: this, args }); started.resolve();
    return (async () => {
      await options.fetchGate;
      const response = new Response(options.body ?? JSON.stringify({ expires_at: privateValue, challenge_id: privateValue }), { status: options.status ?? 202 });
      const clone = response.clone.bind(response);
      response.clone = () => {
        cloneReads++;
        if (options.bodyFailure) throw new Error(privateValue);
        const copy = clone();
        const json = copy.json.bind(copy);
        copy.json = async () => { reading.resolve(); await options.bodyGate; return json(); };
        return copy;
      };
      responses.push(response);
      const [input, init] = args as [string | URL | Request, RequestInit | undefined];
      const url = input instanceof Request ? input.url : String(input);
      const method = init?.method ?? (input instanceof Request ? input.method : 'GET');
      if (url === endpoint && method.toUpperCase() === 'POST') delivered.resolve({ url: () => endpoint, request: () => ({ method: () => 'POST' }), status: () => response.status,
        json: async () => { legacyReads++; throw new Error('synthetic_Chromium_body_resource_unavailable'); } } as unknown as PlaywrightResponse);
      return response;
    })();
  };
  browser.fetch = original;
  const page = {
    waitForResponse: async (matches: (response: PlaywrightResponse) => boolean) => {
      observed = true;
      for (const [url, method] of [[endpoint + '/other', 'POST'], [endpoint.replace('28080', '28081'), 'POST'], [endpoint, 'GET']]) {
        assert.equal(matches({ url: () => url, request: () => ({ method: () => method }) } as unknown as PlaywrightResponse), false);
      }
      const response = await delivered.promise;
      assert.equal(matches(response), true);
      return response;
    },
    evaluateHandle: async (fn: Function, arg: unknown) => {
      browser.__arg = arg;
      const value = vm.runInContext(`(${fn.toString()})(__arg)`, browser);
      return {
        evaluate: async (read: Function) => { browser.__handle = value; return structuredClone(await vm.runInContext(`(${read.toString()})(__handle)`, browser)); },
        dispose: async () => { handlesDisposed++; },
      };
    },
  } as unknown as Pick<Page, 'waitForResponse' | 'evaluateHandle'>;
  return { page, browser, original, started, reading, delivered, calls, responses,
    get counts() { return { legacyReads, cloneReads, handlesDisposed }; },
    get observed() { return observed; },
  };
}

// Break: CDP body resources can already be unavailable at response arrival; earlier .then reading is insufficient.
test('reads real response fields before browser delivery without using the expired Chromium body resource', async () => {
  const h = harness();
  const init = { method: 'POST', body: privateValue, headers: { 'Content-Type': 'application/json' }, credentials: 'omit', redirect: 'error', mode: 'cors' };
  const receiver = {};
  let submitted = false;
  const result = await submitOtpChallenge(h.page, endpoint, true, async () => {
    assert.equal(h.observed, true);
    const response: Response = await h.browser.fetch.call(receiver, endpoint, init);
    assert.ok(response);
    assert.equal(response, h.responses[0]);
    assert.equal((await response.json()).challenge_id, privateValue);
    submitted = true;
  });
  assert.deepEqual(result, { status: 202, fields: ['challenge_id', 'expires_at'] });
  assert.equal(JSON.stringify(result).includes(privateValue), false);
  assert.equal(submitted, true);
  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0]!.receiver, receiver);
  assert.equal(h.calls[0]!.args[0], endpoint);
  assert.equal(h.calls[0]!.args[1], init);
  assert.deepEqual(h.counts, { legacyReads: 0, cloneReads: 1, handlesDisposed: 1 });
  assert.equal(h.browser.fetch, h.original);
});

test('ignores other URLs and methods, captures native Request once and exposes extra fields', async () => {
  const h = harness({ body: JSON.stringify({ challenge_id: privateValue, expires_at: privateValue, unexpected: true }) });
  const request = new Request(endpoint, { method: 'POST', body: privateValue, credentials: 'omit' });
  const result = await submitOtpChallenge(h.page, endpoint, true, async () => {
    for (const [url, method] of [[endpoint + '?other=1', 'POST'], [endpoint.replace('28080', '28081'), 'POST'], [endpoint, 'GET']]) {
      await h.browser.fetch(url, { method });
      assert.equal(h.counts.cloneReads, 0);
    }
    await h.browser.fetch(request);
    await h.browser.fetch(endpoint, { method: 'POST' });
  });
  assert.deepEqual(result, { status: 202, fields: ['challenge_id', 'expires_at', 'unexpected'] });
  assert.equal(h.calls[3]!.args.length, 1);
  assert.equal(h.calls[3]!.args[0], request);
  assert.equal(h.counts.cloneReads, 1);
  assert.equal(h.calls.length, 5);
});

test('captures native URL with method override and never reads a configuration error body', async () => {
  const h = harness({ status: 503, bodyFailure: true });
  assert.deepEqual(await submitOtpChallenge(h.page, endpoint, false, async () => {
    await h.browser.fetch(new URL(endpoint), { method: 'post' });
  }), { status: 503, fields: undefined });
  assert.equal(h.counts.cloneReads, 0);
  assert.equal(h.browser.fetch, h.original);
  assert.equal(h.counts.handlesDisposed, 1);
});

test('reports body observation failure without exposing values or replacing the original response', async () => {
  const h = harness({ bodyFailure: true });
  let returned: Response | undefined;
  await assert.rejects(submitOtpChallenge(h.page, endpoint, true, async () => {
    returned = await h.browser.fetch(endpoint, { method: 'POST' });
  }), (error: Error) => error.message === 'OTP response observation failed');
  await tick();
  assert.equal(returned, h.responses[0]);
  assert.equal(h.browser.fetch, h.original);
  assert.equal(h.counts.handlesDisposed, 1);
});

for (const sync of [false, true]) test(`cleans up on ${sync ? 'synchronous' : 'asynchronous'} submission failure and observes late wait rejection`, async () => {
  const h = harness();
  const failure = new Error('synthetic_submission_failed');
  const submit = sync ? () => { throw failure; } : async () => { throw failure; };
  await assert.rejects(submitOtpChallenge(h.page, endpoint, true, submit), error => error === failure);
  assert.equal(h.browser.fetch, h.original);
  assert.equal(h.counts.handlesDisposed, 1);
  h.delivered.reject(new Error('synthetic_late_wait_failed'));
  await tick();
});

test('late fetch/body failure after submission failure is observed and cannot clobber a foreign fetch owner', async () => {
  const gate = Promise.withResolvers<void>();
  const h = harness({ fetchGate: gate.promise, bodyFailure: true });
  const failure = new Error('synthetic_submission_failed');
  const foreign = () => Promise.reject(new Error('must_not_call_foreign_fetch'));
  let pending: Promise<Response> | undefined;
  await assert.rejects(submitOtpChallenge(h.page, endpoint, true, async () => {
    pending = h.browser.fetch(endpoint, { method: 'POST' });
    await h.started.promise;
    h.browser.fetch = foreign;
    throw failure;
  }), error => error === failure);
  assert.equal(h.browser.fetch, foreign);
  assert.equal(h.counts.handlesDisposed, 1);
  gate.resolve();
  assert.equal(await pending, h.responses[0]);
  assert.equal(h.browser.fetch, foreign);
});

test('response wait failure cancels an unmatched observer and disposes its handle', async () => {
  const h = harness();
  const failure = new Error('synthetic_default_response_timeout');
  await assert.rejects(submitOtpChallenge(h.page, endpoint, true, async () => { h.delivered.reject(failure); }), error => error === failure);
  assert.equal(h.browser.fetch, h.original);
  assert.equal(h.counts.handlesDisposed, 1);
});

// Break: delivering the original response before clone consumption recreates a body-lifetime race.
test('holds application delivery until the clone is read, even after the response event', async () => {
  const gate = Promise.withResolvers<void>();
  const h = harness({ bodyGate: gate.promise });
  let applicationReceived = false;
  const pending = submitOtpChallenge(h.page, endpoint, true, async () => {
    await h.browser.fetch(endpoint, { method: 'POST' });
    applicationReceived = true;
  });
  await h.reading.promise;
  await h.delivered.promise;
  assert.equal(applicationReceived, false);
  gate.resolve();
  assert.deepEqual(await pending, { status: 202, fields: ['challenge_id', 'expires_at'] });
  assert.equal(applicationReceived, true);
});

test('passes unsupported request inputs unchanged without consuming the single observation', async () => {
  const h = harness();
  const input = { toString: () => endpoint };
  await submitOtpChallenge(h.page, endpoint, true, async () => {
    await h.browser.fetch(input, { method: 'POST' });
    assert.equal(h.counts.cloneReads, 0);
    await h.browser.fetch(endpoint, { method: 'POST' });
  });
  assert.equal(h.calls[0]!.args[0], input);
  assert.equal(h.counts.cloneReads, 1);
});

test('rejects overlapping observers before submission and leaves the first owner intact', async () => {
  const h = harness();
  const started = Promise.withResolvers<void>();
  const gate = Promise.withResolvers<void>();
  const first = submitOtpChallenge(h.page, endpoint, true, async () => { started.resolve(); await gate.promise; });
  await started.promise;
  const owner = h.browser.fetch;
  await assert.rejects(submitOtpChallenge(h.page, endpoint, true, async () => { throw new Error('second_submit_must_not_run'); }),
    (error: Error) => error.message === 'OTP response observation already active');
  assert.equal(h.browser.fetch, owner);
  const failure = new Error('synthetic_first_submit_failed');
  gate.reject(failure);
  await assert.rejects(first, error => error === failure);
  h.delivered.reject(new Error('synthetic_late_wait_failed'));
  await tick();
  assert.equal(h.browser.fetch, h.original);
});

test('reports native fetch failure privately while preserving its application rejection', async () => {
  const gate = Promise.withResolvers<void>();
  const h = harness({ fetchGate: gate.promise });
  const failure = new Error(privateValue);
  let applicationError: unknown;
  const pending = submitOtpChallenge(h.page, endpoint, true, async () => {
    void h.browser.fetch(endpoint, { method: 'POST' }).catch((error: unknown) => { applicationError = error; });
  });
  await h.started.promise;
  gate.reject(failure);
  await assert.rejects(pending, (error: Error) => error.message === 'OTP response observation failed');
  await tick();
  assert.equal(applicationError, failure);
  assert.equal(h.browser.fetch, h.original);
  assert.equal(h.counts.handlesDisposed, 1);
  h.delivered.reject(new Error('synthetic_late_wait_failed'));
  await tick();
});

test('malformed JSON fails observation privately and preserves the application response body', async () => {
  const body = `{"secret":"${privateValue}" BROKEN`;
  const h = harness({ body });
  let applicationBody: string | undefined;
  await assert.rejects(submitOtpChallenge(h.page, endpoint, true, async () => {
    const response = await h.browser.fetch(endpoint, { method: 'POST' });
    applicationBody = await response.text();
  }), (error: Error) => error.message === 'OTP response observation failed');
  await tick();
  assert.equal(applicationBody, body);
  assert.equal(h.calls.length, 1);
  assert.equal(h.counts.handlesDisposed, 1);
});

test('cleanup leaves a foreign fetch replacement intact even before any matching request', async () => {
  const h = harness();
  const failure = new Error('synthetic_submission_failed');
  const foreign = () => Promise.reject(new Error('must_not_call_foreign_fetch'));
  await assert.rejects(submitOtpChallenge(h.page, endpoint, true, async () => {
    h.browser.fetch = foreign;
    throw failure;
  }), error => error === failure);
  assert.equal(h.browser.fetch, foreign);
  assert.equal(h.counts.handlesDisposed, 1);
  h.delivered.reject(new Error('synthetic_late_wait_failed'));
  await tick();
});
