import {render, screen, waitFor, fireEvent} from "@testing-library/react";
import {afterEach, expect, it, vi} from "vitest";
import userEvent from "@testing-library/user-event";
import {DonationDetails} from "./DonationDetails";
import {donation, donationId, paymentId} from "./test-fixtures";
afterEach(()=>vi.unstubAllGlobals());
it.each(["<script>alert('x')</script>  café", "", "   "])("renders the detail-only message as escaped whitespace-preserving text (%j)",async(message)=>{
  const requests:{url:string;init:RequestInit}[]=[];vi.stubGlobal("fetch",async(url:string,init:RequestInit)=>{requests.push({url,init});return Response.json({donation,message});});
  render(<DonationDetails donationId={donationId}/>);expect(screen.getByRole("status")).toHaveTextContent("Loading donation");await screen.findByRole("heading",{name:`Donation ${donationId}`});
  const content=screen.getByLabelText("Donor message");expect(content.textContent).toBe(message);expect(content).toHaveStyle({whiteSpace:"pre-wrap"});expect(content.querySelector("script")).toBeNull();
  expect(requests.map(x=>x.url)).toEqual([`/api/backend/v1/admin/donations/${donationId}`]);expect(requests[0].init.cache).toBe("no-store");
  expect(screen.getByRole("link",{name:"Payment details, refunds and reconciliation"})).toHaveAttribute("href",`/payments/${paymentId}`);
  expect(screen.queryByRole("button",{name:"Refund"})).toBeNull();expect(screen.getByText("SUCCEEDED")).toBeInTheDocument();
});
it.each(["ABSENT","REDACTED"])("shows %s without redaction or plaintext",async(message_state)=>{
  vi.stubGlobal("fetch",async()=>Response.json({donation:{...donation,message_state,has_message:false},message:null}));render(<DonationDetails donationId={donationId}/>);
  expect(await screen.findByText(message_state)).toBeInTheDocument();expect(screen.queryByLabelText("Donor message")).toBeNull();expect(screen.queryByRole("button",{name:"Redact message"})).toBeNull();
});
it("scrubs the actual retained DTO immediately after success, closes the dialog and restores heading focus",async()=>{
  const received={donation:{...donation},message:"<script>PRIVATE-MESSAGE</script>"};let reads=0;
  vi.stubGlobal("fetch",async(_url:string,init:RequestInit)=>{if(init.method==="POST")return Response.json({donation_id:donationId,message_state:"REDACTED",changed:true,replayed:false});reads++;const response=Response.json(received);response.json=async()=>received;return response;});
  render(<DonationDetails donationId={donationId}/>);await userEvent.setup().click(await screen.findByRole("button",{name:"Redact message"}));fireEvent.change(screen.getByLabelText("Donation UUID confirmation"),{target:{value:donationId}});fireEvent.click(screen.getByRole("button",{name:"Confirm redaction"}));
  expect(await screen.findByText("REDACTED")).toBeInTheDocument();expect(received.message).toBeNull();expect(reads).toBe(1);expect(screen.queryByRole("dialog")).toBeNull();expect(screen.queryByLabelText("Donor message")).toBeNull();
  await waitFor(()=>expect(screen.getByRole("heading",{name:`Donation ${donationId}`})).toHaveFocus());
});
it("retains the message on cancellation or API error and aborts/unsets the DTO on unmount",async()=>{
  const received={donation:{...donation},message:"still present"};let signal:AbortSignal|undefined;
  vi.stubGlobal("fetch",async(_url:string,init:RequestInit)=>{if(init.method==="POST")return Response.json({title:"Resource conflict",detail:"PRIVATE"},{status:409});signal=init.signal as AbortSignal;const response=Response.json(received);response.json=async()=>received;return response;});
  const view=render(<DonationDetails donationId={donationId}/>);fireEvent.click(await screen.findByRole("button",{name:"Redact message"}));fireEvent.click(screen.getByRole("button",{name:"Cancel"}));expect(screen.getByLabelText("Donor message")).toHaveTextContent("still present");
  fireEvent.click(screen.getByRole("button",{name:"Redact message"}));fireEvent.change(screen.getByLabelText("Donation UUID confirmation"),{target:{value:donationId}});fireEvent.click(screen.getByRole("button",{name:"Confirm redaction"}));
  expect(await screen.findByRole("alert")).toHaveTextContent("Resource conflict");expect(screen.getByLabelText("Donor message")).toHaveTextContent("still present");expect(screen.queryByText("PRIVATE")).toBeNull();view.unmount();expect(signal?.aborted).toBe(true);expect(received.message).toBeNull();
});
it("does not fetch invalid IDs and handles authoritative errors/empty details safely",async()=>{
  const fetcher=vi.fn();vi.stubGlobal("fetch",fetcher);const view=render(<DonationDetails donationId="invalid"/>);expect(screen.getByRole("alert")).toHaveTextContent("valid Donation UUID");expect(fetcher).not.toHaveBeenCalled();view.unmount();
  fetcher.mockResolvedValue(Response.json({title:"Donation not found",detail:"PRIVATE"},{status:404}));const errorView=render(<DonationDetails donationId={donationId}/>);expect(await screen.findByRole("alert")).toHaveTextContent("Donation not found");expect(screen.queryByText("PRIVATE")).toBeNull();errorView.unmount();
  fetcher.mockResolvedValue(Response.json({}));render(<DonationDetails donationId={donationId}/>);expect(await screen.findByText("No donation details available.")).toBeInTheDocument();
});
