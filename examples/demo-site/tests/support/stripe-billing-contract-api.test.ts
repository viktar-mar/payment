import assert from 'node:assert/strict';
import test from 'node:test';
// @ts-ignore Native Node executes the same source as Playwright.
import {contractProviderRequestAllowed} from './stripe-billing-contract-api.ts';

const owner = { key: 'pk_test_fixture', secret: 'pi_owned_secret_fixture' };
const api = 'https://api.stripe.com';
const ownedAuth = { authorization: 'Bearer ' + owner.key };
const formType = { 'content-type': 'application/x-www-form-urlencoded' };
const request = (url: string, method = 'POST', headers: Record<string, string> = {},
                 body = 'source=opaque') => contractProviderRequestAllowed(new URL(url), method,
  url.startsWith(api + '/') && method === 'POST' ? { ...formType, ...headers } : headers, owner, body);

test('the 3DS network boundary permits different authentication routes and auxiliary provider requests', () => {
  assert.equal(request(api + '/v1/3ds2/authenticate', 'POST', {}, 'key=' + owner.key), true);
  assert.equal(request(api + '/v1/3ds2/next-step', 'POST', { authorization: 'Bearer ' + owner.key }), true);
  assert.equal(request(api + '/v1/3ds2/next-step', 'OPTIONS', { 'access-control-request-method': 'POST' }), true);
  assert.equal(request('https://hooks.stripe.com/issuer/arbitrary-resource'), true);
  assert.equal(request('https://issuer.stripe.com/challenge/updated-asset', 'GET'), true);
});

test('the 3DS network boundary permits only the original PaymentIntent read', () => {
  const owned = api + '/v1/payment_intents/pi_owned?client_secret=' + owner.secret;
  assert.equal(request(owned, 'GET', ownedAuth), true);
  assert.equal(request(owned, 'OPTIONS', { 'access-control-request-method': 'GET' }), true);
  assert.equal(request(owned, 'POST', ownedAuth), false);
  assert.equal(request(owned + '&client_secret=' + owner.secret, 'GET', ownedAuth), false);
  assert.equal(request(owned.replace('pi_owned?', 'pi_foreign?'), 'GET', ownedAuth), false);
  assert.equal(request(owned.replace('secret_fixture', 'secret_foreign'), 'GET', ownedAuth), false);
  for (const path of ['/v1/payment_intents', '/v1/payment_intents/pi_owned/confirm', '/v1/confirmation_tokens',
    '/v1/payment_methods', '/v1/refunds', '/v1/3ds2/%2frefunds', '/v1/3ds2']) {
    assert.equal(request(api + path, 'POST', ownedAuth), false, path);
  }
});

test('Stripe API authentication calls refuse missing or overridden Stripe credentials', () => {
  const url = api + '/v1/3ds2/authenticate';
  for (const body of [
    'key=pk_live_fixture', 'key=pk_test_other', 'stripe_account=acct_foreign',
    'client_secret=pi_foreign_secret_fixture', 'key%5B%5D=' + owner.key,
    'source=%invalid', 'key=' + owner.key + '&key=' + owner.key,
  ]) assert.equal(request(url, 'POST', ownedAuth, body), false, body);
  assert.equal(request(url, 'POST'), false);
  assert.equal(request(url, 'POST', { ...ownedAuth, 'content-type': 'application/json' }, '{"key":"pk_live_fixture"}'), false);
  assert.equal(request(url, 'POST', ownedAuth, 'unrecognized_provider_field=opaque'), true);
});

test('provider traffic rejects foreign account context, authorization and cookies', () => {
  for (const headers of [
    { authorization: 'Bearer pk_test_foreign' }, { cookie: 'session=private' },
    { 'stripe-account': 'acct_foreign' }, { 'stripe-context': 'acct_foreign' }, { 'proxy-authorization': 'Basic private' },
  ] as Record<string, string>[]) {
    assert.equal(request(api + '/v1/3ds2/authenticate', 'POST', headers), false);
  }
  assert.equal(request('https://hooks.stripe.com/issuer', 'POST', { authorization: 'Bearer ' + owner.key }), false);
  assert.equal(request(api + '/v1/3ds2/authenticate', 'POST', { authorization: '' }), false);
  assert.equal(request(api + '/v1/3ds2/authenticate', 'PATCH'), false);
  assert.equal(request(api + '/v1/3ds2/authenticate?key=pk_live_foreign'), false);
  assert.equal(contractProviderRequestAllowed(new URL(api + '/v1/3ds2/authenticate'), 'POST', {},
    { ...owner, key: 'pk_live_fixture' }), false);
});
