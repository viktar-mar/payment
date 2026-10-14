"use client";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { api, errorTitle } from "../../../lib/api/client";
import { validUuid } from "../../customers/context";
import { CampaignForm } from "./CampaignForm";
import type { CampaignPage } from "./types";

export function CampaignList({ siteId }: { siteId: string }) {
  const [offset,setOffset]=useState(0);const [revision,setRevision]=useState(0);const [creating,setCreating]=useState(false);
  const createTrigger=useRef<HTMLButtonElement>(null);
  if(!validUuid(siteId)) return <p role="alert">Select a valid Site to manage campaigns.</p>;
  return <div className="space-y-6">
    <button ref={createTrigger} onClick={()=>setCreating(value=>!value)}>{creating?"Close creation form":"Create campaign"}</button>
    {creating && <CampaignForm siteId={siteId} onSaved={()=>{setCreating(false);setOffset(0);setRevision(value=>value+1);createTrigger.current?.focus();}} onRefresh={()=>{setCreating(false);setRevision(value=>value+1);createTrigger.current?.focus();}} />}
    <CampaignRows key={`${siteId}:${offset}:${revision}`} siteId={siteId} offset={offset} onPage={setOffset} />
  </div>;
}
function CampaignRows({siteId,offset,onPage}:{siteId:string;offset:number;onPage:(offset:number)=>void}) {
  const [page,setPage]=useState<CampaignPage>();const [error,setError]=useState("");const limit=20;
  useEffect(()=>{
    const controller=new AbortController();
    api<CampaignPage>(`v1/admin/sites/${siteId}/donation-campaigns`,{query:{offset,limit},signal:controller.signal})
      .then(result=>{if(!controller.signal.aborted)setPage(result);}).catch(failure=>{if(!controller.signal.aborted)setError(errorTitle(failure));});
    return ()=>controller.abort();
  },[siteId,offset]);
  const rows=page?.items??[];
  return <>
    {error&&<p role="alert">{error}</p>}{!page&&!error&&<p role="status">Loading campaigns…</p>}
    {page&&!rows.length&&<p role="status">No campaigns found.</p>}
    {!!rows.length&&<div className="surface payment-table-wrap"><table className="payment-table"><caption>Donation campaigns</caption>
      <thead><tr>{["Campaign","Key","Status","Currency"].map(label=><th scope="col" key={label}>{label}</th>)}</tr></thead>
      <tbody>{rows.map((campaign,index)=><tr key={campaign.id??index}><td>{campaign.id&&validUuid(campaign.id)?<Link prefetch={false} href={`/sites/${siteId}/donation-campaigns/${campaign.id}`}>{campaign.display_name}</Link>:campaign.display_name}</td><td>{campaign.key}</td><td>{campaign.status}</td><td>{campaign.currency}</td></tr>)}</tbody>
    </table></div>}
    {page&&<nav aria-label="Campaign pagination"><p role="status">Offset {offset} · up to {limit} campaigns per page</p>
      <button disabled={offset===0} onClick={()=>onPage(Math.max(0,offset-limit))}>Previous page</button>
      {rows.length===limit&&offset+limit<=100000&&<button onClick={()=>onPage(offset+limit)}>Next page</button>}
    </nav>}
  </>;
}
