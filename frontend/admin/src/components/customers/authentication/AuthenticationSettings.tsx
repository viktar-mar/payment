"use client";
import { useEffect, useRef, useState } from "react";
import { api, ApiError, errorTitle, type ApiOptions } from "../../../lib/api/client";
import type { components } from "../../../lib/api/schema";
import { validUuid } from "../context";
import { IdentityProviderForm } from "./IdentityProviderForm";
import { ProviderReadiness } from "./ProviderReadiness";
type Schemas = components["schemas"];
type Provider = Schemas["ProviderResponse"];
type Mode = NonNullable<Provider["mode"]>;

export function AuthenticationSettings({ siteId }: { siteId: string }) {
  if (!validUuid(siteId)) return <p role="alert">Provide a valid Site UUID.</p>;
  return <SiteAuthenticationSettings key={siteId} siteId={siteId} />;
}
function SiteAuthenticationSettings({ siteId }: { siteId: string }) {
  const path = `v1/admin/sites/${siteId}/identity-providers`;
  const [site, setSite] = useState<Schemas["SiteResponse"]>(); const [providers, setProviders] = useState<Provider[]>();
  const [error, setError] = useState(""); const [revision, setRevision] = useState(0);
  const [editing, setEditing] = useState<{ mode: Mode; provider?: Provider }>();
  const [pending, setPending] = useState(false); const [message, setMessage] = useState("");
  const busy = useRef(false); const mutation = useRef<AbortController | undefined>(undefined); const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => () => mutation.current?.abort(), []);
  useEffect(() => {
    const controller = new AbortController();
    async function read() {
      try {
        const sites = await api<Schemas["SiteResponse"][]>("admin/v1/sites", { signal: controller.signal }); if (controller.signal.aborted) return;
        const current = sites.find(value => value.id === siteId); if (!current) { setError("Site not found."); return; }
        setSite(current);
        const result = await api<Provider[]>(path, { signal: controller.signal }); if (!controller.signal.aborted) { setProviders(result); setError(""); }
      } catch (failure) { if (!controller.signal.aborted) setError(errorTitle(failure)); }
    }
    void read(); return () => controller.abort();
  }, [siteId, path, revision]);
  async function write(url: string, options: ApiOptions, announcement: string) {
    if (busy.current) return; busy.current = true; mutation.current = new AbortController(); setPending(true); setError("");
    try {
      await api<Provider>(url, { ...options, signal: mutation.current.signal }); if (mutation.current.signal.aborted) return;
      setEditing(undefined); setMessage(announcement); setProviders(undefined); setRevision(value => value + 1); heading.current?.focus();
    } catch (failure) {
      if (!mutation.current.signal.aborted) setError(failure instanceof ApiError && failure.status === 409 ? "Refresh providers before editing; a newer configuration may exist. Resubmit replacement configuration after reviewing it." : errorTitle(failure));
    } finally { busy.current = false; if (!mutation.current.signal.aborted) setPending(false); }
  }
  function refresh() { if (busy.current) return; setEditing(undefined); setError(""); setProviders(undefined); setRevision(value => value + 1); heading.current?.focus(); }
  const modes = site?.authentication_modes ?? [];
  return <div className="space-y-6">
    <h1 tabIndex={-1} ref={heading}>Site authentication{site?.display_name ? ` · ${site.display_name}` : ""}</h1>
    {site && <section className="surface"><h2>Enabled authentication modes</h2><p>{modes.length ? modes.join(", ") : "No authentication modes enabled."}</p><p>Site modes are read-only.</p></section>}
    {error && <p role="alert">{error}</p>}
    {message && <p role="status">{message}</p>}
    {!providers && !error && <p role="status">Loading authentication settings…</p>}
    {pending && <p role="status">Saving provider configuration…</p>}
    {site && <p><button disabled={pending} onClick={refresh}>Refresh providers</button></p>}
    {providers && !providers.length && <p>No identity providers configured.</p>}
    {providers?.map((provider, index) => <section className="surface" key={provider.id ?? index} aria-label={`Provider ${provider.provider_key}`}>
      <h2>{provider.provider_key}</h2><ProviderReadiness provider={provider} />
      {provider.status === "ACTIVE" && provider.id && provider.mode && modes.includes(provider.mode) && <>
        <button disabled={pending} onClick={() => { setEditing({ mode: provider.mode!, provider }); setError(""); setMessage(""); }}>Edit {provider.provider_key}</button>{" "}
        <button disabled={pending} onClick={() => { const body: Schemas["StatusRequest"] = { status: "DISABLED", expected_version: provider.version }; void write(`${path}/${provider.id}/status`, { method: "POST", body }, "Provider disabled."); }}>Disable {provider.provider_key}</button>
      </>}
    </section>)}
    {site?.status === "ACTIVE" && <div>{modes.map(mode => <button disabled={pending} key={mode} onClick={() => { setEditing({ mode }); setError(""); setMessage(""); }}>Add {mode} provider</button>)}</div>}
    {editing && <IdentityProviderForm key={`${editing.provider?.id ?? "new"}/${editing.mode}`} {...editing} pending={pending} onCancel={() => { if (!busy.current) { setEditing(undefined); heading.current?.focus(); } }} onSave={body => { void write(editing.provider ? `${path}/${editing.provider.id}` : path, { method: editing.provider ? "PUT" : "POST", body }, "Provider saved."); }} />}
  </div>;
}
