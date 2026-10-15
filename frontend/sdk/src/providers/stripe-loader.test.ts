import { beforeEach, expect, it, vi } from 'vitest';
vi.mock('@stripe/stripe-js/pure/index.js', () => ({ loadStripe: vi.fn() }));
import { loadStripe } from '@stripe/stripe-js/pure/index.js';
import { loadStripeClient } from './stripe-loader.js';
beforeEach(() => { vi.mocked(loadStripe).mockReset(); });
it('loads distinct publishable keys without sharing a Stripe account client', async () => {
  const first = { account: 'a' }; const second = { account: 'b' };
  vi.mocked(loadStripe).mockResolvedValueOnce(first as never).mockResolvedValueOnce(second as never);
  expect(await loadStripeClient('pk_test_A')).toBe(first);
  expect(await loadStripeClient('pk_test_B')).toBe(second);
  expect(loadStripe).toHaveBeenNthCalledWith(1, 'pk_test_A');
  expect(loadStripe).toHaveBeenNthCalledWith(2, 'pk_test_B');
});
it('preserves null and rejection for the driver to safely handle', async () => {
  vi.mocked(loadStripe).mockResolvedValueOnce(null).mockRejectedValueOnce(new Error('external'));
  expect(await loadStripeClient('pk_test_A')).toBeNull();
  await expect(loadStripeClient('pk_test_B')).rejects.toThrow('external');
});
