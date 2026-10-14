"use client";
import { useId, useState, type FormEvent } from "react";
import { OneTimeSecret } from "./OneTimeSecret";
import { useIntegrationRequest } from "./useIntegrationRequest";
import type { CallbackMetadata, CallbackReveal, CallbackRequest } from "./wire-types";
export function CallbackConfiguration({ siteId }: { siteId: string }) {
  const request = useIntegrationRequest(); const [metadata, setMetadata] = useState<CallbackMetadata>(); const [secret, setSecret] = useState<string>(); const [version, setVersion] = useState(0); const [error, setError] = useState(""); const id = useId();
  function revealed(response: CallbackReveal) { setMetadata({ site_id: response.site_id, endpoint_url: response.endpoint_url, enabled: response.enabled, secret_version: response.secret_version }); setSecret(response.secret); setVersion(value => value + 1); }
  async function configure(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (request.pending || secret) return; const data = new FormData(event.currentTarget); const endpoint = String(data.get("endpoint") ?? "").trim(); setError("");
    try { const url = new URL(endpoint); if (endpoint.length > 2048 || url.username || url.password || url.hash || !["http:", "https:"].includes(url.protocol) || url.protocol === "http:" && !["localhost", "127.0.0.1", "[::1]"].includes(url.hostname)) throw new Error(); }
    catch { setError("Enter an HTTPS callback URL without credentials or a fragment (HTTP allowed for localhost)."); return; }
    const body: CallbackRequest = { endpoint_url: endpoint, enabled: data.get("enabled") === "on" };
    await request.run<CallbackReveal>(`v1/admin/sites/${siteId}/callback`, { method: "PUT", body }, revealed);
  }
  return <section className="surface space-y-4" aria-label="Callback configuration"><h2 tabIndex={-1}>Callback configuration</h2><p>Only configuration returned in this session is shown. No read endpoint exists; this form does not indicate the stored configuration. Server policy validates the endpoint.</p>
    <form className="form-stack" onSubmit={configure} noValidate><label>Callback endpoint URL<input name="endpoint" type="url" maxLength={2048} disabled={request.pending || !!secret} aria-describedby={`${id}-error`} /></label><label className="check"><input name="enabled" type="checkbox" disabled={request.pending || !!secret} />Callback enabled</label><button disabled={request.pending || !!secret}>Save callback</button></form>
    <button disabled={request.pending || !!secret} onClick={() => { setError(""); request.run<CallbackReveal>(`v1/admin/sites/${siteId}/callback/rotate-secret`, { method: "POST" }, revealed); }}>Rotate callback secret</button>
    {(error || request.error) && <p role="alert" id={`${id}-error`}>{error || request.error}</p>}<p role="status" aria-live="polite">{request.pending ? "Submitting callback action…" : metadata ? "Callback configuration returned. Store any new one-time secret securely." : ""}</p>
    {secret && <OneTimeSecret key={version} label="Callback secret" secret={secret} onDismiss={() => setSecret(undefined)} />}
    {metadata && <dl className="details"><dt>Current-session endpoint</dt><dd>{metadata.endpoint_url}</dd><dt>Enabled</dt><dd>{String(metadata.enabled)}</dd><dt>Secret version</dt><dd>{metadata.secret_version}</dd></dl>}
  </section>;
}
