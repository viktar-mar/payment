import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { CampaignForm } from "./CampaignForm";
import { campaign, siteId } from "./test-fixtures";
afterEach(() => vi.unstubAllGlobals());
function form() { render(<CampaignForm siteId={siteId} campaign={campaign} onSaved={() => {}} onRefresh={() => {}} />); }
it("creates a draft with a single pending intent and no optimistic version",async()=>{
  let accept:(response:Response)=>void=()=>{};const requests:RequestInit[]=[];
  vi.stubGlobal("fetch",(_:string,init:RequestInit)=>{requests.push(init);return new Promise<Response>(resolve=>{accept=resolve;});});
  const saved=vi.fn();render(<CampaignForm siteId={siteId} onSaved={saved} onRefresh={()=>{}} />);
  fireEvent.change(screen.getByLabelText("Campaign key"),{target:{value:"help-now"}});fireEvent.change(screen.getByLabelText("Display name"),{target:{value:"Help"}});
  fireEvent.click(screen.getByRole("button",{name:"Save campaign"}));fireEvent.click(screen.getByRole("button",{name:"Save campaign"}));
  expect(requests).toHaveLength(1);expect(requests[0].method).toBe("POST");expect(JSON.parse(String(requests[0].body))).not.toHaveProperty("expected_version");
  expect(screen.getByRole("status")).toHaveTextContent("Saving campaign");expect(screen.getByRole("button",{name:"Save campaign"})).toBeDisabled();
  accept(Response.json(campaign));await waitFor(()=>expect(saved).toHaveBeenCalledWith(campaign));
});
it("preserves ordered amounts, edits them by keyboard and sends Site-owned optimistic update", async () => {
  const requests: { url: string; init: RequestInit }[] = [];
  vi.stubGlobal("fetch", async (url: string, init: RequestInit) => { requests.push({ url, init }); return Response.json({ ...campaign, version: 1 }); });
  const user = userEvent.setup(); form();
  const amounts = screen.getByLabelText("Suggested amounts (minor units, in display order)");
  expect(amounts).toHaveValue("500, 100"); await user.clear(amounts); await user.type(amounts, "100, 500, 900");
  expect(screen.getByRole("status")).toHaveTextContent("100, 500, 900");
  await user.click(screen.getByRole("button", { name: "Save campaign" }));
  await waitFor(() => expect(requests).toHaveLength(1));
  expect(requests[0].url).toBe(`/api/backend/v1/admin/sites/${siteId}/donation-campaigns/${campaign.id}`);
  expect(requests[0].init.method).toBe("PUT");
  expect(JSON.parse(String(requests[0].init.body))).toEqual({ key: "help-now", display_name: campaign.display_name, purpose_description: campaign.purpose_description, currency: "EUR", suggested_amounts: [100, 500, 900], minimum_amount: 100, maximum_amount: 1000, custom_amount_allowed: false, anonymous_allowed: true, expected_version: 0 });
});
it.each(["100, 100", "99, 500", "500, 1001", "1.5, 500", "", "9007199254740992"])("rejects invalid suggested amounts %s before sending", async amounts => {
  const fetcher=vi.fn(); vi.stubGlobal("fetch",fetcher); form();
  fireEvent.change(screen.getByLabelText("Suggested amounts (minor units, in display order)"), { target: { value: amounts } });
  fireEvent.click(screen.getByRole("button", { name: "Save campaign" }));
  expect(screen.getByRole("alert")).toHaveTextContent(/amount/i); expect(fetcher).not.toHaveBeenCalled();
  expect(screen.getByRole("alert")).toHaveFocus();
});
it("previews min/max and custom policy and permits no suggestions when custom is enabled", async () => {
  const requests: RequestInit[]=[]; vi.stubGlobal("fetch",async (_:string,init:RequestInit)=>{requests.push(init);return Response.json(campaign);}); form();
  fireEvent.change(screen.getByLabelText("Minimum amount (minor units)"),{target:{value:"600"}});
  fireEvent.click(screen.getByRole("button",{name:"Save campaign"})); expect(screen.getByRole("alert")).toHaveTextContent(/amount/i);
  fireEvent.change(screen.getByLabelText("Minimum amount (minor units)"),{target:{value:"100"}});
  fireEvent.click(screen.getByLabelText("Allow custom amounts")); fireEvent.change(screen.getByLabelText("Suggested amounts (minor units, in display order)"),{target:{value:""}});
  expect(screen.getByRole("status")).toHaveTextContent("100–1000");
  fireEvent.click(screen.getByRole("button",{name:"Save campaign"})); await waitFor(()=>expect(requests).toHaveLength(1));
  expect(JSON.parse(String(requests[0].body)).suggested_amounts).toEqual([]);
});
it("locks currency after first activation including paused and escapes display/purpose text", () => {
  render(<CampaignForm siteId={siteId} campaign={{...campaign,status:"PAUSED",activated_once:true}} onSaved={()=>{}} onRefresh={()=>{}} />);
  expect(screen.getByLabelText("Currency")).toBeDisabled(); expect(screen.getByLabelText("Campaign key")).toBeDisabled();
  expect(screen.getByLabelText("Display name")).toHaveValue(campaign.display_name);
  expect(document.querySelector("script, img")).toBeNull();
});
it("retains the idempotency key for unchanged ambiguous retry and replaces it after editing", async () => {
  const requests: RequestInit[]=[]; vi.stubGlobal("fetch",async (_:string,init:RequestInit)=>{requests.push(init);throw new TypeError("transport private");}); form();
  fireEvent.click(screen.getByRole("button",{name:"Save campaign"}));
  await screen.findByRole("button",{name:"Retry save"}); fireEvent.click(screen.getByRole("button",{name:"Retry save"}));
  await waitFor(()=>expect(requests).toHaveLength(2));
  expect(new Headers(requests[1].headers).get("Idempotency-Key")).toBe(new Headers(requests[0].headers).get("Idempotency-Key"));
  await screen.findByRole("button",{name:"Retry save"}); fireEvent.change(screen.getByLabelText("Display name"),{target:{value:"Updated"}});
  fireEvent.click(screen.getByRole("button",{name:"Save campaign"})); await waitFor(()=>expect(requests).toHaveLength(3));
  expect(new Headers(requests[2].headers).get("Idempotency-Key")).not.toBe(new Headers(requests[0].headers).get("Idempotency-Key"));
});
it("announces authoritative server errors and requires conflict refresh before resubmitting", async () => {
  vi.stubGlobal("fetch",async ()=>Response.json({title:"Resource conflict",detail:"PRIVATE"},{status:409}));
  const refresh=vi.fn(); render(<CampaignForm siteId={siteId} campaign={campaign} onSaved={()=>{}} onRefresh={refresh} />);
  fireEvent.click(screen.getByRole("button",{name:"Save campaign"}));
  expect(await screen.findByRole("alert")).toHaveTextContent(/refresh/i); expect(screen.getByRole("alert")).not.toHaveTextContent("PRIVATE");
  expect(screen.getByRole("button",{name:"Save campaign"})).toBeDisabled();
  fireEvent.click(screen.getByRole("button",{name:"Refresh campaign"})); expect(refresh).toHaveBeenCalledOnce();
});
