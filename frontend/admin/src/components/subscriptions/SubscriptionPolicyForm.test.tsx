import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { SubscriptionPolicyForm } from "./SubscriptionPolicyForm";
import { deferred, policy, siteId } from "./test-fixtures";
afterEach(() => vi.unstubAllGlobals());
it("sends the exact policy version and retains saved enabled state after the disable guard", async () => {
  const writes: { body: unknown; key: string | null }[] = []; vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { if (init.method === "POST") { writes.push({ body: JSON.parse(init.body as string), key: new Headers(init.headers).get("Idempotency-Key") }); return Response.json({ title: "Subscriptions remain" }, { status: 409 }); } return Response.json(policy); });
  render(<SubscriptionPolicyForm siteId={siteId} />); const user = userEvent.setup(); await screen.findByText(/Saved policy version: 4/); await user.click(screen.getByLabelText("Subscriptions enabled")); await user.click(screen.getByRole("button", { name: "Save policy" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(/Finish non-terminal subscriptions/); expect(screen.getByText(/Saved policy: enabled/)).toBeInTheDocument(); expect(writes[0].body).toEqual({ version: 4, enabled: false, grace_days: 3, allow_immediate_cancellation: true, portal_enabled: false, defer_upgrades: false, portal_return_urls: [] }); expect(writes[0].key).toBeTruthy();
});
it("does not expose the stale policy editor during an explicit conflict reload", async () => {
  const next = deferred<Response>(); let reads = 0;
  vi.stubGlobal("fetch", (_path: string, init: RequestInit) => init.method === "POST" ? Promise.resolve(Response.json({}, { status: 409 })) : ++reads === 1 ? Promise.resolve(Response.json(policy)) : next.promise);
  render(<SubscriptionPolicyForm siteId={siteId} />); const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "Save policy" })); await screen.findByRole("alert"); await user.click(screen.getByRole("button", { name: "Reload policy" }));
  expect(screen.queryByRole("button", { name: "Save policy" })).not.toBeInTheDocument();
  await act(async () => next.resolve(Response.json({ ...policy, version: 5 })));
  expect(await screen.findByText("Saved policy version: 5")).toBeInTheDocument();
});
it("rejects fractional/negative grace, presents concurrent version conflict without overwrite and requires explicit reload", async () => {
  let reads = 0; let writes = 0; vi.stubGlobal("fetch", async (_: string, init: RequestInit) => { if (init.method === "POST") { writes++; return Response.json({ title: "Policy version conflict" }, { status: 409 }); } reads++; return Response.json({ ...policy, version: reads === 1 ? 4 : 5, grace_days: reads === 1 ? 3 : 7 }); });
  render(<SubscriptionPolicyForm siteId={siteId} />); await screen.findByText(/Saved policy version: 4/); const user = userEvent.setup();
  for (const value of ["-1", "1.5"]) { fireEvent.change(screen.getByLabelText("Grace days (UTC)"), { target: { value } }); await user.click(screen.getByRole("button", { name: "Save policy" })); expect(screen.getByRole("alert")).toHaveTextContent(/integer/); } expect(writes).toBe(0);
  fireEvent.change(screen.getByLabelText("Grace days (UTC)"), { target: { value: "6" } }); await user.click(screen.getByRole("button", { name: "Save policy" })); expect(await screen.findByRole("alert")).toHaveTextContent(/Reload the policy/); expect(screen.getByLabelText("Grace days (UTC)")).toHaveValue("6"); expect(reads).toBe(1);
  await user.click(screen.getByRole("button", { name: "Reload policy" })); await waitFor(() => expect(screen.getByLabelText("Grace days (UTC)")).toHaveValue("7")); expect(writes).toBe(1);
});
