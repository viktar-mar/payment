import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { RefundDialog } from "./RefundDialog";
import { payment } from "./test-fixtures";
afterEach(() => vi.unstubAllGlobals());
it("validates integer amount and note, sends exact currency/reason, accepts without claiming success", async () => {
  const requests: RequestInit[] = []; let accepted = 0;
  vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => { requests.push(init); return Response.json({ refund_id: "refund-new", status: "PENDING" }, { status: 202 }); });
  render(<RefundDialog payment={payment} onClose={() => {}} onCreated={() => { accepted++; }} />);
  const user = userEvent.setup(); const input = screen.getByLabelText("Amount in minor units"); await user.clear(input); await user.type(input, "701"); await user.click(screen.getByRole("button", { name: "Confirm refund" }));
  expect(screen.getByRole("alert")).toHaveTextContent("Enter an integer amount from 1 to 700"); expect(requests).toHaveLength(0);
  await user.clear(input); await user.type(input, "200"); await user.selectOptions(screen.getByLabelText("Reason"), "DUPLICATE"); await user.type(screen.getByLabelText("Technical note (optional)"), "technical reason");
  await user.click(screen.getByRole("button", { name: "Confirm refund" }));
  expect(await screen.findByRole("status")).toHaveTextContent("Refund accepted; awaiting confirmation"); expect(accepted).toBe(1);
  expect(JSON.parse(requests[0].body as string)).toEqual({ amount_minor: 200, currency: "EUR", reason_code: "DUPLICATE", technical_note: "technical reason" });
});
it("uses a fresh key after dismissal even for identical full-refund intent, and bounds note input", async () => {
  const requests: RequestInit[] = []; vi.stubGlobal("fetch", async (_path: string, init: RequestInit) => { requests.push(init); throw new TypeError("network"); });
  const props = { payment, onClose: () => {}, onCreated: () => {} }; const user = userEvent.setup(); const first = render(<RefundDialog {...props} />);
  await user.click(screen.getByRole("button", { name: "Confirm refund" })); await screen.findByRole("alert"); first.unmount();
  render(<RefundDialog {...props} />); const note = screen.getByLabelText("Technical note (optional)"); expect(note).toHaveAttribute("maxlength", "500");
  fireEvent.change(note, { target: { value: "x".repeat(501) } }); await user.click(screen.getByRole("button", { name: "Confirm refund" })); expect(screen.getByRole("alert")).toHaveTextContent("at most 500"); expect(requests).toHaveLength(1);
  fireEvent.change(note, { target: { value: "" } }); await user.click(screen.getByRole("button", { name: "Confirm refund" })); await waitFor(() => expect(requests).toHaveLength(2));
  expect(JSON.parse(requests[1].body as string)).toEqual({ amount_minor: 700, currency: "EUR", reason_code: "REQUESTED_BY_CUSTOMER" });
  expect(new Headers(requests[1].headers).get("Idempotency-Key")).not.toBe(new Headers(requests[0].headers).get("Idempotency-Key"));
});
it("restores an intentional heading fallback when the original trigger disappeared", async () => {
  const heading = document.createElement("h1"); heading.tabIndex = -1; heading.dataset.paymentHeading = ""; document.body.append(heading);
  const trigger = document.createElement("button"); document.body.append(trigger); trigger.focus(); const view = render(<RefundDialog payment={payment} onClose={() => {}} onCreated={() => {}} />);
  trigger.remove(); view.unmount(); expect(heading).toHaveFocus(); heading.remove();
});
it("reuses a transport retry key and changes it when intent changes", async () => {
  const keys: (string | null)[] = [];
  vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => { keys.push(new Headers(init.headers).get("Idempotency-Key")); throw new TypeError("network"); });
  render(<RefundDialog payment={payment} onClose={() => {}} onCreated={() => {}} />); const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "Confirm refund" })); await screen.findByRole("alert"); await user.click(screen.getByRole("button", { name: "Retry refund" }));
  await waitFor(() => expect(keys).toHaveLength(2)); expect(keys[0]).toBeTruthy(); expect(keys[1]).toBe(keys[0]);
  await user.clear(screen.getByLabelText("Amount in minor units")); await user.type(screen.getByLabelText("Amount in minor units"), "100"); await user.click(screen.getByRole("button", { name: "Confirm refund" }));
  await waitFor(() => expect(keys).toHaveLength(3)); expect(keys[2]).not.toBe(keys[1]);
});
it("focuses cancel, traps keyboard focus, dismisses idle with Escape and restores exact trigger", async () => {
  const trigger = document.createElement("button"); document.body.append(trigger); trigger.focus(); let closed = false;
  const view = render(<RefundDialog payment={payment} onClose={() => { closed = true; }} onCreated={() => {}} />); const user = userEvent.setup();
  expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus();
  await user.tab({ shift: true }); expect(screen.getByRole("button", { name: "Confirm refund" })).toHaveFocus(); await user.tab(); expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus();
  await user.keyboard("{Escape}"); expect(closed).toBe(true); view.unmount(); expect(trigger).toHaveFocus(); trigger.remove();
});
it("blocks duplicate submits and Escape while pending and aborts mutation on unmount", async () => {
  let calls = 0; let signal: AbortSignal | undefined; let closed = false;
  vi.stubGlobal("fetch", (_url: string, init: RequestInit) => { calls++; signal = init.signal as AbortSignal; return new Promise(() => {}); });
  const view = render(<RefundDialog payment={payment} onClose={() => { closed = true; }} onCreated={() => {}} />); const user = userEvent.setup(); await user.dblClick(screen.getByRole("button", { name: "Confirm refund" }));
  expect(calls).toBe(1); expect(screen.getByRole("status")).toHaveTextContent("Submitting"); await user.keyboard("{Escape}"); expect(closed).toBe(false); view.unmount(); expect(signal?.aborted).toBe(true);
});
