import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { FakeSubscriptionControls } from "./FakeSubscriptionControls";
import { activeSubscription, siteId, subscriptionId } from "./test-fixtures";
afterEach(() => vi.unstubAllGlobals());
it("labels simulation as local-only, validates UTC and retains the same retry intent across locale changes", async () => {
  const posts: { path: string; body: unknown; key: string | null }[] = []; const refresh = vi.fn();
  vi.stubGlobal("fetch", async (path: string, init: RequestInit) => { posts.push({ path, body: JSON.parse(init.body as string), key: new Headers(init.headers).get("Idempotency-Key") }); if (posts.length === 1) throw new TypeError("PRIVATE"); return Response.json({ simulated: true }); });
  const subscription = activeSubscription(); const view = render(<FakeSubscriptionControls subscription={subscription} locale="en" onChanged={refresh} />); const user = userEvent.setup();
  expect(screen.getByText(/Non-local and non-Fake accounts are rejected/)).toBeInTheDocument(); await user.click(screen.getByRole("button", { name: "Simulate subscription" })); await user.selectOptions(screen.getByLabelText("Simulation action"), "ADVANCE");
  fireEvent.change(screen.getByLabelText("Until (UTC)"), { target: { value: "2026-02-30T00:00:00Z" } }); await user.click(screen.getByRole("button", { name: "Confirm simulation" })); expect(posts).toHaveLength(0);
  fireEvent.change(screen.getByLabelText("Until (UTC)"), { target: { value: "2026-11-12T12:00:00Z" } }); await user.click(screen.getByRole("button", { name: "Confirm simulation" })); await screen.findByText("Unable to connect");
  view.rerender(<FakeSubscriptionControls subscription={subscription} locale="pt" onChanged={refresh} />); expect(posts).toHaveLength(1); await user.click(screen.getByRole("button", { name: "Repetir simulação" }));
  await waitFor(() => expect(refresh).toHaveBeenCalledOnce()); expect(posts[1]).toEqual(posts[0]); expect(posts[0]).toMatchObject({ path: `/api/backend/v1/admin/subscriptions/${subscriptionId}/fake-controls?site_id=${siteId}`, body: { action: "ADVANCE", until: "2026-11-12T12:00:00Z" } }); expect(posts[0].key).toBeTruthy(); expect(screen.queryByText("PRIVATE")).not.toBeInTheDocument();
});
it("refreshes a stale simulation intent without automatically reapplying", async () => {
  const refresh = vi.fn(); const fetcher = vi.fn(async () => Response.json({}, { status: 409 })); vi.stubGlobal("fetch", fetcher);
  render(<FakeSubscriptionControls subscription={activeSubscription()} locale="en" onChanged={refresh} />); const user = userEvent.setup(); await user.click(screen.getByRole("button", { name: "Simulate subscription" })); await user.click(screen.getByRole("button", { name: "Confirm simulation" }));
  await waitFor(() => expect(refresh).toHaveBeenCalledOnce()); expect(fetcher).toHaveBeenCalledOnce(); expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
});
