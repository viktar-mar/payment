import { expect, it } from 'vitest';
import * as sdk from '../index.js';

it('ignores completion from the previous authority', () => {
  const current = { authorityRevision: 2, phase: 'idle', subscription: null, operation: null };
  const stale = { ...current, authorityRevision: 1, phase: 'ready' };
  // Optional access lets the initial missing feature fail as a behavioral assertion.
  const accept = (sdk as unknown as { acceptManagerResult?: (s: typeof current, r: number, n: typeof stale) => unknown }).acceptManagerResult;
  expect(accept?.(current, 1, stale)).toBe(current);
});

it('preserves the owning authority revision when accepting a result', () => {
  const current = { authorityRevision: 2, phase: 'idle', subscription: null, operation: null };
  const next = { ...current, authorityRevision: 999, phase: 'ready' };
  const accept = (sdk as unknown as { acceptManagerResult?: (s: typeof current, r: number, n: typeof next) => unknown }).acceptManagerResult;
  expect(accept?.(current, 2, next)).toEqual({ ...next, authorityRevision: 2 });
});
