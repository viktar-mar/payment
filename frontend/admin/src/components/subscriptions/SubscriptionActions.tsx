"use client";
import { useEffect, useRef, useState, type FormEvent } from "react";
import type { components } from "../../lib/api/schema";
import { ApiError, errorTitle } from "../../lib/api/client";
import { useSubscriptionApi } from "./SubscriptionAuthority";
import { ActionDialog } from "../payments/ActionDialog";
import { useIntentMutation } from "../payments/useIntentMutation";
import { copy, utc, type Locale } from "./SubscriptionFilters";
type S = components["schemas"];
type Action = S["SubscriptionResponse"]["permitted_actions"][number] | "RECONCILE";
const actions: readonly [Action, string, string][] = [["CHANGE_PLAN", "Change plan", "Alterar plano"], ["CLEAR_CHANGE", "Clear scheduled change", "Remover alteração agendada"], ["CANCEL_PERIOD_END", "Cancel at period end", "Cancelar no fim do período"], ["CANCEL_IMMEDIATE", "Cancel immediately", "Cancelar imediatamente"], ["WITHDRAW_CANCELLATION", "Withdraw cancellation", "Retirar cancelamento"], ["SETUP_METHOD", "Set up payment method", "Configurar método de pagamento"], ["RECOVER_INVOICE", "Recover invoice", "Recuperar fatura"], ["RESUME", "Resume subscription", "Retomar assinatura"], ["OPEN_PORTAL", "Open Portal", "Abrir Portal"]];
function Confirmation({ subscription, action, locale, onClose, onChanged, onResult }: { subscription: S["SubscriptionResponse"]; action: Action; locale: Locale; onClose: () => void; onChanged: () => void; onResult: (message: string, portal?: S["SubscriptionPortalResponse"]) => void }) {
  const api = useSubscriptionApi();
  const t = (en: string, pt: string) => copy(locale, en, pt); const mutation = useIntentMutation(api); const preview = useRef<AbortController | undefined>(undefined); const version = useRef(0);
  const [confirmation, setConfirmation] = useState(""); const [consent, setConsent] = useState(false); const [offers, setOffers] = useState<S["SubscriptionOffers"]>(); const [target, setTarget] = useState(""); const [timing, setTiming] = useState<S["SubscriptionPreviewRequest"]["timing"]>("NEXT_PERIOD"); const [quote, setQuote] = useState<S["SubscriptionQuoteResponse"]>(); const [previewing, setPreviewing] = useState(false); const [validation, setValidation] = useState(""); const [policy, setPolicy] = useState<S["SubscriptionPolicyResponse"]>(); const [returnUrl, setReturnUrl] = useState("");
  const base = `v1/admin/subscriptions/${subscription.subscription_id}`; const query = { site_id: subscription.site_id };
  const selected = offers?.items.find(item => item.price.price_id === target); const isCancel = action === "CANCEL_IMMEDIATE" || action === "CANCEL_PERIOD_END";
  const label = isCancel ? t("cancellation", "cancelamento") : action === "CHANGE_PLAN" ? t("plan change", "alteração de plano") : action === "RECONCILE" ? t("reconciliation", "reconciliação") : copy(locale, actions.find(item => item[0] === action)?.[1] ?? action, actions.find(item => item[0] === action)?.[2] ?? action);
  useEffect(() => { const controller = new AbortController();
    if (action === "CHANGE_PLAN") api<S["SubscriptionOffers"]>(`v1/admin/sites/${subscription.site_id}/customers/${subscription.customer_id}/products/${subscription.product_id}/subscription-offers`, { signal: controller.signal }).then(value => { if (!controller.signal.aborted) setOffers(value); }).catch(failure => { if (!controller.signal.aborted) setValidation(errorTitle(failure)); });
    if (action === "OPEN_PORTAL") api<S["SubscriptionPolicyResponse"]>(`v1/admin/sites/${subscription.site_id}/subscription-policy`, { signal: controller.signal }).then(value => { if (!controller.signal.aborted) setPolicy(value); }).catch(failure => { if (!controller.signal.aborted) setValidation(errorTitle(failure)); });
    return () => { controller.abort(); preview.current?.abort(); };
  }, [api, action, subscription.site_id, subscription.customer_id, subscription.product_id]);
  function changed() { mutation.changed(); setValidation(""); setQuote(undefined); version.current++; preview.current?.abort(); setPreviewing(false); }
  async function getQuote() { if (!selected || !selected.allowed_timings.includes(timing)) return; preview.current?.abort(); const controller = new AbortController(); preview.current = controller; const current = ++version.current; setPreviewing(true); setValidation(""); setQuote(undefined);
    const body: S["SubscriptionPreviewRequest"] = { target_price_id: target, timing };
    try { const value = await api<S["SubscriptionQuoteResponse"]>(`${base}/change-preview`, { method: "POST", body, query, signal: controller.signal }); if (!controller.signal.aborted && version.current === current && value.subscription_id === subscription.subscription_id && value.target_price.price_id === target && value.timing === timing && value.subscription_revision === subscription.revision) setQuote(value); }
    catch (failure) { if (!controller.signal.aborted) { if (failure instanceof ApiError && failure.status === 409) { onResult(t("Review the refreshed subscription and confirm a new intent.", "Reveja a assinatura atualizada e confirme uma nova intenção.")); onChanged(); onClose(); } else setValidation(errorTitle(failure)); } } finally { if (!controller.signal.aborted && version.current === current) setPreviewing(false); }
  }
  async function submit(event: FormEvent) { event.preventDefault(); if (mutation.pending || previewing) return;
    let path: string; let body: S["SubscriptionCancelRequest"] | S["SubscriptionChangeRequest"] | S["SubscriptionConsentRequest"] | S["SubscriptionRecoveryRequest"] | S["SubscriptionPortalRequest"] | Record<string, never>;
    switch (action) {
      // This clock read is inside the submit event, never render; expiry must be rechecked at confirmation.
      // eslint-disable-next-line react-hooks/purity
      case "CHANGE_PLAN": if (!quote || quote.target_price.price_id !== target || quote.timing !== timing || Date.parse(quote.expires_at) <= Date.now()) { setQuote(undefined); setValidation(t("Preview the change again before confirming.", "Pré-visualize novamente a alteração antes de confirmar.")); return; } path = "changes"; body = { quote_id: quote.quote_id }; break;
      case "CANCEL_IMMEDIATE": if (confirmation !== subscription.subscription_id) return; path = "cancellation"; body = { mode: "IMMEDIATE", confirmation }; break;
      case "CANCEL_PERIOD_END": path = "cancellation"; body = { mode: "PERIOD_END" }; break;
      case "CLEAR_CHANGE": path = "changes/clear"; body = {}; break;
      case "WITHDRAW_CANCELLATION": path = "cancellation/withdraw"; body = {}; break;
      case "SETUP_METHOD": case "RESUME": if (!consent) return; path = action === "RESUME" ? "resumptions" : "method-setups"; body = { recurring_consent: consent }; break;
      case "RECOVER_INVOICE": if (!subscription.debt) return; path = "recoveries"; body = { billing_record_id: subscription.debt.billing_record_id }; break;
      case "OPEN_PORTAL": if (!policy?.portal_return_urls.includes(returnUrl)) return; path = "portal-sessions"; body = { return_url: returnUrl }; break;
      default: path = "reconcile"; body = {};
    }
    if (action === "OPEN_PORTAL") {
      await mutation.run<S["SubscriptionPortalResponse"]>(`${base}/${path}`, { method: "POST", body, query }, result => { onResult(result.simulated ? t("Portal session simulated locally.", "Sessão do Portal simulada localmente.") : t("Portal session created.", "Sessão do Portal criada."), result); onChanged(); onClose(); });
    } else await mutation.run<S["SubscriptionMutationResponse"]>(`${base}/${path}`, { method: "POST", body, query }, result => { onResult(result.status === "UNKNOWN" ? t("Result not confirmed", "Resultado não confirmado") : result.status === "REQUIRES_ACTION" ? t("Customer action required", "É necessária uma ação do cliente") : t("Command accepted; refresh to confirm the current state.", "Comando aceite; atualize para confirmar o estado atual.")); onChanged(); onClose(); });
  }
  useEffect(() => { if (mutation.errorStatus === 409) { onResult(copy(locale, "Review the refreshed subscription and confirm a new intent.", "Reveja a assinatura atualizada e confirme uma nova intenção.")); onChanged(); onClose(); } }, [mutation.errorStatus, onResult, onChanged, onClose, locale]); // A conflict never replays a command.
  return <ActionDialog title={`${t("Confirm", "Confirmar")} ${label}`} pending={mutation.pending} onClose={onClose} fallbackSelector="[data-subscription-heading]"><form className="form-stack" onSubmit={event => { void submit(event); }} noValidate>
    {isCancel && <p>{action === "CANCEL_IMMEDIATE" ? t("This requests immediate termination. Access ends only after provider confirmation.", "Isto pede o fim imediato. O acesso termina apenas após confirmação do fornecedor.") : t("Keep access until the confirmed period end.", "Mantenha o acesso até ao fim confirmado do período.")}</p>}
    {action === "CANCEL_IMMEDIATE" && <label>{t("Subscription UUID confirmation", "Confirmação do UUID da assinatura")}<input value={confirmation} maxLength={36} disabled={mutation.pending} onChange={event => { setConfirmation(event.target.value); changed(); }} /></label>}
    {action === "CHANGE_PLAN" && <><label>{t("Target price", "Preço de destino")}<select value={target} disabled={mutation.pending} onChange={event => { setTarget(event.target.value); changed(); const item = offers?.items.find(item => item.price.price_id === event.target.value); setTiming(item?.allowed_timings[0] ?? "NEXT_PERIOD"); }}><option value="">{t("Choose an offered price", "Escolha um preço disponível")}</option>{offers?.items.filter(item => item.price.product_id === subscription.product_id && item.price.currency === subscription.billing_price.currency && item.price.price_id !== subscription.billing_price.price_id).map(item => <option key={item.price.price_id} value={item.price.price_id}>{item.price.price_key} · v{item.price.version} · {item.price.price_id} · {item.direction}</option>)}</select></label>
      <label>{t("Timing", "Momento")}<select value={timing} disabled={mutation.pending} onChange={event => { setTiming(event.target.value as typeof timing); changed(); }}>{(selected?.allowed_timings ?? ["NEXT_PERIOD"]).map(value => <option key={value}>{value}</option>)}</select></label><button type="button" disabled={!selected || mutation.pending || previewing} onClick={getQuote}>{t("Preview change", "Pré-visualizar alteração")}</button>
      {quote && <div><p>{t("Due now", "Devido agora")}: {quote.due_now_minor} {t("minor units", "unidades menores")} {quote.currency}</p><p>{t("Next amount", "Próximo valor")}: {quote.next_amount_minor} {t("minor units", "unidades menores")} {quote.currency}</p><p>{t("Effective", "Em vigor")}: {utc(quote.effective_at)}</p><p>{t("Quote expires", "A proposta expira")}: {utc(quote.expires_at)}</p><p>{quote.tax_final ? t("Tax final", "Imposto final") : t("Tax estimated", "Imposto estimado")}</p></div>}</>}
    {(action === "SETUP_METHOD" || action === "RESUME") && <label className="check"><input type="checkbox" checked={consent} disabled={mutation.pending} onChange={event => { setConsent(event.target.checked); changed(); }} />{t("I confirm recurring payment consent", "Confirmo o consentimento para pagamentos recorrentes")}</label>}
    {action === "RECOVER_INVOICE" && <p>{t("Recover billing record", "Recuperar registo de faturação")}: {subscription.debt?.billing_record_id}</p>}
    {action === "OPEN_PORTAL" && <label>{t("Allowed return URL", "URL de retorno permitido")}<select value={returnUrl} disabled={mutation.pending} onChange={event => { setReturnUrl(event.target.value); changed(); }}><option value="">{t("Choose a return URL", "Escolha um URL de retorno")}</option>{policy?.portal_return_urls.map(url => <option key={url}>{url}</option>)}</select></label>}
    {(validation || mutation.error) && <p role="alert">{validation || mutation.error}</p>}{previewing && <p role="status">{t("Loading quote…", "A carregar proposta…")}</p>}
    {(action !== "CHANGE_PLAN" || quote) && <button disabled={mutation.pending || (action === "CANCEL_IMMEDIATE" && confirmation !== subscription.subscription_id) || ((action === "SETUP_METHOD" || action === "RESUME") && !consent) || (action === "OPEN_PORTAL" && !returnUrl)}>{mutation.retry ? `${t("Retry", "Repetir")} ${label}` : `${t("Confirm", "Confirmar")} ${label}`}</button>}
  </form></ActionDialog>;
}
export function SubscriptionActions({ subscription, operation, onChanged, locale = "en" }: { subscription: S["SubscriptionResponse"]; operation?: S["SubscriptionOperationResponse"]; onChanged: () => void; locale?: Locale }) {
  const [intent, setIntent] = useState<{ action: Action; revision: number }>(); const [result, setResult] = useState(""); const [refreshFrom, setRefreshFrom] = useState<S["SubscriptionResponse"]>(); const t = (en: string, pt: string) => copy(locale, en, pt);
  // Navigation is deliberately ephemeral: never diagnostic, persisted, or part of a safe operation read.
  const [portal, setPortal] = useState<S["SubscriptionPortalResponse"]>();
  useEffect(() => {
    if (!portal) return;
    const remaining = Date.parse(portal.expires_at) - Date.now();
    const timer = setTimeout(() => setPortal(undefined), Number.isFinite(remaining) ? Math.max(0, Math.min(remaining, 2147483647)) : 0);
    return () => clearTimeout(timer);
  }, [portal]);
  let portalHref: string | undefined;
  if (portal && Number.isFinite(Date.parse(portal.expires_at))) { try { const url = new URL(portal.navigation_url); if (["http:", "https:"].includes(url.protocol) && !url.username && !url.password) portalHref = url.href; } catch { /* Invalid provider navigation is never rendered. */ } }
  const awaitingRefresh = refreshFrom === subscription;
  const unresolved = !!subscription.pending_operation || !!operation && !["SUCCEEDED", "FAILED"].includes(operation.status); const unknown = subscription.pending_operation?.status === "UNKNOWN" || operation?.status === "UNKNOWN";
  const permitted = (action: Action) => !awaitingRefresh && (action === "RECONCILE" || !unresolved && subscription.permitted_actions.some(value => value === action));
  return <section className="surface space-y-4" aria-label={t("Subscription actions", "Ações da assinatura")}><h2>{t("Actions", "Ações")}</h2>{unknown ? <p role="status">{t("Result not confirmed", "Resultado não confirmado")}</p> : unresolved ? <p role="status">{t("Operation pending", "Operação pendente")}</p> : result && <p role="status">{result}</p>}
    {operation?.action_available && <p>{t("Customer action required. Use the customer payment flow or local simulation controls.", "É necessária uma ação do cliente. Utilize o fluxo do cliente ou os controlos de simulação local.")}</p>}
    <div className="flex flex-wrap gap-3">{actions.map(([action, en, pt]) => <button key={action} disabled={!permitted(action)} onClick={() => { setResult(""); setPortal(undefined); setIntent({ action, revision: subscription.revision }); }}>{t(en, pt)}</button>)}<button onClick={onChanged}>{t("Refresh status", "Atualizar estado")}</button><button disabled={!permitted("RECONCILE")} onClick={() => { setPortal(undefined); setIntent({ action: "RECONCILE", revision: subscription.revision }); }}>{t("Reconcile provider result", "Reconciliar resultado do fornecedor")}</button></div>
    {portalHref && <p><a href={portalHref} target="_blank" rel="noreferrer" onClick={event => { if (!portal || Date.parse(portal.expires_at) <= Date.now()) { event.preventDefault(); setPortal(undefined); } }}>{portal?.simulated ? t("Open simulated Portal", "Abrir Portal simulado") : t("Open Portal session", "Abrir sessão do Portal")}</a> · {t("Expires", "Expira")}: {utc(portal?.expires_at)}</p>}
    <p>{t("Refunds do not cancel this subscription.", "Os reembolsos não cancelam esta assinatura.")}</p>
    {intent && intent.revision === subscription.revision && permitted(intent.action) && <Confirmation key={`${subscription.subscription_id}:${intent.revision}:${intent.action}`} subscription={subscription} action={intent.action} locale={locale} onClose={() => setIntent(undefined)} onChanged={onChanged} onResult={(message, navigation) => { setResult(message); setPortal(navigation); setRefreshFrom(subscription); }} />}
  </section>;
}
