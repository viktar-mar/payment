"use client";
import { useEffect, useId, useRef, type ReactNode } from "react";

/** Captures the exact trigger before focusing; a refreshed-away trigger falls back to the detail heading. */
export function ActionDialog({ title, pending, onClose, children, fallbackSelector = "[data-payment-heading]" }: {
  title: string; pending: boolean; onClose: () => void; children: ReactNode; fallbackSelector?: string;
}) {
  const id = useId();
  const root = useRef<HTMLDivElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    cancel.current?.focus();
    return () => {
      if (trigger?.isConnected) trigger.focus();
      else document.querySelector<HTMLElement>(fallbackSelector)?.focus();
    };
  }, [fallbackSelector]);
  return <div ref={root} role="dialog" tabIndex={-1} aria-modal="true" aria-labelledby={id} className="dialog"
    onKeyDown={event => {
      if (event.key === "Escape" && !pending) { event.preventDefault(); onClose(); }
      if (event.key !== "Tab") return;
      // Preserve DOM order across mixed control types (including selector engines which group comma matches).
      const controls = Array.from(event.currentTarget.querySelectorAll<HTMLElement>("*")).filter(element => element.matches('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href], [tabindex="0"]'));
      const first = controls[0]; const last = controls.at(-1);
      if (!first) { event.preventDefault(); root.current?.focus(); }
      else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }}>
    <div className="surface"><h2 id={id}>{title}</h2>
      <button ref={cancel} type="button" disabled={pending} onClick={onClose}>Cancel</button>
      {children}
    </div>
  </div>;
}
