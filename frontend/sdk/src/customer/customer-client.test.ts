import { expect, it, vi } from "vitest";
import { createGenericPaymentClient } from "../client.js";
import * as sdk from "../index.js";
import { authInput, base, customerToken, customerWire, future, id, json, sessionKey } from "../test/customer-fixtures.js";
import { wire, readWire, input, token } from "../test/fixtures.js";
const client = (signal?: AbortSignal): any => createGenericPaymentClient({ apiBaseUrl: base, signal });
it("uses separate SITE_TOKEN session and LINK proof endpoints and never sends source in headers", async () => {
  const requests: Request[] = []; vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(requests.length === 1 ? customerWire() : { proof_token: "P".repeat(43), expires_at: future() }); });
  const c = client(); const s = await c.exchangeSiteToken(authInput); const proof = await c.exchangeSiteToken({ ...authInput, intent: "LINK" });
  expect(proof.proofToken).toBe("P".repeat(43)); expect(s.sessionToken).toBe(customerToken);
  expect(requests.map(r => r.url)).toEqual([base + "/api/v1/public/customer-auth/site-token/exchanges", base + "/api/v1/public/customer-auth/site-token/link-proofs"]);
  for (const r of requests) { expect(await r.json()).toEqual({ site_key: "test-site", provider_key: "site-token", token: "source-secret" }); expect(r.headers.has("authorization")).toBe(false); expect(r.headers.has("origin")).toBe(false); expect(r.mode).toBe("cors"); expect(r.credentials).toBe("omit"); expect(r.cache).toBe("no-store"); expect(r.redirect).toBe("error"); }
  expect(sessionStorage.getItem(sessionKey())).not.toContain(proof.proofToken);
});
it("maps OIDC and OTP wire contracts and validates expected intent", async () => {
  const requests: Request[] = []; const exp = future();
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(requests.length === 1 ? { attempt_id: id, nonce: "N".repeat(43), expires_at: exp } : requests.length === 3 ? { challenge_id: id, expires_at: exp } : customerWire(), requests.length === 1 ? 201 : requests.length === 3 ? 202 : 200); });
  const c = client(); const attempt = await c.beginOidcAuthentication({ siteKey: "test-site", providerKey: "oidc-token", intent: "AUTHENTICATE" });
  expect(attempt).toEqual({ attemptId: id, nonce: "N".repeat(43), expiresAt: exp });
  await c.exchangeOidcToken({ siteKey: "test-site", providerKey: "oidc-token", intent: "AUTHENTICATE", ...attempt, token: "jwt-secret" });
  expect(await requests[1].json()).toEqual({ site_key: "test-site", provider_key: "oidc-token", intent: "AUTHENTICATE", attempt_id: id, nonce: "N".repeat(43), token: "jwt-secret" });
  const challenge = await c.requestEmailOtp({ siteKey: "test-site", email: "private@example.test", intent: "AUTHENTICATE" }); expect(challenge).toEqual({ challengeId: id, expiresAt: exp });
  await c.verifyEmailOtp({ siteKey: "test-site", challengeId: id, code: "012345", intent: "AUTHENTICATE" });
  expect(requests[2].url).toBe(base + "/api/v1/public/customer-auth/email-otp/challenges"); expect(await requests[2].json()).toEqual({ site_key: "test-site", email: "private@example.test", intent: "AUTHENTICATE" });
  expect(requests[3].url).toBe(base + `/api/v1/public/customer-auth/email-otp/challenges/${id}/verify`); expect(await requests[3].json()).toEqual({ site_key: "test-site", code: "012345" });
  await expect(c.verifyEmailOtp({ siteKey: "test-site", challengeId: id, code: "012345", intent: "LINK" })).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  expect(sessionStorage.getItem(sessionKey())).not.toMatch(/private|012345|jwt-secret|nonce/);
});
it("attaches matching sessions only to catalog creation; preserves guest omit, handoff and Checkout auth", async () => {
  const requests: Request[] = []; vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(url.includes("customer-auth") ? customerWire() : url.endsWith("confirm") ? readWire() : wire(), url.includes("/sites/") ? 201 : 200); });
  const c = client(); await c.createCatalogCheckout(input); await c.exchangeSiteToken(authInput); const checkout = await c.createCatalogCheckout(input); await c.createCatalogCheckout({ ...input, customerSession: "omit" }); await c.exchangeHandoff({ ...input, handoffToken: token }); await c.confirm(checkout);
  expect(requests.map(r => r.headers.get("authorization"))).toEqual([null, null, `Bearer ${customerToken}`, null, null, `Bearer ${token}`]);
  expect(sessionStorage.getItem(sessionKey())).not.toBeNull();
});
it.each(["corrupt", "expired", "supplied"])("fails closed before fetch for %s customer session", async kind => {
  vi.stubGlobal("fetch", async () => json(customerWire())); const c = client(); const s = await c.exchangeSiteToken(authInput);
  if (kind === "corrupt") sessionStorage.setItem(sessionKey(), "{}");
  if (kind === "expired") { const r = JSON.parse(sessionStorage.getItem(sessionKey())!); r.expiresAt = "2000-01-01T00:00:00Z"; sessionStorage.setItem(sessionKey(), JSON.stringify(r)); }
  const fetch = vi.fn(async () => json(wire(), 201)); vi.stubGlobal("fetch", fetch);
  await expect(kind === "supplied" ? c.getCurrentCustomer({ ...s, origin: "https://evil.test" }) : c.createCatalogCheckout(input)).rejects.toMatchObject({ code: kind === "expired" ? "SESSION_EXPIRED" : "INVALID_SESSION" }); expect(fetch).not.toHaveBeenCalled();
});
it("maps current customer and bounded history; rejects changed identity/expiry and unsafe donation additions", async () => {
  const c = client(); const response = customerWire(); vi.stubGlobal("fetch", async () => json(response)); const s = await c.exchangeSiteToken(authInput);
  const me = { ...response.customer, authentication_modes: ["SITE_TOKEN"], session_expires_at: s.expiresAt };
  vi.stubGlobal("fetch", async () => json(me)); expect(await c.getCurrentCustomer(s)).toEqual({ id, status: "ACTIVE", maskedEmail: null, authenticationModes: ["SITE_TOKEN"], sessionExpiresAt: s.expiresAt });
  for (const patch of [{ id: "22222222-2222-4222-8222-222222222222" }, { session_expires_at: "2099-01-01T00:00:00Z" }]) { vi.stubGlobal("fetch", async () => json({ ...me, ...patch })); await expect(c.getCurrentCustomer(s)).rejects.toMatchObject({ code: "INVALID_RESPONSE" }); }
  const requests: Request[] = []; const now = new Date().toISOString(); vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(url.includes("donations") ? { items: [], next_cursor: null } : { items: [{ payment_id: id, source: "CATALOG", amount_minor: 123, currency: "EUR", status: "SUCCEEDED", created_at: now, updated_at: now, catalog: { price_id: id, price_key: "lifetime", version: 1 } }], next_cursor: "cursor+value" }); });
  expect((await c.listCustomerPayments(s, { limit: 100, cursor: "a+b" })).items[0]).toEqual({ paymentId: id, source: "CATALOG", amountMinor: 123, currency: "EUR", status: "SUCCEEDED", createdAt: now, updatedAt: now, catalog: { priceId: id, priceKey: "lifetime", version: 1 } });
  await c.listCustomerDonations(s); expect(requests[0].url).toBe(base + "/api/v1/customer/payments?limit=100&cursor=a%2Bb"); expect(requests[1].url).toBe(base + "/api/v1/customer/donations?limit=20");
  for (const options of [{ limit: 0 }, { limit: 101 }, { cursor: " " }, { cursor: "x".repeat(4097) }, { extra: true }]) await expect(c.listCustomerPayments(s, options)).rejects.toMatchObject({ code: "INVALID_CONFIGURATION" });
  vi.stubGlobal("fetch", async () => json({ items: [{ email: "private" }], next_cursor: null })); await expect(c.listCustomerDonations(s)).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
});
it('preserves mixed Payment sources, null Donation catalog and cursor while rejecting unknown sources and fields', async () => {
  const c=client();vi.stubGlobal('fetch',async()=>json(customerWire()));const s=await c.exchangeSiteToken(authInput);
  const now='2026-01-01T00:00:00Z';
  const row={payment_id:id,amount_minor:123,currency:'EUR',status:'SUCCEEDED',created_at:now,updated_at:now,catalog:null};
  const rows=[{...row,source:'CATALOG',catalog:{price_id:id,price_key:'lifetime',version:1}},{...row,source:'DYNAMIC'},{...row,source:'DONATION'}];
  vi.stubGlobal('fetch',async()=>json({items:rows,next_cursor:'mixed+cursor'}));
  const result=await c.listCustomerPayments(s,{limit:3});
  expect(result.items.map(i=>i.source)).toEqual(['CATALOG','DYNAMIC','DONATION']);
  expect(result.items[2].catalog).toBeNull();expect(result.nextCursor).toBe('mixed+cursor');
  for(const patch of [{source:'UNKNOWN'},{message:'private'}]) {
    vi.stubGlobal('fetch',async()=>json({items:[{...row,source:'DONATION',...patch}],next_cursor:null}));
    await expect(c.listCustomerPayments(s)).rejects.toMatchObject({code:'INVALID_RESPONSE'});
  }
});
it("clears only rejected session, never retries guest, and ignores failed source authentication", async () => {
  const c = client(); vi.stubGlobal("fetch", async () => json(customerWire())); await c.exchangeSiteToken(authInput);
  vi.stubGlobal("fetch", async () => json({ detail: "source-secret" }, 401)); await expect(c.exchangeSiteToken(authInput)).rejects.toMatchObject({ code: "REQUEST_FAILED" }); expect(sessionStorage.getItem(sessionKey())).not.toBeNull();
  const requests: string[] = []; vi.stubGlobal("fetch", async (url: string) => { requests.push(url); return json({ detail: "source-secret" }, 401); });
  await expect(c.createCatalogCheckout(input)).rejects.toMatchObject({ code: "INVALID_SESSION" }); expect(requests).toHaveLength(1); expect(sessionStorage.getItem(sessionKey())).toBeNull();
});
it.each(["extra", "scope", "status", "token", "huge", "wrongStatus", "proof"])("rejects hostile session response %s without persistence", async kind => {
  const r: any = customerWire(); if (kind === "extra") r.email = "private"; if (kind === "scope") r.scopes.push("ADMIN"); if (kind === "status") r.customer.status = "ANONYMIZED"; if (kind === "token") r.session_token = "bad";
  vi.stubGlobal("fetch", async () => kind === "huge" ? new Response("x".repeat(131073)) : json(kind === "proof" ? { proof_token: customerToken, expires_at: future() } : r, kind === "wrongStatus" ? 201 : 200));
  await expect(client().exchangeSiteToken(authInput)).rejects.toMatchObject({ code: "INVALID_RESPONSE" }); expect(sessionStorage.length).toBe(0);
});
it("uses valid in-memory capability when sessionStorage is unavailable", async () => {
  vi.stubGlobal("sessionStorage", { getItem() { throw Error(); }, setItem() { throw Error(); }, removeItem() { throw Error(); }, clear() {} });
  const auths: (string | null)[] = []; vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { auths.push(new Headers(init.headers).get("authorization")); return json(url.includes("customer-auth") ? customerWire() : wire(), url.includes("/sites/") ? 201 : 200); });
  const c = client(); await c.exchangeSiteToken(authInput); await c.createCatalogCheckout(input); expect(auths).toEqual([null, `Bearer ${customerToken}`]);
});
it("preserves aborted late authentication and leaves no session", async () => {
  const abort = new AbortController(); vi.stubGlobal("fetch", async () => { abort.abort(); return json(customerWire()); });
  await expect(client(abort.signal).exchangeSiteToken(authInput)).rejects.toMatchObject({ name: "AbortError" }); expect(sessionStorage.length).toBe(0);
});
it.each(["logout", "reject", "authenticate"])("late %s cannot clear or replace a newer session", async kind => {
  const c = client(); vi.stubGlobal("fetch", async () => json(customerWire())); const first = await c.exchangeSiteToken(authInput);
  let finish!: (value: Response) => void; vi.stubGlobal("fetch", () => new Promise(resolve => { finish = resolve; }));
  const pending = (kind === "logout" ? c.logoutCustomerSession(first) : kind === "reject" ? c.getCurrentCustomer(first) : c.exchangeSiteToken(authInput)).catch((e: unknown) => e);
  vi.stubGlobal("fetch", async () => json({ ...customerWire(), session_token: "R".repeat(43) })); await c.exchangeSiteToken(authInput);
  finish(kind === "logout" ? new Response(null, { status: 204 }) : kind === "reject" ? json({}, 401) : json(customerWire())); await pending;
  expect((sdk as any).recoverCustomerSession({ apiBaseUrl: base, siteKey: "test-site" }).sessionToken).toBe("R".repeat(43));
});
it("links exact proof, parses 201/200 outcomes and locally logs out even on revoke failure", async () => {
  const c = client(); vi.stubGlobal("fetch", async () => json(customerWire())); const s = await c.exchangeSiteToken(authInput);
  const requests: Request[] = []; vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return requests.length < 3 ? json({ identity_id: id, outcome: requests.length === 1 ? "LINKED" : "ALREADY_LINKED" }, requests.length === 1 ? 201 : 200) : new Response(null, { status: 204 }); });
  for (const outcome of ["LINKED", "ALREADY_LINKED"]) expect(await c.linkIdentity(s, { proofToken: "P".repeat(43) })).toEqual({ identityId: id, outcome });
  expect(await requests[0].json()).toEqual({ proof_token: "P".repeat(43) }); expect(requests[0].headers.has("idempotency-key")).toBe(false);
  await c.logoutCustomerSession(s); expect(requests[2].method).toBe("DELETE"); expect(sessionStorage.getItem(sessionKey())).toBeNull();
  vi.stubGlobal("fetch", async () => json(customerWire())); const replacement = await c.exchangeSiteToken(authInput);
  vi.stubGlobal("fetch", async () => { throw Error("secret"); }); await expect(c.logoutCustomerSession(replacement)).rejects.toMatchObject({ code: "TRANSPORT_UNAVAILABLE" }); expect(sessionStorage.getItem(sessionKey())).toBeNull();
});
it.each(["malformed", "oversized", "empty"])("clears exact rejected session even with %s 401 body", async kind => {
  const c = client(); vi.stubGlobal("fetch", async () => json(customerWire())); const s = await c.exchangeSiteToken(authInput);
  vi.stubGlobal("fetch", async () => new Response(kind === "malformed" ? "{" : kind === "oversized" ? "x".repeat(131073) : null, { status: 401 }));
  await expect(c.getCurrentCustomer(s)).rejects.toMatchObject({ code: "INVALID_SESSION" }); expect(sessionStorage.getItem(sessionKey())).toBeNull();
});
it("cancels a stalled response body on abort and preserves AbortError", async () => {
  let cancelled = false; const controller = new AbortController();
  vi.stubGlobal("fetch", async () => new Response(new ReadableStream({ pull() {}, cancel() { cancelled = true; } })));
  const pending = client(controller.signal).exchangeSiteToken(authInput); await new Promise(resolve => setTimeout(resolve, 0)); controller.abort();
  await expect(pending).rejects.toMatchObject({ name: "AbortError" }); expect(cancelled).toBe(true); expect(sessionStorage.length).toBe(0);
});
it("rejects mismatched identity-link status/outcome combinations", async () => {
  const c = client(); vi.stubGlobal("fetch", async () => json(customerWire())); const s = await c.exchangeSiteToken(authInput);
  vi.stubGlobal("fetch", async () => json({ identity_id: id, outcome: "LINKED" }, 200));
  await expect(c.linkIdentity(s, { proofToken: "P".repeat(43) })).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
});
it("keeps valid memory session when writes exceed quota while reads still work", async () => {
  vi.stubGlobal("sessionStorage", { getItem: () => null, setItem() { throw new DOMException("quota", "QuotaExceededError"); }, removeItem() {}, clear() {}, length: 0 });
  const requests: Request[] = []; vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(url.includes("customer-auth") ? customerWire() : wire(), url.includes("/sites/") ? 201 : 200); });
  const c = client(); await c.exchangeSiteToken(authInput); await c.createCatalogCheckout(input);
  expect(requests[1].headers.get("authorization")).toBe(`Bearer ${customerToken}`); expect(sessionStorage.length).toBe(0);
});
it("keeps the original Checkout response cap for Customer-authorized catalog creates", async () => {
  const c = client(); vi.stubGlobal("fetch", async () => json(customerWire())); await c.exchangeSiteToken(authInput);
  vi.stubGlobal("fetch", async () => new Response(" ".repeat(32768) + JSON.stringify(wire()), { status: 201 }));
  await expect(c.createCatalogCheckout(input)).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  expect(sessionStorage.length).toBe(1);
});
it.each(["UNKNOWN", "EXPIRED"])("rejects SDK-derived %s statuses in backend payment history", async status => {
  const c = client(); vi.stubGlobal("fetch", async () => json(customerWire())); const s = await c.exchangeSiteToken(authInput); const now = new Date().toISOString();
  vi.stubGlobal("fetch", async () => json({ items: [{ payment_id: id, source: "DYNAMIC", amount_minor: 1, currency: "EUR", status, created_at: now, updated_at: now, catalog: null }], next_cursor: null }));
  await expect(c.listCustomerPayments(s)).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
});
it("rejects raw email smuggled in masked_email before storage or safe projections", async () => {
  const c = client(); const response = customerWire();
  vi.stubGlobal("fetch", async () => json({ ...response, customer: { ...response.customer, masked_email: "private@example.test" } }));
  await expect(c.exchangeSiteToken(authInput)).rejects.toMatchObject({ code: "INVALID_RESPONSE" }); expect(sessionStorage.length).toBe(0);
});
it("uses the authoritative masked email projection and clears matching anonymized sessions", async () => {
  const c = client(); const response = customerWire(); vi.stubGlobal("fetch", async () => json({ ...response, customer: { ...response.customer, masked_email: "***@***" } }));
  const s = await c.exchangeSiteToken(authInput); expect(s.customer.maskedEmail).toBe("***@***");
  vi.stubGlobal("fetch", async () => json({ id, status: "ANONYMIZED", masked_email: null, authentication_modes: [], session_expires_at: s.expiresAt }));
  await expect(c.getCurrentCustomer(s)).rejects.toMatchObject({ code: "INVALID_SESSION" }); expect(sessionStorage.getItem(sessionKey())).toBeNull();
});
it("rejects opaque browser Origin and caller source/header overrides before fetch", async () => {
  const fetch = vi.fn(async () => json(wire(), 201)); vi.stubGlobal("fetch", fetch);
  for (const patch of [{ origin: "https://evil.test" }, { authorization: "Bearer secret" }, { token: "" }, { intent: "ALIEN" }]) await expect(client().exchangeSiteToken({ ...authInput, ...patch })).rejects.toMatchObject({ code: "INVALID_CONFIGURATION" });
  vi.stubGlobal("location", { origin: "null" }); await expect(client().exchangeSiteToken(authInput)).rejects.toMatchObject({ code: "INVALID_CONFIGURATION" }); expect(fetch).not.toHaveBeenCalled();
});
it("never accesses persistent storage or cookie credentials", async () => {
  vi.stubGlobal("localStorage", new Proxy({}, { get() { throw Error("persistent storage used"); } }));
  const cookieRead = vi.spyOn(document, "cookie", "get"); const cookieWrite = vi.spyOn(document, "cookie", "set");
  vi.stubGlobal("fetch", async () => json(customerWire())); const c = client(); await c.exchangeSiteToken(authInput);
  expect(cookieRead).not.toHaveBeenCalled(); expect(cookieWrite).not.toHaveBeenCalled();
});
it.each(["corrupt", "expired"])("public recovery of %s record cannot silently downgrade subsequent catalog creation", async kind => {
  vi.stubGlobal("fetch", async () => json(customerWire())); const c = client(); await c.exchangeSiteToken(authInput);
  const record = JSON.parse(sessionStorage.getItem(sessionKey())!); record.expiresAt = "2000-01-01T00:00:00Z";
  sessionStorage.setItem(sessionKey(), kind === "corrupt" ? "{}" : JSON.stringify(record));
  expect((sdk as any).recoverCustomerSession({ apiBaseUrl: base, siteKey: "test-site" })).toBeNull(); expect(sessionStorage.getItem(sessionKey())).toBeNull();
  const fetch = vi.fn(async () => json(wire(), 201)); vi.stubGlobal("fetch", fetch);
  await expect(c.createCatalogCheckout(input)).rejects.toMatchObject({ code: kind === "expired" ? "SESSION_EXPIRED" : "INVALID_SESSION" }); expect(fetch).not.toHaveBeenCalled();
});

it.each(["corrupt", "expired"])("quota replacement stays authoritative over its exact %s stale record", async kind => {
  const c = client(); vi.stubGlobal("fetch", async () => json(customerWire())); await c.exchangeSiteToken(authInput);
  const record = JSON.parse(sessionStorage.getItem(sessionKey())!); record.expiresAt = "2000-01-01T00:00:00Z";
  const stale = kind === "corrupt" ? "{}" : JSON.stringify(record); sessionStorage.setItem(sessionKey(), stale);
  const storage = sessionStorage; vi.stubGlobal("sessionStorage", { getItem: (k: string) => storage.getItem(k), setItem() { throw new DOMException("quota", "QuotaExceededError"); }, removeItem: (k: string) => storage.removeItem(k), clear: () => storage.clear() });
  const sent: (string | null)[] = []; vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { sent.push(new Headers(init.headers).get("authorization")); return json(url.includes("customer-auth") ? { ...customerWire(), session_token: "B".repeat(43) } : wire(), url.includes("/sites/") ? 201 : 200); });
  await c.exchangeSiteToken(authInput); await c.createCatalogCheckout(input); await c.createCatalogCheckout(input);
  expect(sent.length).toBe(3); expect(sent.slice(1).every(h => h === `Bearer ${"B".repeat(43)}`)).toBe(true); expect(storage.getItem(sessionKey()) === stale).toBe(true);
});

it.each(["valid", "corrupt", "expired"])("quota fallback does not override a genuinely different %s replacement record", async kind => {
  const c = client(); vi.stubGlobal("fetch", async () => json(customerWire())); await c.exchangeSiteToken(authInput);
  const replacement = JSON.parse(sessionStorage.getItem(sessionKey())!); replacement.sessionToken = "R".repeat(43);
  const storage = sessionStorage; vi.stubGlobal("sessionStorage", { getItem: (k: string) => storage.getItem(k), setItem() { throw new DOMException("quota", "QuotaExceededError"); }, removeItem: (k: string) => storage.removeItem(k), clear: () => storage.clear() });
  vi.stubGlobal("fetch", async () => json({ ...customerWire(), session_token: "B".repeat(43) })); await c.exchangeSiteToken(authInput);
  if (kind === "expired") replacement.expiresAt = "2000-01-01T00:00:00Z";
  storage.setItem(sessionKey(), kind === "corrupt" ? '{"different":true}' : JSON.stringify(replacement));
  const headers: (string | null)[] = []; vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { headers.push(new Headers(init.headers).get("authorization")); return json(wire(), 201); });
  if (kind === "valid") { await c.createCatalogCheckout(input); expect(headers.length).toBe(1); expect(headers[0] === `Bearer ${"R".repeat(43)}`).toBe(true); }
  else { await expect(c.createCatalogCheckout(input)).rejects.toMatchObject({ code: kind === "expired" ? "SESSION_EXPIRED" : "INVALID_SESSION" }); expect(headers).toHaveLength(0); }
});

it.each(["logout", "failed revoke", "aborted revoke", "rejection", "server expiry", "local expiry", "blocked removal"])("clearing quota replacement on %s never resurrects its older stored Customer", async kind => {
  const c = client(); vi.stubGlobal("fetch", async () => json(customerWire())); await c.exchangeSiteToken(authInput);
  const storage = sessionStorage; const older = storage.getItem(sessionKey());
  vi.stubGlobal("sessionStorage", { getItem: (k: string) => storage.getItem(k), setItem() { throw new DOMException("quota", "QuotaExceededError"); }, removeItem(k: string) { if (kind === "blocked removal") throw Error("blocked"); storage.removeItem(k); }, clear: () => storage.clear() });
  const expiresAt = new Date(Date.now() + 60000).toISOString();
  vi.stubGlobal("fetch", async () => json({ ...customerWire(), session_token: "B".repeat(43), expires_at: expiresAt, customer: { id: "22222222-2222-4222-8222-222222222222", status: "ACTIVE", masked_email: null } })); const replacement = await c.exchangeSiteToken(authInput);
  if (kind === "local expiry") { vi.useFakeTimers(); vi.setSystemTime(Date.parse(expiresAt) + 1); await expect(c.getCurrentCustomer(replacement)).rejects.toMatchObject({ code: "SESSION_EXPIRED" }); }
  else if (kind === "rejection" || kind === "server expiry") { vi.stubGlobal("fetch", async () => json(kind === "rejection" ? {} : { code: "SESSION_EXPIRED" }, kind === "rejection" ? 401 : 400)); await expect(c.getCurrentCustomer(replacement)).rejects.toMatchObject({ code: kind === "rejection" ? "INVALID_SESSION" : "SESSION_EXPIRED" }); }
  else if (kind === "failed revoke") { vi.stubGlobal("fetch", async () => { throw Error("offline"); }); await expect(c.logoutCustomerSession(replacement)).rejects.toMatchObject({ code: "TRANSPORT_UNAVAILABLE" }); }
  else if (kind === "aborted revoke") { const abort = new AbortController(); abort.abort(); await expect(c.logoutCustomerSession(replacement, abort.signal)).rejects.toMatchObject({ name: "AbortError" }); }
  else { vi.stubGlobal("fetch", async () => new Response(null, { status: 204 })); await c.logoutCustomerSession(replacement); }
  expect(storage.getItem(sessionKey()) === older).toBe(true);
  const fetch = vi.fn(async () => json(wire(), 201)); vi.stubGlobal("fetch", fetch);
  await expect(c.createCatalogCheckout(input)).rejects.toMatchObject({ code: kind.includes("expiry") ? "SESSION_EXPIRED" : "INVALID_SESSION" }); expect(fetch).not.toHaveBeenCalled();
});

it.each(["logout", "rejection"])("blocked removal of the exact %s session cannot reuse its stored credential", async kind => {
  const c = client(); vi.stubGlobal("fetch", async () => json(customerWire())); const s = await c.exchangeSiteToken(authInput);
  const storage = sessionStorage; vi.stubGlobal("sessionStorage", { getItem: (k: string) => storage.getItem(k), setItem: (k: string, value: string) => storage.setItem(k, value), removeItem() { throw Error("blocked"); }, clear: () => storage.clear() });
  vi.stubGlobal("fetch", async () => kind === "logout" ? new Response(null, { status: 204 }) : json({}, 401));
  if (kind === "logout") await c.logoutCustomerSession(s); else await expect(c.getCurrentCustomer(s)).rejects.toMatchObject({ code: "INVALID_SESSION" });
  const fetch = vi.fn(async () => json(wire(), 201)); vi.stubGlobal("fetch", fetch);
  await expect(c.createCatalogCheckout(input)).rejects.toMatchObject({ code: "INVALID_SESSION" }); expect(fetch).not.toHaveBeenCalled(); expect(storage.getItem(sessionKey()) !== null).toBe(true);
});

it.each(["fresh authentication", "stored replacement"])("cleared quota ownership permits a genuinely newer %s", async kind => {
  const c = client(); vi.stubGlobal("fetch", async () => json(customerWire())); await c.exchangeSiteToken(authInput);
  const storage = sessionStorage; const newer = JSON.parse(storage.getItem(sessionKey())!); newer.sessionToken = "R".repeat(43);
  vi.stubGlobal("sessionStorage", { getItem: (k: string) => storage.getItem(k), setItem() { throw new DOMException("quota", "QuotaExceededError"); }, removeItem: (k: string) => storage.removeItem(k), clear: () => storage.clear() });
  vi.stubGlobal("fetch", async () => json({ ...customerWire(), session_token: "B".repeat(43) })); const replacement = await c.exchangeSiteToken(authInput);
  vi.stubGlobal("fetch", async () => new Response(null, { status: 204 })); await c.logoutCustomerSession(replacement);
  if (kind === "fresh authentication") { vi.stubGlobal("fetch", async () => json({ ...customerWire(), session_token: "R".repeat(43) })); await c.exchangeSiteToken(authInput); } else storage.setItem(sessionKey(), JSON.stringify(newer));
  let selected: string | null = null; vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { selected = new Headers(init.headers).get("authorization"); return json(wire(), 201); });
  await c.createCatalogCheckout(input); expect(selected === `Bearer ${"R".repeat(43)}`).toBe(true);
});

it("old exact-token logout preserves a quota replacement even when it removes the failed-write snapshot", async () => {
  const c = client(); vi.stubGlobal("fetch", async () => json(customerWire())); const older = await c.exchangeSiteToken(authInput);
  const storage = sessionStorage; vi.stubGlobal("sessionStorage", { getItem: (k: string) => storage.getItem(k), setItem() { throw new DOMException("quota", "QuotaExceededError"); }, removeItem: (k: string) => storage.removeItem(k), clear: () => storage.clear() });
  vi.stubGlobal("fetch", async () => json({ ...customerWire(), session_token: "B".repeat(43) })); await c.exchangeSiteToken(authInput);
  vi.stubGlobal("fetch", async () => new Response(null, { status: 204 })); await c.logoutCustomerSession(older);
  let selected: string | null = null; vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { selected = new Headers(init.headers).get("authorization"); return json(wire(), 201); });
  await c.createCatalogCheckout(input); expect(selected === `Bearer ${"B".repeat(43)}`).toBe(true); expect(storage.getItem(sessionKey())).toBeNull();
});

it.each(["corrupt", "expired"])("public recovery cleanup preserves quota memory owning the exact %s snapshot", async kind => {
  const c = client(); vi.stubGlobal("fetch", async () => json(customerWire())); await c.exchangeSiteToken(authInput);
  const record = JSON.parse(sessionStorage.getItem(sessionKey())!); record.expiresAt = "2000-01-01T00:00:00Z";
  sessionStorage.setItem(sessionKey(), kind === "corrupt" ? "{}" : JSON.stringify(record));
  const storage = sessionStorage; vi.stubGlobal("sessionStorage", { getItem: (k: string) => storage.getItem(k), setItem() { throw new DOMException("quota", "QuotaExceededError"); }, removeItem: (k: string) => storage.removeItem(k), clear: () => storage.clear() });
  vi.stubGlobal("fetch", async () => json({ ...customerWire(), session_token: "B".repeat(43) })); await c.exchangeSiteToken(authInput);
  expect(sdk.recoverCustomerSession({ apiBaseUrl: base, siteKey: "test-site" })).toBeNull(); expect(storage.getItem(sessionKey())).toBeNull();
  const sent: (string | null)[] = []; vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { sent.push(new Headers(init.headers).get("authorization")); return json(wire(), 201); });
  await c.createCatalogCheckout(input); await c.createCatalogCheckout(input);
  expect(sent.length).toBe(2); expect(sent.every(h => h === `Bearer ${"B".repeat(43)}`)).toBe(true);
});

it.each(["arbitrary absence", "different corrupt", "different expired", "new valid", "different cleanup after owned cleanup", "empty snapshot with different cleanup"])("public recovery quota reconciliation distinguishes %s from owned cleanup", async kind => {
  const c = client(); vi.stubGlobal("fetch", async () => json(customerWire())); await c.exchangeSiteToken(authInput);
  const stored = JSON.parse(sessionStorage.getItem(sessionKey())!); stored.sessionToken = "R".repeat(43);
  if (kind === "empty snapshot with different cleanup") sessionStorage.removeItem(sessionKey()); else sessionStorage.setItem(sessionKey(), "{}"); const storage = sessionStorage;
  vi.stubGlobal("sessionStorage", { getItem: (k: string) => storage.getItem(k), setItem() { throw new DOMException("quota", "QuotaExceededError"); }, removeItem: (k: string) => storage.removeItem(k), clear: () => storage.clear() });
  vi.stubGlobal("fetch", async () => json({ ...customerWire(), session_token: "B".repeat(43) })); await c.exchangeSiteToken(authInput);
  if (kind === "new valid" || kind === "different cleanup after owned cleanup") expect(sdk.recoverCustomerSession({ apiBaseUrl: base, siteKey: "test-site" })).toBeNull();
  if (kind === "arbitrary absence") storage.removeItem(sessionKey());
  else if (kind === "new valid") storage.setItem(sessionKey(), JSON.stringify(stored));
  else { if (kind === "different expired") stored.expiresAt = "2000-01-01T00:00:00Z"; storage.setItem(sessionKey(), kind === "different expired" ? JSON.stringify(stored) : '{"different":true}'); }
  const recovered = sdk.recoverCustomerSession({ apiBaseUrl: base, siteKey: "test-site" }); expect(recovered === null).toBe(kind !== "new valid");
  const sent: (string | null)[] = []; vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { sent.push(new Headers(init.headers).get("authorization")); return json(wire(), 201); });
  if (kind === "arbitrary absence" || kind === "new valid") { await c.createCatalogCheckout(input); expect(sent.length).toBe(1); expect(sent[0] === (kind === "new valid" ? `Bearer ${"R".repeat(43)}` : null)).toBe(true); }
  else { await expect(c.createCatalogCheckout(input)).rejects.toMatchObject({ code: kind === "different expired" ? "SESSION_EXPIRED" : "INVALID_SESSION" }); expect(sent).toHaveLength(0); }
});

it.each(["SDK save/logout", "successful SDK save", "matching exact-token clear", "nonmatching exact-token clear"])("cleanup receipt cannot reactivate earlier memory after another client's %s", async kind => {
  const a = client(); const b = client(); const storage = sessionStorage;
  vi.stubGlobal("fetch", async () => json({ ...customerWire(), session_token: "R".repeat(43) }));
  const prior = kind.includes("exact-token") ? await b.exchangeSiteToken(authInput) : undefined;
  const priorRecord = storage.getItem(sessionKey()); storage.setItem(sessionKey(), "{}"); let quota = true;
  vi.stubGlobal("sessionStorage", { getItem: (k: string) => storage.getItem(k), setItem(k: string, value: string) { if (quota) throw new DOMException("quota", "QuotaExceededError"); storage.setItem(k, value); }, removeItem: (k: string) => storage.removeItem(k), clear: () => storage.clear() });
  vi.stubGlobal("fetch", async () => json({ ...customerWire(), session_token: "A".repeat(43) })); await a.exchangeSiteToken(authInput);
  expect(sdk.recoverCustomerSession({ apiBaseUrl: base, siteKey: "test-site" })).toBeNull(); expect(storage.getItem(sessionKey())).toBeNull(); quota = false;
  let replacement = prior;
  if (kind.includes("SDK save")) {
    vi.stubGlobal("fetch", async () => json({ ...customerWire(), session_token: "R".repeat(43) })); replacement = await b.exchangeSiteToken(authInput);
    expect(JSON.parse(storage.getItem(sessionKey())!).sessionToken === "R".repeat(43)).toBe(true);
  } else {
    const record = JSON.parse(priorRecord!); if (kind === "nonmatching exact-token clear") record.sessionToken = "C".repeat(43); storage.setItem(sessionKey(), JSON.stringify(record));
  }
  if (kind === "successful SDK save") storage.removeItem(sessionKey());
  else {
    vi.stubGlobal("fetch", async () => new Response(null, { status: 204 })); await b.logoutCustomerSession(replacement);
    if (kind === "nonmatching exact-token clear") { expect(JSON.parse(storage.getItem(sessionKey())!).sessionToken === "C".repeat(43)).toBe(true); storage.removeItem(sessionKey()); }
  }
  expect(storage.getItem(sessionKey())).toBeNull();
  const sent: (string | null)[] = []; vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { sent.push(new Headers(init.headers).get("authorization")); return json(wire(), 201); });
  if (kind.includes("SDK save")) { await a.createCatalogCheckout(input); expect(sent).toEqual([null]); }
  else { await expect(a.createCatalogCheckout(input)).rejects.toMatchObject({ code: "INVALID_SESSION" }); expect(sent).toHaveLength(0); }
});
