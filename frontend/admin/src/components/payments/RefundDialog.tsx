"use client";
import { useId, useState, type FormEvent } from "react";
import type { components } from "../../lib/api/schema";
import { ActionDialog } from "./ActionDialog";
import { useIntentMutation } from "./useIntentMutation";
export function RefundDialog({ payment, onClose, onCreated }: {
  payment: components["schemas"]["Projection"]; onClose: () => void; onCreated: () => void;
}) {
  const mutation = useIntentMutation(); const id = useId();
  const [amount, setAmount] = useState(String(payment.refundable_amount_minor ?? 0));
  const [reason, setReason] = useState<components["schemas"]["RefundRequest"]["reason_code"]>("REQUESTED_BY_CUSTOMER");
  const [note, setNote] = useState(""); const [validation, setValidation] = useState(""); const [accepted, setAccepted] = useState(false);
  function change() { mutation.changed(); setValidation(""); }
  async function submit(event: FormEvent) {
    event.preventDefault(); if (accepted || mutation.pending) return;
    const value = Number(amount); const max = payment.refundable_amount_minor ?? 0;
    if (!/^\d+$/.test(amount) || !Number.isSafeInteger(value) || value < 1 || value > max || !Number.isSafeInteger(max)) { setValidation(`Enter an integer amount from 1 to ${max}`); return; }
    if (!payment.payment_id || !payment.currency || !/^[A-Z]{3}$/.test(payment.currency)) { setValidation("Payment currency or identity unavailable"); return; }
    if (note.length > 500) { setValidation("Technical note must be at most 500 characters"); return; }
    const body: components["schemas"]["RefundRequest"] = { amount_minor: value, currency: payment.currency, reason_code: reason, ...(note ? { technical_note: note } : {}) };
    await mutation.run<components["schemas"]["RefundResponse"]>(`v1/admin/payments/${payment.payment_id}/refunds`, { method: "POST", body }, () => { setAccepted(true); onCreated(); });
  }
  const error = validation || mutation.error;
  return <ActionDialog title="Refund payment" pending={mutation.pending} onClose={onClose}>
    <p>Refunds are confirmed asynchronously. Currency: {payment.currency}. Available: {payment.refundable_amount_minor} minor units.</p>
    {accepted ? <><p role="status">Refund accepted; awaiting confirmation.</p><button onClick={onClose}>Close</button></> : <form className="form-stack" onSubmit={submit} noValidate>
      <label>Amount in minor units<input inputMode="numeric" value={amount} disabled={mutation.pending} aria-invalid={!!validation} aria-describedby={`${id}-help${error ? ` ${id}-error` : ""}`} onChange={event => { setAmount(event.target.value); change(); }} /></label>
      <p className="hint" id={`${id}-help`}>Enter full available amount or an integer partial amount. No decimals.</p>
      <label>Reason<select value={reason} disabled={mutation.pending} onChange={event => { setReason(event.target.value as typeof reason); change(); }}>{["REQUESTED_BY_CUSTOMER", "DUPLICATE", "FRAUDULENT", "OTHER"].map(value => <option key={value}>{value}</option>)}</select></label>
      <label>Technical note (optional)<textarea value={note} maxLength={500} disabled={mutation.pending} aria-describedby={`${id}-note`} onChange={event => { setNote(event.target.value); change(); }} /></label><p id={`${id}-note`} className="hint">Maximum 500 characters. Do not enter personal data or secrets.</p>
      {error && <p role="alert" id={`${id}-error`}>{error}</p>}
      {mutation.pending && <p role="status">Submitting refund…</p>}
      <button disabled={mutation.pending}>{mutation.retry ? "Retry refund" : "Confirm refund"}</button>
    </form>}
  </ActionDialog>;
}
