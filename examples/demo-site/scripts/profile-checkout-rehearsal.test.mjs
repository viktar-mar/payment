import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {buildRehearsal} from './profile-checkout-rehearsal.mjs';

test('rehearses the actual checkout document with only its two local scripts allowed', async () => {
  const html = await buildRehearsal();
  const original = await readFile(new URL('../billing-failed-renewal.html', import.meta.url), 'utf8');
  for (const id of ['pay', 'confirm-method']) {
    const button = original.match(new RegExp('<button id="' + id + '"[^>]*>[^<]+</button>'))[0];
    assert.ok(html.includes(button));
  }
  const policy = html.match(/http-equiv="Content-Security-Policy" content="([^"]+)"/)[1];
  assert.match(policy, /default-src 'none'/);
  assert.match(policy, /connect-src 'none'/);
  assert.match(policy, /form-action 'none'/);
  const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(match => match[1]);
  assert.equal(scripts.length, 2);
  const hashes = scripts.map(s => "'sha256-" + createHash('sha256').update(s).digest('base64') + "'");
  assert.ok(policy.split('; ').includes('script-src ' + hashes.join(' ')));
  assert.doesNotMatch(html, /<script[^>]+src=|pk_(test|live)_|https:\/\//);
  assert.match(scripts[0], /event\.isTrusted/);
  assert.doesNotMatch(scripts[1], /\.confirm\(\)|\.onclick\s*=|dispatchEvent/);
});
