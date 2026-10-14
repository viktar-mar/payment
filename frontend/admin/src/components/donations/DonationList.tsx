"use client";
import Link from "next/link";
import {useCallback,useEffect,useState} from "react";
import {api,errorTitle,queryParams} from "../../lib/api/client";
import type {components} from "../../lib/api/schema";
import {StatusBadge} from "../payments/StatusBadge";
import {DonationFilters,boundedDonationQuery,donationQueryError,type DonationQuery} from "./DonationFilters";
export function DonationList(){
  const [query,setQuery]=useState<DonationQuery>();const [page,setPage]=useState<components["schemas"]["DonationAdminPage"]>();const [error,setError]=useState("");
  const transition=useCallback((next:DonationQuery)=>{setPage(undefined);setError("");setQuery(next);},[]);
  useEffect(()=>{const read=()=>transition(boundedDonationQuery(new URLSearchParams(window.location.search)));read();window.addEventListener("popstate",read);return()=>window.removeEventListener("popstate",read);},[transition]);
  useEffect(()=>{
    if(!query)return;const controller=new AbortController();
    if(!donationQueryError(query))api<components["schemas"]["DonationAdminPage"]>("v1/admin/donations",{query,signal:controller.signal})
      .then(result=>{if(!controller.signal.aborted){setPage(result);setError("");}}).catch(failure=>{if(!controller.signal.aborted)setError(errorTitle(failure));});
    return()=>controller.abort();
  },[query]);
  function apply(next:DonationQuery){transition(next);window.history.replaceState(null,"",`${window.location.pathname}?${queryParams(next)}`);}
  const invalid=donationQueryError(query??{}),rows=page?.items??[],current=page?.page??query?.page??0,size=page?.size??query?.size??20;
  const max=Math.min(100000,Math.max(0,Math.ceil((page?.total??0)/Math.max(1,size))-1));
  return <div className="space-y-6">
    {query&&<DonationFilters key={queryParams(query).toString()} query={query} onApply={apply}/>}
    {error&&<p role="alert">{error}</p>}{invalid&&<p role="alert">{invalid}</p>}
    {!page&&!error&&!invalid&&<p role="status">Loading donations…</p>}
    {page&&!rows.length&&<p>No donations found.</p>}
    {page&&rows.length>0&&<div className="surface payment-table-wrap"><table className="payment-table"><caption>Donations</caption><thead><tr>{["Donation","Site","Campaign","Customer","Payment","Amount","Payment status","Message state","Created","Updated"].map(label=><th scope="col" key={label}>{label}</th>)}</tr></thead><tbody>
      {rows.map((d,index)=><tr key={d.donation_id??index}>
        <td data-label="Donation">{d.donation_id&&<Link prefetch={false} href={`/donations/${d.donation_id}`}>{d.donation_id}</Link>}</td>
        <td data-label="Site">{d.site_id}</td><td data-label="Campaign"><Link href={`/sites/${d.site_id}/donation-campaigns/${d.campaign_id}`}>{d.campaign_key} (version {d.campaign_version})</Link></td>
        <td data-label="Customer">{d.customer_id?<Link href={`/customers/${d.customer_id}?site_id=${d.site_id}`}>{d.customer_id}</Link>:"Anonymous"}</td>
        <td data-label="Payment">{d.payment_id&&<Link href={`/payments/${d.payment_id}`}>{d.payment_id}</Link>}</td><td data-label="Amount">{d.amount_minor} minor units {d.currency}</td>
        <td data-label="Payment status"><StatusBadge status={d.status}/></td><td data-label="Message state">{d.message_state}</td><td data-label="Created">{d.created_at}</td><td data-label="Updated">{d.updated_at}</td>
      </tr>)}
    </tbody></table></div>}
    {page&&<nav aria-label="Donation pagination"><p role="status">Page {current+1} · {page.total} donations</p><button disabled={current<=0} onClick={()=>apply({...query,page:Math.min(max,current-1)})}>Previous page</button>{" "}<button disabled={current>=max} onClick={()=>apply({...query,page:current+1})}>Next page</button></nav>}
  </div>;
}
