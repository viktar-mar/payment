import Link from "next/link";
import { CampaignList } from "../../../../components/donations/campaigns/CampaignList";
export default async function CampaignsPage({params}:{params:Promise<{siteId:string}>}) {
  const {siteId}=await params;
  return <><Link href="/sites">← Sites</Link><h1>Donation campaigns</h1><CampaignList siteId={siteId} /></>;
}
