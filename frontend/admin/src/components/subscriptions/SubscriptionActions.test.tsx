import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { SubscriptionActions } from "./SubscriptionActions";
import { activeSubscription, unknownCancellationSubscription, unknownCancellationOperation, subscriptionId, operationId, targetPriceId, price, siteId, deferred, policy } from "./test-fixtures";
afterEach(() => vi.unstubAllGlobals());
it("shows ambiguous result without claiming cancellation and keeps conflicting commands unavailable", async () => {
  const refresh = vi.fn(); render(<SubscriptionActions subscription={unknownCancellationSubscription()} operation={unknownCancellationOperation()} onChanged={refresh} />);
  expect(screen.getByText("Result not confirmed")).toBeInTheDocument();
  expect(screen.queryByText("Subscription cancelled")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Cancel at period end" })).toBeDisabled();
  await userEvent.click(screen.getByRole("button", { name: "Refresh status" })); expect(refresh).toHaveBeenCalledOnce();
});
it("keeps the locally simulated Portal navigation ephemeral and locale-only updates never submit", async () => {
  const posts: unknown[] = []; const returnUrl = "http://localhost:3000/subscriptions";
  vi.stubGlobal("fetch", async (_path: string, init: RequestInit) => {
    if (init.method !== "POST") return Response.json({ ...policy, portal_enabled: true, portal_return_urls: [returnUrl] });
    posts.push(JSON.parse(init.body as string)); return Response.json({ navigation_url: `${returnUrl}?subscriptionPortal=simulated`, expires_at: "2099-01-01T00:00:00Z", simulated: true });
  });
  const subscription = { ...activeSubscription(), permitted_actions: ["OPEN_PORTAL" as const] }; const refresh = vi.fn();
  const view = render(<SubscriptionActions subscription={subscription} onChanged={refresh} />); const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "Open Portal" })); await user.selectOptions(await screen.findByLabelText("Allowed return URL"), returnUrl); await user.click(screen.getByRole("button", { name: "Confirm Open Portal" }));
  expect(await screen.findByRole("link", { name: "Open simulated Portal" })).toHaveAttribute("href", `${returnUrl}?subscriptionPortal=simulated`); expect(posts).toEqual([{ return_url: returnUrl }]);
  view.rerender(<SubscriptionActions subscription={subscription} onChanged={refresh} locale="pt" />); expect(screen.getByRole("link", { name: "Abrir Portal simulado" })).toBeInTheDocument(); expect(posts).toHaveLength(1);
  view.unmount(); render(<SubscriptionActions subscription={subscription} onChanged={refresh} />); expect(screen.queryByRole("link", { name: "Open simulated Portal" })).not.toBeInTheDocument();
});
it("requires exact immediate confirmation, traps keyboard focus and retries unchanged intent with one key", async () => {
  const keys: (string | null)[] = []; const requests: unknown[] = [];
  vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { keys.push(new Headers(init.headers).get("Idempotency-Key")); requests.push(JSON.parse(init.body as string)); throw new TypeError("private-token"); });
  render(<SubscriptionActions subscription={activeSubscription()} onChanged={vi.fn()} />); const user = userEvent.setup(); const trigger = screen.getByRole("button", { name: "Cancel immediately" }); await user.click(trigger);
  expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus();
  expect(screen.getByRole("button", { name: "Confirm cancellation" })).toBeDisabled();
  fireEvent.change(screen.getByLabelText("Subscription UUID confirmation"), { target: { value: subscriptionId } });
  await user.tab({ shift: true }); expect(screen.getByRole("button", { name: "Confirm cancellation" })).toHaveFocus();
  await user.click(screen.getByRole("button", { name: "Confirm cancellation" })); await screen.findByRole("alert"); await user.click(screen.getByRole("button", { name: "Retry cancellation" }));
  await waitFor(() => expect(keys).toHaveLength(2)); expect(keys[0]).toBeTruthy(); expect(keys[1]).toBe(keys[0]); expect(requests[0]).toEqual({ mode: "IMMEDIATE", confirmation: subscriptionId }); expect(screen.queryByText(/private-token/)).not.toBeInTheDocument();
  await user.keyboard("{Escape}"); expect(trigger).toHaveFocus();
});
it("uses server permitted actions and refreshes a stale mutation without reapplying", async () => {
  let writes = 0; const refresh = vi.fn(); vi.stubGlobal("fetch", async () => { writes++; return Response.json({ title: "Stale subscription" }, { status: 409 }); });
  render(<SubscriptionActions subscription={{ ...activeSubscription(), permitted_actions: ["CANCEL_PERIOD_END"] }} onChanged={refresh} />); const user = userEvent.setup();
  expect(screen.getByRole("button", { name: "Cancel immediately" })).toBeDisabled();
  await user.click(screen.getByRole("button", { name: "Cancel at period end" })); await user.click(screen.getByRole("button", { name: "Confirm cancellation" }));
  await screen.findByText(/Review the refreshed subscription/); expect(refresh).toHaveBeenCalledOnce(); expect(writes).toBe(1); expect(screen.queryByRole("button", { name: "Confirm cancellation" })).not.toBeInTheDocument();
});
it("clears a quote when timing changes and submits only a freshly confirmed quote", async () => {
  const offers = [{ price: { ...price, price_id: targetPriceId, price_key: "yearly" }, direction: "UPGRADE", allowed_timings: ["IMMEDIATE", "NEXT_PERIOD"], trial_eligible: false }];
  const writes: { path: string; body: unknown }[] = [];
  vi.stubGlobal("fetch", async (path: string, init: RequestInit) => {
    if (path.includes("subscription-offers")) return Response.json({ items: offers });
    const body = JSON.parse(init.body as string); writes.push({ path, body });
    if (path.includes("change-preview")) return Response.json({ quote_id: operationId, subscription_id: subscriptionId, from_price_id: price.price_id, from_price_version: 1, target_price: offers[0].price, timing: body.timing, due_now_minor: 300, next_amount_minor: 1200, currency: "EUR", effective_at: "2026-10-12T12:00:00Z", expires_at: "2099-01-01T00:00:00Z", tax_final: false, subscription_revision: 3, policy_version: 4 });
    return Response.json({ operation_id: operationId, subscription_id: subscriptionId, status: "UNKNOWN", action_available: false, next_action: null });
  });
  render(<SubscriptionActions subscription={activeSubscription()} onChanged={vi.fn()} />); const user = userEvent.setup(); await user.click(screen.getByRole("button", { name: "Change plan" })); await user.selectOptions(await screen.findByLabelText("Target price"), targetPriceId); await user.click(screen.getByRole("button", { name: "Preview change" })); await screen.findByText(/300 minor units EUR/);
  await user.selectOptions(screen.getByLabelText("Timing"), "NEXT_PERIOD"); expect(screen.queryByRole("button", { name: "Confirm plan change" })).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Preview change" })); await user.click(await screen.findByRole("button", { name: "Confirm plan change" })); await screen.findByText("Result not confirmed"); expect(writes.at(-1)).toEqual({ path: `/api/backend/v1/admin/subscriptions/${subscriptionId}/changes?site_id=${siteId}`, body: { quote_id: operationId } });
});
it("locks commands after an accepted result until a fresh projection arrives", async () => {
  const refresh = vi.fn(); const subscription = activeSubscription();
  vi.stubGlobal("fetch", async () => Response.json({ operation_id: operationId, subscription_id: subscriptionId, status: "UNKNOWN", action_available: false, next_action: null }));
  const view = render(<SubscriptionActions subscription={subscription} onChanged={refresh} />); const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "Cancel at period end" })); await user.click(screen.getByRole("button", { name: "Confirm cancellation" }));
  await screen.findByText("Result not confirmed"); expect(refresh).toHaveBeenCalledOnce();
  expect(screen.getByRole("button", { name: "Cancel immediately" })).toBeDisabled(); expect(screen.getByRole("button", { name: "Reconcile provider result" })).toBeDisabled();
  view.rerender(<SubscriptionActions subscription={{ ...subscription, revision: 4 }} onChanged={refresh} />);
  expect(screen.getByRole("button", { name: "Cancel immediately" })).toBeEnabled();
});
it("ignores a late preview after target intent changes and refreshes a preview conflict without applying", async () => {
  const late = deferred<Response>(); const refresh = vi.fn(); let previews = 0; let writes = 0;
  vi.stubGlobal("fetch", (path: string) => {
    if (path.includes("subscription-offers")) return Promise.resolve(Response.json({ items: [{ price: { ...price, price_id: targetPriceId }, direction: "UPGRADE", allowed_timings: ["IMMEDIATE", "NEXT_PERIOD"], trial_eligible: false }] }));
    if (path.includes("change-preview")) { previews++; return previews === 1 ? late.promise : Promise.resolve(Response.json({}, { status: 409 })); }
    writes++; return Promise.resolve(Response.json({}));
  });
  render(<SubscriptionActions subscription={activeSubscription()} onChanged={refresh} />); const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "Change plan" })); await user.selectOptions(await screen.findByLabelText("Target price"), targetPriceId); await user.click(screen.getByRole("button", { name: "Preview change" }));
  await user.selectOptions(screen.getByLabelText("Target price"), "");
  await act(async () => late.resolve(Response.json({ quote_id: operationId, subscription_id: subscriptionId, target_price: { ...price, price_id: targetPriceId }, timing: "IMMEDIATE", subscription_revision: 3, expires_at: "2099-01-01T00:00:00Z", due_now_minor: 123 })));
  expect(screen.queryByRole("button", { name: "Confirm plan change" })).not.toBeInTheDocument();
  await user.selectOptions(screen.getByLabelText("Target price"), targetPriceId); await user.click(screen.getByRole("button", { name: "Preview change" }));
  await waitFor(() => expect(refresh).toHaveBeenCalledOnce()); expect(writes).toBe(0); expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
});
it("rechecks quote expiry at confirmation instead of applying an expired quote", async () => {
  let applications = 0;
  vi.stubGlobal("fetch", async (path: string) => {
    if (path.includes("subscription-offers")) return Response.json({ items: [{ price: { ...price, price_id: targetPriceId }, direction: "UPGRADE", allowed_timings: ["NEXT_PERIOD"], trial_eligible: false }] });
    if (path.includes("change-preview")) return Response.json({ quote_id: operationId, subscription_id: subscriptionId, target_price: { ...price, price_id: targetPriceId }, timing: "NEXT_PERIOD", subscription_revision: 3, expires_at: "2000-01-01T00:00:00Z", effective_at: "2099-01-01T00:00:00Z", due_now_minor: 0, next_amount_minor: 1000, currency: "EUR", tax_final: true });
    applications++; return Response.json({});
  });
  render(<SubscriptionActions subscription={activeSubscription()} onChanged={vi.fn()} />); const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "Change plan" })); await user.selectOptions(await screen.findByLabelText("Target price"), targetPriceId); await user.click(screen.getByRole("button", { name: "Preview change" })); await user.click(await screen.findByRole("button", { name: "Confirm plan change" }));
  expect(screen.getByRole("alert")).toHaveTextContent("Preview the change again before confirming."); expect(applications).toBe(0); expect(screen.queryByRole("button", { name: "Confirm plan change" })).not.toBeInTheDocument();
});
