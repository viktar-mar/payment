"use client";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useState, type ReactNode } from "react";
import { api, errorTitle } from "../lib/api/client";
export function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  async function signOut() {
    setPending(true);
    setError("");
    try {
      await api("admin/v1/auth/session", { method: "DELETE" });
      router.replace("/login");
      router.refresh();
    } catch (error) {
      setError(errorTitle(error));
    } finally {
      setPending(false);
    }
  }
  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <header className="border-b border-slate-200 bg-white">
        <nav
          aria-label="Main"
          className="mx-auto flex max-w-5xl flex-wrap items-center gap-6 px-6 py-4"
        >
          <span className="font-semibold">Payment admin</span>
          <Link href="/sites" prefetch={pathname === "/login" ? false : undefined}>Sites</Link>
          <Link href="/payments" prefetch={pathname === "/login" ? false : undefined}>Payments</Link>
          <Link href="/provider-operations" prefetch={pathname === "/login" ? false : undefined}>Provider operations</Link>
          <Link href="/customers" prefetch={pathname === "/login" ? false : undefined}>Customers</Link>
          <Link href="/donations" prefetch={pathname === "/login" ? false : undefined}>Donations</Link>
          <Link href="/subscriptions" prefetch={pathname === "/login" ? false : undefined}>Subscriptions / Assinaturas</Link>
          {pathname === "/login" ? (
            <Link href="/login">Sign in</Link>
          ) : (
            <button disabled={pending} onClick={signOut}>
              {pending ? "Signing out…" : "Sign out"}
            </button>
          )}
          {error && <p role="alert">{error}</p>}
        </nav>
      </header>
      <main id="main" className="mx-auto max-w-5xl space-y-6 px-4 py-8 sm:px-6">
        {children}
      </main>
    </>
  );
}
