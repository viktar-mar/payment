import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { SubscriptionList } from "./SubscriptionList";
import { utc } from "./SubscriptionFilters";
import { activeSubscription, customerId, deferred, siteId, subscriptionId } from "./test-fixtures";
afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState(null, "", "/"); });
it("filters within a Site, carries cursor pagination and links the detail Site context", async () => {
  window.history.replaceState(null, "", `/subscriptions?site_id=${siteId}`); const urls: string[] = [];
  vi.stubGlobal("fetch", async (path: string) => { urls.push(path); return Response.json(path.includes("admin/v1/sites") ? [{ id: siteId, display_name: "Shop" }] : { items: [activeSubscription()], next_cursor: path.includes("cursor=") ? null : "next-safe" }); });
  render(<SubscriptionList />); expect(await screen.findByRole("link", { name: subscriptionId })).toHaveAttribute("href", `/subscriptions/${subscriptionId}?site_id=${siteId}`);
  expect(screen.getByRole("link", { name: "Manage Site subscription policy" })).toHaveAttribute("href", `/sites/${siteId}/subscriptions`);
  await userEvent.click(screen.getByRole("button", { name: "Next page" })); await waitFor(() => expect(urls.some(url => url.includes("cursor=next-safe"))).toBe(true));
  fireEvent.change(screen.getByLabelText("Customer UUID"), { target: { value: customerId } }); await userEvent.click(screen.getByRole("button", { name: "Apply filters" }));
  await waitFor(() => expect(urls.at(-1)).toContain(`customer_id=${customerId}`)); expect(urls.at(-1)).not.toContain("cursor=");
});
it("renders UTC without accepting calendar rollover and preserves nanosecond wire timestamps", () => {
  expect(utc("2026-02-30T12:00:00Z")).toBe("—"); expect(utc("2026-10-12T12:00:00.123456789Z")).toBe("2026-10-12T12:00:00.123Z (UTC)");
});
it("discards late filter responses and changes language without a financial request", async () => {
  window.history.replaceState(null, "", `/subscriptions?site_id=${siteId}`); const late = deferred<Response>(); let reads = 0; const methods: string[] = [];
  vi.stubGlobal("fetch", (path: string, init: RequestInit) => { methods.push(init.method ?? "GET"); if (path.includes("admin/v1/sites")) return Promise.resolve(Response.json([{ id: siteId }])); reads++; return reads === 1 ? late.promise : Promise.resolve(Response.json({ items: [], next_cursor: null })); });
  render(<SubscriptionList />); fireEvent.change(await screen.findByLabelText("Customer UUID"), { target: { value: customerId } }); await userEvent.click(screen.getByRole("button", { name: "Apply filters" })); await screen.findByText("No subscriptions found.");
  await act(async () => late.resolve(Response.json({ items: [activeSubscription()], next_cursor: null }))); expect(screen.queryByRole("link", { name: subscriptionId })).not.toBeInTheDocument();
  await userEvent.selectOptions(screen.getByLabelText("Language"), "pt"); expect(screen.getByText("Nenhuma assinatura encontrada.")).toBeInTheDocument(); expect(methods.every(method => method === "GET")).toBe(true);
});
