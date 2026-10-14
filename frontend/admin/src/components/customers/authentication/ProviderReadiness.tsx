import { Fragment } from "react";
import type { components } from "../../../lib/api/schema";

export function ProviderReadiness({ provider }: { provider: components["schemas"]["ProviderResponse"] }) {
  const token = provider.mode === "OIDC_TOKEN" || provider.mode === "SITE_TOKEN";
  const fields = [["Status", provider.status], ["Version", provider.version], ["Mode", provider.mode], ["Readiness", provider.readiness],
    ...(token ? [["Issuer", provider.issuer], ["Audiences", provider.audiences?.join(", ")], ["Algorithms", provider.allowed_algorithms?.join(", ")], ["Key source", provider.jwks_source_type], ["Last successful refresh", provider.last_successful_refresh_at], ["JWKS refresh state", provider.jwks_refresh_state]] : []),
    ...(provider.mode === "EMAIL_OTP" ? [["Sender reference", provider.sender_ref], ["Template key", provider.template_key]] : [])];
  return <>
    <dl className="details">{fields.map(([name, value]) => <Fragment key={name}><dt>{name}</dt><dd>{value ?? "—"}</dd></Fragment>)}</dl>
    {token && <><h3>Public key metadata</h3>
    {!provider.keys?.length && <p>No public key metadata.</p>}
    <ul>{provider.keys?.map((key, index) => <li key={index}><dl className="details">{[["Key ID", key.kid], ["Key type", key.kty], ["Algorithm", key.algorithm], ["Fingerprint", key.fingerprint]].map(([name, value]) => <Fragment key={name}><dt>{name}</dt><dd>{value ?? "—"}</dd></Fragment>)}</dl></li>)}</ul></>}
  </>;
}
