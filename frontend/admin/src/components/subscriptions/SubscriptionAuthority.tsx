"use client";
import Link from "next/link";
import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { api, ApiError, type ApiOptions } from "../../lib/api/client";
import { copy, type Locale } from "./SubscriptionFilters";

const Requests = createContext<typeof api>(api);
export const useSubscriptionApi = () => useContext(Requests);

/** A mounted subscription view loses all local authority on its first 401. */
export function SubscriptionAuthority({ children, locale }: { children: ReactNode; locale: Locale }) {
  const lifetime = useRef<AbortController | undefined>(undefined);
  const [lost, setLost] = useState(false);
  useEffect(() => () => { lifetime.current?.abort(); lifetime.current = undefined; }, []);
  const request = useCallback(async <T,>(path: string, options: ApiOptions = {}): Promise<T> => {
    lifetime.current ??= new AbortController();
    const authority = lifetime.current;
    const signal = options.signal ? AbortSignal.any([authority.signal, options.signal]) : authority.signal;
    if (signal.aborted) throw new DOMException("Authority unavailable", "AbortError");
    try {
      const result = await api<T>(path, { ...options, signal });
      // Even a transport ignoring abort cannot restore authority with a late success.
      if (signal.aborted) throw new DOMException("Authority unavailable", "AbortError");
      return result;
    } catch (failure) {
      if (!authority.signal.aborted && failure instanceof ApiError && failure.status === 401) {
        authority.abort(); setLost(true);
      }
      throw failure;
    }
  }, []);
  return lost ? <section role="alert"><p>{copy(locale, "Your session ended. Sign in again to manage subscriptions.", "A sua sessão terminou. Inicie sessão novamente para gerir assinaturas.")}</p><Link href="/login">{copy(locale, "Sign in again", "Iniciar sessão novamente")}</Link></section>
    : <Requests.Provider value={request}>{children}</Requests.Provider>;
}
