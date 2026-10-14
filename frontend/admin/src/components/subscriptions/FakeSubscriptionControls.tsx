"use client";
import { useEffect, useRef, useState, type FormEvent } from "react";
import type { components } from "../../lib/api/schema";
import { ActionDialog } from "../payments/ActionDialog";
import { useIntentMutation } from "../payments/useIntentMutation";
import { isUtcInstant } from "../../lib/utcInstant";
import { copy, type Locale } from "./SubscriptionFilters";
import { useSubscriptionApi } from "./SubscriptionAuthority";
type S = components["schemas"];
export function FakeSubscriptionControls({ subscription, locale, onChanged }: { subscription: S["SubscriptionResponse"]; locale: Locale; onChanged: () => void }) {
  const mutation = useIntentMutation(useSubscriptionApi()); const [open, setOpen] = useState(false); const [action, setAction] = useState<S["SubscriptionFakeRequest"]["action"]>("SCENARIO"); const [scenario, setScenario] = useState<NonNullable<S["SubscriptionFakeRequest"]["scenario"]>>("SUCCESS"); const [until, setUntil] = useState(""); const [error, setError] = useState(""); const [message, setMessage] = useState(""); const t = (en: string, pt: string) => copy(locale, en, pt);
  const conflict = mutation.errorStatus === 409; const conflictNotified = useRef(false);
  useEffect(() => { if (!conflict) conflictNotified.current = false; else if (open && !conflictNotified.current) { conflictNotified.current = true; onChanged(); } }, [open, conflict, onChanged]);
  function changed() { mutation.changed(); setError(""); }
  function submit(event: FormEvent) { event.preventDefault(); if (action === "ADVANCE" && !isUtcInstant(until)) { setError(t("Enter a UTC instant ending in Z.", "Introduza uma data UTC terminada em Z.")); return; }
    const body: S["SubscriptionFakeRequest"] = { action, ...(action === "SCENARIO" ? { scenario } : {}), ...(action === "ADVANCE" ? { until } : {}) };
    void mutation.run<S["SubscriptionFakeResponse"]>(`v1/admin/subscriptions/${subscription.subscription_id}/fake-controls`, { method: "POST", query: { site_id: subscription.site_id }, body }, () => { setMessage(t("Local simulation accepted; refresh to inspect provider facts.", "Simulação local aceite; atualize para consultar os factos do fornecedor.")); setOpen(false); onChanged(); });
  }
  return <section className="surface"><h2>{t("Local-only Fake Provider simulation", "Simulação do fornecedor Fake apenas local")}</h2><p>{t("Simulation is not the customer payment driver. Non-local and non-Fake accounts are rejected by the server.", "A simulação não é o fluxo de pagamento do cliente. O servidor rejeita ambientes não locais e contas não Fake.")}</p><button onClick={() => { mutation.changed(); setOpen(true); }}>{t("Simulate subscription", "Simular assinatura")}</button>{conflict ? <p role="status">{t("Review refreshed subscription state before another simulation.", "Reveja o estado atualizado da assinatura antes de outra simulação.")}</p> : message && <p role="status">{message}</p>}{open && !conflict && <ActionDialog title={t("Confirm local subscription simulation", "Confirmar simulação local da assinatura")} pending={mutation.pending} onClose={() => setOpen(false)} fallbackSelector="[data-subscription-heading]"><form onSubmit={submit}>
    <label>{t("Simulation action", "Ação de simulação")}<select value={action} disabled={mutation.pending} onChange={event => { setAction(event.target.value as typeof action); changed(); }}>{["SCENARIO", "COMPLETE_ACTION", "ADVANCE"].map(value => <option key={value}>{value}</option>)}</select></label>
    {action === "SCENARIO" && <label>{t("Scenario", "Cenário")}<select value={scenario} disabled={mutation.pending} onChange={event => { setScenario(event.target.value as typeof scenario); changed(); }}>{["SUCCESS", "PROCESSING", "REQUIRES_ACTION", "DECLINE", "UNKNOWN", "ZERO_PRORATION", "UNSUPPORTED_SETTLEMENT"].map(value => <option key={value}>{value}</option>)}</select></label>}{action === "ADVANCE" && <label>{t("Until (UTC)", "Até (UTC)")}<input value={until} disabled={mutation.pending} onChange={event => { setUntil(event.target.value); changed(); }} /></label>}
    {(error || mutation.error) && <p role="alert">{error || mutation.error}</p>}<button disabled={mutation.pending}>{mutation.retry ? t("Retry simulation", "Repetir simulação") : t("Confirm simulation", "Confirmar simulação")}</button>
  </form></ActionDialog>}</section>;
}
