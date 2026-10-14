import { it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { defineGenericPaymentWidget, GenericPaymentWidget } from "./generic-payment-widget.js";
import { wire, readWire, json, token } from "../test/fixtures.js";
defineGenericPaymentWidget();
function mount() { const el = document.createElement("generic-payment-widget") as GenericPaymentWidget; el.siteKey = "test-site"; el.priceKey = "lifetime"; el.apiBaseUrl = "https://api.test"; document.body.append(el); return el; }
const button = (el: GenericPaymentWidget) => el.shadowRoot!.querySelector("button")!;
const status = (el: GenericPaymentWidget) => el.shadowRoot!.querySelector('[role="status"]')!;
async function until(el: GenericPaymentWidget, state: string) { await vi.waitFor(() => expect(el.state).toBe(state)); }
it("registers idempotently, renders idle shadow UI with labels, focus, theme and reduced motion", () => {
  defineGenericPaymentWidget(); const el = mount(); expect(el.state).toBe("idle"); expect(button(el).textContent).toBe("Start checkout"); expect(status(el).getAttribute("aria-live")).toBe("polite");
  expect(el.shadowRoot!.querySelector("style")!.textContent).toContain("--gp-accent"); expect(el.shadowRoot!.querySelector("style")!.textContent).toContain("prefers-reduced-motion"); expect(el.shadowRoot!.querySelector("style")!.textContent).toContain(":focus-visible");
  expect("apiKey" in el).toBe(false); expect("partnerApiKey" in el).toBe(false); expect(el.getAttributeNames()).toEqual(["site-key", "price-key", "api-base-url"]);
});
it("supports native keyboard checkout, loading guard and focus after ready", async () => {
  let resolve!: (r: Response) => void; const fetch = vi.fn(() => new Promise<Response>(r => { resolve = r; })); vi.stubGlobal("fetch", fetch); const el = mount();
  const user = userEvent.setup(); button(el).focus(); await user.keyboard("{Enter}"); expect(el.state).toBe("loading"); expect(button(el).disabled).toBe(true); button(el).click(); expect(fetch).toHaveBeenCalledTimes(1);
  resolve(json(wire())); await until(el, "ready"); expect(el.shadowRoot!.activeElement).toBe(button(el)); expect(button(el).textContent).toContain("Pay"); expect(status(el).textContent).toContain("EUR");
  expect(el.shadowRoot!.textContent).not.toContain(token); expect(el.outerHTML).not.toContain(token);
});
it.each([["PROCESSING", "processing"], ["REQUIRES_ACTION", "requires_action"], ["SUCCEEDED", "succeeded"], ["FAILED", "failed"], ["CANCELLED", "failed"], ["REFUNDED", "succeeded"], ["REQUIRES_PAYMENT_METHOD", "ready"]])("maps %s and emits only safe actual transitions", async (wireStatus, wanted) => {
  const details: unknown[] = []; vi.stubGlobal("fetch", async (_: string, init: RequestInit) => json(new Headers(init.headers).has("authorization") ? readWire(wireStatus) : wire()));
  const el = mount(); for (const name of ["ready", "processing", "requires-action", "succeeded", "failed"]) el.addEventListener(name, e => { const event = e as CustomEvent; expect(event.composed).toBe(true); expect(event.bubbles).toBe(true); details.push(event.detail); expect(Object.isFrozen(event.detail)).toBe(true); });
  button(el).click(); await until(el, "ready"); button(el).click(); await until(el, wanted);
  expect(details.length).toBeGreaterThan(0); expect(JSON.stringify(details)).not.toContain(token); expect(JSON.stringify(details)).not.toContain("provider");
  for (const d of details) expect(Object.keys(d as object).sort()).toEqual(["paymentId", "status"]);
  el.remove();
});
it("ambiguous confirmation becomes unknown without automatic replay and with safe text", async () => {
  let calls = 0; vi.stubGlobal("fetch", async () => { if (++calls === 1) return json(wire()); throw new TypeError(token); }); const el = mount(); button(el).click(); await until(el, "ready"); button(el).click(); await until(el, "unknown"); expect(calls).toBe(2); expect(el.shadowRoot!.textContent).not.toContain(token); expect(button(el).textContent).toBe("Check status");
});
it("maps expired response and clears only its recovery", async () => {
  let calls = 0; vi.stubGlobal("fetch", async () => ++calls === 1 ? json(wire()) : json({ code: "CHECKOUT_EXPIRED" }, 410)); const el = mount(); button(el).click(); await until(el, "ready"); button(el).click(); await until(el, "expired"); expect(sessionStorage.length).toBe(0);
});
it("decline retry uses same Payment and fresh explicit confirm key", async () => {
  const keys: string[] = []; const urls: string[] = [];
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { urls.push(url); if (new Headers(init.headers).has("authorization")) { keys.push(new Headers(init.headers).get("idempotency-key")!); return json(readWire("REQUIRES_PAYMENT_METHOD")); } return json(wire()); });
  const el = mount(); button(el).click(); await until(el, "ready"); button(el).click(); await vi.waitFor(() => expect(keys).toHaveLength(1)); await until(el, "ready"); button(el).click(); await vi.waitFor(() => expect(keys).toHaveLength(2)); await until(el, "ready"); expect(keys[0]).not.toBe(keys[1]); expect(urls[1]).toBe(urls[2]);
});
it("disconnect aborts pending creation, suppresses late events and persistence", async () => {
  let resolve!: (r: Response) => void; let signal!: AbortSignal; const ready = vi.fn();
  vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { signal = init.signal!; return new Promise<Response>(r => { resolve = r; }); });
  const el = mount(); el.addEventListener("ready", ready); button(el).click(); el.remove(); expect(signal.aborted).toBe(true); resolve(json(wire())); await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); expect(ready).not.toHaveBeenCalled(); expect(sessionStorage.length).toBe(0);
});
it("configuration change aborts and prevents stale mutation", async () => {
  let resolve!: (r: Response) => void; let signal!: AbortSignal;
  vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { signal = init.signal!; return new Promise<Response>(r => { resolve = r; }); });
  const el = mount(); button(el).click(); el.priceKey = "other-price"; expect(signal.aborted).toBe(true); resolve(json(wire())); await Promise.resolve(); await Promise.resolve(); expect(el.state).toBe("idle"); expect(sessionStorage.length).toBe(0);
});
it("recovers matching session, reads immediately and emits succeeded once across repeated reads and reconnect", async () => {
  const fetch = vi.fn(async (_: string, init: RequestInit) => json(new Headers(init.headers).has("authorization") ? readWire("SUCCEEDED") : wire())); vi.stubGlobal("fetch", fetch);
  const first = mount(); button(first).click(); await until(first, "ready"); first.remove(); const recovered = mount(); let successes = 0; recovered.addEventListener("succeeded", () => successes++); await until(recovered, "succeeded"); expect(successes).toBe(1); expect(fetch).toHaveBeenCalledTimes(2);
  recovered.remove(); document.body.append(recovered); await until(recovered, "succeeded"); await Promise.resolve(); expect(successes).toBe(1);
});
it("disconnect cancels active polling with no late DOM/event update", async () => {
  let reads = 0; let readSignal!: AbortSignal; let resolve!: (r: Response) => void;
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { if (init.method === "GET") { reads++; readSignal = init.signal!; return new Promise<Response>(r => { resolve = r; }); } return json(url.endsWith("confirm") ? readWire() : wire()); });
  const el = mount(); const success = vi.fn(); el.addEventListener("succeeded", success); button(el).click(); await until(el, "ready"); button(el).click(); await vi.waitFor(() => expect(reads).toBe(1)); const text = el.shadowRoot!.textContent; el.remove(); expect(readSignal.aborted).toBe(true); resolve(json(readWire("SUCCEEDED"))); await Promise.resolve(); await Promise.resolve(); expect(success).not.toHaveBeenCalled(); expect(el.shadowRoot!.textContent).toBe(text); expect(sessionStorage.length).toBe(1);
});
it("initial failed transition emits a fresh empty safe lifecycle detail", async () => {
  vi.stubGlobal("fetch", async () => json({ code: "PRICE_NOT_AVAILABLE", detail: token }, 404)); const el = mount(); const details: unknown[] = []; el.addEventListener("failed", e => details.push((e as CustomEvent).detail)); button(el).click(); await until(el, "failed"); expect(details).toEqual([{}]); expect(Object.isFrozen(details[0])).toBe(true); expect(JSON.stringify(details)).not.toContain(token);
});
it("terminal transition moves focus to its live status region", async () => {
  let calls = 0; vi.stubGlobal("fetch", async () => json(++calls === 1 ? wire() : readWire("SUCCEEDED"))); const el = mount(); button(el).click(); await until(el, "ready"); button(el).click(); await until(el, "succeeded"); expect(el.shadowRoot!.activeElement).toBe(status(el)); expect(status(el)).toHaveTextContent("Payment succeeded");
});
it("locale property reformats ready amount and preserves matching recovery identity", async () => {
  vi.stubGlobal("fetch", async (_: string, init: RequestInit) => json(init.method === "GET" ? readWire("REQUIRES_PAYMENT_METHOD") : wire())); const el = mount(); button(el).click(); await until(el, "ready"); el.locale = "de-DE"; await until(el, "ready"); expect(status(el).textContent).toContain("49,99"); expect(sessionStorage.length).toBe(1);
});
for (const prior of ["ready", "succeeded"]) {
  it.each(["missing", "blocked", "malformed", "expired", "mismatched", "oversized", "unsupported"])(`Fix1 I2 reconnect from ${prior} with %s recovery renders neutral idle`, async loss => {
    const requests: Request[] = [];
    vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(url.endsWith("confirm") ? readWire("SUCCEEDED") : wire()); });
    const el = mount(); button(el).click(); await until(el, "ready");
    if (prior === "succeeded") { button(el).click(); await until(el, "succeeded"); }
    el.remove(); const target = sessionStorage; const recordKey = target.key(0)!; const record = JSON.parse(target.getItem(recordKey)!);
    let restoreStorage: (() => void) | undefined;
    if (loss === "missing") target.removeItem(recordKey);
    else if (loss === "blocked") {
      const blocked = vi.spyOn(globalThis, "sessionStorage", "get").mockImplementation(() => { throw new DOMException("Synthetic blocked storage", "SecurityError"); });
      restoreStorage = () => blocked.mockRestore();
    }
    else if (loss === "malformed") target.setItem(recordKey, "{");
    else if (loss === "oversized") target.setItem(recordKey, "x".repeat(8193));
    else {
      if (loss === "expired") { record.expiresAt = "2000-01-01T00:00:00Z"; record.state.expiresAt = record.expiresAt; }
      if (loss === "mismatched") record.siteKey = "other-site";
      if (loss === "unsupported") record.version = 2;
      target.setItem(recordKey, JSON.stringify(record));
    }
    const before = requests.length; const events: string[] = [];
    for (const name of ["ready", "processing", "requires-action", "succeeded", "failed", "unknown", "expired"]) el.addEventListener(name, () => events.push(name));
    try {
      document.body.append(el); await Promise.resolve(); await Promise.resolve();
      expect(el.state).toBe("idle"); expect(status(el)).toHaveTextContent("Ready to start checkout");
      expect(button(el)).toBeVisible(); expect(button(el)).toBeEnabled(); expect(button(el).textContent).toBe("Start checkout");
      expect(events).toEqual([]); expect(requests).toHaveLength(before);
      button(el).click(); await until(el, "ready");
      expect(requests).toHaveLength(before + 1); expect(requests.at(-1)!.url).toBe("https://api.test/api/v1/public/sites/test-site/checkouts");
      expect(requests.at(-1)!.headers.has("authorization")).toBe(false); expect(events).toEqual(["ready"]);
    } finally { restoreStorage?.(); }
  });
}
it("Fix1 I2 matching reconnect waits for safe read without duplicate succeeded transition", async () => {
  let resume!: (response: Response) => void; let readSignal!: AbortSignal;
  const fetch = vi.fn(async (_: string, init: RequestInit) => {
    if (init.method === "GET") { readSignal = init.signal!; return new Promise<Response>(resolve => { resume = resolve; }); }
    return json(new Headers(init.headers).has("authorization") ? readWire("SUCCEEDED") : wire());
  }); vi.stubGlobal("fetch", fetch);
  const el = mount(); let successes = 0; el.addEventListener("succeeded", () => successes++);
  button(el).click(); await until(el, "ready"); button(el).click(); await until(el, "succeeded");
  el.remove(); document.body.append(el); await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(3));
  expect(readSignal.aborted).toBe(false); expect(button(el).hidden).toBe(true); expect(successes).toBe(1);
  resume(json(readWire("SUCCEEDED"))); await Promise.resolve(); await Promise.resolve();
  expect(el.state).toBe("succeeded"); expect(successes).toBe(1);
});
it("Fix1 I3 terminal locale change rerenders without reset, network or duplicate event", async () => {
  const requests: Request[] = []; vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(url.endsWith("confirm") ? readWire("SUCCEEDED") : wire()); });
  const el = mount(); let successes = 0; el.addEventListener("succeeded", () => successes++);
  button(el).click(); await until(el, "ready"); button(el).click(); await until(el, "succeeded");
  const recordKey = sessionStorage.key(0)!; const record = sessionStorage.getItem(recordKey);
  el.locale = "de-DE"; expect(el.state).toBe("succeeded"); await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  expect(requests).toHaveLength(2); expect(successes).toBe(1); expect(sessionStorage.getItem(recordKey)).toBe(record);
  expect(status(el)).toHaveTextContent("Payment succeeded");
});
it("Fix1 I3 ready locale change preserves session and events while reformatting money", async () => {
  const requests: Request[] = []; vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json(url.endsWith("confirm") ? readWire("SUCCEEDED") : wire()); });
  const el = mount(); let readies = 0; el.addEventListener("ready", () => readies++);
  button(el).click(); await until(el, "ready"); const recordKey = sessionStorage.key(0)!; const record = sessionStorage.getItem(recordKey);
  el.setAttribute("locale", "de-DE"); expect(el.state).toBe("ready"); expect(status(el).textContent).toContain("49,99");
  await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  expect(requests).toHaveLength(1); expect(requests[0].signal.aborted).toBe(false); expect(readies).toBe(1);
  expect(sessionStorage.getItem(recordKey)).toBe(record);
  button(el).click(); await until(el, "succeeded"); expect(requests).toHaveLength(2);
  expect(requests[1].url).toBe("https://api.test/api/v1/public/checkouts/11111111-1111-4111-8111-111111111111/confirm");
});
it("Fix1 I3 locale during creation does not abort or replace the active request", async () => {
  let resolve!: (response: Response) => void; let signal!: AbortSignal;
  const fetch = vi.fn(async (_: string, init: RequestInit) => { signal = init.signal!; return new Promise<Response>(done => { resolve = done; }); }); vi.stubGlobal("fetch", fetch);
  const el = mount(); button(el).click(); expect(el.state).toBe("loading"); el.locale = "de-DE";
  expect(signal.aborted).toBe(false); expect(el.state).toBe("loading"); expect(fetch).toHaveBeenCalledTimes(1);
  resolve(json(wire())); await until(el, "ready"); expect(status(el).textContent).toContain("49,99");
});
for (const recoveredStatus of ["CREATED", "REQUIRES_PAYMENT_METHOD"]) {
  for (const localeChange of [false, true]) {
    it.each(["native", "programmatic"])(`Fix2 F1 ${recoveredStatus} reconnect gates synchronous %s activation with locale=${localeChange}`, async activation => {
      const requests: Request[] = []; let resume!: (response: Response) => void;
      vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
        requests.push(new Request(url, init));
        if (init.method === "GET") return new Promise<Response>(resolve => { resume = resolve; });
        return json(url.endsWith("confirm") ? readWire("SUCCEEDED") : wire(recoveredStatus));
      });
      const el = mount(); const events: string[] = [];
      for (const name of ["ready", "succeeded", "unknown"]) el.addEventListener(name, () => events.push(name));
      button(el).click(); await until(el, "ready"); el.remove(); document.body.append(el);
      if (localeChange) el.locale = "de-DE";
      // No await: exercise the public action before the queued recovery read starts.
      if (activation === "native") button(el).click();
      else button(el).dispatchEvent(new MouseEvent("click", { bubbles: true }));
      expect(requests.map(request => request.method)).toEqual(["POST"]);
      expect(button(el)).toBeDisabled(); expect(events).toEqual(["ready"]);
      await vi.waitFor(() => expect(requests).toHaveLength(2));
      expect(requests[1].method).toBe("GET"); expect(requests[1].headers.has("idempotency-key")).toBe(false);
      button(el).dispatchEvent(new MouseEvent("click")); expect(requests).toHaveLength(2);
      resume(json(readWire(recoveredStatus)));
      await vi.waitFor(() => expect(button(el)).toBeEnabled()); expect(el.state).toBe("ready");
      expect(events).toEqual(["ready"]);
      button(el).click(); await until(el, "succeeded");
      expect(requests.map(request => request.method)).toEqual(["POST", "GET", "POST"]);
      expect(requests[2].url).toBe(requests[1].url + "/confirm");
      expect(requests[2].headers.get("idempotency-key")).not.toBe(requests[0].headers.get("idempotency-key"));
      expect(events).toEqual(["ready", "succeeded"]);
    });
  }
  it.each(["PROVIDER_DECLINED", "PROVIDER_TEMPORARILY_UNAVAILABLE"])(`Fix2 F2 ${recoveredStatus} preserves recoverable %s as ready for a fresh explicit intent`, async code => {
    const requests: Request[] = []; const unknown = vi.fn(); const readyDetails: unknown[] = [];
    vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
      requests.push(new Request(url, init));
      return requests.length === 1 ? json(wire(recoveredStatus)) : requests.length === 2 ? json({ code, detail: token }, 503) : json(readWire("SUCCEEDED"));
    });
    const el = mount(); el.addEventListener("unknown", unknown); el.addEventListener("ready", event => readyDetails.push((event as CustomEvent).detail));
    button(el).click(); await until(el, "ready"); const key = sessionStorage.key(0)!; const record = sessionStorage.getItem(key);
    button(el).click(); await vi.waitFor(() => expect(button(el)).toBeEnabled());
    expect(el.state).toBe("ready"); expect(button(el).textContent).toBe("Pay"); expect(unknown).not.toHaveBeenCalled();
    expect(readyDetails).toEqual([{ paymentId: wire().payment_id, status: recoveredStatus }, { paymentId: wire().payment_id, status: recoveredStatus }]);
    expect(sessionStorage.getItem(key)).toBe(record); expect(requests).toHaveLength(2);
    expect(el.shadowRoot!.textContent).not.toContain(token);
    button(el).click(); await until(el, "succeeded");
    expect(requests.map(request => request.method)).toEqual(["POST", "POST", "POST"]);
    expect(requests[1].url).toBe(requests[2].url);
    expect(requests[1].headers.get("idempotency-key")).not.toBe(requests[2].headers.get("idempotency-key"));
  });
}
it.each(["transport", "decode", "unsupported-provider", "provider-unknown", "generic-problem"])("Fix2 F2 ambiguous %s still gates confirm behind an explicit safe read", async kind => {
  const requests: Request[] = []; let resume!: (response: Response) => void; const unknown = vi.fn();
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
    requests.push(new Request(url, init));
    if (requests.length === 1) return json(wire());
    if (requests.length === 2) {
      if (kind === "transport") throw new TypeError(token);
      if (kind === "decode") return new Response("{");
      if (kind === "unsupported-provider") return json({ ...readWire(), provider_type: "ALIEN" });
      return json(kind === "provider-unknown" ? { code: "PROVIDER_RESULT_UNKNOWN" } : { detail: token }, 503);
    }
    if (init.method === "GET") return new Promise<Response>(resolve => { resume = resolve; });
    return json(readWire("SUCCEEDED"));
  });
  const el = mount(); el.addEventListener("unknown", unknown);
  button(el).click(); await until(el, "ready"); button(el).click(); await until(el, "unknown");
  expect(unknown).toHaveBeenCalledTimes(1); expect(requests).toHaveLength(2); expect(button(el).textContent).toBe("Check status");
  expect(JSON.parse(sessionStorage.getItem(sessionStorage.key(0)!)!).state.status).toBe("UNKNOWN");
  button(el).click(); expect(requests.map(request => request.method)).toEqual(["POST", "POST", "GET"]);
  button(el).dispatchEvent(new MouseEvent("click")); expect(requests).toHaveLength(3);
  resume(json(readWire("REQUIRES_PAYMENT_METHOD"))); await until(el, "ready");
  button(el).click(); await until(el, "succeeded");
  expect(requests.map(request => request.method)).toEqual(["POST", "POST", "GET", "POST"]);
  expect(requests[1].url).toBe(requests[3].url); expect(requests[1].headers.get("idempotency-key")).not.toBe(requests[3].headers.get("idempotency-key"));
});
it.each(["PROVIDER_DECLINED", "PROVIDER_TEMPORARILY_UNAVAILABLE"])("Fix2 F2 %s on recovery GET does not authorize confirmation", async code => {
  const requests: Request[] = []; let resume!: (response: Response) => void;
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => {
    requests.push(new Request(url, init));
    if (requests.length === 1) return json(wire());
    if (requests.length === 2) return json({ code }, 503);
    return new Promise<Response>(resolve => { resume = resolve; });
  });
  const el = mount(); button(el).click(); await until(el, "ready"); el.remove(); document.body.append(el);
  await until(el, "unknown"); expect(button(el).textContent).toBe("Check status");
  expect(requests.map(request => request.method)).toEqual(["POST", "GET"]);
  button(el).click(); expect(requests.map(request => request.method)).toEqual(["POST", "GET", "GET"]);
  resume(json(readWire("REQUIRES_PAYMENT_METHOD"))); await until(el, "ready");
});
