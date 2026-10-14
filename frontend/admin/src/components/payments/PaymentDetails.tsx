"use client";
import Link from "next/link";
import { Fragment, useEffect, useRef, useState, type ReactNode } from "react";
import type { components } from "../../lib/api/schema";
import { api, errorTitle } from "../../lib/api/client";
import { PaymentTimeline } from "./PaymentTimeline";
import { PaymentActions } from "./PaymentActions";
import { StatusBadge } from "./StatusBadge";
import { copy, Language, utc, type Locale } from "../subscriptions/SubscriptionFilters";
type Schemas = components["schemas"];
function Fields({ fields }: { fields: readonly (readonly [string, ReactNode])[] }) { return <dl className="details">{fields.map(([name, value]) => <Fragment key={name}><dt>{name}</dt><dd>{value ?? "—"}</dd></Fragment>)}</dl>; }
export function PaymentDetails({ paymentId }: { paymentId: string }) {
  const [details, setDetails] = useState<Schemas["Details"]>(); const [error, setError] = useState(""); const [revision, setRevision] = useState(0); const generation = useRef(0);
  const [locale, setLocale] = useState<Locale>("en");
  useEffect(() => {
    const controller = new AbortController(); const version = ++generation.current;
    api<Schemas["Details"]>(`v1/admin/payments/${paymentId}`, { signal: controller.signal })
      .then(result => { if (!controller.signal.aborted && generation.current === version) { setDetails(result); setError(""); } })
      .catch(failure => { if (!controller.signal.aborted) setError(errorTitle(failure)); });
    return () => controller.abort();
  }, [paymentId, revision]);
  function refresh(result?: Schemas["Details"]) { if (result) { ++generation.current; setDetails(result); setError(""); } else setRevision(value => value + 1); }
  const p = details?.payment;
  return <div className="space-y-6">
    {error && <p role="alert">{error}</p>}{!details && !error && <p role="status">Loading payment…</p>}
    {details && !p && <p>No payment details available.</p>}
    {details && p && <>
      <h1 tabIndex={-1} data-payment-heading>Payment {p.payment_id}</h1>
      <section className="surface" aria-label="Payment summary"><h2>Payment summary</h2><StatusBadge status={p.status} />
        <Fields fields={[["Payment ID", p.payment_id], ["Site", p.site_id], ["Payment account", p.payment_account_id], ["Source", p.source], ["Price ID", p.price_id], ["Price key", p.price_key], ["Price version", p.price_version], ["External order", p.external_order_id], ["Amount", `${p.amount_minor} minor units ${p.currency}`], ["Tax mode", p.tax_mode], ["Tax amount in minor units", p.tax_amount_minor], ["Provider", p.provider], ["Refundable amount in minor units", p.refundable_amount_minor], ["Confirmed refunded amount in minor units", p.refunded_amount_minor], ["Created", p.created_at], ["Updated", p.updated_at]]} />
      </section>
      {(p.customer_id || p.donation_id || p.campaign_id) && <section className="surface" aria-label="Donation and Customer context"><h2>Donation and Customer context</h2><Fields fields={[
        ["Customer", p.customer_id ? <Link href={`/customers/${p.customer_id}?site_id=${p.site_id}`}>{p.customer_id}</Link> : "Anonymous"],
        ["Donation", p.donation_id ? <Link prefetch={false} href={`/donations/${p.donation_id}`}>{p.donation_id}</Link> : null],
        ["Campaign", p.campaign_id ? <Link href={`/sites/${p.site_id}/donation-campaigns/${p.campaign_id}`}>{p.campaign_id}</Link> : null],
        ["Campaign key", p.campaign_key], ["Campaign version", p.campaign_version],
      ]} /></section>}
      <PaymentActions details={details} onRefresh={refresh} />
      {p.subscription_id && <section className="surface" aria-label={copy(locale,"Subscription billing context","Contexto de faturação da assinatura")}><h2>{copy(locale,"Subscription billing context","Contexto de faturação da assinatura")}</h2><Language locale={locale} onChange={setLocale} /><Fields fields={[
        [copy(locale,"Subscription","Assinatura"),<Link key="subscription" href={`/subscriptions/${p.subscription_id}?site_id=${p.site_id}`}>{p.subscription_id}</Link>], [copy(locale,"Billing record","Registo de faturação"),p.billing_record_id], [copy(locale,"Billing kind","Tipo de faturação"),p.subscription_billing_kind], [copy(locale,"Period start","Início do período"),utc(p.subscription_period_start)], [copy(locale,"Period end","Fim do período"),utc(p.subscription_period_end)],
      ]} /><p>{copy(locale,"Refunds do not cancel this subscription.","Os reembolsos não cancelam esta assinatura.")}</p></section>}
      <section className="surface" aria-label="Attempts"><h2>Attempts</h2>{!details.attempts?.length && <p>No attempts yet.</p>}<ul className="space-y-4">{details.attempts?.map((a, i) => <li key={a.id ?? i}><h3>Attempt {a.attempt_number}</h3><StatusBadge status={a.status} /><Fields fields={[["Attempt ID", a.id], ["Provider intent", a.provider_intent_reference], ["Method family", a.payment_method_family], ["Result code", a.result_code], ["Created", a.created_at], ["Updated", a.updated_at]]} /></li>)}</ul></section>
      <PaymentTimeline events={details.events ?? []} />
      <section className="surface" aria-label="Refunds"><h2>Refunds</h2>{!details.refunds?.length && <p>No refunds yet.</p>}<ul className="space-y-4">{details.refunds?.map((r, i) => <li key={r.id ?? i}><h3>Refund {r.id}</h3><StatusBadge status={r.status} /><Fields fields={[["Refund ID", r.id], ["Amount", `${r.amount_minor} minor units ${r.currency}`], ["Provider refund", r.provider_refund_reference], ["Reason", r.reason_code], ["Created", r.created_at], ["Updated", r.updated_at]]} /></li>)}</ul></section>
      <section className="surface" aria-label="Outbox events"><h2>Outbox events</h2>{!details.outbox_events?.length && <p>No outbox events yet.</p>}<ul className="space-y-4">{details.outbox_events?.map((o, i) => <li key={o.id ?? i}><h3>Outbox {o.id}</h3><StatusBadge status={o.status} /><Fields fields={[["Event ID", o.id], ["Callback type", o.callback_type], ["Attempt count", o.attempt_count], ["Next attempt", o.next_attempt_at], ["Created", o.created_at], ["Updated", o.updated_at]]} /></li>)}</ul></section>
      <section className="surface" aria-label="Delivery attempts"><h2>Delivery attempts</h2>{!details.deliveries?.length && <p>No deliveries yet.</p>}<ul className="space-y-4">{details.deliveries?.map((d, i) => <li key={d.id ?? i}><h3>Delivery {d.attempt_number} for outbox {d.outbox_event_id}</h3><StatusBadge status={d.status} /><Fields fields={[["Delivery ID", d.id], ["Outbox event ID", d.outbox_event_id], ["Delivery mode", d.delivery_mode], ["HTTP status", d.http_status], ["Error category", d.error_category], ["Started", d.started_at], ["Finished", d.finished_at]]} /></li>)}</ul></section>
    </>}
  </div>;
}
