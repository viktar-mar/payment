import {act, fireEvent, render, screen, waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {useState} from "react";
import {afterEach, expect, it, vi} from "vitest";
import {RedactMessageDialog} from "./RedactMessageDialog";
import {donationId} from "./test-fixtures";
afterEach(()=>{vi.unstubAllGlobals();document.cookie="XSRF-TOKEN=; Max-Age=0; Path=/";});
function Harness(){const [open,setOpen]=useState(false);const [done,setDone]=useState(false);return <><button onClick={()=>setOpen(true)}>Redact message</button>{done&&<p role="status">REDACTED</p>}{open&&<RedactMessageDialog donationId={donationId} onClose={()=>setOpen(false)} onRedacted={()=>{setDone(true);setOpen(false);}}/>}</>;}
it("requires the exact UUID, warns irreversibility, traps keyboard focus and cancels without a request",async()=>{
  const fetcher=vi.fn();vi.stubGlobal("fetch",fetcher);render(<Harness/>);const user=userEvent.setup();const trigger=screen.getByRole("button",{name:"Redact message"});await user.click(trigger);
  expect(screen.getByRole("button",{name:"Cancel"})).toHaveFocus();expect(screen.getByRole("dialog")).toHaveTextContent("cannot be undone");expect(screen.getByRole("dialog")).toHaveTextContent("does not refund");
  const input=screen.getByLabelText("Donation UUID confirmation");fireEvent.change(input,{target:{value:donationId+" "}});expect(screen.getByRole("button",{name:"Confirm redaction"})).toBeDisabled();
  fireEvent.change(input,{target:{value:donationId}});expect(screen.getByRole("button",{name:"Confirm redaction"})).toBeEnabled();await user.tab({shift:true});expect(screen.getByRole("button",{name:"Confirm redaction"})).toHaveFocus();await user.tab();expect(screen.getByRole("button",{name:"Cancel"})).toHaveFocus();await user.keyboard("{Escape}");expect(screen.queryByRole("dialog")).toBeNull();expect(trigger).toHaveFocus();expect(fetcher).not.toHaveBeenCalled();
});
it("sends one CSRF-protected key per intent, retains it on ambiguous retry and blocks pending double-submit",async()=>{
  document.cookie="XSRF-TOKEN=redact-csrf; Path=/";const requests:{url:string;init:RequestInit}[]=[];let resolve:(r:Response)=>void=()=>{};
  vi.stubGlobal("fetch",async(url:string,init:RequestInit)=>{requests.push({url,init});if(requests.length===1)throw new TypeError("network");return new Promise<Response>(done=>{resolve=done;});});render(<Harness/>);const user=userEvent.setup();await user.click(screen.getByRole("button",{name:"Redact message"}));fireEvent.change(screen.getByLabelText("Donation UUID confirmation"),{target:{value:donationId}});await user.click(screen.getByRole("button",{name:"Confirm redaction"}));
  expect(await screen.findByRole("alert")).toHaveTextContent("Unable to connect");await user.click(screen.getByRole("button",{name:"Retry redaction"}));fireEvent.submit(screen.getByRole("button",{name:"Retry redaction"}).closest("form")!);await user.keyboard("{Escape}");expect(screen.getByRole("dialog")).toBeInTheDocument();expect(screen.getByRole("button",{name:"Cancel"})).toBeDisabled();expect(requests).toHaveLength(2);
  const headers=requests.map(r=>new Headers(r.init.headers));expect(headers[0].get("Idempotency-Key")).toMatch(/^[a-f0-9-]{36}$/);expect(headers[1].get("Idempotency-Key")).toBe(headers[0].get("Idempotency-Key"));expect([...headers[1].keys()].filter(k=>k==="idempotency-key")).toHaveLength(1);expect(headers[1].get("X-XSRF-TOKEN")).toBe("redact-csrf");expect(requests[1].url).toBe(`/api/backend/v1/admin/donations/${donationId}/message-redactions`);expect(JSON.parse(requests[1].init.body as string)).toEqual({confirmation:donationId});
  await act(async()=>resolve(Response.json({donation_id:donationId,message_state:"REDACTED",changed:true,replayed:false})));expect(await screen.findByRole("status")).toHaveTextContent("REDACTED");
});
it("changes the key for an edited/reopened intent and announces safe definitive errors",async()=>{
  const keys:(string|null)[]=[];vi.stubGlobal("fetch",async(_url:string,init:RequestInit)=>{keys.push(new Headers(init.headers).get("Idempotency-Key"));return Response.json({title:"Resource conflict",detail:"PRIVATE"},{status:409});});render(<Harness/>);const user=userEvent.setup();const trigger=screen.getByRole("button",{name:"Redact message"});
  async function submit(){fireEvent.change(screen.getByLabelText("Donation UUID confirmation"),{target:{value:donationId}});await user.click(screen.getByRole("button",{name:"Confirm redaction"}));expect(await screen.findByRole("alert")).toHaveTextContent("Resource conflict");}
  await user.click(trigger);await submit();fireEvent.change(screen.getByLabelText("Donation UUID confirmation"),{target:{value:""}});await submit();await user.click(screen.getByRole("button",{name:"Cancel"}));await user.click(trigger);await submit();await waitFor(()=>expect(keys).toHaveLength(3));expect(new Set(keys).size).toBe(3);expect(screen.queryByText("PRIVATE")).toBeNull();
});
