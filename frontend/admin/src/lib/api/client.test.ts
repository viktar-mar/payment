import { afterEach, expect, it, vi } from "vitest";
import { api, ApiError, type ApiOptions } from "./client";
it("rejects non-scalar queries and invalid safe-header values before fetch", async () => {
  const fetchSpy = vi.fn(); vi.stubGlobal("fetch", fetchSpy);
  for (const value of [{ nested: "value" }, ["valid", {}], Infinity, NaN]) await expect(api("v1/admin/payments", { query: { value } as unknown as ApiOptions["query"] })).rejects.toThrow("Invalid query value");
  for (const value of ["", "x".repeat(65), "bad\r\nheader", "white space"]) await expect(api("v1/admin/payments", { headers: { "X-Request-ID": value } })).rejects.toThrow("Invalid caller headers");
  expect(fetchSpy).not.toHaveBeenCalled();
});
it("serializes separate scalar/repeated queries deterministically without empty values", async () => {
  let destination: unknown;
  vi.stubGlobal("fetch", async (url: unknown) => { destination = url; return new Response(null, { status: 204 }); });
  await api("v1/admin/payments", { query: { z: ["one", "two"], empty: "", missing: undefined, nil: null, order: "a&b ?/+", page: 0 } });
  expect(destination).toBe("/api/backend/v1/admin/payments?order=a%26b+%3F%2F%2B&page=0&z=one&z=two");
  await expect(api("v1/admin/payments?site=x")).rejects.toThrow("Invalid API path");
});
it("emits exactly one validated dedicated idempotency key and a bounded safe request id", async () => {
  let headers: Headers | undefined;
  vi.stubGlobal("fetch", async (_url: unknown, init: RequestInit) => { headers = new Headers(init.headers); return new Response(null, { status: 204 }); });
  await api("v1/admin/payments/p/reconcile", { method: "POST", idempotencyKey: "intent-one", headers: { "X-Request-ID": "request-one" } });
  expect(headers?.get("Idempotency-Key")).toBe("intent-one"); expect(headers?.get("X-Request-ID")).toBe("request-one");
  for (const idempotencyKey of ["", " ", "x".repeat(256), "bad\nkey"]) await expect(api("v1/admin/payments", { idempotencyKey })).rejects.toThrow("Invalid idempotency key");
});
it.each(["Cookie", "Authorization", "Origin", "Host", "Forwarded", "X-Forwarded-For", "Content-Length", "Idempotency-Key", "Accept", "Content-Type", "X-XSRF-TOKEN"])("rejects caller override %s before fetch", async (name) => {
  const fetchSpy = vi.fn(); vi.stubGlobal("fetch", fetchSpy);
  // Exercise the runtime boundary even when an untyped caller bypasses TypeScript.
  await expect(api("v1/admin/payments", { headers: { [name]: "unsafe" } })).rejects.toThrow("Invalid caller headers"); expect(fetchSpy).not.toHaveBeenCalled();
});
it("preserves aborts and normalizes other transport failures", async () => {
  const aborted = new DOMException("abort", "AbortError"); vi.stubGlobal("fetch", async () => { throw aborted; }); await expect(api("v1/admin/payments")).rejects.toBe(aborted);
  vi.stubGlobal("fetch", async () => { throw new TypeError("PRIVATE"); }); await expect(api("v1/admin/payments")).rejects.toEqual(new ApiError("Unable to connect", 0, "ambiguous"));
});
afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; Max-Age=0; Path=/";
});
it("sends same-origin JSON mutations with the decoded CSRF cookie", async () => {
  document.cookie = "XSRF-TOKEN=token%2Bvalue; Path=/";
  let received: RequestInit | undefined;
  let destination: unknown;
  vi.stubGlobal("fetch", async (url: unknown, init: RequestInit) => {
    destination = url;
    received = init;
    return new Response(null, { status: 204 });
  });
  expect(
    await api("admin/v1/auth/sessions", {
      method: "POST",
      body: { username: "admin", password: "secret" },
    }),
  ).toBeUndefined();
  expect(destination).toBe("/api/backend/admin/v1/auth/sessions");
  expect(new Headers(received?.headers).get("X-XSRF-TOKEN")).toBe(
    "token+value",
  );
  expect(received).toMatchObject({
    method: "POST",
    credentials: "same-origin",
    cache: "no-store",
    body: '{"username":"admin","password":"secret"}',
  });
});
it("surfaces only stable Problem Details titles, never backend detail or unexpected body", async () => {
  vi.stubGlobal("fetch", async () =>
    Response.json(
      {
        title: "Invalid credentials",
        detail: "secret",
        code: "invalid_credentials",
      },
      { status: 401 },
    ),
  );
  await expect(api("admin/v1/sites")).rejects.toMatchObject({
    title: "Invalid credentials",
    status: 401,
  });
  vi.stubGlobal(
    "fetch",
    async () => new Response("secret stack trace", { status: 502 }),
  );
  await expect(api("admin/v1/sites")).rejects.toEqual(
    new ApiError("Request failed", 502),
  );
});
it("rejects arbitrary destinations and path traversal", async () => {
  for (const path of [
    "https://evil.test",
    "//evil.test",
    "admin/../secret",
    "admin/%2e%2e/secret",
  ]) {
    await expect(api(path)).rejects.toThrow("Invalid API path");
  }
});

it("classifies only the stable BFF unavailable problem as ambiguous without exposing extra fields", async () => {
  const stable = { type: "urn:payment:problem:backend_unavailable", title: "Backend unavailable", status: 502, code: "backend_unavailable", request_id: "request-one", detail: "PRIVATE" };
  vi.stubGlobal("fetch", async () => Response.json(stable, { status: 502, headers: { "content-type": "application/problem+json" } }));
  await expect(api("v1/admin/payments/p/refunds", { method: "POST" })).rejects.toMatchObject({ title: "Backend unavailable", status: 502, outcome: "ambiguous" });
  for (const problem of [{ ...stable, type: "urn:other" }, { ...stable, status: 503 }, { ...stable, code: "other" }, { title: "Backend unavailable", code: "backend_unavailable" }]) {
    vi.stubGlobal("fetch", async () => Response.json(problem, { status: 502, headers: { "content-type": "application/problem+json" } }));
    await expect(api("v1/admin/payments/p/refunds", { method: "POST" })).rejects.toMatchObject({ status: 502, outcome: "definitive" });
  }
  vi.stubGlobal("fetch", async () => Response.json(stable, { status: 502 }));
  await expect(api("v1/admin/payments/p/refunds", { method: "POST" })).rejects.toMatchObject({ status: 502, outcome: "definitive" });
});

it("normalizes unsuccessful consumption of successful response bodies as ambiguous", async () => {
  vi.stubGlobal("fetch", async () => new Response(new ReadableStream({ start(controller) { controller.error(new TypeError("PRIVATE")); } }), { status: 202 }));
  await expect(api("v1/admin/payments/p/refunds", { method: "POST" })).rejects.toMatchObject({ title: "Unable to connect", status: 202, outcome: "ambiguous" });
  vi.stubGlobal("fetch", async () => new Response("invalid JSON PRIVATE", { status: 202 }));
  await expect(api("v1/admin/payments/p/refunds", { method: "POST" })).rejects.toMatchObject({ title: "Request failed", status: 202, outcome: "ambiguous" });
});

it("preserves response-consumption aborts for successful and error responses", async () => {
  const abort = new DOMException("abort", "AbortError");
  for (const status of [202, 502]) {
    vi.stubGlobal("fetch", async () => new Response(new ReadableStream({ start(controller) { controller.error(abort); } }), { status }));
    await expect(api("v1/admin/payments/p/refunds", { method: "POST" })).rejects.toBe(abort);
  }
});
