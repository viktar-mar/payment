import { SubscriptionDetails } from "../../../components/subscriptions/SubscriptionDetails";
export default async function SubscriptionPage({ params }: { params: Promise<{ subscriptionId: string }> }) { const { subscriptionId } = await params; return <SubscriptionDetails key={subscriptionId} subscriptionId={subscriptionId} />; }
