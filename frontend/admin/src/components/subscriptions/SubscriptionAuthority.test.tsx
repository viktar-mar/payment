import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { StrictMode } from "react";
import { afterEach, expect, it, vi } from "vitest";
import { SubscriptionDetails } from "./SubscriptionDetails";
import { activeSubscription, bill, deferred, operationId, price, siteId, subscriptionId, targetPriceId } from "./test-fixtures";
afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });
const rejected = () => Response.json({ title: "Authentication required" }, { status: 401 });
const accepted = () => Response.json({ operation_id: operationId, subscription_id: subscriptionId, status: "PROCESSING", action_available: false, next_action: null });
function mount() { window.history.replaceState(null, "", `/?site_id=${siteId}`); render(<SubscriptionDetails subscriptionId={subscriptionId} />); }
it("preserves a fresh mounted authority through StrictMode effect replay", async () => {
  vi.stubGlobal("fetch", async (path: string) => read(path));
  window.history.replaceState(null, "", `/?site_id=${siteId}`);
  render(<StrictMode><SubscriptionDetails subscriptionId={subscriptionId} /></StrictMode>);
  expect(await screen.findByRole("region", { name: "Subscription summary" })).toBeInTheDocument();
});
function read(path: string) {
  if (path.includes("billing-records")) return Response.json({ items: [bill], next_cursor: null });
  if (path.includes("/events")) return Response.json({ items: [], next_cursor: null });
  if (path.includes("subscription-offers")) return Response.json({ items: [{ price: { ...price, price_id: targetPriceId }, direction: "UPGRADE", allowed_timings: ["IMMEDIATE"], trial_eligible: false }] });
  return Response.json(activeSubscription());
}
async function revoked() {
  expect(await screen.findByRole("link", { name: "Sign in again" })).toHaveAttribute("href", "/login");
  expect(screen.queryByRole("region", { name: "Subscription summary" })).not.toBeInTheDocument();
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  expect(screen.queryByRole("link", { name: "Open payment and refunds" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Cancel immediately" })).not.toBeInTheDocument();
}
it("read 401 removes authority and fences an already pending mutation and billing response", async () => {
  const lateMutation = deferred<Response>(), lateBills = deferred<Response>(); let refreshed = false, posts = 0;
  vi.stubGlobal("fetch", (path: string, init: RequestInit) => {
    if (init.method === "POST") { posts++; return lateMutation.promise; }
    if (refreshed && path.includes("billing-records")) return lateBills.promise;
    if (refreshed && !path.includes("/events")) return Promise.resolve(rejected());
    return Promise.resolve(read(path));
  });
  mount(); const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "Cancel at period end" }));
  await user.click(screen.getByRole("button", { name: "Confirm cancellation" })); await waitFor(() => expect(posts).toBe(1));
  // The read is concurrent with the in-flight dialog submission.
  refreshed = true; await user.click(screen.getByRole("button", { name: "Refresh status" })); await revoked();
  await act(async () => { lateMutation.resolve(accepted()); lateBills.resolve(read("billing-records")); });
  await revoked(); expect(posts).toBe(1); expect(screen.queryByText(/Command accepted/)).not.toBeInTheDocument();
});
it("confirmation 401 removes the dialog and all subscription authority without retrying", async () => {
  let posts = 0; vi.stubGlobal("fetch", async (path: string, init: RequestInit) => { if (init.method === "POST") { posts++; return rejected(); } return read(path); });
  mount(); const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "Cancel at period end" }));
  await user.click(screen.getByRole("button", { name: "Confirm cancellation" })); await revoked(); expect(posts).toBe(1);
});
it("preview 401 removes its quote intent and authority without a financial POST", async () => {
  let financial = 0; vi.stubGlobal("fetch", async (path: string, init: RequestInit) => {
    if (path.includes("change-preview")) return rejected();
    if (init.method === "POST") { financial++; return accepted(); } return read(path);
  });
  mount(); const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "Change plan" }));
  await user.selectOptions(await screen.findByLabelText("Target price"), targetPriceId); await user.click(screen.getByRole("button", { name: "Preview change" }));
  await revoked(); expect(financial).toBe(0);
});
