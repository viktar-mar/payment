import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { AnonymizeCustomerDialog } from "./AnonymizeCustomerDialog";
import { customerId, siteId, deferred } from "./test-fixtures";
afterEach(() => vi.unstubAllGlobals());
const props = { customerId, siteId, onClose: () => {}, onAnonymized: () => {} };
it("links a guarded customer to subscription management without automatically cancelling", async () => {
  const methods: string[] = []; vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { methods.push(init.method ?? "GET"); return Response.json({ title: "Subscriptions remain" }, { status: 409 }); });
  render(<AnonymizeCustomerDialog {...props} />); fireEvent.change(screen.getByLabelText("Customer UUID confirmation"), { target: { value: customerId } }); await userEvent.click(screen.getByRole("button", { name: "Confirm anonymization" }));
  expect(await screen.findByText("Finish non-terminal subscriptions and confirm the provider result before anonymizing this customer.")).toBeInTheDocument(); expect(screen.getByRole("link", { name: "Manage Customer subscriptions" })).toHaveAttribute("href", `/subscriptions?site_id=${siteId}&customer_id=${customerId}`); expect(methods).toEqual(["POST"]);
});
it("requires exact UUID and warns of permanent PII/messages deletion and retained financial facts", async () => {
  const calls: [string, RequestInit][] = []; vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { calls.push([url, init]); return Response.json({ status: "ANONYMIZED" }); });
  render(<AnonymizeCustomerDialog {...props} />); const user = userEvent.setup();
  expect(screen.getByText(/financial facts/i)).toBeInTheDocument(); expect(screen.getByText(/messages/i)).toBeInTheDocument(); expect(screen.getByText(/sessions/i)).toBeInTheDocument();
  const input = screen.getByLabelText("Customer UUID confirmation"); const submit = screen.getByRole("button", { name: "Confirm anonymization" }); expect(submit).toBeDisabled();
  for (const value of [customerId.toUpperCase(), ` ${customerId}`, `${customerId} `]) { fireEvent.change(input, { target: { value } }); expect(submit).toBeDisabled(); }
  fireEvent.change(input, { target: { value: customerId } }); await user.click(submit);
  await waitFor(() => expect(calls).toHaveLength(1)); expect(calls[0][0]).toBe(`/api/backend/v1/admin/sites/${siteId}/customers/${customerId}/anonymization`); expect(JSON.parse(calls[0][1].body as string)).toEqual({ confirmation: customerId }); expect(new Headers(calls[0][1].headers).get("Idempotency-Key")).toBeTruthy();
});
it("preserves unchanged transport retry key, changes intent key and never renders unsafe failures", async () => {
  const keys: (string | null)[] = []; vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { keys.push(new Headers(init.headers).get("Idempotency-Key")); throw new TypeError("DO-NOT-SHOW"); });
  const view = render(<AnonymizeCustomerDialog {...props} />); const user = userEvent.setup(); const input = screen.getByLabelText("Customer UUID confirmation"); fireEvent.change(input, { target: { value: customerId } }); await user.click(screen.getByText("Confirm anonymization")); expect(await screen.findByRole("alert")).toHaveTextContent("Unable to connect"); await user.click(screen.getByText("Retry anonymization")); await waitFor(() => expect(keys).toHaveLength(2)); expect(keys[1]).toBe(keys[0]);
  fireEvent.change(input, { target: { value: "" } }); fireEvent.change(input, { target: { value: customerId } }); await user.click(screen.getByText("Confirm anonymization")); await waitFor(() => expect(keys).toHaveLength(3)); expect(keys[2]).not.toBe(keys[1]); expect(screen.queryByText(/DO-NOT-SHOW/)).not.toBeInTheDocument(); view.unmount();
  render(<AnonymizeCustomerDialog {...props} />); fireEvent.change(screen.getByLabelText("Customer UUID confirmation"), { target: { value: customerId } }); await user.click(screen.getByText("Confirm anonymization")); await waitFor(() => expect(keys).toHaveLength(4)); expect(keys[3]).not.toBe(keys[2]);
});
it("focuses Cancel, traps focus, restores trigger and dismisses idle Escape", async () => {
  const trigger = document.createElement("button"); document.body.append(trigger); trigger.focus(); const close = vi.fn(); const view = render(<AnonymizeCustomerDialog {...props} onClose={close} />); const user = userEvent.setup(); expect(screen.getByText("Cancel")).toHaveFocus(); await user.tab({ shift: true }); expect(screen.getByLabelText("Customer UUID confirmation")).toHaveFocus(); await user.tab(); expect(screen.getByText("Cancel")).toHaveFocus(); await user.keyboard("{Escape}"); expect(close).toHaveBeenCalledOnce(); view.unmount(); expect(trigger).toHaveFocus(); trigger.remove();
});
it("blocks rapid duplicate submit, pending dismissal and aborts owned request on unmount", async () => {
  const pending = deferred<Response>(); let signal: AbortSignal | undefined; let calls = 0; const close = vi.fn(); vi.stubGlobal("fetch", (_: string, init: RequestInit) => { calls++; signal = init.signal as AbortSignal; return pending.promise; }); const view = render(<AnonymizeCustomerDialog {...props} onClose={close} />); fireEvent.change(screen.getByLabelText("Customer UUID confirmation"), { target: { value: customerId } }); const form = screen.getByText("Confirm anonymization").closest("form")!; fireEvent.submit(form); fireEvent.submit(form); expect(calls).toBe(1); expect(screen.getByText("Cancel")).toBeDisabled(); expect(screen.getByRole("status")).toHaveTextContent(/Submitting/); fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" }); expect(close).not.toHaveBeenCalled(); view.unmount(); expect(signal?.aborted).toBe(true);
});
it("restores the Customer heading when the trigger was refreshed away", () => { const heading = document.createElement("h1"); heading.tabIndex = -1; heading.dataset.customerHeading = ""; document.body.append(heading); const trigger = document.createElement("button"); document.body.append(trigger); trigger.focus(); const view = render(<AnonymizeCustomerDialog {...props} />); trigger.remove(); view.unmount(); expect(heading).toHaveFocus(); heading.remove(); });
