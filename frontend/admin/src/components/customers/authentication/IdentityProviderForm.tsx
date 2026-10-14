"use client";
import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import type { components } from "../../../lib/api/schema";
type Request = components["schemas"]["ProviderRequest"];
type Provider = components["schemas"]["ProviderResponse"];
type Mode = NonNullable<Request["mode"]>;
const safeKey = /^[a-z0-9][a-z0-9-]{2,62}$/;

/** Structural input feedback only. The backend owns cryptographic/key validation. */
function publicKeys(input: string): NonNullable<Request["public_jwk_set"]> | undefined {
  if (input.length > 65536) return;
  try {
    const value: unknown = JSON.parse(input);
    if (!value || typeof value !== "object" || !("keys" in value) || !Array.isArray(value.keys) || value.keys.length < 1 || value.keys.length > 20) return;
    for (const key of value.keys) {
      if (!key || typeof key !== "object" || !["RSA", "EC"].includes(key.kty) || ["d", "p", "q", "dp", "dq", "qi", "oth", "k"].some(name => name in key)) return;
      if (key.kty === "RSA" && (typeof key.n !== "string" || typeof key.e !== "string" || !key.n || !key.e)) return;
      if (key.kty === "EC" && (key.crv !== "P-256" || typeof key.x !== "string" || typeof key.y !== "string" || !key.x || !key.y)) return;
    }
    return value as NonNullable<Request["public_jwk_set"]>;
  } catch { return; }
}
export function IdentityProviderForm({ mode, provider, pending, onSave, onCancel }: {
  mode: Mode; provider?: Provider; pending: boolean; onSave: (body: Request) => void; onCancel: () => void;
}) {
  const id = useId(); const first = useRef<HTMLInputElement>(null); const issuerInput = useRef<HTMLInputElement>(null); const senderInput = useRef<HTMLInputElement>(null);
  const [key, setKey] = useState(provider?.provider_key ?? ""); const [issuer, setIssuer] = useState(provider?.issuer ?? "");
  const [audiences, setAudiences] = useState(provider?.audiences?.join(", ") ?? "");
  const [algorithms, setAlgorithms] = useState<string[]>(provider?.allowed_algorithms ?? ["RS256"]);
  const [source, setSource] = useState<NonNullable<Request["jwks_source_type"]>>(provider?.jwks_source_type ?? "REMOTE");
  const [uri, setUri] = useState(""); const [keys, setKeys] = useState(""); const [email, setEmail] = useState(""); const [verified, setVerified] = useState("");
  const [sender, setSender] = useState(provider?.sender_ref ?? ""); const [template, setTemplate] = useState(provider?.template_key ?? "");
  const [validation, setValidation] = useState("");
  useEffect(() => { (provider ? mode === "EMAIL_OTP" ? senderInput : issuerInput : first).current?.focus(); }, [mode, provider]);
  function submit(event: FormEvent) {
    event.preventDefault(); if (pending) return; setValidation("");
    if (!safeKey.test(key)) { setValidation("Enter a provider key of 3–63 lowercase letters, digits or hyphens."); return; }
    const body: Request = { provider_key: key, mode, ...(provider ? { expected_version: provider.version } : {}) };
    if (mode === "EMAIL_OTP") {
      if (!safeKey.test(sender) || !safeKey.test(template)) { setValidation("Enter valid sender and template references (3–63 lowercase letters, digits or hyphens)."); return; }
      body.sender_ref = sender; body.template_key = template;
    } else {
      const audienceValues = audiences.split(",").map(value => value.trim()).filter(Boolean);
      if (!issuer.trim() || issuer.length > 2048 || audiences.length > 2048 || !audienceValues.length || audienceValues.length > 20 || !algorithms.length || algorithms.some(value => !["RS256", "ES256"].includes(value))) { setValidation("Provide issuer, audiences and at least one RS256 or ES256 algorithm within the input limits."); return; }
      body.issuer = issuer.trim(); body.audiences = audienceValues; body.allowed_algorithms = algorithms; body.jwks_source_type = source;
      if (source === "REMOTE") {
        try { const url = new URL(uri); if (uri.length > 2048 || url.protocol !== "https:" || url.username || url.password || url.hash) throw new Error(); }
        catch { setValidation("Enter a valid HTTPS JWKS URL without credentials or fragment."); return; }
        body.jwks_uri = uri;
      } else {
        const material = publicKeys(keys); if (!material) { setValidation("Enter a bounded public JWK set JSON object with public RSA or P-256 EC keys only."); return; }
        body.public_jwk_set = material;
      }
      if (mode === "OIDC_TOKEN") {
        if (email.length > 128 || verified.length > 128 || !!email !== !!verified || [email, verified].some(value => value && !/^[A-Za-z0-9_.-]+$/.test(value))) { setValidation("Provide both valid email claim names or leave both empty."); return; }
        if (email) { body.email_claim = email; body.verified_email_claim = verified; }
      }
    }
    onSave(body);
  }
  return <section className="surface" aria-label={`${mode} provider form`}>
    <h2>{provider ? "Edit" : "Add"} {mode} provider</h2>
    {provider && mode !== "EMAIL_OTP" && <p>To edit token configuration, resubmit replacement source material and claim mappings. Saved source keys, JWKS URL and claims are intentionally not returned.</p>}
    <form className="form-stack" onSubmit={submit} noValidate>
      <label>Provider key<input ref={first} value={key} maxLength={63} disabled={pending || !!provider} onChange={event => setKey(event.target.value)} /></label>
      <p>Authentication mode: {mode}{provider ? " (immutable)" : ""}</p>
      {mode === "EMAIL_OTP" ? <>
        <label>Sender reference<input ref={senderInput} value={sender} maxLength={63} disabled={pending} onChange={event => setSender(event.target.value)} /></label>
        <label>Template key<input value={template} maxLength={63} disabled={pending} onChange={event => setTemplate(event.target.value)} /></label>
        <p>References select existing mail configuration. No SMTP credentials are accepted here.</p>
      </> : <>
        <label>Issuer<input ref={issuerInput} value={issuer} maxLength={2048} disabled={pending} onChange={event => setIssuer(event.target.value)} /></label>
        <label>Audiences (comma separated)<input value={audiences} maxLength={2048} disabled={pending} onChange={event => setAudiences(event.target.value)} /></label>
        <fieldset disabled={pending}><legend>Allowed signing algorithms</legend>{["RS256", "ES256"].map(algorithm => <label key={algorithm}><input type="checkbox" checked={algorithms.includes(algorithm)} onChange={event => setAlgorithms(current => event.target.checked ? [...current, algorithm] : current.filter(value => value !== algorithm))} />{algorithm}</label>)}</fieldset>
        <label>Key source<select value={source} disabled={pending} onChange={event => { setSource(event.target.value as typeof source); setUri(""); setKeys(""); setValidation(""); }}><option value="REMOTE">Remote HTTPS JWKS</option><option value="STATIC_PUBLIC">Static public JWK set</option></select></label>
        {source === "REMOTE" ? <label>HTTPS JWKS URL<input value={uri} maxLength={2048} disabled={pending} autoComplete="off" onChange={event => setUri(event.target.value)} /></label> : <label>Public JWK set JSON<textarea value={keys} maxLength={65536} disabled={pending} autoComplete="off" spellCheck={false} onChange={event => setKeys(event.target.value)} /></label>}
        {mode === "OIDC_TOKEN" && <><label>Email claim (optional)<input value={email} maxLength={128} disabled={pending} onChange={event => setEmail(event.target.value)} /></label><label>Verified email claim (optional)<input value={verified} maxLength={128} disabled={pending} onChange={event => setVerified(event.target.value)} /></label></>}
      </>}
      {validation && <p role="alert" id={`${id}-error`}>{validation}</p>}
      <button disabled={pending}>Save provider</button><button type="button" disabled={pending} onClick={onCancel}>Cancel editing</button>
    </form>
  </section>;
}
