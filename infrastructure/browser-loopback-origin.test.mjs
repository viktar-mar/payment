import test from 'node:test';
import assert from 'node:assert/strict';
import { alternateLoopbackOrigin } from '../examples/demo-site/tests/support/loopback-origin.mjs';

test('CORS fixture changes only loopback hostname, retaining the actual server port', () => {
  for (const [input, expected] of [
    ['http://127.0.0.1:4173', 'http://localhost:4173'],
    ['http://127.0.0.1:41827', 'http://localhost:41827'],
    ['http://localhost:41828', 'http://127.0.0.1:41828'],
    ['https://127.0.0.1:41829/demo?not-forwarded=1#fragment', 'https://localhost:41829'],
  ]) {
    const actual = alternateLoopbackOrigin(input);
    assert.equal(actual, expected);
    assert.notEqual(actual, new URL(input).origin);
    assert.equal(new URL(actual).port, new URL(input).port);
  }
});

test('CORS fixture refuses non-owned hosts and non-HTTP navigation', () => {
  for (const input of ['https://example.test:4173', 'file:///tmp/demo', 'not a URL']) {
    assert.throws(() => alternateLoopbackOrigin(input));
  }
});
