"use client";
import { useEffect, useRef, useState } from "react";
import { api, ApiError, errorTitle, type ApiOptions } from "../../lib/api/client";

/** One mounted intent owns one in-memory key. Only an unchanged transport retry keeps it. */
export function useIntentMutation(request: typeof api = api) {
  const key = useRef<{ fingerprint: string; value: string } | undefined>(undefined);
  const busy = useRef(false);
  const controller = useRef<AbortController | undefined>(undefined);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [errorStatus, setErrorStatus] = useState<number>();
  const [retry, setRetry] = useState(false);
  useEffect(() => () => { controller.current?.abort(); key.current = undefined; }, []);
  function changed() { key.current = undefined; setRetry(false); setError(""); setErrorStatus(undefined); }
  async function run<T>(path: string, options: ApiOptions, onAccepted: (result: T) => void) {
    if (busy.current) return;
    const fingerprint = JSON.stringify([path, options.method ?? "POST", options.body]);
    if (key.current?.fingerprint !== fingerprint) key.current = { fingerprint, value: crypto.randomUUID() };
    busy.current = true; controller.current = new AbortController(); setPending(true); setError(""); setErrorStatus(undefined);
    try {
      const result = await request<T>(path, { ...options, idempotencyKey: key.current.value, signal: controller.current.signal });
      if (controller.current.signal.aborted) return;
      key.current = undefined; setRetry(false); onAccepted(result);
    } catch (failure) {
      if (controller.current.signal.aborted) return;
      const transport = failure instanceof ApiError && failure.outcome === "ambiguous";
      if (!transport) key.current = undefined;
      setRetry(transport); setError(errorTitle(failure)); setErrorStatus(failure instanceof ApiError ? failure.status : undefined);
    } finally {
      busy.current = false;
      if (!controller.current.signal.aborted) setPending(false);
    }
  }
  return { run, changed, pending, error, errorStatus, retry };
}
