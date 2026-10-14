"use client";
import Link from "next/link";
import { Fragment, useEffect, useRef, useState } from "react";
import type { components } from "../../lib/api/schema";
import { api, errorTitle } from "../../lib/api/client";
import { AnonymizeCustomerDialog } from "./AnonymizeCustomerDialog";
import { validUuid } from "./context";

export function CustomerDetails(props: { siteId: string; customerId: string }) {
  if (!validUuid(props.siteId)) return <p role="alert">Provide a valid Site UUID in site_id.</p>;
  if (!validUuid(props.customerId)) return <p role="alert">Provide a valid Customer UUID.</p>;
  return <SiteCustomerDetails key={`${props.siteId}/${props.customerId}`} {...props} />;
}
function SiteCustomerDetails({ siteId, customerId }: { siteId: string; customerId: string }) {
  const [customer, setCustomer] = useState<components["schemas"]["CustomerResponse"]>();
  const [error, setError] = useState(""); const [revision, setRevision] = useState(0);
  const [dialog, setDialog] = useState(false); const [anonymized, setAnonymized] = useState(false);
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    const controller = new AbortController();
    api<components["schemas"]["CustomerResponse"]>(`v1/admin/sites/${siteId}/customers/${customerId}`, { signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) { setCustomer(result); setError(""); } })
      .catch(failure => { if (!controller.signal.aborted) setError(errorTitle(failure)); });
    return () => controller.abort();
  }, [siteId, customerId, revision]);
  function refreshAfterAnonymization() {
    setCustomer(undefined); setError(""); setAnonymized(true); setDialog(false); setRevision(value => value + 1);
  }
  return <div className="space-y-6">
    <Link href={`/customers?site_id=${siteId}`}>← Customers for this Site</Link>
    <h1 ref={heading} tabIndex={-1} data-customer-heading>Customer {customerId}</h1>
    {anonymized && <p role="status">Customer anonymized. Financial facts retained.</p>}
    {error && <p role="alert">{error}</p>}
    {!customer && !error && <p role="status">Loading customer…</p>}
    {customer && <>
      <section className="surface" aria-label="Customer summary"><h2>Customer summary</h2>
        <dl className="details">{[["Customer UUID", customer.customer_id], ["Site UUID", customer.site_id], ["Status", customer.status], ["Masked email", customer.masked_email], ["Created", customer.created_at], ["Updated", customer.updated_at], ["Anonymized", customer.anonymized_at]].map(([name, value]) => <Fragment key={name}><dt>{name}</dt><dd>{value ?? "—"}</dd></Fragment>)}</dl>
      </section>
      <section className="surface" aria-label="Customer identities"><h2>Identities</h2>
        {!customer.identities?.length && <p>No identities.</p>}
        <ul>{customer.identities?.map((identity, index) => <li key={index}><dl className="details">{[["Mode", identity.mode], ["Issuer", identity.issuer_key], ["Created", identity.created_at], ["Verified", identity.verified_at], ["Last used", identity.last_used_at]].map(([name, value]) => <Fragment key={name}><dt>{name}</dt><dd>{value ?? "—"}</dd></Fragment>)}</dl></li>)}</ul>
      </section>
      <Link href={`/payments?site_id=${siteId}`}>All payments for this Site</Link>
      {customer.status === "ACTIVE" && !anonymized && <p><button onClick={() => setDialog(true)}>Anonymize customer</button></p>}
    </>}
    {dialog && <AnonymizeCustomerDialog siteId={siteId} customerId={customerId} onClose={() => setDialog(false)} onAnonymized={refreshAfterAnonymization} />}
  </div>;
}
