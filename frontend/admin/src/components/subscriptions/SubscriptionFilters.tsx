"use client";
import { useState, type FormEvent } from "react";
import type { operations } from "../../lib/api/schema";
export type Locale = "en" | "pt";
export function copy(locale: Locale, en: string, pt: string) { return locale === "pt" ? pt : en; }
export function Language({ locale, onChange }: { locale: Locale; onChange: (locale: Locale) => void }) {
  return <label>{copy(locale, "Language", "Idioma")}<select value={locale} onChange={event => onChange(event.target.value === "pt" ? "pt" : "en")}><option value="en">English</option><option value="pt">Português</option></select></label>;
}
export function utc(value: string | null | undefined) {
  if (!value || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(value)) return "—";
  const date = new Date(value); return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 19) === value.slice(0, 19) ? `${date.toISOString()} (UTC)` : "—";
}
export const uuid = (value: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
export type SubscriptionQuery = operations["list_2"]["parameters"]["query"];
export const states: NonNullable<SubscriptionQuery["state"]>[] = ["INCOMPLETE", "TRIALING", "ACTIVE", "PAST_DUE", "PAUSED", "UNPAID", "CANCELLED", "CANCEL_AT_PERIOD_END"];
export function readQuery(params: URLSearchParams): SubscriptionQuery {
  const state = params.get("state");
  return { site_id: params.get("site_id") ?? "", ...(params.get("customer_id") ? { customer_id: params.get("customer_id")! } : {}), ...(params.get("product_id") ? { product_id: params.get("product_id")! } : {}), ...(states.some(value => value === state) ? { state: state as SubscriptionQuery["state"] } : {}), limit: 20, ...(params.get("cursor") ? { cursor: params.get("cursor")!.slice(0, 4097) } : {}) };
}
export function validQuery(query: SubscriptionQuery) { return uuid(query.site_id) && (!query.customer_id || uuid(query.customer_id)) && (!query.product_id || uuid(query.product_id)) && (!query.cursor || query.cursor.length <= 4096); }
export function SubscriptionFilters({ query, locale = "en", onApply }: { query: SubscriptionQuery; locale?: Locale; onApply: (query: SubscriptionQuery) => void }) {
  const [site, setSite] = useState(query.site_id); const [customer, setCustomer] = useState(query.customer_id ?? ""); const [product, setProduct] = useState(query.product_id ?? ""); const [state, setState] = useState(query.state ?? "");
  const t = (en: string, pt: string) => copy(locale, en, pt);
  function submit(event: FormEvent) { event.preventDefault(); onApply({ site_id: site, ...(customer ? { customer_id: customer } : {}), ...(product ? { product_id: product } : {}), ...(state ? { state: state as SubscriptionQuery["state"] } : {}), limit: 20 }); }
  return <form className="form-stack surface" onSubmit={submit} noValidate>
    <label>{t("Site UUID", "UUID do Site")}<input value={site} maxLength={36} onChange={event => setSite(event.target.value)} /></label>
    <label>{t("Customer UUID", "UUID do cliente")}<input value={customer} maxLength={36} onChange={event => setCustomer(event.target.value)} /></label>
    <label>{t("Product UUID", "UUID do produto")}<input value={product} maxLength={36} onChange={event => setProduct(event.target.value)} /></label>
    <label>{t("State", "Estado")}<select value={state} onChange={event => setState(event.target.value)}><option value="">{t("All states", "Todos os estados")}</option>{states.map(value => <option key={value}>{value}</option>)}</select></label>
    <button>{t("Apply filters", "Aplicar filtros")}</button>
  </form>;
}
