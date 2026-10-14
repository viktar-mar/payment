"use client";
import Link from "next/link";
import { useEffect, useMemo, useState, useSyncExternalStore, type FormEvent } from "react";
import type { components, operations } from "../../lib/api/schema";
import { api, errorTitle, queryParams } from "../../lib/api/client";
import { validUuid } from "./context";

type Query = NonNullable<operations["listAdminCustomers"]["parameters"]["query"]> & { site_id: string };
function boundedQuery(search: URLSearchParams): Query {
  const integer = (name: string, min: number, max: number, fallback: number) => {
    const value = search.get(name); return value !== null && /^\d+$/.test(value) ? Math.min(max, Math.max(min, Number(value))) : fallback;
  };
  const status = search.get("status");
  return { site_id: (search.get("site_id") ?? "").slice(0, 37),
    ...(status === "ACTIVE" || status === "ANONYMIZED" ? { status } : {}),
    customer_id: (search.get("customer_id") ?? "").slice(0, 37), masked_email: (search.get("masked_email") ?? "").slice(0, 254),
    offset: integer("offset", 0, 100000, 0), limit: integer("limit", 1, 50, 20) };
}
function subscribeUrl(listener: () => void) { window.addEventListener("popstate", listener); return () => window.removeEventListener("popstate", listener); }
const currentSearch = () => window.location.search;
const serverSearch = () => "";
export function CustomerList() {
  const search = useSyncExternalStore(subscribeUrl, currentSearch, serverSearch);
  const query = useMemo(() => boundedQuery(new URLSearchParams(search)), [search]);
  const [sites, setSites] = useState<components["schemas"]["SiteResponse"][]>(); const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController(); api<components["schemas"]["SiteResponse"][]>("admin/v1/sites", { signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) setSites(result); }).catch(failure => { if (!controller.signal.aborted) setError(errorTitle(failure)); });
    return () => controller.abort();
  }, []);
  function apply(next: Query) { const bounded = boundedQuery(queryParams(next)); window.history.replaceState(null, "", `${window.location.pathname}?${queryParams(bounded)}`); window.dispatchEvent(new PopStateEvent("popstate")); }
  const validSite = query && validUuid(query.site_id) && sites?.some(site => site.id === query.site_id);
  const invalidId = query?.customer_id && !validUuid(query.customer_id);
  return <div className="space-y-6">
    {error && <p role="alert">{error}</p>}
    {!sites && !error && <p role="status">Loading Sites…</p>}
    {query && <CustomerFilters key={`filters-${queryParams(query)}`} query={query} sites={sites ?? []} onApply={apply} />}
    {!validSite && <p>Select a valid Site to view customers.</p>}
    {invalidId && <p role="alert">Enter a valid Customer UUID.</p>}
    {query && validSite && !invalidId && <CustomerRows key={`rows-${queryParams(query)}`} query={query} onPage={apply} />}
  </div>;
}
function CustomerFilters({ query, sites, onApply }: { query: Query; sites: components["schemas"]["SiteResponse"][]; onApply: (query: Query) => void }) {
  const [status, setStatus] = useState(query.status ?? ""); const [customerId, setCustomerId] = useState(query.customer_id ?? ""); const [maskedEmail, setMaskedEmail] = useState(query.masked_email ?? "");
  const [limit, setLimit] = useState(String(query.limit ?? 20));
  function submit(event: FormEvent) { event.preventDefault(); const search = queryParams({ ...query, status, customer_id: customerId, masked_email: maskedEmail, limit, offset: 0 }); onApply(boundedQuery(search)); }
  return <form aria-label="Customer filters" className="surface filter-grid" onSubmit={submit}>
    <label>Site<select value={sites.some(site => site.id === query.site_id) ? query.site_id : ""} onChange={event => onApply({ site_id: event.target.value, limit: 20, offset: 0 })}><option value="">Select a Site</option>{sites.map(site => <option value={site.id} key={site.id}>{site.display_name}</option>)}</select></label>
    <label>Status<select value={status} onChange={event => setStatus(event.target.value as typeof status)}><option value="">All statuses</option><option>ACTIVE</option><option>ANONYMIZED</option></select></label>
    <label>Customer UUID<input value={customerId} maxLength={36} onChange={event => setCustomerId(event.target.value)} /></label>
    <label>Masked email<input value={maskedEmail} maxLength={254} onChange={event => setMaskedEmail(event.target.value)} /></label>
    <label>Page size<input type="number" min={1} max={50} value={limit} onChange={event => setLimit(event.target.value)} /></label>
    <button>Apply filters</button>
  </form>;
}
function CustomerRows({ query, onPage }: { query: Query; onPage: (query: Query) => void }) {
  const [page, setPage] = useState<components["schemas"]["CustomerAdminPage"]>(); const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController(); const { site_id, ...filters } = query;
    api<components["schemas"]["CustomerAdminPage"]>(`v1/admin/sites/${site_id}/customers`, { query: filters, signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) setPage(result); }).catch(failure => { if (!controller.signal.aborted) setError(errorTitle(failure)); });
    return () => controller.abort();
  }, [query]);
  const rows = page?.items ?? []; const offset = query.offset ?? 0; const limit = query.limit ?? 20;
  return <>
    {error && <p role="alert">{error}</p>}{!page && !error && <p role="status">Loading customers…</p>}
    {page && !rows.length && <p role="status">No customers found.</p>}
    {page && !!rows.length && <div className="surface payment-table-wrap"><table className="payment-table"><caption>Customers</caption><thead><tr>{["Customer", "Status", "Masked email", "Created"].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead><tbody>{rows.map((customer, index) => <tr key={customer.customer_id ?? index}>
      <td>{customer.customer_id && validUuid(customer.customer_id) ? <Link href={`/customers/${customer.customer_id}?site_id=${query.site_id}`}>{customer.customer_id}</Link> : "Unavailable"}</td><td>{customer.status}</td><td>{customer.masked_email ?? "—"}</td><td>{customer.created_at}</td>
    </tr>)}</tbody></table></div>}
    {page && <nav aria-label="Customer pagination"><p role="status">Offset {offset} · up to {limit} customers per page</p><button disabled={offset === 0} onClick={() => onPage({ ...query, offset: Math.max(0, offset - limit) })}>Previous page</button>{rows.length === limit && offset + limit <= 100000 && <button onClick={() => onPage({ ...query, offset: offset + limit })}>Next page</button>}</nav>}
  </>;
}
