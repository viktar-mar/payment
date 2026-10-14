import type { components } from "../../lib/api/schema";
export function PaymentTimeline({ events }: { events: components["schemas"]["Event"][] }) {
  return <section className="surface" aria-label="Event timeline"><h2>Event timeline</h2>
    {!events.length && <p>No events yet.</p>}
    <ol className="space-y-4">{[...events].sort((a, b) => (a.sequence ?? 0) - (b.sequence ?? 0)).map((event, index) => <li key={event.id ?? index}>
      <h3>{event.sequence} · {event.event_type}</h3>
      <dl className="details">{([ ["Event ID", event.id], ["Source", event.source], ["Amount", event.amount_minor === undefined || event.amount_minor === null ? undefined : `${event.amount_minor} minor units ${event.currency ?? ""}`], ["Provider event", event.provider_event_id], ["Provider object", event.provider_object_id], ["Request ID", event.request_id], ["Occurred", event.occurred_at], ["Received", event.received_at] ] as const).map(([label, value]) => <div className="detail-pair" key={label}><dt>{label}</dt><dd>{value ?? "—"}</dd></div>)}</dl>
    </li>)}</ol>
  </section>;
}
