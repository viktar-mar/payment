import Link from "next/link";
import { CatalogView } from "../../../../components/CatalogView";
export default async function CatalogPage({
  params,
}: {
  params: Promise<{ siteId: string }>;
}) {
  const { siteId } = await params;
  return (
    <>
      <Link href="/sites">← Sites</Link>
      <h1>Catalog</h1>
      <CatalogView siteId={siteId} />
    </>
  );
}
