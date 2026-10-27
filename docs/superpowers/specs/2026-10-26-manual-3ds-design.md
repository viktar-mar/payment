# Manual 3DS Sandbox acceptance — bounded offline preparation

The user requests an offline manual3DS mode with subagents, tests and independent review, preserving server payment/journal/callback proof. Docker and Sandbox must not run. Existing manual-payment architecture and its design remain binding except the explicit additions below. This is preparation, not acceptance or financial authorization.

## Approach

Extend the existing manual-payment controller/gateway/page rather than copy them. Add optional `PAYMENT_STRIPE_SANDBOX_MANUAL_3DS=true`, accepted only alongside the existing three required manual-payment opt-ins. Missing/empty/false retains ordinary manual-payment behavior; every other value/accessor is rejected. Canonical selected env preserves true only when selected. The ordinary controller CLI snapshots this name explicitly; no new entrypoint or dependencies.

Controller/gateway receive optional `threeDSecure:true` only for this selected mode. The gateway's protected session response adds exactly `X-Payment-Scenario: 3DS` for this mode; its Checkout body, routes, manifest/CSP/cookie/Host/Origin/expiry/one-shot authorization remain unchanged. No URL flag, browser query, storage or user supplied body may choose the scenario. The page reads the finite same-origin header during its existing session exchange; absent header means ordinary payment, unexpected present value fails closed before mounting.

Manual3DS instructions use test card `4000 0025 0000 3155`, future expiry and synthetic CVC; onePay, then human completes the genuine Stripe challenge immediately. Do not search/control/read provider iframe DOM, PAN/CVC, URLs or raw errors. Before session selection, show neutral instructions without a conflicting normal-card instruction. After ordinary session selection retain normal4242instructions and behavior. The3DS case keeps its widget/provider generation connected and visible while the challenge is pending or the SDK is checking/processing/requires_action; terminal result, post-attempt ready, expiry or close removes it. Ordinary manual payment behavior remains unchanged. Existing SDK one-shot lease and guards continue to deny every second confirmation; no SDK/public API change.

## Evidence and time

Preserve existing binding/baseline/signed event/journal/outbox/callback/charge capture checks. The CLI's final read-only inspection additionally requires the same captured charge's `payment_method_details.card.three_d_secure` to have `result=authenticated`, `authentication_flow=challenge`, and a string2.x.xversion. Missing/malformed/frictionless/non-authenticated data must remain unverified/PENDING, never ordinary success promoted to3DS. No additional Stripe API request is needed: the existing exact Intent expands latest_charge. The3DS inspector returns the existing captured/eventVerified booleans plus `threeDSecureAuthenticated:true`; ordinary result remains exactly its existing shape.

Reports retain version1 and ordinary MANUAL_PAYMENT shape. Only selected3DS uses `mode=MANUAL_3DS` with additional `three_ds_verified:boolean`; SUCCEEDED requires every old proof/cleanup and this new proof true. False means unverified, not absence. Ordinary reports cannot carry that field; strict parsing, hostile descriptors and4096byte/redaction bounds remain. Browser closure stays NOT_OWNED.

The existing selected-mode server verifier must recognize legitimate intermediate `CUSTOMER_ACTION_REQUIRED` journal history and its joined `payment.requires_action` outbox/receiver records before the unique success. Admit this only for selected 3DS after the original permit; preserve original identities, provider-event correlation, final signed success/callback proof, capture and finite limits. Ordinary and pre-permit allowlists remain unchanged. The SDK can render a repeated nonterminal `REQUIRES_ACTION` snapshot as widget `ready`; selected post-attempt ready must retire the form once with the existing neutral server-report message. This is no browser success claim and cannot trigger another Pay/Resume or new polling. Initial ready still permits the sole Pay, and a pending challenge remains connected. Existing terminal error/expiry/close paths still retire it.

Do not renew/increase deadlines:45min original authority, existing gateway/page≤5min, provider/result verification≤45s/30probes after permission, cleanup≤original+2min and existing per-operation ceilings. Human must complete3DS promptly within that existing verification window. Failure/timeout leaves PENDING and requires new explicit authority, not anotherPay. No old payment/refund retry, reconciliation, new diagnostic platform or fallback selector. No real runtime in this task.

## Verification and delivery

Use existing synthetic dependencies and intercepted Chromium where relevant. Meaningful RED before behavior changes; targeted/regression green, infrastructure Docker-free selection, complete demo native support, demo typecheck/build, SDK one-shot regression and ordinary Playwright discovery only. Docker-backed tests and realSandbox are excluded even if the general suite contains them. No Java/backend/schema/dependency/production changes.

Independent task reviews and final combined review are required. Preserve pre-existing acceptance-document outcome and ignored/private evidence. Keep stage5A not accepted. A future run needs fresh explicit approval; documentation must not present a live URL or current payment.

Executor choices: reuse same entry/page/routes with finite header; require challenge/authenticated/version2 server proof; keep existing45s window rather than silently prolonging financial authority. These are implementation choices within the requested manual3DS scope, not claims about the prior failed issuer DOM.
