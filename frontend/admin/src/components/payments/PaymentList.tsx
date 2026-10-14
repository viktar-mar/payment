"use client";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { api, errorTitle, queryParams } from "../../lib/api/client";
import type { components } from "../../lib/api/schema";
import { boundedQuery, PaymentFilters, queryError, type PaymentQuery } from "./PaymentFilters";
import { StatusBadge } from "./StatusBadge";
export function PaymentList() {
  const [query, setQuery] = useState<PaymentQuery>();
  const [page, setPage] = useState<components["schemas"]["Page"]>();
  const [error, setError] = useState("");
  const transition = useCallback((next: PaymentQuery) => { setPage(undefined); setError(""); setQuery(next); }, []);
  useEffect(() => {
    const read = () => transition(boundedQuery(new URLSearchParams(window.location.search)));
    read(); window.addEventListener("popstate", read); return () => window.removeEventListener("popstate", read);
  }, [transition]);
  useEffect(() => {
    if (!query) return;
    const controller = new AbortController(); const invalid = queryError(query);
    if (!invalid) api<components["schemas"]["Page"]>("v1/admin/payments", { query, signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) { setPage(result); setError(""); } })
      .catch(failure => { if (!controller.signal.aborted) setError(errorTitle(failure)); });
    return () => controller.abort();
  }, [query]);
  function apply(next: PaymentQuery) { transition(next); window.history.replaceState(null, "", `${window.location.pathname}?${queryParams(next)}`); }
  const rows = page?.items ?? []; const current = page?.page ?? query?.page ?? 0; const size = page?.size ?? query?.size ?? 20;
  const max = Math.min(100000, Math.max(0, Math.ceil((page?.total ?? 0) / Math.max(1, size)) - 1));
  return <div className="space-y-6">
    {query && <PaymentFilters key={queryParams(query).toString()} query={query} onApply={apply} />}
    {error && <p role="alert">{error}</p>}
    {queryError(query ?? {}) && <p role="alert">{queryError(query ?? {})}</p>}
    {!page && !error && !queryError(query ?? {}) && <p role="status">Loading payments…</p>}
    {page && rows.length === 0 && <p>No payments found.</p>}
    {page && rows.length > 0 && <div className="surface payment-table-wrap"><table className="payment-table"><caption>Payments</caption><thead><tr>{["Payment", "Site", "Amount", "Status", "Provider", "Created"].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead><tbody>{rows.map((payment, index) => <tr key={payment.payment_id ?? index}>
      <td data-label="Payment">{payment.payment_id && <Link href={`/payments/${payment.payment_id}`}>{payment.payment_id}</Link>}</td>
      <td data-label="Site">{payment.site_id}</td><td data-label="Amount">{payment.amount_minor} minor units {payment.currency}</td>
      <td data-label="Status"><StatusBadge status={payment.status} /></td><td data-label="Provider">{payment.provider}</td><td data-label="Created">{payment.created_at}</td>
    </tr>)}</tbody></table></div>}
    {page && <nav aria-label="Payment pagination"><p role="status">Page {current + 1} · {page.total} payments</p><button disabled={current <= 0} onClick={() => apply({ ...query, page: Math.min(max, current - 1) })}>Previous page</button>{" "}<button disabled={current >= max} onClick={() => apply({ ...query, page: current + 1 })}>Next page</button></nav>}
  </div>;
}
