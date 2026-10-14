"use client";
import { useId, useState, type FormEvent } from "react";
import { OneTimeSecret } from "./OneTimeSecret";
import { useIntegrationRequest } from "./useIntegrationRequest";
import type { KeyCreate, KeyMetadata, KeyReveal } from "./wire-types";
import { isUtcInstant } from "../../lib/utcInstant";
import { copy, Language, type Locale } from "../subscriptions/SubscriptionFilters";
const availableScopes: KeyCreate["scopes"] = ["payments:create", "payments:read", "subscriptions:read", "subscriptions:manage"];
export function SiteApiKeys({ siteId }: { siteId: string }) {
  const request = useIntegrationRequest(); const [keys, setKeys] = useState<KeyMetadata[]>([]); const [secret, setSecret] = useState<string>(); const [secretVersion, setSecretVersion] = useState(0);
  const [error, setError] = useState(""); const [message, setMessage] = useState(""); const [revoking, setRevoking] = useState<string>(); const id = useId();
  const [locale, setLocale] = useState<Locale>("en");
  function revealed(response: KeyReveal) {
    // Explicit fields: neither the entire response nor plaintext enters metadata/history.
    const metadata: KeyMetadata = { key_id: response.key_id, site_id: response.site_id, scopes: response.scopes, status: response.status, created_at: response.created_at, expires_at: response.expires_at };
    setKeys(current => [...current.filter(key => key.key_id !== metadata.key_id), metadata]);
    setSecret(response.api_key); setSecretVersion(value => value + 1); setMessage("API key issued. Store the one-time value securely.");
  }
  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (request.pending || secret) return; const data = new FormData(event.currentTarget); const scopes = data.getAll("scopes").map(String); setError("");
    if (!scopes.length || !scopes.every((scope): scope is KeyCreate["scopes"][number] => availableScopes.some(allowed => allowed === scope))) { setError("Select at least one scope"); return; }
    const expiry = String(data.get("expiry") ?? "").trim();
    if (expiry && (!isUtcInstant(expiry) || Date.parse(expiry) <= Date.now())) { setError("Enter a future UTC expiry ending in Z"); return; }
    const body: KeyCreate = { scopes, ...(expiry ? { expires_at: expiry } : {}) };
    await request.run<KeyReveal>(`v1/admin/sites/${siteId}/api-keys`, { method: "POST", body }, revealed);
  }
  return <section className="surface space-y-4" aria-label="Site API keys"><h2 tabIndex={-1}>Site API keys</h2>
    <p>Only keys created or rotated in this session are shown. There is no key-list endpoint; reloading clears this view. Prefix and last-used time are not returned by these actions.</p>
    <Language locale={locale} onChange={setLocale} /><p>{copy(locale,"Subscription scopes require explicit opt-in on a new key for an enabled Site. Rotating an old key preserves its scopes; selecting scopes here never widens existing keys.","Os âmbitos de assinatura exigem seleção explícita numa nova chave para um Site ativo. Rodar uma chave antiga preserva os seus âmbitos; a seleção aqui nunca amplia chaves existentes.")}</p>
    <form className="form-stack" onSubmit={create} noValidate><fieldset disabled={request.pending || !!secret}><legend>Scopes</legend>{availableScopes.map(scope => <label className="check" key={scope}><input type="checkbox" name="scopes" value={scope} aria-describedby={`${id}-error`} />{scope}</label>)}</fieldset>
      <label>Expiry (UTC, optional)<input name="expiry" disabled={request.pending || !!secret} placeholder="2027-01-01T00:00:00Z" aria-describedby={`${id}-error`} /></label><button disabled={request.pending || !!secret}>Create API key</button>
    </form>
    {(error || request.error) && <p role="alert" id={`${id}-error`}>{error || request.error}</p>}<p role="status" aria-live="polite">{request.pending ? "Submitting key action…" : message}</p>
    {secret && <OneTimeSecret key={secretVersion} label="API key" secret={secret} onDismiss={() => setSecret(undefined)} />}
    <ul className="space-y-4">{keys.map((key, index) => <li key={key.key_id ?? index}><h3>{key.key_id}</h3><dl className="details"><dt>Status</dt><dd>{key.status}</dd><dt>Scopes</dt><dd>{key.scopes?.join(", ")}</dd><dt>Created</dt><dd>{key.created_at}</dd><dt>Expires</dt><dd>{key.expires_at ?? "No expiry returned"}</dd></dl>
      {key.key_id && key.status !== "REVOKED" && <div className="flex flex-wrap gap-3"><button disabled={request.pending || !!secret} onClick={() => { setError(""); request.run<KeyReveal>(`v1/admin/sites/${siteId}/api-keys/${key.key_id}/rotate`, { method: "POST" }, revealed); }}>Rotate {key.key_id}</button>
        <button disabled={request.pending || !!secret} onClick={() => {
          // Revoke is explicit; clicking twice is guarded by the request ref.
          setRevoking(key.key_id); setError(""); request.run<void>(`v1/admin/sites/${siteId}/api-keys/${key.key_id}/revoke`, { method: "POST" }, () => { setKeys(current => current.map(item => item.key_id === key.key_id ? { ...item, status: "REVOKED" } : item)); setMessage(`Key ${key.key_id} revoked.`); setRevoking(undefined); });
        }}>Revoke {key.key_id}</button></div>}
    </li>)}</ul>{revoking && request.pending && <p>Revoking {revoking}…</p>}
  </section>;
}
