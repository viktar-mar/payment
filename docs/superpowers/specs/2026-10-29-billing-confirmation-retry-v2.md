# Billing PAYMENT v2: an attributable, explicit retry

## Goal and authority

Finish the offline 5B safe-retry gap without reopening a spent browser permission. The user authorized implementation with subagents, tests, independent review and hosted CI, and explicitly prohibited local Docker and Sandbox runs. This is a bounded protocol correction to the original browser-only design: fresh PAYMENT flows can select server confirmation; existing v1 flows and SETUP retain their existing semantics.

The original browser call has no server-recorded Stripe request identity. A current failed Charge, changed timestamp, browser error, or `requires_payment_method` cannot identify that call. A lost acknowledgement or an unresolved challenge must therefore never create permission. This slice supports a positive successor only after a terminal synchronous card decline attributed to the original server confirmation request.

## Required behavior

1. Select `SERVER_CONFIRM_V2` durably and atomically before exposing a client secret for a fresh PAYMENT grant. An already session-bound or consumed v1 grant cannot migrate. A v2 reservation blocks the v1 client-session and browser-confirm endpoints. Concurrent protocol selection has one winner. Keep original v1 descriptors, receipts, HMACs, consumed tombstones and SDK spent latches unchanged.
2. Bind every v2 chain/attempt to the original account, site, customer, subscription, operation, provider step, invoice, allocation and PaymentIntent, plus the current CustomerSession/origin and UTC expiry. Use additive storage, immutable identity and database uniqueness for root grant, predecessor and command idempotency. Lock/recheck authority and local version around provider reads. No provider calls inside a database transaction.
3. Elements collects a ConfirmationToken. Explicit Pay atomically consumes one v2 capability, persists its token digest/parameter fingerprint and stable provider key, then makes exactly one server confirmation call against the original Billing PaymentIntent. Keep the raw token and client secret in request/browser memory only. No raw exception text, provider body, URL or credentials in persistence/logs. Do not enqueue or automatically retry confirmation. Preserve SDK `maxNetworkRetries(0)`.
4. Browser v2 never calls `confirmPayment` or `confirmSetup`; it may call `handleNextAction` once when the original server outcome requires authentication. Only ordinary authoritative Billing observation/cash joins settle payment, journal, callbacks or access. A command response or browser result does not settle them.
5. A retry proof requires the trusted original synchronous `402/card_error` response, its request ID, embedded exact original PI and exact failed Charge; require consistent decline fields. Retrieve that exact Charge and the original invoice/allocation/payment graph. Require owned account/customer/mode/currency/amount, failed terminal Charge, no paid/captured/received cash, and current eligibility. Native status is a fresh eligibility check, never a replacement for dispatch attribution. Unsupported errors or missing evidence produce no proof.
6. An explicit, separately idempotent Retry command consumes one durable verified proof and creates at most one fresh successor in the same chain, on the same original PI. Revalidate provider graph, session, consent/policy/account, subscription/operation and expiry. Preserve predecessor consumption and receipts. No new invoice, subscription, PI or `invoice.pay`. A replay of Confirm never returns dispatch permission or invokes Stripe. A replay of Retry returns only its same durable result and cannot mint another successor. GET/status/polling never confirm or allocate a successor.
7. A lost browser ACK after a durably recorded proven decline can be recovered by read-only status and a new explicit Retry. A network timeout, missing original receipt, process crash, outstanding 3DS, canceled/paid/processing graph, legacy consumption or SETUP cannot acquire a successor from later provider state. The old no-replay guarantees remain intact.
8. SDK provides a usable explicit Retry after a proven decline, including reading the durable result after a lost HTTP ACK. It retires old provider objects/secret references, uses the fresh server-issued attempt/capability, checks identity/session/visibility/expiry before dispatch, and never restores a spent v1 permission. Backend wire validation and SDK parsing are strict; sensitive responses are CustomerSession-only and `Cache-Control: no-store`.

## Scope and validation

The positive scope is fresh PAYMENT v2 synchronous card declines. Legacy consumed grants, SETUP retries and unbound asynchronous 3DS failures are deliberately unresolved. Native Billing interoperability remains NOT_RUN and full 5B remains NOT_ACCEPTED until separately authorized Sandbox acceptance. This task does not build clock runners or diagnostic frameworks.

Required tests cover protocol-selection races and legacy exclusions; one Confirm winner; exact synchronous decline attribution versus unrelated/current failure; wrong account/PI/Charge/invoice/currency/amount/mode; cash/processing/canceled/drift; unknown/lost ACK before and after persisted receipt; one successor under concurrency and idempotency; no provider mutation on reads; immutable receipts and no raw secrets; SDK token/server-confirm/3DS flow, explicit retry, authority loss and existing v1 safety. Use the pinned Stripe SDK with owned loopback HTTP contract fixtures. Database integration and complete regression run only in hosted synthetic CI.

## Primary references

- [Stripe PaymentIntent confirmation](https://docs.stripe.com/api/payment_intents/confirm)
- [ConfirmationToken collection](https://docs.stripe.com/js/confirmation_tokens/create_confirmation_token)
- [ConfirmationToken object](https://docs.stripe.com/api/confirmation_tokens/object)
- [Stripe error contract](https://docs.stripe.com/api/errors)
- [Browser next-action handling](https://docs.stripe.com/js/payment_intents/handle_next_action)

These describe primitives; offline fixtures do not establish native acceptance or undocumented failure-attribution guarantees.
