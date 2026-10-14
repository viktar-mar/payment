import { describe, it, expect, vi } from "vitest";
import { createGenericPaymentClient } from "./client.js";
import { PaymentSdkError } from "./errors.js";
import { recoverCheckout } from "./storage.js";
import { input, wire, readWire, json, token } from "./test/fixtures.js";
describe("client transport security", () => {
  it("uses exact catalog, handoff, confirm and read contracts with isolated bearer headers", async () => {
    const requests: Request[] = [];
    vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(requests.length < 3 ? wire() : readWire()); });
    const client = createGenericPaymentClient({ apiBaseUrl: "https://API.test:443/base/" });
    const session = await client.createCatalogCheckout(input);
    await client.exchangeHandoff({ ...input, handoffToken: token });
    await client.confirm(session); await client.get(session);
    expect(requests.map(r => [r.url, r.method])).toEqual([
      ["https://api.test/base/api/v1/public/sites/test-site/checkouts", "POST"],
      ["https://api.test/base/api/v1/public/handoffs", "POST"],
      [`https://api.test/base/api/v1/public/checkouts/${session.checkoutId}/confirm`, "POST"],
      [`https://api.test/base/api/v1/public/checkouts/${session.checkoutId}`, "GET"],
    ]);
    expect(await requests[0].json()).toEqual({ price_key: "lifetime" });
    expect(await requests[1].json()).toEqual({ handoff_token: token });
    expect(await requests[2].json()).toEqual({ payment_method_family: "CARD" });
    requests.forEach((r, i) => { expect(r.mode).toBe("cors"); expect(r.credentials).toBe("omit"); expect(r.cache).toBe("no-store"); expect(r.redirect).toBe("error"); expect(r.url).not.toContain(token); expect(r.headers.get("authorization")).toBe(i < 2 ? null : `Bearer ${token}`); expect(r.headers.has("origin")).toBe(false); });
    expect(requests[0].headers.get("idempotency-key")).toMatch(/^[a-f0-9-]{36}$/);
    expect(requests[2].headers.get("idempotency-key")).not.toBe(requests[0].headers.get("idempotency-key"));
    expect(requests[1].headers.has("idempotency-key")).toBe(false);
    expect(JSON.stringify(session.state)).not.toContain(token);
  });
  it.each(["http://evil.test", "https://user@api.test", "https://api.test?q=x", "https://api.test#x", "javascript:x", "https://api.test/%2f", "https://api.test/../x"])("rejects unsafe API base %s before fetch", base => { expect(() => createGenericPaymentClient({ apiBaseUrl: base })).toThrow(PaymentSdkError); });
  it.each(["http://localhost:8080/", "http://127.0.0.1:8080", "http://[::1]:8080"])("allows documented loopback %s", base => { expect(() => createGenericPaymentClient({ apiBaseUrl: base })).not.toThrow(); });
  it("rejects identifiers before transport and never accepts amount input", async () => {
    const fetch = vi.fn(); vi.stubGlobal("fetch", fetch);
    const client = createGenericPaymentClient({ apiBaseUrl: "https://api.test" });
    for (const siteKey of ["../evil", "abc?x=1", "abc/def", "a".repeat(64)]) await expect(client.createCatalogCheckout({ ...input, siteKey })).rejects.toBeInstanceOf(PaymentSdkError);
    await expect(client.createCatalogCheckout({ ...input, amount_minor: 1 } as never)).rejects.toBeInstanceOf(PaymentSdkError);
    expect(fetch).not.toHaveBeenCalled();
  });
  it("does not replay ambiguous financial POST and preserves abort identity", async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError(token)); vi.stubGlobal("fetch", fetch);
    const client = createGenericPaymentClient({ apiBaseUrl: "https://api.test" });
    await expect(client.createCatalogCheckout(input)).rejects.toMatchObject({ code: "TRANSPORT_UNAVAILABLE", message: "Payment service unavailable" });
    expect(fetch).toHaveBeenCalledTimes(1);
    const abort = new DOMException("synthetic", "AbortError"); fetch.mockRejectedValue(abort);
    await expect(client.createCatalogCheckout(input)).rejects.toBe(abort);
  });
  it("returns fixed errors without untrusted problem fields or secret text", async () => {
    vi.stubGlobal("fetch", async () => json({ code: "CHECKOUT_EXPIRED", title: token, detail: token, request_id: token }, 410));
    await expect(createGenericPaymentClient({ apiBaseUrl: "https://api.test" }).createCatalogCheckout(input)).rejects.toMatchObject({ code: "CHECKOUT_EXPIRED", message: "Checkout expired" });
    vi.stubGlobal("fetch", async () => json({ code: token, detail: token }, 500));
    try { await createGenericPaymentClient({ apiBaseUrl: "https://api.test" }).createCatalogCheckout(input); } catch (e) { expect(JSON.stringify(e)).not.toContain(token); expect(String(e)).not.toContain(token); }
  });
  it.each([
    { status: "ALIEN" }, { provider_type: "STRIPE" }, { confirmation_mode: "CLIENT" },
    { provider_client_payload: { client_secret: "synthetic" } }, { amount_minor: 1.5 }, { currency: "eur" },
    { checkout_token: "bad" }, { expires_at: "2026-02-30T00:00:00Z" }, { payment_id: "../evil" }, { price: { price_key: "evil" } },
  ])("rejects malformed success closed %#", async patch => {
    vi.stubGlobal("fetch", async () => json({ ...wire(), ...patch }));
    await expect(createGenericPaymentClient({ apiBaseUrl: "https://api.test" }).createCatalogCheckout(input)).rejects.toBeInstanceOf(PaymentSdkError);
    expect(sessionStorage.length).toBe(0);
  });
  it("rejects redirects and malformed JSON, and read-token or identity changes", async () => {
    const client = createGenericPaymentClient({ apiBaseUrl: "https://api.test" });
    vi.stubGlobal("fetch", async () => new Response(null, { status: 302 })); await expect(client.createCatalogCheckout(input)).rejects.toBeInstanceOf(PaymentSdkError);
    vi.stubGlobal("fetch", async () => new Response("{")); await expect(client.createCatalogCheckout(input)).rejects.toBeInstanceOf(PaymentSdkError);
    vi.stubGlobal("fetch", async () => json(wire())); const session = await client.createCatalogCheckout(input);
    await expect(client.get(session)).rejects.toBeInstanceOf(PaymentSdkError);
    vi.stubGlobal("fetch", async () => json({ ...readWire(), payment_id: "44444444-4444-4444-8444-444444444444" })); await expect(client.get(session)).rejects.toBeInstanceOf(PaymentSdkError);
  });
  it("uses fresh keys for explicit decline retry on the same Payment", async () => {
    const keys: string[] = []; vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { const headers = new Headers(init.headers); if (headers.has("authorization")) keys.push(headers.get("idempotency-key")!); return json(keys.length ? readWire("REQUIRES_PAYMENT_METHOD") : wire()); });
    const client = createGenericPaymentClient({ apiBaseUrl: "https://api.test" }); const session = await client.createCatalogCheckout(input);
    expect((await client.confirm(session)).paymentId).toBe(session.state.paymentId); await client.confirm(session);
    expect(keys).toHaveLength(2); expect(keys[0]).not.toBe(keys[1]);
  });
  it("preserves response-body AbortError identity", async () => {
    const abort = new DOMException("synthetic", "AbortError"); vi.stubGlobal("fetch", async () => ({ ok: true, status: 200, text: async () => { throw abort; } }));
    await expect(createGenericPaymentClient({ apiBaseUrl: "https://api.test" }).createCatalogCheckout(input)).rejects.toBe(abort);
  });
  it("rejects cancelled late response before storing and keeps unowned replacement record on clear", async () => {
    const controller = new AbortController(); vi.stubGlobal("fetch", async () => { controller.abort(); return json(wire()); });
    await expect(createGenericPaymentClient({ apiBaseUrl: "https://api.test", signal: controller.signal }).createCatalogCheckout(input)).rejects.toMatchObject({ name: "AbortError" }); expect(sessionStorage.length).toBe(0);
    vi.stubGlobal("fetch", async () => json(wire())); const client = createGenericPaymentClient({ apiBaseUrl: "https://api.test" }); const session = await client.createCatalogCheckout(input); const recordKey = sessionStorage.key(0)!; const record = JSON.parse(sessionStorage.getItem(recordKey)!); record.checkoutToken = "Y".repeat(43); sessionStorage.setItem(recordKey, JSON.stringify(record)); client.clear(session); expect(sessionStorage.getItem(recordKey)).not.toBeNull();
  });
  it.each(["transport", "decode", "provider"])("blocks a second confirm after ambiguous %s until safe read authorizes retry", async kind => {
    const fetch = vi.fn(async () => json(wire())); vi.stubGlobal("fetch", fetch); const client = createGenericPaymentClient({ apiBaseUrl: "https://api.test" }); const session = await client.createCatalogCheckout(input);
    fetch.mockImplementationOnce(async () => { if (kind === "transport") throw new TypeError("synthetic"); return kind === "decode" ? new Response("{") : json({ ...readWire(), provider_type: "ALIEN" }); });
    await expect(client.confirm(session)).rejects.toBeInstanceOf(PaymentSdkError); expect(session.state.status).toBe("UNKNOWN");
    await expect(client.confirm(session)).rejects.toMatchObject({ code: "PAYMENT_STATE_CONFLICT" }); expect(fetch).toHaveBeenCalledTimes(2);
    fetch.mockImplementation(async () => json(readWire("REQUIRES_PAYMENT_METHOD"))); await client.get(session); await client.confirm(session); expect(fetch).toHaveBeenCalledTimes(4);
  });
  it.each(["malformed", "generic", "unrecognized", "oversized"])("Fix1 I1 blocks unusable non-OK %s confirmation until a safe read", async kind => {
    const requests: Request[] = [];
    vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
      requests.push(new Request(url, init));
      if (requests.length === 1) return json(wire());
      if (requests.length === 2) {
        if (kind === "malformed") return new Response("{", { status: 500 });
        if (kind === "oversized") return new Response("x".repeat(32769), { status: 500 });
        return json(kind === "generic" ? { detail: token } : { code: "UNRECOGNIZED", detail: token }, 500);
      }
      return json(readWire(requests.length === 3 ? "REQUIRES_PAYMENT_METHOD" : "PROCESSING"));
    });
    const client = createGenericPaymentClient({ apiBaseUrl: "https://api.test" });
    const session = await client.createCatalogCheckout(input); const paymentId = session.state.paymentId;
    await expect(client.confirm(session)).rejects.toMatchObject({ code: "REQUEST_FAILED", message: "Payment request failed" });
    expect(session.state.status).toBe("UNKNOWN");
    expect(recoverCheckout({ ...input, apiBaseUrl: "https://api.test" })?.state.status).toBe("UNKNOWN");
    await expect(client.confirm(session)).rejects.toMatchObject({ code: "PAYMENT_STATE_CONFLICT" });
    expect(requests).toHaveLength(2);
    await client.get(session); expect(session.state.status).toBe("REQUIRES_PAYMENT_METHOD");
    expect((await client.confirm(session)).paymentId).toBe(paymentId);
    expect(requests.map(r => r.method)).toEqual(["POST", "POST", "GET", "POST"]);
    expect(requests[1].url).toBe(requests[3].url);
    expect(requests[1].headers.get("idempotency-key")).not.toBe(requests[3].headers.get("idempotency-key"));
  });
  it.each(["PROVIDER_DECLINED", "PROVIDER_TEMPORARILY_UNAVAILABLE"])("Fix1 I1 preserves known recoverable %s fresh explicit intent", async code => {
    const requests: Request[] = [];
    vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return requests.length === 1 ? json(wire()) : requests.length === 2 ? json({ code }, 503) : json(readWire("PROCESSING")); });
    const client = createGenericPaymentClient({ apiBaseUrl: "https://api.test" }); const session = await client.createCatalogCheckout(input);
    await expect(client.confirm(session)).rejects.toMatchObject({ code }); expect(session.state.status).toBe("REQUIRES_PAYMENT_METHOD");
    expect((await client.confirm(session)).paymentId).toBe(session.state.paymentId);
    expect(requests).toHaveLength(3); expect(requests[1].url).toBe(requests[2].url);
    expect(requests[1].headers.get("idempotency-key")).not.toBe(requests[2].headers.get("idempotency-key"));
  });
  it.each(["CHECKOUT_EXPIRED", "CHECKOUT_TOKEN_INVALID", "ORIGIN_NOT_ALLOWED", "INVALID_CONFIGURATION", "IDEMPOTENCY_CONFLICT", "PAYMENT_STATE_CONFLICT"])("Fix1 I1 retains definitive allowlisted %s classification", async code => {
    let calls = 0; vi.stubGlobal("fetch", async () => ++calls === 1 ? json(wire()) : json({ code, detail: token }, 409));
    const client = createGenericPaymentClient({ apiBaseUrl: "https://api.test" }); const session = await client.createCatalogCheckout(input);
    await expect(client.confirm(session)).rejects.toMatchObject({ code });
    expect(session.state.status).toBe("REQUIRES_PAYMENT_METHOD"); expect(calls).toBe(2);
  });
  it.each(["catalog", "handoff"])("Fix1 I1 keeps unusable %s creation errors definitive and unreplayed", async journey => {
    const fetch = vi.fn(async () => new Response("{", { status: 500 })); vi.stubGlobal("fetch", fetch);
    const client = createGenericPaymentClient({ apiBaseUrl: "https://api.test" });
    await expect(journey === "catalog" ? client.createCatalogCheckout(input) : client.exchangeHandoff({ ...input, handoffToken: token })).rejects.toMatchObject({ code: "REQUEST_FAILED" });
    expect(fetch).toHaveBeenCalledTimes(1); expect(sessionStorage.length).toBe(0);
  });
});
