"use client";
import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { code as currencyCode } from "currency-codes";
import type { components } from "../../../lib/api/schema";
import { useIntentMutation } from "../../payments/useIntentMutation";
import type { Campaign } from "./types";

export function CampaignForm({ siteId, campaign, onSaved, onRefresh }: {
  siteId: string; campaign?: Campaign; onSaved: (campaign: Campaign) => void; onRefresh: () => void;
}) {
  const [key, setKey] = useState(campaign?.key ?? "");
  const [name, setName] = useState(campaign?.display_name ?? "");
  const [purpose, setPurpose] = useState(campaign?.purpose_description ?? "");
  const [currency, setCurrency] = useState(campaign?.currency ?? "EUR");
  const [amounts, setAmounts] = useState(campaign?.suggested_amounts?.join(", ") ?? "500, 1000");
  const [minimum, setMinimum] = useState(String(campaign?.minimum_amount ?? 100));
  const [maximum, setMaximum] = useState(String(campaign?.maximum_amount ?? 100000));
  const [custom, setCustom] = useState(campaign?.custom_amount_allowed ?? true);
  const [anonymous, setAnonymous] = useState(campaign?.anonymous_allowed ?? false);
  const [validation, setValidation] = useState(""); const mutation = useIntentMutation();
  const alert = useRef<HTMLParagraphElement>(null); const errorId = useId();
  const conflict = mutation.errorStatus === 409;
  const error = validation || (conflict ? "Refresh campaign before editing; a newer configuration may exist. Review it before submitting again." : mutation.error);
  useEffect(() => { if (error) alert.current?.focus(); }, [error]);
  function changed() { setValidation(""); mutation.changed(); }
  function integer(text: string) { return /^\d+$/.test(text) && Number.isSafeInteger(Number(text)) && Number(text) > 0; }
  function allowedAmounts() {
    if (!integer(minimum) || !integer(maximum) || Number(minimum) > Number(maximum)) throw new Error("Enter positive ordered minimum and maximum amounts in minor units.");
    const pieces = amounts.trim() ? amounts.split(",").map(value => value.trim()) : [];
    if (pieces.some(value => !integer(value)) || new Set(pieces.map(Number)).size !== pieces.length || pieces.some(value => Number(value) < Number(minimum) || Number(value) > Number(maximum)) || (!custom && !pieces.length))
      throw new Error("Suggested amounts must be unique positive integers within the minimum and maximum; suggested-only campaigns need at least one amount.");
    return pieces.map(Number);
  }
  let preview = "";
  try { const suggestions = allowedAmounts(); preview = `Suggested amounts: ${suggestions.join(", ") || "none"}. ${custom ? `Custom amounts: ${minimum}–${maximum}` : "Only suggested amounts allowed"}. ${currency} minor units. Server validation is authoritative.`; }
  catch (failure) { preview = failure instanceof Error ? failure.message : "Review amount policy."; }
  async function submit(event: FormEvent) {
    event.preventDefault(); if (mutation.pending || conflict || campaign?.status === "ARCHIVED") return;
    try {
      if (!/^[a-z0-9][a-z0-9-]{2,62}$/.test(key) || !name.trim()) throw new Error("Enter a safe campaign key and display name.");
      if (!currencyCode(currency) || !/^[A-Z]{3}$/.test(currency)) throw new Error("Enter a valid uppercase ISO currency.");
      const suggestions = allowedAmounts();
      if (campaign && (!campaign.id || !Number.isSafeInteger(campaign.version))) throw new Error("Refresh campaign before editing.");
      const body: components["schemas"]["CampaignConfigurationRequest"] = {
        key, display_name: name, purpose_description: purpose || null, currency, suggested_amounts: suggestions,
        minimum_amount: Number(minimum), maximum_amount: Number(maximum), custom_amount_allowed: custom, anonymous_allowed: anonymous,
        ...(campaign ? { expected_version: campaign.version } : {}),
      };
      const path = `v1/admin/sites/${siteId}/donation-campaigns${campaign ? `/${campaign.id}` : ""}`;
      await mutation.run<Campaign>(path, { method: campaign ? "PUT" : "POST", body }, onSaved);
    } catch (failure) { setValidation(failure instanceof Error ? failure.message : "Review campaign configuration."); }
  }
  return <form className="surface form-stack" aria-label={campaign ? "Edit campaign" : "Create campaign"} onSubmit={submit} noValidate>
    <h2>{campaign ? "Edit campaign" : "Create campaign"}</h2>
    {error && <p role="alert" tabIndex={-1} ref={alert} id={errorId}>{error}</p>}
    {conflict && <button type="button" onClick={onRefresh}>Refresh campaign</button>}
    <fieldset disabled={mutation.pending || conflict || campaign?.status === "ARCHIVED"} aria-describedby={error ? errorId : undefined} className="form-stack">
      <label>Campaign key<input value={key} disabled={!!campaign} onChange={event => { setKey(event.target.value); changed(); }} /></label>
      <label>Display name<input value={name} onChange={event => { setName(event.target.value); changed(); }} /></label>
      <label>Purpose description<textarea value={purpose} onChange={event => { setPurpose(event.target.value); changed(); }} /></label>
      <label>Currency<input value={currency} disabled={campaign?.activated_once} onChange={event => { setCurrency(event.target.value.toUpperCase()); changed(); }} aria-describedby={campaign?.activated_once ? `${errorId}-currency` : undefined} /></label>
      {campaign?.activated_once && <p id={`${errorId}-currency`}>Currency is fixed after first activation.</p>}
      <label>Suggested amounts (minor units, in display order)<input inputMode="numeric" value={amounts} onChange={event => { setAmounts(event.target.value); changed(); }} /></label>
      <label>Minimum amount (minor units)<input inputMode="numeric" value={minimum} onChange={event => { setMinimum(event.target.value); changed(); }} /></label>
      <label>Maximum amount (minor units)<input inputMode="numeric" value={maximum} onChange={event => { setMaximum(event.target.value); changed(); }} /></label>
      <label><input type="checkbox" checked={custom} onChange={event => { setCustom(event.target.checked); changed(); }} />Allow custom amounts</label>
      <label><input type="checkbox" checked={anonymous} onChange={event => { setAnonymous(event.target.checked); changed(); }} />Allow anonymous donations</label>
      <p role="status" aria-live="polite">{mutation.pending ? "Saving campaign…" : preview}</p>
      <button>{mutation.retry ? "Retry save" : "Save campaign"}</button>
    </fieldset>
  </form>;
}
