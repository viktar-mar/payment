import Link from "next/link";
import { SiteApiKeys } from "../../../../components/integration/SiteApiKeys";
import { CallbackConfiguration } from "../../../../components/integration/CallbackConfiguration";
import {CheckoutReturnUrls} from '../../../../components/integration/CheckoutReturnUrls';
export default async function IntegrationPage({ params }: { params: Promise<{ siteId: string }> }) { const { siteId } = await params; return <><Link href="/sites">← Sites</Link><h1>Site integration</h1><SiteApiKeys key={`keys-${siteId}`} siteId={siteId} /><CallbackConfiguration key={`callback-${siteId}`} siteId={siteId} /><CheckoutReturnUrls key={`return-${siteId}`} siteId={siteId}/></>; }
