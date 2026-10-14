"use client";
import { useEffect, useState, type FormEvent } from "react";
import type { components } from "../../lib/api/schema";
import { api, errorTitle } from "../../lib/api/client";
import { useIntentMutation } from "../payments/useIntentMutation";
import { copy, Language, uuid, type Locale } from "./SubscriptionFilters";
type Schemas = components["schemas"];
function Editor({ saved, locale, onSaved, onReload }: { saved: Schemas["SubscriptionPolicyResponse"]; locale: Locale; onSaved: (value: Schemas["SubscriptionPolicyResponse"]) => void; onReload: () => void }) {
  const mutation = useIntentMutation(); const [enabled, setEnabled] = useState(saved.enabled); const [grace, setGrace] = useState(String(saved.grace_days)); const [immediate, setImmediate] = useState(saved.allow_immediate_cancellation); const [portal, setPortal] = useState(saved.portal_enabled); const [defer, setDefer] = useState(saved.defer_upgrades); const [urls, setUrls] = useState(saved.portal_return_urls.join("\n")); const [validation, setValidation] = useState("");
  const t = (en: string, pt: string) => copy(locale, en, pt); const conflict = mutation.errorStatus === 409;
  function changed() { mutation.changed(); setValidation(""); }
  async function submit(event: FormEvent) { event.preventDefault(); if (mutation.pending || conflict) return;
    const days = Number(grace); const returns = urls.split("\n").map(value => value.trim()).filter(Boolean);
    if (!/^\d+$/.test(grace) || !Number.isSafeInteger(days) || days > 365) { setValidation(t("Enter an integer grace duration from 0 to 365 days.", "Introduza um número inteiro de 0 a 365 dias de tolerância.")); return; }
    if (returns.length > 20 || returns.some(value => { try { const url = new URL(value); return value.length > 2048 || !["https:", "http:"].includes(url.protocol) || !!url.username || !!url.password || !!url.hash; } catch { return true; } })) { setValidation(t("Enter up to 20 valid HTTP(S) return URLs without credentials or fragments.", "Introduza até 20 URLs HTTP(S) válidos sem credenciais ou fragmentos.")); return; }
    const body: Schemas["SubscriptionPolicyRequest"] = { version: saved.version, enabled, grace_days: days, allow_immediate_cancellation: immediate, portal_enabled: portal, defer_upgrades: defer, portal_return_urls: returns };
    await mutation.run<Schemas["SubscriptionPolicyResponse"]>(`v1/admin/sites/${saved.site_id}/subscription-policy`, { method: "POST", body }, onSaved);
  }
  return <form className="form-stack" onSubmit={submit} noValidate><p>{t("Saved policy version", "Versão guardada")}: {saved.version}</p><p>{t("Saved policy", "Política guardada")}: {saved.enabled ? t("enabled", "ativa") : t("disabled", "inativa")}</p>
    <fieldset disabled={mutation.pending || conflict}><label className="check"><input type="checkbox" checked={enabled} onChange={event => { setEnabled(event.target.checked); changed(); }} />{t("Subscriptions enabled", "Assinaturas ativas")}</label>
    <label>{t("Grace days (UTC)", "Dias de tolerância (UTC)")}<input inputMode="numeric" value={grace} maxLength={10} onChange={event => { setGrace(event.target.value); changed(); }} /></label><p>{t("0 disables grace. Each day is 24 hours in UTC.", "0 desativa a tolerância. Cada dia tem 24 horas em UTC.")}</p>
    <label className="check"><input type="checkbox" checked={immediate} onChange={event => { setImmediate(event.target.checked); changed(); }} />{t("Allow immediate cancellation", "Permitir cancelamento imediato")}</label>
    <label className="check"><input type="checkbox" checked={portal} onChange={event => { setPortal(event.target.checked); changed(); }} />{t("Portal enabled", "Portal ativo")}</label>
    <label className="check"><input type="checkbox" checked={defer} onChange={event => { setDefer(event.target.checked); changed(); }} />{t("Allow deferred upgrades", "Permitir melhorias diferidas")}</label>
    <label>{t("Allowed Portal return URLs (one per line)", "URLs de retorno do Portal permitidos (um por linha)")}<textarea value={urls} maxLength={41000} onChange={event => { setUrls(event.target.value); changed(); }} /></label></fieldset>
    {(validation || mutation.error) && <div role="alert">{validation || mutation.error}{conflict && <><p>{t("Reload the policy and review your changes before saving again.", "Recarregue a política e reveja as alterações antes de guardar novamente.")}</p>{!enabled && <p>{t("Finish non-terminal subscriptions and confirm the provider result before disabling subscriptions.", "Termine as assinaturas não concluídas e confirme o resultado do fornecedor antes de desativar assinaturas.")}</p>}</>}</div>}
    <button disabled={mutation.pending || conflict}>{mutation.retry ? t("Retry policy", "Repetir política") : t("Save policy", "Guardar política")}</button><button type="button" disabled={mutation.pending} onClick={onReload}>{t("Reload policy", "Recarregar política")}</button>
  </form>;
}
export function SubscriptionPolicyForm({ siteId }: { siteId: string }) {
  const [locale, setLocale] = useState<Locale>("en"); const [policy, setPolicy] = useState<Schemas["SubscriptionPolicyResponse"]>(); const [error, setError] = useState(""); const [revision, setRevision] = useState(0);
  useEffect(() => { if (!uuid(siteId)) return; const controller = new AbortController(); api<Schemas["SubscriptionPolicyResponse"]>(`v1/admin/sites/${siteId}/subscription-policy`, { signal: controller.signal }).then(value => { if (!controller.signal.aborted) { setPolicy(value); setError(""); } }).catch(failure => { if (!controller.signal.aborted) setError(errorTitle(failure)); }); return () => controller.abort(); }, [siteId, revision]);
  const t = (en: string, pt: string) => copy(locale, en, pt);
  return <section className="surface space-y-4" aria-label={t("Subscription policy", "Política de assinaturas")}><h2>{t("Subscription policy", "Política de assinaturas")}</h2><Language locale={locale} onChange={setLocale} />{error && <p role="alert">{error}</p>}{!uuid(siteId) && <p role="alert">{t("Invalid Site UUID", "UUID do Site inválido")}</p>}{policy?.site_id === siteId && <Editor key={`${siteId}:${policy.version}:${revision}`} saved={policy} locale={locale} onSaved={setPolicy} onReload={() => { setPolicy(undefined); setError(""); setRevision(value => value + 1); }} />}</section>;
}
