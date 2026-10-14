import { expect, it, vi } from 'vitest';
import * as sdk from '../index.js';
import { base, json } from '../test/customer-fixtures.js';
import { actionWire, mutationWire, operationId, subscriptionId, subscriptionSession } from '../test/subscription-fixtures.js';

it('completes a bound Fake capability once through the Customer confirmation route', async () => {
  const client = sdk.createGenericPaymentClient({ apiBaseUrl: base }); const session = subscriptionSession();
  vi.stubGlobal('fetch', async () => json(actionWire()));
  const action = await client.subscriptions.action({ session, subscriptionId, operationId });
  const seen: Request[] = []; vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { seen.push(new Request(url, init)); return json({ ...mutationWire(), status: 'SUCCEEDED' }, 202); });
  const Driver = (sdk as unknown as { SubscriptionFakeActionDriver?: new (client: sdk.SubscriptionClient) => { complete: typeof complete } }).SubscriptionFakeActionDriver;
  async function complete(_action: sdk.SubscriptionAction, _input: sdk.SubscriptionMutation & { operationId: string }): Promise<sdk.SubscriptionOperation> { throw new Error('missing driver'); }
  expect(Driver).toBeTypeOf('function');
  const driver = new Driver!(client.subscriptions); const input = { session, subscriptionId, operationId, idempotencyKey: 'explicit-confirmation' };
  expect(await driver.complete(action, input)).toMatchObject({ status: 'SUCCEEDED' });
  expect(seen[0].url).toBe(`${base}/api/v1/customer/subscriptions/${subscriptionId}/operations/${operationId}/action-confirmations`);
  expect(seen[0].headers.get('idempotency-key')).toBe('explicit-confirmation');
  await expect(driver.complete(action, input)).rejects.toMatchObject({ code: 'INVALID_SESSION' }); expect(seen).toHaveLength(1);
});
