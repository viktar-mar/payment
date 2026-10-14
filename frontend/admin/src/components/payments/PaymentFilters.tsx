"use client";
import { useId, useState, type FormEvent } from "react";
import type { operations, components } from "../../lib/api/schema";
import { isUtcInstant } from "../../lib/utcInstant";
type GeneratedQuery = NonNullable<operations["listAdminPayments"]["parameters"]["query"]>;
// OpenAPI describes query integers as strings. Keep bounded numeric UI state;
// URLSearchParams serializes these values to the accepted HTTP query contract.
export type PaymentQuery = Omit<GeneratedQuery, "page" | "size"> & { page?: number; size?: number };
const statuses: NonNullable<components["schemas"]["Projection"]["status"]>[] = ["CREATED", "REQUIRES_PAYMENT_METHOD", "REQUIRES_ACTION", "PROCESSING", "SUCCEEDED", "FAILED", "CANCELLED", "PARTIALLY_REFUNDED", "REFUNDED"];
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function boundedQuery(search: URLSearchParams): PaymentQuery {
  const result: PaymentQuery = {};
  for (const name of ["site_id", "external_order_id", "payment_id", "customer_id", "campaign_id", "donation_id", "created_from", "created_to"] as const) { const value = search.get(name); if (value) result[name] = value; }
  const status = search.get("status"); if (statuses.includes(status as typeof statuses[number])) result.status = status as typeof statuses[number];
  const provider = search.get("provider"); if (provider === "FAKE" || provider === "STRIPE") result.provider = provider;
  const integer = (name: string, min: number, max: number, fallback: number) => {
    const value = search.get(name); if (value === null || !/^\d+$/.test(value)) return fallback;
    return Math.min(max, Math.max(min, Number(value)));
  };
  result.page = integer("page", 0, 100000, 0); result.size = integer("size", 1, 100, 20);
  result.sort = search.get("sort") === "CREATED_ASC" ? "CREATED_ASC" : "CREATED_DESC";
  return result;
}
export function queryError(query: PaymentQuery) {
  if ([query.site_id, query.payment_id, query.customer_id, query.campaign_id, query.donation_id].some(value => value && !uuid.test(value))) return "Enter valid UUID filters";
  for (const value of [query.created_from, query.created_to]) if (value && !isUtcInstant(value)) return "Enter valid UTC instants ending in Z";
  if (query.created_from && query.created_to && Date.parse(query.created_from) >= Date.parse(query.created_to)) return "Created from must be before created to";
  return "";
}
export function PaymentFilters({ query, onApply }: { query: PaymentQuery; onApply: (query: PaymentQuery) => void }) {
  const errorId = useId(); const [error, setError] = useState("");
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget); const params = new URLSearchParams();
    for (const [key, value] of data.entries()) if (typeof value === "string") params.set(key, value.trim());
    params.set("page", "0"); const next = boundedQuery(params); const problem = queryError(next); setError(problem); if (!problem) onApply(next);
  }
  return <form className="surface filter-grid" onSubmit={submit} aria-label="Payment filters">
    {([ ["site_id", "Site ID"], ["external_order_id", "External order ID"], ["payment_id", "Payment ID"], ["customer_id", "Customer ID"], ["campaign_id", "Campaign ID"], ["donation_id", "Donation ID"], ["created_from", "Created from (UTC)"], ["created_to", "Created to (UTC)"] ] as const).map(([name, label]) => <label key={name}>{label}<input name={name} defaultValue={query[name] ?? ""} maxLength={name === "external_order_id" ? 255 : 40} aria-describedby={error ? errorId : undefined} /></label>)}
    <label>Status<select name="status" defaultValue={query.status ?? ""}><option value="">All statuses</option>{statuses.map(status => <option key={status}>{status}</option>)}</select></label>
    <label>Provider<select name="provider" defaultValue={query.provider ?? ""}><option value="">All providers</option><option>FAKE</option><option>STRIPE</option></select></label>
    <label>Page size<input name="size" type="number" min={1} max={100} defaultValue={query.size} /></label>
    <label>Sort<select name="sort" defaultValue={query.sort}><option value="CREATED_DESC">Newest first</option><option value="CREATED_ASC">Oldest first</option></select></label>
    {error && <p id={errorId} role="alert">{error}</p>}<button>Apply filters</button>
  </form>;
}
