# Owned method browser confirmation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prepare one authenticated original browser SETUP confirmation for the persisted owned method transition.

**Architecture:** Reuse the existing subscription Elements UI. Add only the explicit persisted issuer/consumer proof necessary to bind one original browser permission to its exact owned-method reservation; preserve observer/FactService completion and ordinary denial.

**Tech Stack:** Existing Java25, PostgreSQL/JDBC, stripe-java and TypeScript SDK; cached pure tests locally and fresh hosted synthetic integration.

**Spec:** docs/superpowers/specs/2026-11-07-owned-method-browser-design.md

## Global Constraints

- No local Docker/Sandbox/provider account call/financial operation/Python/install/SQL/application/server/browser/socket/old operation/DISPATCHING/key/operational archive access. No default Maven discovery. Explicit audited pure tests locally; fresh hosted integration is a separate authorized CI gate.
- Existing isolated worktree, source baseline32a0654, root owns Git. Sequential source writers; no worker Git mutation or spawning. All edits via apply_patch. Preserve reports and worktree; no merge, PR or cleanup.
- Ordinary clock-owner rejection and prior receipt predicates remain closed. No fabricated SETUP success, broad capability bypass, native launch or grace-time producer. Source/hosted synthetic evidence grants no native acceptance.

## Review Focus

- A different session for the same customer cannot inherit the consenting browser: Task1 enrollment and Task2 authority negatives.
- Selecting/consuming after lock waits must use current UTC, not a stale pre-lock instant: Task1 hosted expired-after-lock and Task2 boundary tests.
- A lost/late GET or consumed root cannot mint another browser permission: Task2 one-read/release/replay tests and Task3 one-click SDK tests.
- Enrolled binding and METHOD writes cannot bypass the browser or downgrade into ordinary V35: Task1 direct-write guards and Task3 authentic composition; non-enrolled regressions retained.
- Hidden→visible, logout, abort or PAYMENT flow confusion cannot dispatch SETUP/payment work: Task3 actual SDK handoff tests.

### Task 1: Atomic browser enrollment and durable one-shot receipts

**Files:** Create `backend/src/main/java/dev/genericpayments/platform/provider/application/model/OwnedMethodBrowser.java`, persistence companion `provider/adapter/out/persistence/JdbcOwnedMethodBrowserStore.java` under that Java root, and `backend/src/main/resources/db/migration/V36__stripe_billing_owned_method_browser.sql`. Modify `provider/application/port/out/OwnedFailedRenewalMethodStore.java`, `provider/adapter/out/persistence/JdbcOwnedFailedRenewalMethodStore.java`, `provider/application/service/OwnedFailedRenewalMethodService.java` and narrowly the existing provider-step guard where needed. Tests live in matching application/persistence packages; update only migration registries that enumerate versions.

**Interfaces:** The model has these redacted immutable records (Setup means `SubscriptionBillingProofStore.Setup`, Reservation is the existing method model):

```java
Enrollment(UUID sessionId, String origin, Instant sessionExpiresAt)
Pending(Reservation reservation, Enrollment enrollment, PaymentAccount account,
    Subscription subscription, SubscriptionOperation operation, CustomerBinding binding,
    Setup setup, ProviderOperation setupStep)
Root(UUID id, UUID reservationId, UUID setupOperation, UUID setupStep, long stepVersion,
    String setupReference, String keyVersion, String digest, Instant issuedAt, Instant expiresAt)
Selection(UUID rootId, String keyDigest, Instant startedAt, Instant deadlineAt)
```

Extend the existing method-store port with default-denied browser methods, implemented by its JDBC adapter/companion; every call joins the caller transaction:

```java
Reservation reserveBrowserInTransaction(Admission a, Failure f, SubscriptionOperation o,
    CustomerBinding b, Sample sample, Instant now, Enrollment enrollment);
Pending requireBrowserPendingInTransaction(Reservation r, CustomerSessionContext session, Instant now);
Optional<Root> browserRootInTransaction(UUID reservationId);
Root insertBrowserRootInTransaction(Pending p, Root proposed);
void requireBrowserActionInTransaction(Pending p, Root root, Instant now);
Selection startBrowserSelectionInTransaction(Pending p, Root root, String keyDigest, Instant now);
void finishBrowserSelectionInTransaction(Pending p, Root root, Selection selected, Instant now);
boolean consumeBrowserFirstInTransaction(Pending p, Root root, String keyDigest,
    String requestFingerprint, Instant now);
```

Produce `reserveBrowserMethod(Admission,SubscriptionCommandContext,boolean): Reservation` on the current service, sharing its genuine reservation body and existing constructor. Browser enrollment is part of the original reservation insert; no post-hoc API. Also produce package-private `<T> T browserPhase(Reservation,SubscriptionCommandContext,Function<Pending,T>)` for Task2's same-package service. This starts a fresh caller transaction, authenticates the original customer session, preserves current operation→authority/lifecycle/account/subscription/bill lock order, verifies original request identity/consent/billing sample/debt and exact RESERVED v0 operation, then obtains Pending. It does not auto-stop an admission on client errors. Browser GET does not require an idempotency key; selection/confirmation validate their own keys later, not reuse of the reservation key.

- [ ] Write and observe failing pure model/default-denial/atomic-service tests. Pin rejection of false consent, non-customer actor, different session/origin, caller ambient transaction, late enrollment and failed enrollment rollback. A valid browser reservation must persist its exact session with the actual original SETUP; existing non-browser service tests stay green.
- [ ] Add V36 browser snapshot columns to `stripe_billing_failed_method_reservations`, all-null or all-nonnull, validated against active current customer/session at insertion. No FK to ephemeral sessions; follow V27 retention. Keep old Reservation public shape and default non-browser inserts unchanged. Existing immutable trigger prevents changing/removing enrollment.
- [ ] Add append-only `stripe_billing_method_browser_roots`, `stripe_billing_method_browser_selections`, `stripe_billing_method_browser_releases`, `stripe_billing_method_browser_consumptions`. Unique original reservation/op/step/digest and one selection/release/consumption per root; exact FKs and proof joins bind all rows. Root interval is issue to min(issue+600s, original session expiry). Selection deadline is min(start+30s, root expiry); release requires that window; consume requires root/session expiry and committed release, not a still-live selection window. Store no raw token, key, secret, card or provider payload. Use positional parameters in new SQL-language functions to avoid column/argument shadowing.
- [ ] Authenticate pending context after original :SETUP ACK/unbound proof and before :METHOD/binding/read/completion. Require actual ACK version/reference/fingerprint/resource/account/dispatch, exact sandbox owner/credentials/binding/sample/failure lineage, no stop or successor state. Root ACTION requires no selection; SELECT, RELEASE and CONSUME validate their own prestates. Receipts use current DB time after blocking locks as well as supplied trusted timestamps; reject future timestamps and expired-at-lock-release requests. Exact matching consumption replay returns false; a changed key/fingerprint refuses.
- [ ] Add filtered SQL/provider admission gates denying method binding (including already-bound proof INSERT) and METHOD reserve/claim/dispatch/ACK on enrolled operations until valid historical consumption. Do not require current client expiry for an already committed historical permit. Preserve prior non-browser V35 workflow and all old migrations. Enforce race serialization with the existing operation locks.
- [ ] Add compilation-only hosted persistence coverage for exact identities, immutable/unique receipts, wrong-session and late/future-time refusal, missing release, rollback, direct bypass, expiry after lock wait and concurrent consumption. Execute only audited pure tests locally; compile all main and hosted sources with cached dependencies. Record final API/hash/test evidence, root commit and independent review before Task2.

### Task 2: Explicit server issuer, one bounded original GET and first browser permission

**Files:** Create `provider/application/service/OwnedMethodBrowserService.java`, application ports `provider/application/port/out/OwnedMethodBrowserReader.java` and `OwnedMethodBrowserTokens.java`, and adapters `provider/adapter/out/stripe/billing/StripeOwnedMethodBrowserReader.java` / `StripeOwnedMethodBrowserTokens.java`. All are under `backend/src/main/java/dev/genericpayments/platform/`. Modify only narrow validator visibility/extraction in `StripeSubscriptionProviderAdapter` if required for reuse; ordinary action/confirmation behavior remains unchanged. Add matching pure service/reader tests and compile-only hosted tests.

**Interfaces:** `OwnedMethodBrowserTokens` exposes `activeVersion()`, `token(version,descriptor)`, `hash(raw)` and `matches(raw,digest)`, backed by the existing `StripeBillingGrantTokens`, no new cryptography/configuration. `OwnedMethodBrowserReader.read(Pending,Instant deadline)` returns a redacted immutable nested `EphemeralSetup(String setupReference,String publishableKey,String clientSecret)`. Service constructor takes expected Reservation, existing method service/store, reader, tokens and Clock. It implements existing `GetSubscriptionActionUseCase`, `CreateSubscriptionClientSessionUseCase`, `ConfirmSubscriptionActionUseCase` and `SubscriptionConfirmationUseCase`.

- [ ] Test first using actual service and stateful inert ports: original action/repeated unselected action, selection/release/first confirm, exact replay false, altered replay refusal, wrong session/operation/origin, expired/changed context and rollback. Assert no observation/fact/method/operation/cash changes and no stop from hostile client scope. Existing DTO status stays RESERVED, nextAction null, first-only browserConfirmAllowed true.
- [ ] Implement unregistered per-reservation get/issue/confirm/select. Every public scope must equal original subscription+SETUP and revalidate its original customer session in `browserPhase`. Root MAC descriptor uses domain `gp:owned-failed-method-browser:v1` with exact immutable enrollment/step/owner/revision/sequence/billing/root facts; verify the stored version/digest and regenerated original MAC. Persist one root and reproduce only its original unselected action. All V2 session/status/current/confirm/retry methods refuse. No new public routing or bean.
- [ ] Share issue/select once: authenticate and commit unique selection start before any reader call; call the reader outside transactions; then reenter caller-thread browser phase, compare full Pending/root/selection and current deadlines and commit release before constructing ephemeral DTO. No retries/re-release after any failure/loss, including the other issuance endpoint. Return existing BROWSER_CONFIRM_V1/stripe/SETUP DTO with no attempt/capability. Confirm consumes committed release atomically before returning actual RESERVED permission; exact replay returns false, never a second browser dispatch.
- [ ] Implement one original `GET /v1/setup_intents/{recorded reference}` with pinned existing API version, authorized account/credentials, two-second wall+monotonic bound capped by selection/root deadline, zero retries, cancellation and preserved interruption. SDK worker has no persistence. Reuse remote identity validation, additionally require requires_payment_method, no attached method, original seti-specific client secret and test pk. No extra account call. Safe fixed failures and redacted record/exception representations; no raw cause/provider payload in reports/logs.
- [ ] Add negative inert SDK cases for wrong/live/customer/metadata/reference/usage/method/status/secret/pk, timeouts/late completion, cancellation and provider exception redaction. Run focused and related audited pure regression; hosted fixtures compile only locally. Record tests/hashes and exact HTTP/DTO handoff, root commit and independent review.

### Task 3: Reusable browser entry and genuine complete composition

**Files:** Create `frontend/sdk/src/subscriptions/stripe-subscription-method.ts` and its focused `.test.ts`; modify `frontend/sdk/src/index.ts` exports and `frontend/sdk/README.md`. Add a hosted browser-method composition test under `backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/persistence/`, extracting only the shared `preparedMethod` seam in the existing `OwnedFailedRenewalIntegrationTest`/support fixture if needed. Add focused controller DTO/no-store tests for the explicit service through existing controllers. Update `docs/stage-5b-acceptance.md`, relevant testing/local-development docs to the new scoped source checkpoint; do not promote native/full5B acceptance.

**Interfaces:** Export:

```ts
mountStripeSubscriptionMethod(options: {
  client: SubscriptionClient; session: CustomerSession; subscriptionId: string; operationId: string;
  container: HTMLElement; isCurrent: () => boolean; signal?: AbortSignal; locale?: 'en'|'pt';
  onRetire?: () => void;
}): Promise<StripeSubscriptionHandle>
```

Export its options and the existing handle type. Consume the real registered SDK client, authenticated operation locator and Task2's existing wire shapes. No supplied action/secret/selected-session/driver option, no generic manager reserved-state change or query permission widening.

- [ ] Write failing actual-client/DOM/Elements substitute tests first. The fixture obtains real branded action and selection over inert fetch, mounts a visible host, and enables a site's explicit confirm button only when mount resolves after ready. Assert exact action/selection/preflight paths/body and one confirmSetup; no confirmPayment, confirmation-token, current-attempt or retry call. Returned operation remains RESERVED/pending with no financial/method-success event.
- [ ] Implement original action→BROWSER_CONFIRM_V1 SETUP-only selection→existing SETUP driver. Capture client authority before awaits. Retain `guardSubscriptionHandoff` until driver retirement, not merely successful mounting; use one idempotent retirement callback to release observers/listeners and destroy handles exactly once. Preserve original spent-before-preflight latch, original selection branding and safe error mapping. No fallback or provider load for wrong flow.
- [ ] Cover duplicate/concurrent submit, denied/unknown/stale preflight, wrong flow/brand, session replacement/logout, expiry before/after selection, hidden→visible during selection/preflight, host removal/pagehide/abort and readiness failure. No local browser/server; JSDOM/controlled SDK only. Run related then full SDK tests/typecheck/build once before final commit; preserve meaningful assertions and report all failures.
- [ ] Hosted backend composition must atomically reserve enrolled method using one actual resolved customer session, create real guarded :SETUP ACK/unbound setup proof, issue/select/release/consume through real service+JDBC with inert pending SetupIntent GET. Verify existing controllers return correct no-store DTOs under actual scope. Consumption changes no original cash/debt/invoice/allocation/PI/revision/sequence or RESERVED op. Only subsequent independent succeeded setup/card proof permits existing bind/METHOD ACK→recordMethodUpdateReply→method observer→real FactService. Keep previous non-enrolled positive chain, ordinary/V29 denial, two-session substitution, lost release, one-shot concurrent consume and rollback/bypass negatives. No seeded successful ACK/operation or browser-as-fact shortcut.
- [ ] Run final audited pure related regression and compile hosted changes locally; update truthful source/hosted/native status docs. Root commits, task review and final whole-change review. Publish to existing feature branch and monitor applicable exact-source CI to completion; local Docker/Sandbox/native execution remains prohibited. Keep all reports/worktree and every scoped ruling in completion evidence.
