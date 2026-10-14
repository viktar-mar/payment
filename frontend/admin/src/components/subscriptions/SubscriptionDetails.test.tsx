import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { SubscriptionDetails } from "./SubscriptionDetails";
import { bill, deferred, instant, operationId, paymentId, siteId, subscriptionId, unknownCancellationOperation, unknownCancellationSubscription } from "./test-fixtures";
afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });
it("reopens the safe pending operation pointer with reads, preserves ACTIVE and links independent refunds", async () => {
  window.history.replaceState(null, "", `/subscriptions/${subscriptionId}?site_id=${siteId}`); const calls: { path: string; method: string }[] = [];
  vi.stubGlobal("fetch", async (path: string, init: RequestInit) => { calls.push({ path, method: init.method ?? "GET" }); if (path.includes("/operations/")) return Response.json(unknownCancellationOperation()); if (path.includes("billing-records")) return Response.json({ items: [bill], next_cursor: "more-bills" }); if (path.includes("/events")) return Response.json({ items: [{ event_id: operationId, subscription_id: subscriptionId, sequence: 2, revision: 3, type: "RECONCILIATION_REQUIRED", source: "API", occurred_at: instant, received_at: instant, operation_id: operationId, billing_record_id: null, payment_id: null }], next_cursor: null }); return Response.json(unknownCancellationSubscription()); });
  render(<SubscriptionDetails subscriptionId={subscriptionId} />); await screen.findByText("Result not confirmed"); expect(within(screen.getByRole("region", { name: "Subscription summary" })).getAllByText("ACTIVE").length).toBeGreaterThan(0);
  expect(screen.getByRole("link", { name: "Open payment and refunds" })).toHaveAttribute("href", `/payments/${paymentId}`); expect(screen.getByText("Refunds do not cancel this subscription.")).toBeInTheDocument(); expect(screen.getByText(/Sequence 2/)).toBeInTheDocument(); expect(calls.some(call => call.path.includes(`/operations/${operationId}?site_id=${siteId}`))).toBe(true); expect(calls.every(call => call.method === "GET")).toBe(true);
  await userEvent.click(screen.getByRole("button", { name: "More billing records" })); await waitFor(() => expect(calls.some(call => call.path.includes("cursor=more-bills"))).toBe(true));
});
it("ignores a late prior route response and requires explicit Site context", async () => {
  const late = deferred<Response>(); window.history.replaceState(null, "", `/?site_id=${siteId}`);
  vi.stubGlobal("fetch", (path: string) => path.includes(subscriptionId) ? late.promise : Promise.resolve(Response.json({ title: "Not found" }, { status: 404 })));
  const view = render(<SubscriptionDetails subscriptionId={subscriptionId} />); view.rerender(<SubscriptionDetails subscriptionId={operationId} />); await screen.findByRole("alert"); await act(async () => late.resolve(Response.json(unknownCancellationSubscription()))); expect(screen.queryByRole("heading", { name: `Subscription ${subscriptionId}` })).not.toBeInTheDocument();
  view.unmount(); window.history.replaceState(null, "", "/"); render(<SubscriptionDetails subscriptionId={subscriptionId} />); expect(await screen.findByText(/Select a Site/)).toBeInTheDocument();
});
