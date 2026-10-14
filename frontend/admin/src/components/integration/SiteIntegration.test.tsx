import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { OneTimeSecret } from "./OneTimeSecret";
import { SiteApiKeys } from "./SiteApiKeys";
import { CallbackConfiguration } from "./CallbackConfiguration";
import { StrictMode } from "react";
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
it("keeps subscription scopes unchecked and adds them only to explicitly created keys",async()=>{
  const bodies:unknown[]=[];vi.stubGlobal("fetch",async(_path:string,init:RequestInit)=>{bodies.push(init.body?JSON.parse(init.body as string):undefined);return Response.json({key_id:"key-optin",scopes:["payments:read"],api_key:"safe-one-time-fixture"});});
  render(<SiteApiKeys siteId="site-1"/>);expect(screen.getByLabelText("subscriptions:read")).not.toBeChecked();expect(screen.getByLabelText("subscriptions:manage")).not.toBeChecked();const user=userEvent.setup();await user.click(screen.getByLabelText("payments:read"));await user.click(screen.getByRole("button",{name:"Create API key"}));await user.click(await screen.findByRole("button",{name:"Dismiss API key"}));await user.click(screen.getByLabelText("subscriptions:manage"));await user.click(screen.getByRole("button",{name:"Rotate key-optin"}));await user.click(await screen.findByRole("button",{name:"Dismiss API key"}));expect(bodies).toEqual([{scopes:["payments:read"]},undefined]);await user.click(screen.getByRole("button",{name:"Create API key"}));await waitFor(()=>expect(bodies).toHaveLength(3));expect(bodies[2]).toEqual({scopes:["payments:read","subscriptions:manage"]});
});
it("reveals, explicitly copies and dismisses a secret without persistence or automatic exposure", async () => {
  const user = userEvent.setup(); const copy = vi.spyOn(navigator.clipboard, "writeText").mockResolvedValue(); const local = vi.spyOn(Storage.prototype, "setItem"); const consoleSpy = vi.spyOn(console, "log"); let dismissed = 0;
  const view = render(<OneTimeSecret label="API key" secret="one-time-fixture" onDismiss={() => { dismissed++; }} />);
  expect(screen.queryByDisplayValue("one-time-fixture")).not.toBeInTheDocument(); expect(copy).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "Reveal API key" })); expect(screen.getByLabelText("API key value")).toHaveValue("one-time-fixture"); await user.click(screen.getByRole("button", { name: "Copy API key" })); expect(await screen.findByRole("status")).toHaveTextContent("Copied"); expect(copy).toHaveBeenCalledWith("one-time-fixture");
  await user.click(screen.getByRole("button", { name: "Dismiss API key" })); expect(dismissed).toBe(1); expect(screen.queryByDisplayValue("one-time-fixture")).not.toBeInTheDocument(); expect(local).not.toHaveBeenCalled(); expect(consoleSpy).not.toHaveBeenCalled(); expect(window.location.href).not.toContain("one-time-fixture"); view.unmount();
});
it("clears plaintext on unmount and copy failure uses safe accessible text", async () => {
  const user = userEvent.setup(); vi.spyOn(navigator.clipboard, "writeText").mockRejectedValue(new Error("PRIVATE")); let clears = 0;
  const view = render(<OneTimeSecret label="Callback secret" secret="callback-fixture" onDismiss={() => { clears++; }} />);
  await user.click(screen.getByRole("button", { name: "Copy Callback secret" })); expect(await screen.findByRole("status")).toHaveTextContent("Unable to copy"); expect(screen.queryByText(/PRIVATE/)).not.toBeInTheDocument(); view.unmount(); await waitFor(() => expect(clears).toBe(1));
});
it("creates scoped keys, retains only current-session metadata, rotates and revokes", async () => {
  const requests: { path: string; body: unknown }[] = [];
  vi.stubGlobal("fetch", async (path: string, init: RequestInit) => { requests.push({ path, body: init.body ? JSON.parse(init.body as string) : undefined }); if (path.endsWith("revoke")) return new Response(null, { status: 204 }); return Response.json({ key_id: requests.length === 1 ? "key-1" : "key-2", site_id: "site-1", scopes: ["payments:read"], status: "ACTIVE", created_at: "2026-10-05T00:00:00Z", api_key: "key-fixture" }, { status: 201 }); });
  render(<SiteApiKeys siteId="site-1" />); expect(screen.getByText(/only keys created or rotated in this session/i)).toBeInTheDocument(); const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "Create API key" })); expect(screen.getByRole("alert")).toHaveTextContent("Select at least one scope"); expect(requests).toHaveLength(0);
  await user.click(screen.getByLabelText("payments:read")); await user.click(screen.getByRole("button", { name: "Create API key" })); await screen.findByRole("button", { name: "Reveal API key" }); expect(requests[0]).toEqual({ path: "/api/backend/v1/admin/sites/site-1/api-keys", body: { scopes: ["payments:read"] } });
  await user.click(screen.getByRole("button", { name: "Dismiss API key" })); await user.click(screen.getByRole("button", { name: "Rotate key-1" })); await screen.findByRole("button", { name: "Reveal API key" }); expect(requests[1].path).toBe("/api/backend/v1/admin/sites/site-1/api-keys/key-1/rotate");
  expect(screen.queryByText("ROTATION_OVERLAP")).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Dismiss API key" })); await user.click(screen.getByRole("button", { name: "Revoke key-2" })); await waitFor(() => expect(screen.getByText("REVOKED")).toBeInTheDocument());
  expect(document.body.textContent).not.toContain("key-fixture");
});
it("survives StrictMode replay, restores reveal focus after hide, and clears on real navigation", async () => {
  const user = userEvent.setup(); const clear = vi.fn();
  const view = render(<StrictMode><OneTimeSecret label="API key" secret="strict-fixture" onDismiss={clear} /></StrictMode>);
  await waitFor(() => expect(screen.getByRole("button", { name: "Reveal API key" })).toBeInTheDocument());
  expect(clear).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "Reveal API key" })); expect(screen.getByLabelText("API key value")).toHaveFocus();
  await user.click(screen.getByRole("button", { name: "Hide API key" })); expect(screen.getByRole("button", { name: "Reveal API key" })).toHaveFocus();
  view.unmount(); await waitFor(() => expect(clear).toHaveBeenCalledTimes(1));
});
it("configures callback and rotates its one-time secret with no invented read endpoint", async () => {
  const requests: { path: string; method: string | undefined; body: unknown }[] = [];
  vi.stubGlobal("fetch", async (path: string, init: RequestInit) => { requests.push({ path, method: init.method, body: init.body ? JSON.parse(init.body as string) : undefined }); return Response.json({ site_id: "site-1", endpoint_url: "https://merchant.test/callback", enabled: true, secret_version: requests.length, secret: "callback-fixture" }); });
  render(<CallbackConfiguration siteId="site-1" />); expect(screen.getByText(/configuration returned in this session/i)).toBeInTheDocument(); expect(requests).toHaveLength(0); const user = userEvent.setup();
  await user.type(screen.getByLabelText("Callback endpoint URL"), "https://merchant.test/callback"); await user.click(screen.getByLabelText("Callback enabled")); await user.click(screen.getByRole("button", { name: "Save callback" }));
  await screen.findByRole("button", { name: "Reveal Callback secret" }); expect(requests[0]).toEqual({ path: "/api/backend/v1/admin/sites/site-1/callback", method: "PUT", body: { endpoint_url: "https://merchant.test/callback", enabled: true } });
  await user.click(screen.getByRole("button", { name: "Dismiss Callback secret" })); await user.click(screen.getByRole("button", { name: "Rotate callback secret" })); await screen.findByRole("button", { name: "Reveal Callback secret" }); expect(requests[1].path).toBe("/api/backend/v1/admin/sites/site-1/callback/rotate-secret");
});
it("aborts integration writes on navigation and exposes safe titles only", async () => {
  let signal: AbortSignal | undefined;
  vi.stubGlobal("fetch", (_path: string, init: RequestInit) => { signal = init.signal as AbortSignal; return new Promise(() => {}); }); const view = render(<SiteApiKeys siteId="site-1" />); const user = userEvent.setup(); await user.click(screen.getByLabelText("payments:create")); await user.click(screen.getByRole("button", { name: "Create API key" })); view.unmount(); expect(signal?.aborted).toBe(true);
  vi.stubGlobal("fetch", async () => Response.json({ title: "Site unavailable", detail: "PRIVATE" }, { status: 404 })); render(<SiteApiKeys siteId="site-1" />); await user.click(screen.getByLabelText("payments:create")); await user.click(screen.getByRole("button", { name: "Create API key" })); expect(await screen.findByRole("alert")).toHaveTextContent("Site unavailable"); expect(screen.queryByText(/PRIVATE/)).not.toBeInTheDocument();
});
it("keeps plaintext out of all persistence/history/console sinks across callback navigation", async () => {
  const user = userEvent.setup(); const storage = vi.spyOn(Storage.prototype, "setItem"); const replace = vi.spyOn(history, "replaceState"); const push = vi.spyOn(history, "pushState");
  const logs = [vi.spyOn(console, "log"), vi.spyOn(console, "info"), vi.spyOn(console, "warn"), vi.spyOn(console, "error"), vi.spyOn(console, "debug")];
  vi.stubGlobal("fetch", async () => Response.json({ site_id: "site-1", endpoint_url: "https://merchant.test/callback", enabled: true, secret_version: 1, secret: "navigation-fixture" }));
  const view = render(<CallbackConfiguration siteId="site-1" />); await user.click(screen.getByRole("button", { name: "Rotate callback secret" })); await user.click(await screen.findByRole("button", { name: "Reveal Callback secret" })); expect(screen.getByLabelText("Callback secret value")).toHaveValue("navigation-fixture"); view.unmount();
  expect(document.body.textContent).not.toContain("navigation-fixture"); expect(window.location.href).not.toContain("navigation-fixture"); expect(storage).not.toHaveBeenCalled(); expect(replace).not.toHaveBeenCalled(); expect(push).not.toHaveBeenCalled(); for (const log of logs) expect(log).not.toHaveBeenCalled();
});
