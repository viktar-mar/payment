"use client";
import { useState, type FormEvent } from "react";
import type { components } from "../../lib/api/schema";
import { ActionDialog } from "./ActionDialog";
import { RefundDialog } from "./RefundDialog";
import { useIntentMutation } from "./useIntentMutation";
type Schemas = components["schemas"];
type Intent = { kind: "reconcile" } | { kind: "retry"; eventId: string } | { kind: "fake" };
const paymentEvents: Schemas["FakeRequest"]["event_type"][] = ["PAYMENT_METHOD_REQUIRED", "CUSTOMER_ACTION_REQUIRED", "PAYMENT_PROCESSING", "PAYMENT_SUCCEEDED", "PAYMENT_FAILED", "PAYMENT_CANCELLED"];
const refundEvents: Schemas["FakeRequest"]["event_type"][] = ["REFUND_PROCESSING", "REFUND_SUCCEEDED", "REFUND_FAILED"];
function Confirmation({ intent, details, onClose, onRefresh, onResult }: { intent: Intent; details: Schemas["Details"]; onClose: () => void; onRefresh: (details?: Schemas["Details"]) => void; onResult: (message: string) => void }) {
  const mutation = useIntentMutation(); const [mode, setMode] = useState<Schemas["RetryRequest"]["mode"]>("SNAPSHOT");
  const [target, setTarget] = useState<Schemas["FakeRequest"]["target"]>("PAYMENT");
  const [eventType, setEventType] = useState<Schemas["FakeRequest"]["event_type"]>("PAYMENT_PROCESSING");
  const [refundId, setRefundId] = useState(""); const [sequence, setSequence] = useState("1"); const [validation, setValidation] = useState("");
  const label = intent.kind === "reconcile" ? "reconcile" : intent.kind === "retry" ? "callback retry" : "fake event";
  function change() { mutation.changed(); setValidation(""); }
  async function submit(event: FormEvent) {
    event.preventDefault(); const paymentId = details.payment?.payment_id; if (!paymentId || mutation.pending) return;
    if (intent.kind === "reconcile") {
      await mutation.run<Schemas["Details"]>(`v1/admin/payments/${paymentId}/reconcile`, { method: "POST" }, result => { onResult("Reconciliation completed; current projection loaded."); onRefresh(result); onClose(); });
    } else if (intent.kind === "retry") {
      const body: Schemas["RetryRequest"] = { mode };
      await mutation.run<Schemas["RetryResponse"]>(`v1/admin/outbox/${intent.eventId}/retry`, { method: "POST", body }, () => { onResult("Callback retry accepted; delivery pending."); onRefresh(); onClose(); });
    } else {
      const value = Number(sequence);
      if (!/^\d+$/.test(sequence) || !Number.isSafeInteger(value) || value < 1 || value > 2147483647) { setValidation("Enter an integer sequence from 1 to 2147483647"); return; }
      if (target === "REFUND" && !details.refunds?.some(refund => refund.id === refundId)) { setValidation("Select an existing refund"); return; }
      const body: Schemas["FakeRequest"] = { target, event_type: eventType, sequence: value, ...(target === "REFUND" ? { refund_id: refundId } : {}) };
      await mutation.run<Schemas["Accepted"]>(`v1/admin/payments/${paymentId}/fake-events`, { method: "POST", body }, () => { onResult("Fake event accepted; inbox processing pending."); onRefresh(); onClose(); });
    }
  }
  return <ActionDialog title={`Confirm ${label}`} pending={mutation.pending} onClose={onClose}>
    <form className="form-stack" onSubmit={submit} noValidate>
      {intent.kind === "reconcile" && <p>Read provider truth and update this payment through the reconciliation rules.</p>}
      {intent.kind === "retry" && <><p>Retry outbox event {intent.eventId}. Choose its original snapshot or the current Site configuration.</p><label>Retry mode<select value={mode} disabled={mutation.pending} onChange={event => { setMode(event.target.value as typeof mode); change(); }}><option value="SNAPSHOT">SNAPSHOT</option><option value="CURRENT">CURRENT</option></select></label></>}
      {intent.kind === "fake" && <>
        <label>Target<select value={target} disabled={mutation.pending} onChange={event => { const next = event.target.value as typeof target; setTarget(next); setEventType(next === "REFUND" ? "REFUND_PROCESSING" : "PAYMENT_PROCESSING"); change(); }}><option>PAYMENT</option><option disabled={!details.refunds?.length}>REFUND</option></select></label>
        {target === "REFUND" && <label>Refund<select value={refundId} disabled={mutation.pending} aria-describedby={validation ? "fake-error" : undefined} onChange={event => { setRefundId(event.target.value); change(); }}><option value="">Choose a refund</option>{details.refunds?.filter(refund => refund.id).map(refund => <option key={refund.id} value={refund.id}>{refund.id}</option>)}</select></label>}
        <label>Event type<select value={eventType} disabled={mutation.pending} onChange={event => { setEventType(event.target.value as typeof eventType); change(); }}>{(target === "PAYMENT" ? paymentEvents : refundEvents).map(type => <option key={type}>{type}</option>)}</select></label>
        <label>Sequence<input inputMode="numeric" value={sequence} disabled={mutation.pending} aria-describedby={validation ? "fake-error" : undefined} onChange={event => { setSequence(event.target.value); change(); }} /></label>
      </>}
      {(validation || mutation.error) && <p role="alert" id="fake-error">{validation || mutation.error}</p>}
      {mutation.pending && <p role="status">Submitting {label}…</p>}
      <button disabled={mutation.pending}>{mutation.retry ? `Retry ${label}` : `Confirm ${label}`}</button>
    </form>
  </ActionDialog>;
}
export function PaymentActions({ details, onRefresh }: { details: Schemas["Details"]; onRefresh: (details?: Schemas["Details"]) => void }) {
  const [intent, setIntent] = useState<Intent>(); const [refund, setRefund] = useState(false); const [result, setResult] = useState(""); const payment = details.payment;
  const refundable = payment && Number.isSafeInteger(payment.refundable_amount_minor) && (payment.refundable_amount_minor ?? 0) > 0 && ["SUCCEEDED", "PARTIALLY_REFUNDED"].includes(payment.status ?? "");
  return <section className="surface" aria-label="Payment actions"><h2>Actions</h2><div className="flex flex-wrap gap-3">
    {refundable && <button onClick={() => { setResult(""); setRefund(true); }}>Refund</button>}
    <button onClick={() => { setResult(""); setIntent({ kind: "reconcile" }); }}>Reconcile</button>
    {details.outbox_events?.filter(event => event.id).map(event => <button key={event.id} onClick={() => { setResult(""); setIntent({ kind: "retry", eventId: event.id! }); }}>Retry callback {event.id}</button>)}
    {payment?.provider === "FAKE" && <button onClick={() => { setResult(""); setIntent({ kind: "fake" }); }}>Simulate fake event</button>}
  </div><p role="status" aria-live="polite">{result}</p>
    {refund && payment && <RefundDialog payment={payment} onClose={() => setRefund(false)} onCreated={() => { setResult("Refund accepted; awaiting confirmation."); onRefresh(); }} />}
    {intent && <Confirmation intent={intent} details={details} onClose={() => setIntent(undefined)} onRefresh={onRefresh} onResult={setResult} />}
  </section>;
}
