import { it, expect, vi } from "vitest";
import { pollCheckout, abortableSleep } from "./polling.js";
const state = (status = "PROCESSING") => ({ status, expiresAt: new Date(Date.now() + 86400000).toISOString() }) as never;
it("reads immediately, sequentially and applies literal bounded delays without test jitter", async () => {
  const delays: number[] = []; let count = 0; let active = 0;
  const values = [];
  for await (const s of pollCheckout(async () => { expect(active++).toBe(0); await Promise.resolve(); active--; return state(++count === 8 ? "SUCCEEDED" : "PROCESSING"); }, { expiresAt: new Date(Date.now() + 86400000).toISOString() }, {}, { now: () => 0, wallNow: Date.now, random: () => 0.5, sleep: async ms => { delays.push(ms); }, jitter: false })) values.push(s);
  expect(delays).toEqual([500, 1000, 2000, 4000, 5000, 5000, 5000]); expect(values).toHaveLength(8);
});
it.each([0, 0.5, 1])("bounds production jitter and deadline with random %s", async random => {
  const delays: number[] = []; let time = 0;
  for await (const _ of pollCheckout(async () => state(), { expiresAt: new Date(Date.now() + 86400000).toISOString() }, {}, { now: () => time, wallNow: Date.now, random: () => random, sleep: async ms => { delays.push(ms); time += ms; }, jitter: true })) {}
  expect(time).toBe(600000); expect(delays.every(ms => ms > 0 && ms <= 5000)).toBe(true); expect(delays[0]).toBeGreaterThanOrEqual(400); expect(delays[0]).toBeLessThanOrEqual(500);
});
it.each(["SUCCEEDED", "FAILED", "CANCELLED", "REFUNDED", "PARTIALLY_REFUNDED", "EXPIRED", "REQUIRES_PAYMENT_METHOD"])("stops on %s without timer", async status => {
  const sleep = vi.fn(); const values = []; for await (const s of pollCheckout(async () => state(status), { expiresAt: new Date(Date.now() + 86400000).toISOString() }, {}, { sleep })) values.push(s);
  expect(values).toHaveLength(1); expect(sleep).not.toHaveBeenCalled();
});
it("checks expiry before read and clamps sleep to expiry", async () => {
  const read = vi.fn(async () => state()); let wall = Date.now(); const initial = wall; const delays: number[] = []; const values = [];
  for await (const s of pollCheckout(read, { expiresAt: new Date(wall + 250).toISOString() }, {}, { wallNow: () => wall, now: () => wall - initial, jitter: false, sleep: async ms => { delays.push(ms); wall += ms; } })) values.push(s);
  expect(delays).toEqual([250]); expect(read).toHaveBeenCalledTimes(1); expect(values.at(-1)).toMatchObject({ status: "EXPIRED" });
});
it("propagates original read abort and pre-abort without reads", async () => {
  const abort = new DOMException("synthetic", "AbortError"); const iterator = pollCheckout(async () => { throw abort; }, { expiresAt: new Date(Date.now() + 86400000).toISOString() })[Symbol.asyncIterator]();
  await expect(iterator.next()).rejects.toBe(abort);
  const controller = new AbortController(); controller.abort(abort); const read = vi.fn(); await expect(pollCheckout(read, { expiresAt: new Date(Date.now() + 10000).toISOString() }, { signal: controller.signal })[Symbol.asyncIterator]().next()).rejects.toBe(abort); expect(read).not.toHaveBeenCalled();
});
it("cleans timer and abort listener on sleep success or cancellation", async () => {
  vi.useFakeTimers(); const c = new AbortController(); const remove = vi.spyOn(c.signal, "removeEventListener");
  const pending = abortableSleep(500, c.signal); const rejection = expect(pending).rejects.toMatchObject({ name: "AbortError" }); c.abort(); await rejection;
  expect(vi.getTimerCount()).toBe(0); expect(remove).toHaveBeenCalledTimes(1);
  const d = new AbortController(); const success = abortableSleep(500, d.signal); await vi.advanceTimersByTimeAsync(500); await success; expect(vi.getTimerCount()).toBe(0);
});
it("expiry aborts first in-flight read and returns a safe expired state without an abort leak", async () => {
  vi.useFakeTimers(); const start = Date.now(); let signal!: AbortSignal;
  const initial = { ...state(), expiresAt: new Date(start + 250).toISOString() };
  const iterator = pollCheckout(async active => { signal = active!; return new Promise((_, reject) => active!.addEventListener("abort", () => reject(active!.reason), { once: true })); }, { expiresAt: new Date(start + 250).toISOString(), state: initial } as never)[Symbol.asyncIterator]();
  const next = iterator.next().then(value => value, error => error); await vi.advanceTimersByTimeAsync(250); expect(signal.aborted).toBe(true); expect(await next).toMatchObject({ value: { status: "EXPIRED" }, done: false }); await iterator.return!(); expect(vi.getTimerCount()).toBe(0);
});
it("watch deadline aborts a first stalled read even after wall clock moves backwards", async () => {
  vi.useFakeTimers(); const start = Date.now(); let signal!: AbortSignal;
  const iterator = pollCheckout(async active => { signal = active!; return new Promise((_, reject) => active!.addEventListener("abort", () => reject(active!.reason), { once: true })); }, { expiresAt: new Date(start + 86400000).toISOString(), state: state() } as never, {}, { wallNow: () => start - 100000 })[Symbol.asyncIterator]();
  const next = iterator.next().then(value => value, error => error); await vi.advanceTimersByTimeAsync(600000); expect(signal.aborted).toBe(true); expect(await next).toMatchObject({ value: { status: "UNKNOWN" }, done: false }); await iterator.return!(); expect(vi.getTimerCount()).toBe(0);
});
it("cancellation during polling sleep rejects and removes every timer", async () => {
  vi.useFakeTimers(); const controller = new AbortController(); const iterator = pollCheckout(async () => state(), { expiresAt: new Date(Date.now() + 10000).toISOString() }, { signal: controller.signal })[Symbol.asyncIterator]();
  await iterator.next(); const pending = iterator.next(); const rejection = expect(pending).rejects.toMatchObject({ name: "AbortError" }); controller.abort(); await rejection; expect(vi.getTimerCount()).toBe(0);
});
