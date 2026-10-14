import Link from "next/link";
import { AuthenticationSettings } from "../../../../components/customers/authentication/AuthenticationSettings";
export default async function AuthenticationPage({ params }: { params: Promise<{ siteId: string }> }) {
  const { siteId } = await params;
  return <><Link href="/sites">← Sites</Link><AuthenticationSettings siteId={siteId} /></>;
}
