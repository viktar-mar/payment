"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { api, errorTitle } from "../../../lib/api/client";
import { validUuid } from "../../customers/context";
import { CampaignForm } from "./CampaignForm";
import { CampaignLifecycleActions } from "./CampaignLifecycleActions";
import type { Campaign } from "./types";

export function CampaignDetails({siteId,campaignId}:{siteId:string;campaignId:string}) {
  const [revision,setRevision]=useState(0);
  if(!validUuid(siteId)||!validUuid(campaignId))return <p role="alert">Select a valid Site and campaign.</p>;
  return <CampaignProjection key={`${siteId}:${campaignId}:${revision}`} siteId={siteId} campaignId={campaignId} onRefresh={()=>setRevision(value=>value+1)} />;
}
function CampaignProjection({siteId,campaignId,onRefresh}:{siteId:string;campaignId:string;onRefresh:()=>void}) {
  const [campaign,setCampaign]=useState<Campaign>();const [error,setError]=useState("");
  useEffect(()=>{
    const controller=new AbortController();api<Campaign>(`v1/admin/sites/${siteId}/donation-campaigns/${campaignId}`,{signal:controller.signal})
      .then(result=>{if(!controller.signal.aborted)setCampaign(result);}).catch(failure=>{if(!controller.signal.aborted)setError(errorTitle(failure));});
    return ()=>controller.abort();
  },[siteId,campaignId]);
  if(error)return <p role="alert">{error}</p>;
  if(!campaign)return <p role="status">Loading campaign…</p>;
  return <div className="space-y-6"><section className="surface"><h2 data-campaign-heading tabIndex={-1}>{campaign.display_name}</h2>
    <p>{campaign.purpose_description}</p><p>{campaign.status}</p><p>Version {campaign.version}</p></section>
    <Link href={`/donations?site_id=${siteId}&campaign_id=${campaignId}`}>Donations for this campaign</Link>
    <CampaignForm key={`form-${campaign.version}`} siteId={siteId} campaign={campaign} onSaved={setCampaign} onRefresh={onRefresh} />
    <CampaignLifecycleActions key={`lifecycle-${campaign.version}`} siteId={siteId} campaign={campaign} onChanged={setCampaign} onRefresh={onRefresh} />
  </div>;
}
