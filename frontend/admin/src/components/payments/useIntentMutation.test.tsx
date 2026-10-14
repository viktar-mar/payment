import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { useIntentMutation } from "./useIntentMutation";

const path = "v1/admin/payments/payment-1/refunds";
const options = { method: "POST", body: { amount_minor: 100, currency: "EUR", reason_code: "REQUESTED_BY_CUSTOMER" } };
function unavailable() {
  return Response.json({ type: "urn:payment:problem:backend_unavailable", title: "Backend unavailable", status: 502, code: "backend_unavailable", request_id: "request-one", detail: "PRIVATE" }, { status: 502, headers: { "content-type": "application/problem+json" } });
}
function lostAcceptedBody() {
  return new Response(new ReadableStream({ start(controller) { controller.error(new TypeError("PRIVATE stream failure")); } }), { status: 202 });
}
afterEach(() => vi.unstubAllGlobals());

it.each([["BFF backend fetch loss", unavailable], ["accepted response stream loss", lostAcceptedBody], ["accepted malformed JSON", () => new Response("malformed PRIVATE", { status: 202 })]])("retains unchanged intent key after %s without automatic replay", async (_name, failure) => {
  const requests: RequestInit[] = [];
  vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => { requests.push(init); return failure(); });
  const { result } = renderHook(() => useIntentMutation()); const accepted = vi.fn();
  await act(async () => { await result.current.run(path, options, accepted); });
  expect(result.current.retry).toBe(true); expect(result.current.error).not.toContain("PRIVATE"); expect(requests).toHaveLength(1); expect(accepted).not.toHaveBeenCalled();
  await act(async () => { await result.current.run(path, options, accepted); });
  expect(requests).toHaveLength(2); expect(new Headers(requests[0].headers).get("Idempotency-Key")).toBeTruthy();
  expect(new Headers(requests[1].headers).get("Idempotency-Key")).toBe(new Headers(requests[0].headers).get("Idempotency-Key"));
  expect(requests[1].body).toBe(requests[0].body);
});

it("guards concurrent duplicate submission synchronously with one request/key", async () => {
  const requests: RequestInit[] = []; let resolve: (response: Response) => void = () => {};
  vi.stubGlobal("fetch", (_url: string, init: RequestInit) => { requests.push(init); return new Promise<Response>(done => { resolve = done; }); });
  const { result } = renderHook(() => useIntentMutation()); const accepted = vi.fn(); let first: Promise<void> | undefined;
  act(() => { first = result.current.run(path, options, accepted); void result.current.run(path, options, accepted); });
  expect(result.current.pending).toBe(true); expect(requests).toHaveLength(1); expect(new Headers(requests[0].headers).get("Idempotency-Key")).toBeTruthy();
  await act(async () => { resolve(Response.json({ status: "PENDING" }, { status: 202 })); await first; });
  expect(accepted).toHaveBeenCalledTimes(1); expect(result.current.pending).toBe(false);
});

it("replaces ambiguous intent key on an edit even when the payload is reverted", async () => {
  const keys: (string | null)[] = [];
  vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => { keys.push(new Headers(init.headers).get("Idempotency-Key")); return unavailable(); });
  const { result } = renderHook(() => useIntentMutation());
  await act(async () => { await result.current.run(path, options, () => {}); });
  act(() => result.current.changed());
  await act(async () => { await result.current.run(path, options, () => {}); });
  expect(keys[0]).toBeTruthy(); expect(keys[1]).not.toBe(keys[0]);
});

it.each([400, 409, 503])("does not retain intent key after definitive HTTP %s", async status => {
  const keys: (string | null)[] = [];
  vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => { keys.push(new Headers(init.headers).get("Idempotency-Key")); return Response.json({ title: "Action rejected", code: "rejected", detail: "PRIVATE" }, { status }); });
  const { result } = renderHook(() => useIntentMutation());
  await act(async () => { await result.current.run(path, options, () => {}); });
  expect(result.current.retry).toBe(false); expect(result.current.error).toBe("Action rejected");
  await act(async () => { await result.current.run(path, options, () => {}); });
  expect(keys[0]).toBeTruthy(); expect(keys[1]).not.toBe(keys[0]);
});

it("clears the ambiguous key after a fully consumed acceptance", async () => {
  const keys: (string | null)[] = []; let calls = 0;
  vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => { keys.push(new Headers(init.headers).get("Idempotency-Key")); return ++calls === 1 ? unavailable() : Response.json({ status: "PENDING" }, { status: 202 }); });
  const { result } = renderHook(() => useIntentMutation());
  await act(async () => { await result.current.run(path, options, () => {}); });
  await act(async () => { await result.current.run(path, options, () => {}); });
  await waitFor(() => expect(result.current.retry).toBe(false));
  await act(async () => { await result.current.run(path, options, () => {}); });
  expect(keys[1]).toBe(keys[0]); expect(keys[2]).not.toBe(keys[1]);
});
