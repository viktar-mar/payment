"use client";
import {useId,useState,type FormEvent} from "react";
import type {operations} from "../../lib/api/schema";
import {validUuid} from "../customers/context";
import {ActionDialog} from "../payments/ActionDialog";
import {useIntentMutation} from "../payments/useIntentMutation";
type Redaction=operations["redactDonationMessage"]["responses"][200]["content"]["*/*"];
export function RedactMessageDialog({donationId,onClose,onRedacted}:{donationId:string;onClose:()=>void;onRedacted:(result:Redaction)=>void}){
  const mutation=useIntentMutation(),id=useId();const [confirmation,setConfirmation]=useState("");const [accepted,setAccepted]=useState(false);
  const exact=validUuid(donationId)&&confirmation===donationId;
  function submit(event:FormEvent){event.preventDefault();if(!exact||accepted)return;
    void mutation.run<Redaction>(`v1/admin/donations/${donationId}/message-redactions`,{method:"POST",body:{confirmation}},result=>{setAccepted(true);onRedacted(result);});
  }
  return <ActionDialog title="Redact donation message" pending={mutation.pending} onClose={onClose} fallbackSelector="[data-donation-heading]">
    <p>Permanently delete the donor message for Donation {donationId}. This action cannot be undone.</p>
    <p>Message redaction does not refund or reconcile the Payment. Financial facts are retained.</p>
    {accepted?<p role="status">Message redaction accepted.</p>:<form className="form-stack" onSubmit={submit}>
      <label>Donation UUID confirmation<input value={confirmation} maxLength={40} disabled={mutation.pending} autoComplete="off" spellCheck={false} aria-describedby={`${id}-help`} onChange={event=>{setConfirmation(event.target.value);mutation.changed();}}/></label>
      <p id={`${id}-help`}>Type the exact Donation UUID shown above to confirm.</p>
      {mutation.error&&<p role="alert">{mutation.error}</p>}{mutation.pending&&<p role="status">Submitting redaction…</p>}
      <button disabled={!exact||mutation.pending}>{mutation.retry?"Retry redaction":"Confirm redaction"}</button>
    </form>}
  </ActionDialog>;
}
