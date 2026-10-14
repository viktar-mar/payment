"use client";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import type { components } from "../../lib/api/schema";
import { api, errorTitle, queryParams } from "../../lib/api/client";
import { copy, Language, readQuery, SubscriptionFilters, utc, validQuery, type Locale, type SubscriptionQuery } from "./SubscriptionFilters";
export function SubscriptionList(): React.JSX.Element {
  const [locale, setLocale] = useState<Locale>("en"); const [query, setQuery] = useState<SubscriptionQuery>(); const [page, setPage] = useState<components["schemas"]["SubscriptionPage"]>(); const [error, setError] = useState(""); const [cursors, setCursors] = useState<(string | undefined)[]>([]);
  const t = (en: string, pt: string) => copy(locale, en, pt);
  const transition = useCallback((next: SubscriptionQuery) => { setPage(undefined); setError(""); setQuery(next); }, []);
  useEffect(() => { const read = () => { setCursors([]); transition(readQuery(new URLSearchParams(window.location.search))); }; read(); window.addEventListener("popstate", read); return () => window.removeEventListener("popstate", read); }, [transition]);
  useEffect(() => { if (!query || !validQuery(query)) return; const controller = new AbortController(); api<components["schemas"]["SubscriptionPage"]>("v1/admin/subscriptions", { query, signal: controller.signal }).then(result => { if (!controller.signal.aborted) setPage(result); }).catch(failure => { if (!controller.signal.aborted) setError(errorTitle(failure)); }); return () => controller.abort(); }, [query]);
  function apply(next: SubscriptionQuery) { transition(next); window.history.replaceState(null, "", `${window.location.pathname}?${queryParams(next)}`); }
  return <div className="space-y-6"><h1>{t("Subscriptions", "Assinaturas")}</h1><Language locale={locale} onChange={setLocale} />
    {query && validQuery(query) && <Link href={`/sites/${query.site_id}/subscriptions`}>{t("Manage Site subscription policy", "Gerir política de assinaturas do Site")}</Link>}
    {query && <SubscriptionFilters key={queryParams(query).toString()} query={query} locale={locale} onApply={next => { setCursors([]); apply(next); }} />}
    {query && !validQuery(query) && <p role="alert">{t("Enter a valid Site UUID and optional Customer/Product UUIDs.", "Introduza um UUID válido de Site e UUIDs opcionais de cliente/produto.")}</p>}
    {error && <p role="alert">{error}</p>}{query && validQuery(query) && !page && !error && <p role="status">{t("Loading subscriptions…", "A carregar assinaturas…")}</p>}
    {page?.items.length === 0 && <p>{t("No subscriptions found.", "Nenhuma assinatura encontrada.")}</p>}
    {!!page?.items.length && <div className="surface payment-table-wrap"><table className="payment-table"><caption>{t("Subscriptions", "Assinaturas")}</caption><thead><tr>{[t("Subscription", "Assinatura"), "Site", t("Customer", "Cliente"), t("Product", "Produto"), t("State", "Estado"), t("Billing price", "Preço faturado"), t("Entitled price", "Preço com acesso"), "UTC"].map(label => <th key={label} scope="col">{label}</th>)}</tr></thead><tbody>{page.items.map(item => <tr key={item.subscription_id}><td><Link href={`/subscriptions/${item.subscription_id}?site_id=${item.site_id}`}>{item.subscription_id}</Link></td><td>{item.site_id}</td><td>{item.customer_id}</td><td>{item.product_id}</td><td>{item.state}</td><td>{item.billing_price.price_key} · v{item.billing_price.version}</td><td>{item.entitled_price ? `${item.entitled_price.price_key} · v${item.entitled_price.version}` : "—"}</td><td>{utc(item.created_at)}</td></tr>)}</tbody></table></div>}
    {page && query && <nav aria-label={t("Subscription pagination", "Paginação de assinaturas")}><button disabled={!cursors.length} onClick={() => { const previous = [...cursors]; const cursor = previous.pop(); setCursors(previous); apply({ ...query, cursor }); }}>{t("Previous page", "Página anterior")}</button>{" "}<button disabled={!page.next_cursor} onClick={() => { setCursors(current => [...current, query.cursor]); apply({ ...query, cursor: page.next_cursor ?? undefined }); }}>{t("Next page", "Página seguinte")}</button></nav>}
  </div>;
}
