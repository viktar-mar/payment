import { expect, it } from 'vitest';
import { parseAuthentication, validateCustomerSession } from './contracts.js';
import { recoverCustomerSession } from './session-storage.js';
import { base, customerWire, sessionKey } from '../test/customer-fixtures.js';
import { siteId, subscriptionSession } from '../test/subscription-fixtures.js';
it('accepts authenticated subscription Site metadata independently of subscription responses', () => {
  const raw = { ...customerWire(), scopes: subscriptionSession().scopes, site_id: siteId };
  expect(parseAuthentication(raw, 'AUTHENTICATE', base, 'test-site')).toHaveProperty('siteId', siteId);
  expect(() => validateCustomerSession({ ...subscriptionSession(), siteId })).not.toThrow();
});
it.each([undefined, null, 'bad', 42])('requires a valid independently authenticated Site UUID for subscription scopes (%s)', site => {
  const raw = { ...customerWire(), scopes: subscriptionSession().scopes, ...(site === undefined ? {} : { site_id: site }) };
  expect(() => parseAuthentication(raw, 'AUTHENTICATE', base, 'test-site')).toThrowError(expect.objectContaining({ code: 'INVALID_RESPONSE' }));
  const { siteId: _, ...session } = { ...subscriptionSession(), siteId };
  expect(() => validateCustomerSession({ ...session, ...(site === undefined ? {} : { siteId: site }) })).toThrowError(expect.objectContaining({ code: 'INVALID_SESSION' }));
});
it('recovers a Site-bound V1 record but removes subscription authority missing Site identity', () => {
  const session = { ...subscriptionSession(), siteId };
  sessionStorage.setItem(sessionKey(), JSON.stringify({ version: 1, ...session }));
  expect(recoverCustomerSession({ apiBaseUrl: base, siteKey: 'test-site' })).toEqual(session);
  const { siteId: _, ...unbound } = session;
  sessionStorage.setItem(sessionKey(), JSON.stringify({ version: 1, ...unbound }));
  expect(recoverCustomerSession({ apiBaseUrl: base, siteKey: 'test-site' })).toBeNull();
  expect(sessionStorage.getItem(sessionKey())).toBeNull();
});
