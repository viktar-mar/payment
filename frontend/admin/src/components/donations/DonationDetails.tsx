"use client";
import Link from "next/link";
import {Fragment,useEffect,useRef,useState,type ReactNode} from "react";
import {api,errorTitle} from "../../lib/api/client";
import type {operations} from "../../lib/api/schema";
import {validUuid} from "../customers/context";
import {StatusBadge} from "../payments/StatusBadge";
import {RedactMessageDialog} from "./RedactMessageDialog";
type GeneratedDetail=operations["getAdminDonation"]["responses"][200]["content"]["*/*"];
// The runtime returns null for ABSENT/REDACTED. Keep generated OpenAPI untouched.
type Detail=Omit<GeneratedDetail,"message"> & {message?:string|null};
type Redaction=operations["redactDonationMessage"]["responses"][200]["content"]["*/*"];
export function DonationDetails({donationId}:{donationId:string}){
  if(!validUuid(donationId))return <p role="alert">Provide a valid Donation UUID.</p>;
  return <DonationProjection key={donationId} donationId={donationId}/>;
}
function DonationProjection({donationId}:{donationId:string}){
  const [detail,setDetail]=useState<Detail>();const retained=useRef<Detail|undefined>(undefined);const [error,setError]=useState("");const [dialog,setDialog]=useState(false);
  useEffect(()=>{
    const controller=new AbortController();api<Detail>(`v1/admin/donations/${donationId}`,{signal:controller.signal})
      .then(result=>{if(controller.signal.aborted){result.message=null;return;}retained.current=result;setDetail(result);})
      .catch(failure=>{if(!controller.signal.aborted)setError(errorTitle(failure));});
    return()=>{controller.abort();if(retained.current)retained.current.message=null;retained.current=undefined;};
  },[donationId]);
  function redacted(result:Redaction){
    // Scrub the original DTO in place before releasing it: prior React state and
    // settled response references must not keep an independent plaintext copy.
    const current=retained.current;
    if(current){current.message=null;if(current.donation){current.donation.message_state=result.message_state;current.donation.has_message=false;}retained.current=undefined;setDetail({donation:current.donation,message:null});}
    setDialog(false);
  }
  const d=detail?.donation;
  const fields:readonly(readonly[string,ReactNode])[]=d?[["Site",d.site_id],["Campaign",<Link key="campaign" href={`/sites/${d.site_id}/donation-campaigns/${d.campaign_id}`}>{d.campaign_key} (version {d.campaign_version})</Link>],["Campaign ID",d.campaign_id],["Customer",d.customer_id?<Link key="customer" href={`/customers/${d.customer_id}?site_id=${d.site_id}`}>{d.customer_id}</Link>:"Anonymous"],["Payment ID",d.payment_id],["Amount",`${d.amount_minor} minor units ${d.currency}`],["Created",d.created_at],["Updated",d.updated_at]]:[];
  return <div className="space-y-6">
    <h1 tabIndex={-1} data-donation-heading>Donation {donationId}</h1>
    {error&&<p role="alert">{error}</p>}{!detail&&!error&&<p role="status">Loading donation…</p>}{detail&&!d&&<p>No donation details available.</p>}
    {d&&<><section className="surface" aria-label="Donation summary"><h2>Donation summary</h2><StatusBadge status={d.status}/><dl className="details">{fields.map(([name,value])=><Fragment key={name}><dt>{name}</dt><dd>{value??"—"}</dd></Fragment>)}</dl></section>
      <section className="surface" aria-label="Message"><h2>Message</h2><p>{d.message_state}</p>{d.message_state==="PRESENT"&&<><p aria-label="Donor message" style={{whiteSpace:"pre-wrap"}}>{detail?.message}</p><button onClick={()=>setDialog(true)}>Redact message</button></>}</section>
      <Link href={`/payments/${d.payment_id}`}>Payment details, refunds and reconciliation</Link>
    </>}
    {dialog&&<RedactMessageDialog donationId={donationId} onClose={()=>setDialog(false)} onRedacted={redacted}/>}
  </div>;
}
