export const payment = {
  payment_id: "payment-1", site_id: "site-1", payment_account_id: "account-1",
  source: "CATALOG" as const, price_id: "price-1", price_key: "one-time", price_version: 3,
  external_order_id: "order & one", amount_minor: 1000, currency: "EUR", tax_mode: "INCLUSIVE" as const,
  tax_amount_minor: 0, status: "SUCCEEDED" as const, provider: "FAKE" as const,
  refundable_amount_minor: 700, refunded_amount_minor: 300,
  created_at: "2026-10-04T12:00:00Z", updated_at: "2026-10-04T13:00:00Z",
};
export const details = {
  payment,
  attempts: [{ id: "attempt-1", attempt_number: 1, status: "SUCCEEDED" as const, provider_intent_reference: "safe-payment-reference", payment_method_family: "CARD", result_code: "OK", created_at: payment.created_at, updated_at: payment.updated_at }],
  events: [{ id: "event-2", sequence: 2, event_type: "PAYMENT_SUCCEEDED" as const, source: "PROVIDER_EVENT" as const, amount_minor: 1000, currency: "EUR", provider_event_id: "safe-event-reference", provider_object_id: "safe-payment-reference", request_id: "request-1", occurred_at: payment.updated_at, received_at: payment.updated_at }, { id: "event-1", sequence: 1, event_type: "PAYMENT_CREATED" as const, source: "API" as const, occurred_at: payment.created_at, received_at: payment.created_at }],
  refunds: [{ id: "refund-1", amount_minor: 300, currency: "EUR", status: "SUCCEEDED" as const, provider_refund_reference: "safe-refund-reference", reason_code: "OTHER" as const, created_at: payment.created_at, updated_at: payment.updated_at }],
  outbox_events: [{ id: "outbox-1", callback_type: "payment.succeeded" as const, status: "DEAD" as const, attempt_count: 6, next_attempt_at: payment.updated_at, created_at: payment.created_at, updated_at: payment.updated_at }],
  deliveries: [{ id: "delivery-1", outbox_event_id: "outbox-1", attempt_number: 6, delivery_mode: "SNAPSHOT_RETRY" as const, status: "FAILED" as const, http_status: 503, error_category: "HTTP_STATUS" as const, started_at: payment.created_at, finished_at: payment.updated_at }],
};
