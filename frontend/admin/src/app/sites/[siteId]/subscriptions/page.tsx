import Link from "next/link";
import { SubscriptionPolicyForm } from "../../../../components/subscriptions/SubscriptionPolicyForm";
export default async function SiteSubscriptionsPage({ params }: { params: Promise<{ siteId: string }> }) { const { siteId } = await params; return <><h1>Subscriptions / Assinaturas</h1><Link href={`/subscriptions?site_id=${siteId}`}>Manage subscriptions / Gerir assinaturas</Link><Link href={`/sites/${siteId}/catalog`}>Plan transitions and Prices / Transições de plano e preços</Link><SubscriptionPolicyForm key={siteId} siteId={siteId} /></>; }
