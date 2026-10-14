import { expect, it, vi } from "vitest";
import { fireEvent, getByLabelText, getByRole, queryByLabelText, waitFor } from "@testing-library/dom";
import userEvent from "@testing-library/user-event";
import * as sdk from "../index.js";
import { base, customerWire, future, id, json } from "../test/customer-fixtures.js";
const mount = () => {
  (sdk as any).defineGenericEmailOtp(); const element = document.createElement("generic-email-otp"); element.setAttribute("api-base-url", base); element.setAttribute("site-key", "test-site"); document.body.append(element); return element;
};
const root = (e: HTMLElement) => e.shadowRoot! as unknown as HTMLElement;
const request = async (e: HTMLElement) => { fireEvent.input(getByLabelText(root(e), "Email"), { target: { value: "private@example.test" } }); fireEvent.submit(root(e).querySelector("form")!); await waitFor(() => expect(getByLabelText(root(e), "Six-digit code")).toBeVisible()); };
it("registers only manually and offers labelled keyboard email submission with uniform challenge text", async () => {
  expect(customElements.get("generic-email-otp")).toBeUndefined(); const requests: Request[] = [];
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); return json({ challenge_id: id, expires_at: future() }, 202); });
  const e = mount(); const user = userEvent.setup(); const email = getByLabelText(root(e), "Email"); await user.type(email, "private@example.test"); await user.keyboard("{Enter}");
  await waitFor(() => expect(e.shadowRoot!.activeElement).toBe(getByLabelText(root(e), "Six-digit code")));
  expect(getByRole(root(e), "status")).toHaveTextContent("If available, use the code sent to your email."); expect(root(e).textContent).not.toContain("private@example.test");
  expect(await requests[0].json()).toEqual({ site_key: "test-site", email: "private@example.test", intent: "AUTHENTICATE" });
  expect(queryByLabelText(root(e), "Email")).toBeNull();
});
it("guards duplicates, six-digit verification and emits only deeply frozen safe authenticated metadata", async () => {
  let resolve!: (r: Response) => void; const requests: Request[] = [];
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { requests.push(new Request(url, init)); if (requests.length === 1) return json({ challenge_id: id, expires_at: future() }, 202); return new Promise<Response>(r => { resolve = r; }); });
  const e = mount(); await request(e); const events: CustomEvent[] = []; e.addEventListener("customer-authenticated", event => events.push(event as CustomEvent));
  const code = getByLabelText(root(e), "Six-digit code") as HTMLInputElement;
  fireEvent.input(code, { target: { value: "123" } }); fireEvent.submit(root(e).querySelector("form")!); expect(requests).toHaveLength(1); expect(getByRole(root(e), "alert")).toHaveTextContent("Enter a six-digit code.");
  fireEvent.input(code, { target: { value: "012345" } }); fireEvent.submit(root(e).querySelector("form")!); fireEvent.submit(root(e).querySelector("form")!); expect(requests).toHaveLength(2); expect(code.value).toBe("");
  resolve(json(customerWire())); await waitFor(() => expect(events).toHaveLength(1)); const event = events[0];
  expect(event.bubbles).toBe(true); expect(event.composed).toBe(true); expect(event.detail).toEqual({ state: "authenticated", customer: { id, status: "ACTIVE", maskedEmail: null }, expiresAt: expect.any(String), scopes: ["CUSTOMER_READ", "CHECKOUT_CREATE", "IDENTITY_LINK"] });
  expect(Object.isFrozen(event.detail)).toBe(true); expect(Object.isFrozen(event.detail.customer)).toBe(true); expect(Object.isFrozen(event.detail.scopes)).toBe(true);
  expect(JSON.stringify(event.detail)).not.toMatch(/sessionToken|proofToken|nonce|012345|private@example/); expect(e.getAttributeNames()).toEqual(["api-base-url", "site-key"]); expect(root(e).querySelector("input")).toBeNull();
});
it("waits 60 seconds to resend, re-requests email after clearing it and keeps generic rate-limit errors", async () => {
  vi.useFakeTimers(); let calls = 0; vi.stubGlobal("fetch", async () => { calls++; return calls === 1 ? json({ challenge_id: id, expires_at: future() }, 202) : json({ code: "RATE_LIMIT_EXCEEDED", detail: "private@example.test" }, 429); });
  const e = mount(); fireEvent.input(getByLabelText(root(e), "Email"), { target: { value: "private@example.test" } }); fireEvent.submit(root(e).querySelector("form")!); await vi.advanceTimersByTimeAsync(1);
  const resend = getByRole(root(e), "button", { name: "Resend code" }); expect(resend).toBeDisabled(); fireEvent.click(resend); expect(calls).toBe(1);
  await vi.advanceTimersByTimeAsync(59998); expect(resend).toBeDisabled(); await vi.advanceTimersByTimeAsync(1); expect(resend).toBeEnabled(); fireEvent.click(resend);
  expect(getByLabelText(root(e), "Email")).toHaveValue(""); fireEvent.input(getByLabelText(root(e), "Email"), { target: { value: "private@example.test" } }); fireEvent.submit(root(e).querySelector("form")!); await vi.advanceTimersByTimeAsync(1);
  expect(getByRole(root(e), "alert")).toHaveTextContent("Please try again later."); expect(root(e).textContent).not.toContain("private@example.test"); e.remove(); expect(vi.getTimerCount()).toBe(0);
});
it.each(["disconnect", "api", "site"])("aborts %s work and suppresses stale events and storage", async kind => {
  let finish!: (r: Response) => void; let signal!: AbortSignal; let n = 0;
  vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { n++; signal = init.signal as AbortSignal; return n === 1 ? json({ challenge_id: id, expires_at: future() }, 202) : new Promise<Response>(resolve => { finish = resolve; }); });
  const e = mount(); await request(e); const emit = vi.fn(); e.addEventListener("customer-authenticated", emit);
  const code = getByLabelText(root(e), "Six-digit code"); fireEvent.input(code, { target: { value: "012345" } }); fireEvent.submit(root(e).querySelector("form")!);
  if (kind === "disconnect") e.remove(); else e.setAttribute(kind === "api" ? "api-base-url" : "site-key", kind === "api" ? "https://other.test" : "other-site");
  expect(signal.aborted).toBe(true); finish(json(customerWire())); await new Promise(resolve => setTimeout(resolve, 0)); expect(emit).not.toHaveBeenCalled(); expect(sessionStorage.length).toBe(0); expect((code as HTMLInputElement).value).toBe("");
});
it("rerenders Portuguese labels without losing pending challenge, work or credentials", async () => {
  let finish!: (r: Response) => void; let signal!: AbortSignal;
  vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { signal = init.signal as AbortSignal; return new Promise<Response>(r => { finish = r; }); });
  const e = mount(); fireEvent.input(getByLabelText(root(e), "Email"), { target: { value: "private@example.test" } }); fireEvent.submit(root(e).querySelector("form")!); e.setAttribute("locale", "pt"); expect(signal.aborted).toBe(false);
  finish(json({ challenge_id: id, expires_at: future() }, 202)); await waitFor(() => expect(getByLabelText(root(e), "Código de seis dígitos")).toBeVisible());
  const code = getByLabelText(root(e), "Código de seis dígitos"); fireEvent.input(code, { target: { value: "123" } }); e.setAttribute("locale", "en"); expect(getByLabelText(root(e), "Six-digit code")).toHaveValue("123");
  expect(root(e).querySelector("style")?.textContent).toContain("--gp-accent");
});
