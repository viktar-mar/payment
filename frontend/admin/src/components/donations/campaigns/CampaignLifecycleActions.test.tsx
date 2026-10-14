import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { CampaignLifecycleActions } from "./CampaignLifecycleActions";
import { campaign, siteId } from "./test-fixtures";
afterEach(()=>vi.unstubAllGlobals());
it.each([["DRAFT",["Activate","Archive"]],["ACTIVE",["Pause","Archive"]],["PAUSED",["Resume","Archive"]],["ARCHIVED",[]]] as const)("offers only legal lifecycle actions for %s",(status,labels)=>{
  render(<CampaignLifecycleActions siteId={siteId} campaign={{...campaign,status}} onChanged={()=>{}} onRefresh={()=>{}} />);
  expect(screen.queryAllByRole("button").map(button=>button.textContent)).toEqual(labels);
});
it("supports keyboard confirmation, disables duplicate submission and restores focus after acceptance",async ()=>{
  const requests:RequestInit[]=[];let resolve:(response:Response)=>void=()=>{};
  vi.stubGlobal("fetch",(_:string,init:RequestInit)=>{requests.push(init);return new Promise<Response>(done=>{resolve=done;});});
  const changed=vi.fn();render(<CampaignLifecycleActions siteId={siteId} campaign={campaign} onChanged={changed} onRefresh={()=>{}} />);
  const user=userEvent.setup(); await user.tab(); await user.keyboard("{Enter}");
  expect(screen.getByRole("dialog")).toHaveAccessibleName("Confirm activate campaign");
  await user.click(screen.getByRole("button",{name:"Confirm activate"}));
  expect(screen.getByRole("button",{name:"Confirm activate"})).toBeDisabled(); expect(screen.getByRole("status")).toHaveTextContent(/submitting/i);
  expect(requests).toHaveLength(1); expect(JSON.parse(String(requests[0].body))).toEqual({status:"ACTIVE",expected_version:0});
  await act(async ()=>resolve(Response.json({...campaign,status:"ACTIVE",activated_once:true,version:1})));
  await waitFor(()=>expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  expect(changed).toHaveBeenCalledWith(expect.objectContaining({status:"ACTIVE",version:1})); expect(screen.getByRole("button",{name:"Activate"})).toHaveFocus();
});
it("keeps an unchanged lifecycle key for ambiguous retries and announces safe errors",async ()=>{
  const keys:(string|null)[]=[];vi.stubGlobal("fetch",async (_:string,init:RequestInit)=>{keys.push(new Headers(init.headers).get("Idempotency-Key"));throw new TypeError("PRIVATE");});
  render(<CampaignLifecycleActions siteId={siteId} campaign={campaign} onChanged={()=>{}} onRefresh={()=>{}} />);
  fireEvent.click(screen.getByRole("button",{name:"Activate"}));fireEvent.click(screen.getByRole("button",{name:"Confirm activate"}));
  expect(await screen.findByRole("alert")).toHaveTextContent("Unable to connect");fireEvent.click(screen.getByRole("button",{name:"Retry activate"}));
  await waitFor(()=>expect(keys).toHaveLength(2));expect(keys[0]).toBeTruthy();expect(keys[1]).toBe(keys[0]);
});
it("conflict requires refresh and Escape cancels while restoring trigger focus",async ()=>{
  vi.stubGlobal("fetch",async ()=>Response.json({title:"Resource conflict"},{status:409}));const refresh=vi.fn();
  render(<CampaignLifecycleActions siteId={siteId} campaign={campaign} onChanged={()=>{}} onRefresh={refresh} />);
  const user=userEvent.setup();await user.click(screen.getByRole("button",{name:"Archive"}));await user.keyboard("{Escape}");
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();expect(screen.getByRole("button",{name:"Archive"})).toHaveFocus();
  await user.click(screen.getByRole("button",{name:"Activate"}));await user.click(screen.getByRole("button",{name:"Confirm activate"}));
  expect(await screen.findByRole("alert")).toHaveTextContent(/refresh/i);expect(screen.getByRole("button",{name:"Confirm activate"})).toBeDisabled();
  await user.click(screen.getByRole("button",{name:"Refresh campaign"}));expect(refresh).toHaveBeenCalledOnce();
});
