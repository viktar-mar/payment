import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { PaymentDetails } from "./PaymentDetails";
import { details } from "./test-fixtures";
afterEach(() => vi.unstubAllGlobals());
it("shows immutable subscription context on direct entry and preserves independent accepted refunds", async () => {
  const subscription="40000000-0000-0000-0000-000000000004", bill="80000000-0000-0000-0000-000000000008"; const methods:string[]=[];
  vi.stubGlobal("fetch",async (_path:string,init:RequestInit)=>{methods.push(init.method??"GET");return init.method==="POST"?Response.json({refund_id:"refund-safe",status:"PROCESSING"},{status:202}):Response.json({...details,payment:{...details.payment,source:"SUBSCRIPTION",subscription_id:subscription,billing_record_id:bill,subscription_billing_kind:"PRORATION",subscription_period_start:"2026-10-12T12:00:00Z",subscription_period_end:"2026-11-12T12:00:00Z"}});});
  render(<PaymentDetails paymentId="payment-1"/>); expect(await screen.findByRole("link",{name:subscription})).toHaveAttribute("href",`/subscriptions/${subscription}?site_id=${details.payment.site_id}`);
  expect(screen.getByText(bill)).toBeInTheDocument();expect(screen.getByText("PRORATION")).toBeInTheDocument();expect(screen.getByText("Refunds do not cancel this subscription.")).toBeInTheDocument();
  const user=userEvent.setup();await user.click(screen.getByRole("button",{name:"Refund"}));await user.click(screen.getByRole("button",{name:"Confirm refund"}));expect(await within(screen.getByRole("dialog")).findByText("Refund accepted; awaiting confirmation.")).toBeInTheDocument();expect(screen.queryByText("Subscription cancelled")).not.toBeInTheDocument();expect(methods.filter(method=>method==="POST")).toHaveLength(1);
});
it("links Donation and Customer context alongside existing Payment actions without requesting a message", async () => {
  const id="10000000-0000-0000-0000-000000000001",customer="40000000-0000-0000-0000-000000000004",campaign="30000000-0000-0000-0000-000000000003";
  const urls:string[]=[];vi.stubGlobal("fetch",async(url:string)=>{urls.push(url);return Response.json({...details,payment:{...details.payment,source:"DONATION",donation_id:id,customer_id:customer,campaign_id:campaign,campaign_key:"help-now",campaign_version:2}});});
  render(<PaymentDetails paymentId="payment-1"/>);expect(await screen.findByRole("link",{name:id})).toHaveAttribute("href",`/donations/${id}`);
  expect(screen.getByRole("link",{name:customer})).toHaveAttribute("href",`/customers/${customer}?site_id=${details.payment.site_id}`);
  expect(screen.getByText("help-now")).toBeInTheDocument();expect(screen.getByRole("button",{name:"Refund"})).toBeInTheDocument();expect(screen.getByRole("button",{name:"Reconcile"})).toBeInTheDocument();
  expect(urls).toEqual(["/api/backend/v1/admin/payments/payment-1"]);
});
it("renders every safe projection section and ordered facts without internal fields", async () => {
  vi.stubGlobal("fetch", async () => Response.json({ ...details, metadata: "PRIVATE METADATA", operation_key: "PRIVATE KEY" }));
  render(<PaymentDetails paymentId="payment-1" />);
  await screen.findByRole("heading", { name: "Payment payment-1" });
  for (const name of ["Payment summary", "Attempts", "Event timeline", "Refunds", "Outbox events", "Delivery attempts"]) expect(screen.getByRole("region", { name })).toBeInTheDocument();
  expect(within(screen.getByRole("region", { name: "Payment summary" })).getByText("1000 minor units EUR")).toBeInTheDocument();
  expect(screen.getByText("account-1")).toBeInTheDocument();
  expect(screen.getByText("price-1")).toBeInTheDocument();
  const facts = within(screen.getByRole("region", { name: "Event timeline" })).getAllByRole("listitem");
  expect(facts[0]).toHaveTextContent("PAYMENT_CREATED"); expect(facts[1]).toHaveTextContent("PAYMENT_SUCCEEDED");
  expect(screen.getByRole("region", { name: "Delivery attempts" })).toHaveTextContent("outbox-1");
  expect(screen.queryByText(/PRIVATE/)).not.toBeInTheDocument();
});
it("refreshes after accepted refund without claiming confirmation and restores focus if refund trigger vanishes", async () => {
  let reads = 0;
  vi.stubGlobal("fetch", async (_path: string, init: RequestInit) => {
    if (init.method === "POST") return Response.json({ refund_id: "new-refund", status: "PENDING" }, { status: 202 });
    reads++; return Response.json(reads === 1 ? details : { ...details, payment: { ...details.payment, status: "REFUNDED", refundable_amount_minor: 0 } });
  });
  render(<PaymentDetails paymentId="payment-1" />); const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "Refund" })); await user.click(screen.getByRole("button", { name: "Confirm refund" }));
  await waitFor(() => expect(reads).toBe(2)); expect(within(screen.getByRole("dialog")).getByRole("status")).toHaveTextContent("Refund accepted; awaiting confirmation"); expect(screen.queryByRole("button", { name: "Refund" })).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Close" })); expect(screen.getByRole("heading", { name: "Payment payment-1" })).toHaveFocus();
});
it("validates fake sequence and existing refund, and changes callback retry keys after mode edits", async () => {
  const requests: { path: string; body: unknown; key: string | null }[] = [];
  vi.stubGlobal("fetch", async (path: string, init: RequestInit) => { if (init.method === "POST") { requests.push({ path, body: JSON.parse(init.body as string), key: new Headers(init.headers).get("Idempotency-Key") }); throw new TypeError("network"); } return Response.json(details); });
  render(<PaymentDetails paymentId="payment-1" />); const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "Simulate fake event" }));
  await user.clear(screen.getByLabelText("Sequence")); await user.type(screen.getByLabelText("Sequence"), "0"); await user.click(screen.getByRole("button", { name: "Confirm fake event" })); expect(screen.getByRole("alert")).toHaveTextContent("integer sequence"); expect(requests).toHaveLength(0);
  await user.clear(screen.getByLabelText("Sequence")); await user.type(screen.getByLabelText("Sequence"), "1"); await user.selectOptions(screen.getByLabelText("Target"), "REFUND"); await user.click(screen.getByRole("button", { name: "Confirm fake event" })); expect(screen.getByRole("alert")).toHaveTextContent("Select an existing refund"); expect(requests).toHaveLength(0);
  await user.keyboard("{Escape}"); await user.click(screen.getByRole("button", { name: "Retry callback outbox-1" })); await user.click(screen.getByRole("button", { name: "Confirm callback retry" })); await screen.findByRole("alert");
  expect(requests[0].body).toEqual({ mode: "SNAPSHOT" }); await user.selectOptions(screen.getByLabelText("Retry mode"), "CURRENT"); await user.selectOptions(screen.getByLabelText("Retry mode"), "SNAPSHOT"); await user.click(screen.getByRole("button", { name: "Confirm callback retry" })); await waitFor(() => expect(requests).toHaveLength(2)); expect(requests[1].key).not.toBe(requests[0].key);
});
it("aborts detail reads on unmount, displays safe errors and empty sections", async () => {
  let signal: AbortSignal | undefined;
  vi.stubGlobal("fetch", (_url: string, init: RequestInit) => { signal = init.signal as AbortSignal; return new Promise(() => {}); });
  const view = render(<PaymentDetails paymentId="payment-1" />); expect(screen.getByRole("status")).toHaveTextContent("Loading payment"); view.unmount(); expect(signal?.aborted).toBe(true);
  vi.stubGlobal("fetch", async () => Response.json({ title: "Payment not found", detail: "PRIVATE" }, { status: 404 }));
  const errorView = render(<PaymentDetails paymentId="payment-1" />); expect(await screen.findByRole("alert")).toHaveTextContent("Payment not found"); errorView.unmount();
  vi.stubGlobal("fetch", async () => Response.json({ payment: details.payment, attempts: [], events: [], refunds: [], outbox_events: [], deliveries: [] }));
  render(<PaymentDetails paymentId="payment-1" />); expect(await screen.findByText("No attempts yet.")).toBeInTheDocument();
});
it("confirms reconciliation with a retry key and refreshes the projection", async () => {
  const requests: { path: string; key: string | null; body: unknown }[] = []; let failure = true;
  vi.stubGlobal("fetch", async (path: string, init: RequestInit) => {
    if (init.method === "POST") { requests.push({ path, key: new Headers(init.headers).get("Idempotency-Key"), body: init.body }); if (failure) { failure = false; throw new TypeError("network"); } }
    return Response.json(details);
  });
  render(<PaymentDetails paymentId="payment-1" />); const trigger = await screen.findByRole("button", { name: "Reconcile" }); const user = userEvent.setup(); await user.click(trigger);
  expect(screen.getByRole("button", { name: "Cancel" })).toHaveFocus(); await user.click(screen.getByRole("button", { name: "Confirm reconcile" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Unable to connect"); await user.click(screen.getByRole("button", { name: "Retry reconcile" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  expect(requests[0].key).toBeTruthy(); expect(requests[1].key).toBe(requests[0].key); expect(trigger).toHaveFocus();
  await user.click(trigger); await user.click(screen.getByRole("button", { name: "Confirm reconcile" })); await waitFor(() => expect(requests).toHaveLength(3)); expect(requests[2].key).not.toBe(requests[1].key);
});
it("sends callback CURRENT retry and scoped fake Payment/Refund events", async () => {
  const requests: { path: string; body: unknown; key: string | null }[] = [];
  vi.stubGlobal("fetch", async (path: string, init: RequestInit) => { if (init.method === "POST") requests.push({ path, body: JSON.parse(init.body as string), key: new Headers(init.headers).get("Idempotency-Key") }); return Response.json(details); });
  render(<PaymentDetails paymentId="payment-1" />); const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "Retry callback outbox-1" }));
  await user.selectOptions(screen.getByLabelText("Retry mode"), "CURRENT"); await user.click(screen.getByRole("button", { name: "Confirm callback retry" }));
  await waitFor(() => expect(requests).toHaveLength(1)); expect(requests[0]).toMatchObject({ path: "/api/backend/v1/admin/outbox/outbox-1/retry", body: { mode: "CURRENT" } });
  await user.click(screen.getByRole("button", { name: "Simulate fake event" })); await user.selectOptions(screen.getByLabelText("Event type"), "PAYMENT_SUCCEEDED"); await user.click(screen.getByRole("button", { name: "Confirm fake event" }));
  await waitFor(() => expect(requests).toHaveLength(2)); expect(requests[1].body).toEqual({ target: "PAYMENT", event_type: "PAYMENT_SUCCEEDED", sequence: 1 });
  await user.click(screen.getByRole("button", { name: "Simulate fake event" })); await user.selectOptions(screen.getByLabelText("Target"), "REFUND");
  expect(screen.queryByRole("option", { name: "PAYMENT_SUCCEEDED" })).not.toBeInTheDocument(); await user.selectOptions(screen.getByLabelText("Refund"), "refund-1"); await user.selectOptions(screen.getByLabelText("Event type"), "REFUND_FAILED");
  await user.click(screen.getByRole("button", { name: "Confirm fake event" })); await waitFor(() => expect(requests).toHaveLength(3)); expect(requests[2].body).toEqual({ target: "REFUND", event_type: "REFUND_FAILED", sequence: 1, refund_id: "refund-1" });
  expect(new Set(requests.map(r => r.key)).size).toBe(3);
});
it("hides fake controls outside FAKE and refund controls without eligible money/state", async () => {
  vi.stubGlobal("fetch", async () => Response.json({ ...details, payment: { ...details.payment, provider: "STRIPE", status: "PROCESSING", refundable_amount_minor: 0 } }));
  render(<PaymentDetails paymentId="payment-1" />); await screen.findByRole("heading", { name: "Payment payment-1" });
  expect(screen.queryByRole("button", { name: "Simulate fake event" })).not.toBeInTheDocument(); expect(screen.queryByRole("button", { name: "Refund" })).not.toBeInTheDocument();
});
