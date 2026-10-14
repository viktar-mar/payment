"use client";
import { useEffect, useState } from "react";
import { api, errorTitle } from "../../lib/api/client";
import type { components } from "../../lib/api/schema";
import { SiteList } from "../../components/SiteList";
import { CreateSiteForm } from "../../components/CreateSiteForm";
export default function SitesPage() {
  const [sites, setSites] = useState<components["schemas"]["SiteResponse"][]>();
  const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    api<components["schemas"]["SiteResponse"][]>("admin/v1/sites", {
      signal: controller.signal,
    })
      .then(setSites)
      .catch((error) => {
        if (!controller.signal.aborted) setError(errorTitle(error));
      });
    return () => controller.abort();
  }, []);
  return (
    <>
      <h1>Sites</h1>
      {error && <p role="alert">{error}</p>}
      {!sites && !error && <p role="status">Loading sites…</p>}
      {sites?.length === 0 && <p>No sites yet.</p>}
      {sites && <SiteList sites={sites} />}
      <CreateSiteForm
        onCreated={(site) => setSites((current) => [...(current ?? []), site])}
      />
    </>
  );
}
