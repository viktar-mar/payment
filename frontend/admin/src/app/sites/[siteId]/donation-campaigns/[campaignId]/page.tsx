import Link from "next/link";
import { CampaignDetails } from "../../../../../components/donations/campaigns/CampaignDetails";
export default async function CampaignPage({params}:{params:Promise<{siteId:string;campaignId:string}>}) {
  const {siteId,campaignId}=await params;
  return <><Link prefetch={false} href={`/sites/${siteId}/donation-campaigns`}>← Donation campaigns</Link><h1>Campaign details</h1><CampaignDetails siteId={siteId} campaignId={campaignId} /></>;
}
