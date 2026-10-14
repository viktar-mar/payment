"use client";
import { useEffect, useId, useRef, useState } from "react";
export function OneTimeSecret({ label, secret, onDismiss }: { label: string; secret: string; onDismiss: () => void }) {
  const [revealed, setRevealed] = useState(false); const [dismissed, setDismissed] = useState(false); const [message, setMessage] = useState("");
  const input = useRef<HTMLInputElement>(null); const reveal = useRef<HTMLButtonElement>(null); const mounted = useRef(false); const cleared = useRef(false); const wasRevealed = useRef(false); const latestDismiss = useRef(onDismiss); const id = useId();
  useEffect(() => { latestDismiss.current = onDismiss; }, [onDismiss]);
  useEffect(() => { mounted.current = true; return () => {
    mounted.current = false;
    // React may replay effects without removing the component. This callback
    // captures refs only, never plaintext; real unmount clears parent state.
    queueMicrotask(() => { if (!mounted.current && !cleared.current) { cleared.current = true; latestDismiss.current(); } });
  }; }, []);
  useEffect(() => { if (revealed) input.current?.focus(); else if (wasRevealed.current) reveal.current?.focus(); wasRevealed.current = revealed; }, [revealed]);
  if (dismissed) return null;
  async function copy() {
    try { await navigator.clipboard.writeText(secret); if (mounted.current) setMessage("Copied. Store this secret securely."); }
    catch { if (mounted.current) setMessage("Unable to copy. Reveal and copy manually."); }
  }
  return <section className="surface" aria-label={`One-time ${label}`}>
    <h3>{label}: shown once</h3><p>Store securely before dismissal. This page cannot retrieve the secret again.</p>
    {!revealed ? <button ref={reveal} onClick={() => setRevealed(true)}>Reveal {label}</button> : <><label htmlFor={id}>{label} value</label><input ref={input} id={id} value={secret} readOnly autoComplete="off" spellCheck={false} /><button onClick={() => { setRevealed(false); setMessage(""); }}>Hide {label}</button></>}
    <button onClick={copy}>Copy {label}</button>{" "}<button onClick={event => {
      const section = event.currentTarget.closest("section"); const fallback = section?.parentElement?.querySelector<HTMLElement>("h2");
      cleared.current = true; setDismissed(true); setRevealed(false); setMessage(""); onDismiss(); fallback?.focus();
    }}>Dismiss {label}</button>
    <p role="status" aria-live="polite">{message}</p>
  </section>;
}
