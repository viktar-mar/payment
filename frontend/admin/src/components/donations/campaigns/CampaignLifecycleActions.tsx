"use client";
import { useState, type FormEvent } from "react";
import { ActionDialog } from "../../payments/ActionDialog";
import { useIntentMutation } from "../../payments/useIntentMutation";
import type { Campaign, CampaignStatus } from "./types";

type Intent = { status: CampaignStatus; label: string };
function Confirmation({ siteId, campaign, intent, onClose, onChanged, onRefresh }: {
  siteId: string; campaign: Campaign; intent: Intent; onClose: () => void; onChanged: (campaign: Campaign) => void; onRefresh: () => void;
}) {
  const mutation = useIntentMutation(); const conflict = mutation.errorStatus === 409;
  async function submit(event: FormEvent) {
    event.preventDefault(); if (!campaign.id || !Number.isSafeInteger(campaign.version) || conflict) return;
    await mutation.run<Campaign>(`v1/admin/sites/${siteId}/donation-campaigns/${campaign.id}/status`, {
      method: "POST", body: { status: intent.status, expected_version: campaign.version },
    }, result => { onChanged(result); onClose(); });
  }
  return <ActionDialog title={`Confirm ${intent.label} campaign`} pending={mutation.pending} onClose={onClose} fallbackSelector="[data-campaign-heading]">
    <form onSubmit={submit}>
      <p>{intent.status === "ARCHIVED" ? "Archiving is permanent. This campaign cannot be edited or reactivated." : `Confirm ${intent.label} for this campaign.`}</p>
      {mutation.error && <p role="alert">{conflict ? "Refresh campaign and review its latest state before retrying." : mutation.error}</p>}
      {mutation.pending && <p role="status">Submitting lifecycle change…</p>}
      {conflict && <button type="button" onClick={() => { onClose(); onRefresh(); }}>Refresh campaign</button>}
      <button disabled={mutation.pending || conflict}>{mutation.retry ? "Retry" : "Confirm"} {intent.label}</button>
    </form>
  </ActionDialog>;
}
export function CampaignLifecycleActions({ siteId, campaign, onChanged, onRefresh }: {
  siteId: string; campaign: Campaign; onChanged: (campaign: Campaign) => void; onRefresh: () => void;
}) {
  const [intent, setIntent] = useState<Intent>();
  const actions: Intent[] = campaign.status === "DRAFT" ? [{ status: "ACTIVE", label: "activate" }] :
    campaign.status === "ACTIVE" ? [{ status: "PAUSED", label: "pause" }] : campaign.status === "PAUSED" ? [{ status: "ACTIVE", label: "resume" }] : [];
  if (campaign.status && campaign.status !== "ARCHIVED") actions.push({ status: "ARCHIVED", label: "archive" });
  return <section className="surface" aria-label="Campaign lifecycle"><h2>Lifecycle</h2>
    {campaign.status === "ARCHIVED" && <p>This campaign is archived permanently.</p>}
    <div className="flex gap-3">{actions.map(action => <button key={action.status} onClick={() => setIntent(action)}>{action.label[0].toUpperCase() + action.label.slice(1)}</button>)}</div>
    {intent && <Confirmation siteId={siteId} campaign={campaign} intent={intent} onChanged={onChanged} onRefresh={onRefresh} onClose={() => setIntent(undefined)} />}
  </section>;
}
