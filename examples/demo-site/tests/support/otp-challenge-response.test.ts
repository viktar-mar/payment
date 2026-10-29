import test from 'node:test';
import assert from 'node:assert/strict';
import type { Page, Response } from '@playwright/test';
// @ts-expect-error Native Node type erasure requires the source extension.
import { submitOtpChallenge } from './otp-challenge-response.ts';

const endpoint = 'http://127.0.0.1:28080/api/v1/public/customer-auth/email-otp/challenges';
function response(json: () => Promise<unknown>, status = 202, url = endpoint, method = 'POST'): Response {
  return { url: () => url, request: () => ({ method: () => method }), status: () => status, json } as unknown as Response;
}

// Break: waiting for keyboard submission completion before reading can outlive Chromium's body resource.
test('captures OTP response fields while the submit action is still pending', async () => {
  const delivered = Promise.withResolvers<Response>();
  let armed = false, bodyAvailable = true, reads = 0, submitted = false;
  const received = response(async () => {
    reads++;
    if (!bodyAvailable) throw new Error('synthetic_body_resource_expired');
    assert.equal(submitted, false);
    return { expires_at: '2099-01-01T00:00:00Z', challenge_id: 'synthetic-challenge' };
  });
  const page = { waitForResponse: () => { armed = true; return delivered.promise; } } as unknown as Pick<Page, 'waitForResponse'>;
  const result = await submitOtpChallenge(page, endpoint, true, async () => {
    assert.equal(armed, true);
    delivered.resolve(received);
    await new Promise<void>(resolve => setImmediate(resolve));
    bodyAvailable = false;
    submitted = true;
  });
  assert.deepEqual(result, { status: 202, fields: ['challenge_id', 'expires_at'] });
  assert.equal(reads, 1);
  assert.equal(submitted, true);
  assert.equal(bodyAvailable, false);
});

// Break: a response from another Origin/path/method must not satisfy this browser submission.
test('matches the exact OTP endpoint and POST and exposes all body field names', async () => {
  const selected = response(async () => ({ challenge_id: 'synthetic', expires_at: 'synthetic', unexpected: true }));
  const page = { waitForResponse: async (matches: (candidate: Response) => boolean) => {
    assert.equal(matches(response(async () => ({}), 202, endpoint.replace('28080', '28081'))), false);
    assert.equal(matches(response(async () => ({}), 202, endpoint + '/other')), false);
    assert.equal(matches(response(async () => ({}), 202, endpoint, 'GET')), false);
    assert.equal(matches(selected), true);
    return selected;
  } } as unknown as Pick<Page, 'waitForResponse'>;
  assert.deepEqual(await submitOtpChallenge(page, endpoint, true, async () => {}),
    { status: 202, fields: ['challenge_id', 'expires_at', 'unexpected'] });
});

test('retains unavailable status without reading a configuration error body', async () => {
  const page = { waitForResponse: async () => response(async () => { throw new Error('body_must_not_be_read'); }, 503) } as unknown as Pick<Page, 'waitForResponse'>;
  assert.deepEqual(await submitOtpChallenge(page, endpoint, false, async () => {}), { status: 503, fields: undefined });
});

test('propagates response body capture failure', async () => {
  const failure = new Error('synthetic_body_read_failed');
  const page = { waitForResponse: async () => response(async () => { throw failure; }) } as unknown as Pick<Page, 'waitForResponse'>;
  await assert.rejects(submitOtpChallenge(page, endpoint, true, async () => {}), error => error === failure);
});

test('observes capture rejection even when submission rejects first', async () => {
  const capture = Promise.withResolvers<Response>();
  const failure = new Error('synthetic_submission_failed');
  const page = { waitForResponse: () => capture.promise } as unknown as Pick<Page, 'waitForResponse'>;
  await assert.rejects(submitOtpChallenge(page, endpoint, true, async () => { throw failure; }), error => error === failure);
  capture.reject(new Error('synthetic_late_response_wait_failed'));
  await new Promise<void>(resolve => setImmediate(resolve));
});

test('observes capture rejection when submission throws synchronously', async () => {
  const capture = Promise.withResolvers<Response>();
  const failure = new Error('synthetic_sync_submission_failed');
  const page = { waitForResponse: () => capture.promise } as unknown as Pick<Page, 'waitForResponse'>;
  await assert.rejects(submitOtpChallenge(page, endpoint, true, () => { throw failure; }), error => error === failure);
  capture.reject(new Error('synthetic_late_response_wait_failed'));
  await new Promise<void>(resolve => setImmediate(resolve));
});
