"use client";
import Link from "next/link";
import type { components } from "../../lib/api/schema";
import { copy, utc, type Locale } from "./SubscriptionFilters";
export function SubscriptionTimeline({ events, locale }: { events: components["schemas"]["SubscriptionEventResponse"][]; locale: Locale }) {
  const t = (en: string, pt: string) => copy(locale, en, pt);
  return <section className="surface" aria-label={t("Subscription timeline", "Histórico da assinatura")}><h2>{t("Lifecycle", "Ciclo de vida")}</h2>{!events.length && <p>{t("No events.", "Sem eventos.")}</p>}<ol>{events.map(event => <li key={event.event_id}>
    <h3>{t("Sequence", "Sequência")} {event.sequence} · {event.type}</h3><p>{event.source} · {t("Revision", "Revisão")} {event.revision}</p><p>{t("Occurred", "Ocorrido")}: {utc(event.occurred_at)} · {t("Received", "Recebido")}: {utc(event.received_at)}</p>
    {event.type === 'RECONCILIATION_REQUIRED' && <p>{t("Reconciliation required. Billing and access remain based on verified server records.", "Reconciliação necessária. A faturação e o acesso continuam a basear-se nos registos verificados do servidor.")}</p>}
    {event.operation_id && <p>{t("Operation", "Operação")}: {event.operation_id}</p>}{event.billing_record_id && <p>{t("Billing record", "Registo de faturação")}: {event.billing_record_id}</p>}{event.payment_id && <Link href={`/payments/${event.payment_id}`}>{t("Open payment and refunds", "Abrir pagamento e reembolsos")}</Link>}
  </li>)}</ol></section>;
}
