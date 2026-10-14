import { CustomerDetails } from "../../../components/customers/CustomerDetails";
export default async function CustomerPage({ params, searchParams }: {
  params: Promise<{ customerId: string }>; searchParams: Promise<{ site_id?: string | string[] }>;
}) {
  const { customerId } = await params; const { site_id } = await searchParams;
  return <CustomerDetails customerId={customerId} siteId={typeof site_id === "string" ? site_id : ""} />;
}
