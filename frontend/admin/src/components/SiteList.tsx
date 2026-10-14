import Link from "next/link";
import type { components } from "../lib/api/schema";
export function SiteList({
  sites,
}: {
  sites: components["schemas"]["SiteResponse"][];
}) {
  return (
    <ul className="space-y-4">
      {sites.map((site) => (
        <li className="surface" key={site.id}>
          <Link href={`/sites/${site.id}/catalog`}>{site.display_name}</Link>
          <p><Link href={`/sites/${site.id}/integration`} aria-label={`Integration settings for ${site.display_name}`}>Integration settings</Link></p>
          <p><Link href={`/sites/${site.id}/authentication`} aria-label={`Authentication settings for ${site.display_name}`}>Authentication settings</Link></p>
          <p><Link href={`/customers?site_id=${site.id}`} aria-label={`Customers for ${site.display_name}`}>Customers</Link></p>
          <p><Link prefetch={false} href={`/sites/${site.id}/donation-campaigns`} aria-label={`Donation campaigns for ${site.display_name}`}>Donation campaigns</Link></p>
          <p><Link href={`/donations?site_id=${site.id}`} aria-label={`Donations for ${site.display_name}`}>Donations</Link></p>
          <dl className="details">
            <dt>Key</dt>
            <dd>{site.key}</dd>
            <dt>Status</dt>
            <dd>{site.status}</dd>
            <dt>Payment account</dt>
            <dd>{site.payment_account_id}</dd>
            <dt>Allowed origins</dt>
            <dd>{site.allowed_origins?.join(", ")}</dd>
            <dt>Authentication</dt>
            <dd>{site.authentication_modes?.join(", ")}</dd>
            <dt>Payment policy</dt>
            <dd>
              {site.payment_method_policy?.mode}{" "}
              {site.payment_method_policy?.methods.join(", ")}
            </dd>
          </dl>
        </li>
      ))}
    </ul>
  );
}
