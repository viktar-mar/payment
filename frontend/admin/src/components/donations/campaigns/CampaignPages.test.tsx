import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { CampaignList } from "./CampaignList";
import { CampaignDetails } from "./CampaignDetails";
import { campaign, siteId } from "./test-fixtures";
afterEach(() => vi.unstubAllGlobals());
it("restores the creation trigger after a successful form unmount",async()=>{
  vi.stubGlobal("fetch",async (_:string,init:RequestInit)=>Response.json(init.method==="GET"?{items:[],offset:0,limit:20}:campaign));
  render(<CampaignList siteId={siteId}/>);await screen.findByText("No campaigns found.");
  fireEvent.click(screen.getByRole("button",{name:"Create campaign"}));
  fireEvent.change(screen.getByLabelText("Campaign key"),{target:{value:"help-now"}});
  fireEvent.change(screen.getByLabelText("Display name"),{target:{value:"Help"}});
  const submit=screen.getByRole("button",{name:"Save campaign"});submit.focus();fireEvent.click(submit);
  await waitFor(()=>expect(screen.queryByRole("form",{name:"Create campaign"})).not.toBeInTheDocument());
  expect(screen.getByRole("button",{name:"Create campaign"})).toHaveFocus();
});
it("links campaign-filtered Donations",async()=>{vi.stubGlobal("fetch",async()=>Response.json(campaign));render(<CampaignDetails siteId={siteId} campaignId={campaign.id!}/>);expect(await screen.findByRole("link",{name:"Donations for this campaign"})).toHaveAttribute("href",`/donations?site_id=${siteId}&campaign_id=${campaign.id}`);});
it("lists only the path Site, escapes text, links details without prefetch and supports bounded pagination", async () => {
  const urls:string[]=[]; vi.stubGlobal("fetch",async(url:string)=>{urls.push(url);return Response.json({items:Array.from({length:20},(_,index)=>({...campaign,id:index===0?campaign.id:`row-${index}`})),offset:0,limit:20});});
  render(<CampaignList siteId={siteId} />);
  const links=await screen.findAllByRole("link",{name:campaign.display_name});
  expect(links[0]).toHaveAttribute("href",`/sites/${siteId}/donation-campaigns/${campaign.id}`);
  expect(document.querySelector("script, img")).toBeNull(); expect(urls[0]).toBe(`/api/backend/v1/admin/sites/${siteId}/donation-campaigns?limit=20&offset=0`);
  fireEvent.click(screen.getByRole("button",{name:"Next page"})); await waitFor(()=>expect(urls[1]).toContain("offset=20"));
  fireEvent.click(screen.getByRole("button",{name:"Previous page"})); await waitFor(()=>expect(urls[2]).toContain("offset=0"));
});
it("does not fetch invalid ownership and announces authoritative read errors safely", async () => {
  const fetcher=vi.fn();vi.stubGlobal("fetch",fetcher); const view=render(<CampaignDetails siteId="invalid" campaignId={campaign.id!} />);
  expect(screen.getByRole("alert")).toHaveTextContent(/valid/i);expect(fetcher).not.toHaveBeenCalled();
  fetcher.mockResolvedValue(Response.json({title:"Campaign not available",detail:"PRIVATE"},{status:404}));
  view.rerender(<CampaignDetails siteId={siteId} campaignId={campaign.id!} />);
  expect(await screen.findByText("Campaign not available")).toHaveAttribute("role","alert"); expect(screen.queryByText("PRIVATE")).toBeNull();
});
it("loads details from the owning Site and refreshes a stale version before another edit", async () => {
  const requests:{url:string;init:RequestInit}[]=[];let reads=0;
  vi.stubGlobal("fetch",async(url:string,init:RequestInit)=>{requests.push({url,init});return init.method==="GET"?Response.json({...campaign,version:reads++}):Response.json({title:"Resource conflict"},{status:409});});
  render(<CampaignDetails siteId={siteId} campaignId={campaign.id!} />);
  await screen.findByLabelText("Display name");fireEvent.click(screen.getByRole("button",{name:"Save campaign"}));
  fireEvent.click(await screen.findByRole("button",{name:"Refresh campaign"}));
  await waitFor(()=>expect(screen.getByText("Version 1")).toBeInTheDocument());
  expect(screen.getByRole("button",{name:"Save campaign"})).toBeEnabled();
  expect(requests.every(request=>request.url.startsWith(`/api/backend/v1/admin/sites/${siteId}/donation-campaigns/${campaign.id}`))).toBe(true);
});
it("refreshes a create conflict, releases the blocked intent and restores the creation trigger focus", async () => {
  let reads=0;
  vi.stubGlobal("fetch",async (_:string,init:RequestInit)=>{
    if(init.method==="GET") { reads++; return Response.json({items:[],offset:0,limit:20}); }
    return Response.json({title:"Resource conflict"},{status:409});
  });
  render(<CampaignList siteId={siteId} />);
  await screen.findByText("No campaigns found.");
  fireEvent.click(screen.getByRole("button",{name:"Create campaign"}));
  fireEvent.change(screen.getByLabelText("Campaign key"),{target:{value:"help-now"}});
  fireEvent.change(screen.getByLabelText("Display name"),{target:{value:"Help"}});
  fireEvent.click(screen.getByRole("button",{name:"Save campaign"}));
  fireEvent.click(await screen.findByRole("button",{name:"Refresh campaign"}));
  await waitFor(()=>expect(reads).toBe(2));
  expect(screen.queryByRole("form",{name:"Create campaign"})).not.toBeInTheDocument();
  expect(screen.getByRole("button",{name:"Create campaign"})).toHaveFocus();
  fireEvent.click(screen.getByRole("button",{name:"Create campaign"}));
  expect(screen.getByRole("button",{name:"Save campaign"})).toBeEnabled();
  expect(screen.getByLabelText("Campaign key")).toHaveValue("");
});
