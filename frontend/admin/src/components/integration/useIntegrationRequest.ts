"use client";
import { useEffect, useRef, useState } from "react";
import { api, errorTitle, type ApiOptions } from "../../lib/api/client";
/** Integration endpoints have no idempotency contract. No automatic network replay. */
export function useIntegrationRequest() {
  const busy = useRef(false); const controller = useRef<AbortController | undefined>(undefined);
  const [pending, setPending] = useState(false); const [error, setError] = useState("");
  useEffect(() => () => controller.current?.abort(), []);
  async function run<T>(path: string, options: ApiOptions, onSuccess: (response: T) => void) {
    if (busy.current) return; busy.current = true; controller.current = new AbortController(); setPending(true); setError("");
    try { const result = await api<T>(path, { ...options, signal: controller.current.signal }); if (!controller.current.signal.aborted) onSuccess(result); }
    catch (failure) { if (!controller.current.signal.aborted) setError(errorTitle(failure)); }
    finally { busy.current = false; if (!controller.current.signal.aborted) setPending(false); }
  }
  return { run, pending, error };
}
