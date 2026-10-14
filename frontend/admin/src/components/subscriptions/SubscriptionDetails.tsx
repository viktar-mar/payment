"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import type { components } from "../../lib/api/schema";
import { errorTitle } from "../../lib/api/client";
import { SubscriptionAuthority, useSubscriptionApi } from "./SubscriptionAuthority";
import { SubscriptionActions } from "./SubscriptionActions";
import { SubscriptionTimeline } from "./SubscriptionTimeline";
import { FakeSubscriptionControls } from "./FakeSubscriptionControls";
import { copy, Language, utc, uuid, type Locale } from "./SubscriptionFilters";
type S = components["schemas"];
function Price({ price }: { price: S["SubscriptionPriceResponse"] | null }) { return price ? <span>{price.price_key} · {price.price_id} · v{price.version} · {price.amount_minor} {price.currency} · {price.interval}</span> : <span>—</span>; }
function Detail({ subscriptionId, siteId, locale }: { subscriptionId: string; siteId: string; locale: Locale }) {
  const api = useSubscriptionApi();
  const [subscription, setSubscription] = useState<S["SubscriptionResponse"]>(); const [operation, setOperation] = useState<S["SubscriptionOperationResponse"]>(); const [bills, setBills] = useState<S["SubscriptionBillingPage"]>(); const [events, setEvents] = useState<S["SubscriptionEventPage"]>(); const [billCursor, setBillCursor] = useState<string>(); const [eventCursor, setEventCursor] = useState<string>(); const [revision, setRevision] = useState(0); const [error, setError] = useState("");
  const t = (en: string, pt: string) => copy(locale, en, pt); const base = `v1/admin/subscriptions/${subscriptionId}`;
  useEffect(() => { const controller = new AbortController();
    api<S["SubscriptionResponse"]>(base, { query: { site_id: siteId }, signal: controller.signal }).then(async value => {
      if (controller.signal.aborted) return; setSubscription(value); setOperation(undefined);
      if (value.pending_operation) { const safe = await api<S["SubscriptionOperationResponse"]>(`${base}/operations/${value.pending_operation.operation_id}`, { query: { site_id: siteId }, signal: controller.signal }); if (!controller.signal.aborted && safe.subscription_id === subscriptionId && safe.operation_id === value.pending_operation.operation_id) setOperation(safe); }
    }).catch(failure => { if (!controller.signal.aborted) setError(errorTitle(failure)); }); return () => controller.abort();
  }, [api, base, siteId, subscriptionId, revision]);
  useEffect(() => { const controller = new AbortController(); api<S["SubscriptionBillingPage"]>(`${base}/billing-records`, { query: { site_id: siteId, limit: 20, cursor: billCursor }, signal: controller.signal }).then(value => { if (!controller.signal.aborted) setBills(value); }).catch(failure => { if (!controller.signal.aborted) setError(errorTitle(failure)); }); return () => controller.abort(); }, [api, base, siteId, billCursor, revision]);
  useEffect(() => { const controller = new AbortController(); api<S["SubscriptionEventPage"]>(`${base}/events`, { query: { site_id: siteId, limit: 20, cursor: eventCursor }, signal: controller.signal }).then(value => { if (!controller.signal.aborted) setEvents(value); }).catch(failure => { if (!controller.signal.aborted) setError(errorTitle(failure)); }); return () => controller.abort(); }, [api, base, siteId, eventCursor, revision]);
  function refresh() { setError(""); setRevision(value => value + 1); }
  return <>{error && <p role="alert">{error}</p>}{!subscription && !error && <p role="status">{t("Loading subscription…", "A carregar assinatura…")}</p>}{subscription && <>
    <h1 tabIndex={-1} data-subscription-heading>{t("Subscription", "Assinatura")} {subscription.subscription_id}</h1>
    <section className="surface" aria-label={t("Subscription summary", "Resumo da assinatura")}><h2>{t("Subscription summary", "Resumo da assinatura")}</h2><dl className="details">
      <dt>Site</dt><dd>{subscription.site_id}</dd><dt>{t("Customer", "Cliente")}</dt><dd><Link href={`/customers/${subscription.customer_id}?site_id=${siteId}`}>{subscription.customer_id}</Link></dd><dt>{t("Product", "Produto")}</dt><dd>{subscription.product_id}</dd>
      <dt>{t("State", "Estado")}</dt><dd>{subscription.state}</dd><dt>{t("Billing status", "Estado de faturação")}</dt><dd>{subscription.billing_status}</dd><dt>{t("Revision", "Revisão")}</dt><dd>{subscription.revision}</dd>
      <dt>{t("Billing Price", "Preço de faturação")}</dt><dd><Price price={subscription.billing_price} /></dd><dt>{t("Entitled Price", "Preço com acesso")}</dt><dd><Price price={subscription.entitled_price} /></dd>
      <dt>{t("Period start / end", "Início / fim do período")}</dt><dd>{utc(subscription.period?.start)} / {utc(subscription.period?.end)}</dd><dt>{t("Paid through", "Pago até")}</dt><dd>{utc(subscription.paid_through)}</dd><dt>{t("Trial start / end", "Início / fim do teste")}</dt><dd>{utc(subscription.trial?.start)} / {utc(subscription.trial?.end)}</dd>
      <dt>{t("Cancellation deadline", "Prazo de cancelamento")}</dt><dd>{utc(subscription.cancellation?.effective_at)}</dd><dt>{t("Grace deadline", "Prazo de tolerância")}</dt><dd>{utc(subscription.debt?.grace_deadline)}</dd><dt>{t("Access", "Acesso")}</dt><dd>{subscription.access.allowed ? t("Allowed", "Permitido") : t("Not allowed", "Não permitido")} · {subscription.access.reason} · {utc(subscription.access.valid_until)}</dd>
      <dt>{t("Next amount", "Próximo valor")}</dt><dd>{subscription.next_amount ? `${subscription.next_amount.amount_minor} ${subscription.next_amount.currency} · ${subscription.next_amount.tax_final ? t("final tax", "imposto final") : t("tax not final", "imposto não final")}` : "—"}</dd>
    </dl>{subscription.pending_change && <p>{t("Pending plan change", "Alteração de plano pendente")}: <Price price={subscription.pending_change.target_price} /> · {subscription.pending_change.state} · {utc(subscription.pending_change.effective_at)}</p>}{subscription.debt && <p>{t("Debt", "Dívida")}: {subscription.debt.billing_record_id} · {subscription.debt.amount_due_minor} {subscription.debt.currency}</p>}</section>
    <SubscriptionActions subscription={subscription} operation={operation} onChanged={refresh} locale={locale} />
    <section className="surface" aria-label={t("Billing records", "Registos de faturação")}><h2>{t("Billing records", "Registos de faturação")}</h2>{bills?.items.map(bill => <article key={bill.billing_record_id}><h3>{bill.billing_record_id} · {bill.kind}</h3><p><Price price={bill.price} /> · {bill.settlement}</p><p>{utc(bill.period.start)} / {utc(bill.period.end)} · {bill.amount_due_minor} {bill.currency}</p>{bill.previous_price && <p>{t("Previous Price", "Preço anterior")}: <Price price={bill.previous_price} /></p>}{bill.payment_ids.map(id => <p key={id}><Link href={`/payments/${id}`}>{t("Open payment and refunds", "Abrir pagamento e reembolsos")}</Link></p>)}</article>)}{!bills?.items.length && <p>{t("No billing records.", "Sem registos de faturação.")}</p>}<button disabled={!billCursor} onClick={() => setBillCursor(undefined)}>{t("First billing records", "Primeiros registos de faturação")}</button><button disabled={!bills?.next_cursor} onClick={() => { setBillCursor(bills?.next_cursor ?? undefined); setBills(undefined); }}>{t("More billing records", "Mais registos de faturação")}</button></section>
    <SubscriptionTimeline events={events?.items ?? []} locale={locale} /><button disabled={!eventCursor} onClick={() => setEventCursor(undefined)}>{t("First events", "Primeiros eventos")}</button><button disabled={!events?.next_cursor} onClick={() => { setEventCursor(events?.next_cursor ?? undefined); setEvents(undefined); }}>{t("More events", "Mais eventos")}</button>
    <FakeSubscriptionControls subscription={subscription} locale={locale} onChanged={refresh} />
  </>}</>;
}
export function SubscriptionDetails({ subscriptionId }: { subscriptionId: string }) {
  const [siteId, setSiteId] = useState(""); const [locale, setLocale] = useState<Locale>("en");
  useEffect(() => { const read = () => setSiteId(new URLSearchParams(window.location.search).get("site_id") ?? ""); read(); window.addEventListener("popstate", read); return () => window.removeEventListener("popstate", read); }, [subscriptionId]);
  return <div className="space-y-6"><Language locale={locale} onChange={setLocale} />{uuid(siteId) && uuid(subscriptionId) ? <SubscriptionAuthority key={`${siteId}:${subscriptionId}`} locale={locale}><Detail subscriptionId={subscriptionId} siteId={siteId} locale={locale} /></SubscriptionAuthority> : <p>{copy(locale, "Select a Site using the subscription list before opening this subscription.", "Selecione um Site na lista antes de abrir esta assinatura.")}</p>}</div>;
}
