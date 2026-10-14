"use client";
import { useId, useState, type FormEvent } from "react";
import type { components } from "../../lib/api/schema";
import { ActionDialog } from "../payments/ActionDialog";
import { useIntentMutation } from "../payments/useIntentMutation";
import { validUuid } from "./context";
import Link from "next/link";
import { copy, Language, type Locale } from "../subscriptions/SubscriptionFilters";

export function AnonymizeCustomerDialog({ siteId, customerId, onClose, onAnonymized }: {
  siteId: string; customerId: string; onClose: () => void; onAnonymized: () => void;
}) {
  const mutation = useIntentMutation(); const id = useId();
  const [confirmation, setConfirmation] = useState(""); const [accepted, setAccepted] = useState(false);
  const [locale, setLocale] = useState<Locale>("en");
  const exact = confirmation === customerId && validUuid(customerId) && validUuid(siteId);
  function submit(event: FormEvent) {
    event.preventDefault(); if (!exact || accepted) return;
    const body: components["schemas"]["CustomerAnonymizationRequest"] = { confirmation };
    void mutation.run<components["schemas"]["CustomerAnonymizationResponse"]>(
      `v1/admin/sites/${siteId}/customers/${customerId}/anonymization`, { method: "POST", body },
      () => { setAccepted(true); onAnonymized(); });
  }
  return <ActionDialog title="Anonymize customer" pending={mutation.pending} onClose={onClose} fallbackSelector="[data-customer-heading]">
    <p>Permanently delete personal information (PII) and messages for Customer {customerId}.</p>
    <p>Revoke sessions and identities. Financial facts are retained. This action cannot be undone.</p>
    <Language locale={locale} onChange={setLocale} />
    {accepted ? <p role="status">Customer anonymized.</p> : <form className="form-stack" onSubmit={submit}>
      <label>Customer UUID confirmation<input value={confirmation} maxLength={40} disabled={mutation.pending} autoComplete="off" spellCheck={false} aria-describedby={`${id}-help`} onChange={event => { setConfirmation(event.target.value); mutation.changed(); }} /></label>
      <p id={`${id}-help`}>Type the exact Customer UUID shown above to confirm.</p>
      {mutation.error && <p role="alert">{mutation.error}</p>}
      {mutation.errorStatus === 409 && <><p>{copy(locale,"Finish non-terminal subscriptions and confirm the provider result before anonymizing this customer.","Termine as assinaturas não concluídas e confirme o resultado do fornecedor antes de anonimizar este cliente.")}</p><Link href={`/subscriptions?site_id=${siteId}&customer_id=${customerId}`}>{copy(locale,"Manage Customer subscriptions","Gerir assinaturas do cliente")}</Link></>}
      {mutation.pending && <p role="status">Submitting anonymization…</p>}
      <button disabled={!exact || mutation.pending}>{mutation.retry ? "Retry anonymization" : "Confirm anonymization"}</button>
    </form>}
  </ActionDialog>;
}
