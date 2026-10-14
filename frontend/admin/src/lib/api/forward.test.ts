// @vitest-environment node
import { NextRequest } from "next/server";
import { afterEach, expect, it, vi } from "vitest";
import { forward } from "./forward";
it("forwards only the explicit idempotency header and strips arbitrary/unsafe headers and response reflection", async () => {
  let forwarded: Headers | undefined;
  vi.stubGlobal("fetch", async (_url: unknown, init: RequestInit) => { forwarded = new Headers(init.headers); return Response.json({}, { headers: { "idempotency-key": "intent", "x-arbitrary": "no" } }); });
  const response = await forward(new NextRequest("http://admin/api/backend/v1/admin/payments/p/reconcile", { method: "POST", headers: { "idempotency-key": "intent", authorization: "blocked", host: "blocked", connection: "blocked", forwarded: "blocked", "x-forwarded-for": "blocked", "x-arbitrary": "blocked" } }), ["v1", "admin", "payments", "p", "reconcile"]);
  expect(forwarded?.get("idempotency-key")).toBe("intent"); for (const name of ["authorization", "host", "connection", "forwarded", "x-forwarded-for", "x-arbitrary"]) expect(forwarded?.get(name)).toBeNull(); expect(response.headers.get("idempotency-key")).toBeNull();
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});
it("admits explicit subscription routes and blocks sibling credential surfaces, unsupported verbs and query injection", async () => {
  let calls = 0; vi.stubGlobal("fetch", async () => { calls++; return Response.json({ status: "UNKNOWN" }); });
  const id = "40000000-0000-0000-0000-000000000004", site = "10000000-0000-0000-0000-000000000001";
  for (const path of [`v1/customer/subscriptions/${id}`, `v1/partner/subscriptions/${id}`, `v1/admin/subscriptions/${id}/unexpected`]) {
    expect((await forward(new NextRequest(`http://admin/api/backend/${path}?site_id=${site}`), path.split("/"))).status).toBe(404);
  }
  const path = `v1/admin/subscriptions/${id}/cancellation`;
  expect((await forward(new NextRequest(`http://admin/api/backend/${path}?site_id=${site}`, { method: "DELETE" }), path.split("/"))).status).toBe(405);
  expect((await forward(new NextRequest(`http://admin/api/backend/v1/admin/subscriptions?site_id=${site}&site_id=${site}`), ["v1", "admin", "subscriptions"])).status).toBe(400);
  expect(calls).toBe(0);
  expect((await forward(new NextRequest(`http://admin/api/backend/${path}?site_id=${site}`, { method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": "intent" }, body: '{"mode":"PERIOD_END"}' }), path.split("/"))).status).toBe(200); expect(calls).toBe(1);
});
it("bounds subscription bodies and responses and does not reflect action secrets in transport diagnostics", async () => {
  const id = "40000000-0000-0000-0000-000000000004", site = "10000000-0000-0000-0000-000000000001", path = `v1/admin/subscriptions/${id}/portal-sessions`; let calls = 0;
  vi.stubGlobal("fetch", async () => { calls++; return new Response("PRIVATE-ACTION-SECRET".repeat(2000), { headers: { "content-type": "application/json", location: "https://private.invalid/token" } }); });
  const request = (body: string) => new NextRequest(`http://admin/api/backend/${path}?site_id=${site}`, { method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": "intent" }, body });
  expect((await forward(request("x".repeat(8193)), path.split("/"))).status).toBe(413); expect(calls).toBe(0);
  const result = await forward(request('{"return_url":"http://localhost:3000/subscriptions"}'), path.split("/")); expect(result.status).toBe(502); expect(await result.text()).not.toMatch(/PRIVATE|private\.invalid/); expect(result.headers.get("location")).toBeNull();
});
it("preserves session/CSRF cookies while sanitizing a subscription error", async () => {
  const cookies = ["PAYMENT_ADMIN_SESSION=; Path=/; Max-Age=0; HttpOnly", "XSRF-TOKEN=next; Path=/"];
  vi.stubGlobal("fetch", async () => { const headers = new Headers(); cookies.forEach(cookie => headers.append("set-cookie", cookie)); return Response.json({ title: "PRIVATE-ACTION-SECRET" }, { status: 409, headers }); });
  const path = "v1/admin/subscriptions/40000000-0000-0000-0000-000000000004";
  const response = await forward(new NextRequest(`http://admin/api/backend/${path}?site_id=10000000-0000-0000-0000-000000000001`), path.split("/"));
  expect(response.status).toBe(409); expect(await response.text()).not.toContain("PRIVATE"); expect(response.headers.getSetCookie()).toEqual(cookies);
});
it("allowlists only actual catalog and integration route verbs", async () => {
  const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
  for (const [path, method] of [["admin/v1/sites/site/catalog", "POST"], ["admin/v1/sites/site/products", "DELETE"], ["v1/admin/sites/site/identity-providers/provider", "POST"], ["v1/admin/sites/site/donation-campaigns", "PUT"]]) {
    expect((await forward(new NextRequest(`http://admin/api/backend/${path}`, { method }), path.split("/"))).status).toBe(405);
  }
  expect(fetcher).not.toHaveBeenCalled();
});
it.each([
  { segments: [".."], status: 400, code: "invalid_api_path", title: "Invalid API path" },
  { segments: ["admin", "v1", "sites"], status: 502, code: "backend_unavailable", title: "Backend unavailable" },
])("returns a complete problem for $code with a validated or generated request id", async ({ segments, status, code, title }) => {
  vi.stubGlobal("fetch", async () => { throw new Error("private upstream secret"); });
  for (const incoming of ["a:b.c-1_2", "x".repeat(64), "", "bad id", "x".repeat(65), "<unsafe>", undefined]) {
    const response = await forward(new NextRequest("http://admin/api/backend/x", {
      headers: incoming === undefined ? {} : { "x-request-id": incoming },
    }), segments);
    expect(response.status).toBe(status);
    expect(response.headers.get("content-type")).toBe("application/problem+json");
    expect(response.headers.get("cache-control")).toBe("no-store");
    const requestId = response.headers.get("x-request-id");
    if (incoming === "a:b.c-1_2" || incoming === "x".repeat(64)) expect(requestId).toBe(incoming);
    else expect(requestId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(await response.json()).toEqual({ type: `urn:payment:problem:${code}`, title, status, code, request_id: requestId });
  }
});

it("preserves session deletion and CSRF cookies as separate headers on logout", async () => {
  const cookies = [
    "PAYMENT_ADMIN_SESSION=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; SameSite=Strict",
    "XSRF-TOKEN=next-token; Path=/; SameSite=Strict",
  ];
  vi.stubGlobal("fetch", async () => {
    const headers = new Headers();
    cookies.forEach((cookie) => headers.append("set-cookie", cookie));
    return new Response(null, { status: 204, headers });
  });
  const response = await forward(new NextRequest("http://admin/api/backend/admin/v1/auth/session", { method: "DELETE" }), ["admin", "v1", "auth", "session"]);
  expect(response.status).toBe(204);
  expect(await response.text()).toBe("");
  expect(response.headers.getSetCookie()).toEqual(cookies);
});
it("forwards the fixed destination, safe request data, status and every cookie unchanged", async () => {
  vi.stubEnv("BACKEND_INTERNAL_URL", "http://backend:8080");
  let target: unknown;
  let options: RequestInit | undefined;
  const cookies = [
    "PAYMENT_ADMIN_SESSION=abc; Path=/; HttpOnly; Secure; SameSite=Strict",
    "XSRF-TOKEN=xyz; Path=/; SameSite=Strict",
  ];
  vi.stubGlobal("fetch", async (url: unknown, init: RequestInit) => {
    target = String(url);
    options = init;
    const headers = new Headers({
      "content-type": "application/json",
      connection: "close",
    });
    cookies.forEach((cookie) => headers.append("set-cookie", cookie));
    return new Response('{"ok":true}', { status: 201, headers });
  });
  const result = await forward(
    new NextRequest("http://admin/api/backend/admin/v1/sites?limit=3", {
      method: "POST",
      headers: {
        cookie: "session=value",
        "x-xsrf-token": "xyz",
        host: "evil",
        connection: "close",
        "content-type": "application/json",
      },
      body: "{}",
    }),
    ["admin", "v1", "sites"],
  );
  expect(target).toBe("http://backend:8080/api/admin/v1/sites?limit=3");
  expect(options).toMatchObject({
    method: "POST",
    redirect: "manual",
    cache: "no-store",
  });
  expect(new Headers(options?.headers).get("cookie")).toBe("session=value");
  expect(new Headers(options?.headers).get("host")).toBeNull();
  expect(new Headers(options?.headers).get("connection")).toBeNull();
  expect(new TextDecoder().decode(options?.body as ArrayBuffer)).toBe("{}");
  expect(result.status).toBe(201);
  expect(await result.json()).toEqual({ ok: true });
  expect(result.headers.getSetCookie()).toEqual(cookies);
  expect(result.headers.get("connection")).toBeNull();
});
it("rejects unsafe segments before forwarding and sanitizes connection failures", async () => {
  expect(
    (
      await forward(new NextRequest("http://admin/api/backend/x"), [
        "..",
        "evil",
      ])
    ).status,
  ).toBe(400);
  vi.stubGlobal("fetch", async () => {
    throw new Error("secret upstream address");
  });
  const result = await forward(new NextRequest("http://admin/api/backend/x"), [
    "admin",
    "v1",
    "sites",
  ]);
  expect(result.status).toBe(502);
  expect(await result.json()).toMatchObject({
    title: "Backend unavailable",
    status: 502,
  });
});
