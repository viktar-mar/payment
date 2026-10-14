import {act, fireEvent, render, screen, waitFor} from "@testing-library/react";
import {afterEach, expect, it, vi} from "vitest";
import {DonationList} from "./DonationList";
import {donation, donationId, campaignId, customerId, paymentId, siteId, page} from "./test-fixtures";
afterEach(()=>{vi.unstubAllGlobals();window.history.replaceState(null,"","/");});
it("queries every Donation filter, bounds paging independently and renders only safe summaries",async()=>{
  window.history.replaceState(null,"",`/donations?site_id=${siteId}&campaign_id=${campaignId}&customer_id=${customerId}&donation_id=${donationId}&payment_id=${paymentId}&status=SUCCEEDED&created_from=2026-01-01T00%3A00%3A00Z&created_to=2027-01-01T00%3A00%3A00Z&page=999999&size=999`);
  const requests:{url:string;init:RequestInit}[]=[];
  vi.stubGlobal("fetch",async(url:string,init:RequestInit)=>{requests.push({url,init});return Response.json({...page,items:[donation,{...donation,donation_id:"anonymous",customer_id:null,message_state:"ABSENT",has_message:false},{...donation,donation_id:"redacted",message_state:"REDACTED",has_message:false}],page:100000,size:50});});
  render(<DonationList/>);expect(screen.getByRole("status")).toHaveTextContent("Loading donations");
  await screen.findByRole("table",{name:"Donations"});
  expect(requests[0].url).toBe(`/api/backend/v1/admin/donations?campaign_id=${campaignId}&created_from=2026-01-01T00%3A00%3A00Z&created_to=2027-01-01T00%3A00%3A00Z&customer_id=${customerId}&donation_id=${donationId}&page=100000&payment_id=${paymentId}&site_id=${siteId}&size=50&status=SUCCEEDED`);
  expect(requests[0].init.cache).toBe("no-store");expect(requests).toHaveLength(1);
  expect(screen.getByRole("link",{name:donationId})).toHaveAttribute("href",`/donations/${donationId}`);
  expect(screen.getAllByRole("link",{name:paymentId})[0]).toHaveAttribute("href",`/payments/${paymentId}`);
  for(const text of ["PRESENT","ABSENT","REDACTED","Anonymous"])expect(screen.getByText(text)).toBeInTheDocument();
  expect(screen.getAllByRole("link",{name:customerId})[0]).toHaveAttribute("href",`/customers/${customerId}?site_id=${siteId}`);
  fireEvent.click(screen.getByRole("button",{name:"Apply filters"}));await waitFor(()=>expect(requests.at(-1)?.url).toContain("page=0"));
});
it.each(["site_id","campaign_id","customer_id","donation_id","payment_id"])("rejects invalid %s in browser state without fetching",async(name)=>{
  window.history.replaceState(null,"",`/donations?${name}=invalid`);const fetcher=vi.fn();vi.stubGlobal("fetch",fetcher);render(<DonationList/>);
  expect(await screen.findByRole("alert")).toHaveTextContent("valid UUID");expect(fetcher).not.toHaveBeenCalled();
});
it.each(["1969-12-31T23:59:59Z","2027-02-30T00:00:00Z","2026-10-10T00:00:00+00:00"])("rejects invalid date %s",async(value)=>{
  window.history.replaceState(null,"",`/donations?created_from=${encodeURIComponent(value)}`);const fetcher=vi.fn();vi.stubGlobal("fetch",fetcher);render(<DonationList/>);
  expect(await screen.findByRole("alert")).toHaveTextContent("valid UTC");expect(fetcher).not.toHaveBeenCalled();
});
it("validates form ranges, omits empty filters and restores defaults",async()=>{
  const urls:string[]=[];vi.stubGlobal("fetch",async(url:string)=>{urls.push(url);return Response.json({...page,items:[],total:0});});render(<DonationList/>);
  await screen.findByText("No donations found.");expect(urls[0]).toBe("/api/backend/v1/admin/donations?page=0&size=20");
  fireEvent.change(screen.getByLabelText("Created from (UTC)"),{target:{value:"2026-12-01T00:00:00Z"}});fireEvent.change(screen.getByLabelText("Created to (UTC)"),{target:{value:"2026-01-01T00:00:00Z"}});
  fireEvent.click(screen.getByRole("button",{name:"Apply filters"}));expect(screen.getByRole("alert")).toHaveTextContent("before");expect(urls).toHaveLength(1);
});
it("discards old rows on navigation, aborts stale reads and announces safe failures",async()=>{
  let calls=0;let signal:AbortSignal|undefined;let resolve:(r:Response)=>void=()=>{};
  vi.stubGlobal("fetch",async(_url:string,init:RequestInit)=>{calls++;if(calls===1)return Response.json(page);if(calls===2){signal=init.signal as AbortSignal;return new Promise<Response>(done=>{resolve=done;});}return Response.json({title:"Donations unavailable",detail:"PRIVATE"},{status:503});});
  render(<DonationList/>);await screen.findByRole("link",{name:donationId});
  const navigate=(query:string)=>act(()=>{window.history.pushState(null,"",`/donations?${query}`);window.dispatchEvent(new PopStateEvent("popstate"));});
  navigate(`customer_id=${customerId}`);expect(screen.queryByRole("table")).toBeNull();expect(screen.getByRole("status")).toHaveTextContent("Loading");
  navigate(`campaign_id=${campaignId}`);expect(await screen.findByRole("alert")).toHaveTextContent("Donations unavailable");expect(signal?.aborted).toBe(true);
  await act(async()=>{resolve(Response.json(page));});expect(screen.queryByRole("table")).toBeNull();expect(screen.queryByText("PRIVATE")).toBeNull();
});
