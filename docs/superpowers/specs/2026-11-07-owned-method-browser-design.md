# Owned method browser confirmation design

## Intent and boundary

Prepare authenticated browser confirmation of a fresh method-change SETUP using the existing Elements driver and persisted owned-method reservation. The user explicitly requests uninterrupted source implementation, subagents, tests, independent review, push and CI. Existing Java hexagonal architecture, SDK protocols and isolated worktree are retained. Native execution is excluded.

The missing boundary belongs after the authentic SETUP_INTENT_CREATE ACK and unbound setup proof, before method binding and the SUBSCRIPTION_UPDATE ACK. SETUP remains RESERVED version zero throughout the browser phase. Only the existing later observer and actual FactService may complete it. The original debt, invoice/allocation/PaymentIntent, cash, revision and observation sequence stay unchanged across browser preparation and confirmation permission.

## Fresh enrollment

Add an explicit `reserveBrowserMethod(admission,context,true)` variant to the existing method service. It shares the genuine reservation transaction and locks, requiring the real consenting customer session, SUBSCRIPTION_MANAGE, matching site/customer/origin and current authority. Existing non-browser reservation behavior stays intact; browser-only store methods default-deny.

A new V36 migration adds immutable, all-or-none browser session UUID/origin/expiry snapshots to the original V35 reservation insert. No post-hoc enrollment or downgrade exists. Validate against the current session at insert and browser phases, following V27's retention-safe snapshot pattern without a foreign key that blocks session cleanup. Enrollment does not expose customer tokens.

Add separate append-only root, selection, release and consumption receipts, one original row of each per enrolled reservation/root. The root pins the exact acknowledged setup step and original SetupIntent. No generic V32 billing root or V29 confirmation chain is opened. Existing V29/V32/V34/V35 migration files remain unchanged.

For enrolled reservations only, additive database/provider guards prohibit initial method binding, prepopulated bound-proof insertion, and METHOD step admission/claim/dispatch/ACK before valid committed consumption. Enrollment and method-step admission serialize on the existing operation locks. After valid consumption, expiry/logout does not retroactively erase that historical permit; existing independent succeeded-SetupIntent/card/method proofs and method-step guards still apply. Non-enrolled historical V35 flows keep their current contracts.

## Browser permission protocol

An explicitly constructed, unregistered service is bound to one expected reservation. It implements the existing action, client-session, first action-confirmation and confirmation-selection application ports. Each request revalidates its own original customer session and exact subscription/SETUP operation locator. Unsupported payment-attempt/status/current/retry methods refuse. No route, Spring bean or native bootstrap is registered.

Issue the root once, with expiry at the earlier of issue+600 seconds and original customer-session expiry. Use the existing grant HMAC/key-version utility through a small application signer port. A domain-separated canonical descriptor includes enrollment, original step identity/fingerprint/reference, account/customer/binding, revision/sequence, billing sample and root UTC times. Persist only digests and safe identities; regenerate/verify using the saved key version. Repeated action lookup can reproduce only the original unselected unexpired token, never extend or replace it.

Client-session issuance and BROWSER_CONFIRM_V1 selection share one persisted selection opportunity. Commit its start before one allowlisted original SetupIntent SDK retrieve, with a 30-second durable selection window capped by root expiry. This logical GET has one immutable deadline at min(start UTC+2 seconds, selection deadline), enforced alongside a two-second monotonic bound, and zero application/Stripe SDK retries. The existing JDK transport may transparently recover an idempotent GET within that same total budget; this is not a guarantee of one wire attempt. No new transport is introduced. Workers perform no persistence. Validate original account/credentials, pending requires_payment_method SetupIntent, no attached method, sandbox/off_session/card/customer/metadata/time identity, strict original seti client-secret form and test publishable key. No account-verification or additional SDK call belongs to this reader.

After GET, reacquire/revalidate the full pending context, exact root and selection, then commit release before returning the ephemeral client secret. Loss or failure burns selection; neither endpoint retries or rereleases. Release must occur within selection/root expiry. Once released, the user may confirm up to root/session expiry; the old selection deadline is historical, not a second short input deadline.

First action confirmation atomically consumes the released root and returns its actual RESERVED operation, no next action, and browser_confirm_allowed=true only after commit. An exact replay may return the same actual operation with permission false; changed-key/body replay refuses. The confirmation request includes a hashed canonical identity/key, never card data. It calls no provider and changes no operation, binding, observation, journal or cash. Consumption is cooperative browser permission, not evidence that Stripe succeeded.

Existing operation/lifecycle/session/account/subscription/bill locks precede root locks. Fresh UTC must be checked after blocking locks; database insert guards compare current database UTC as well as receipt times. Session revalidation holds its existing row locks. A stale supplied timestamp cannot authorize an expired consume. Foreign, malformed, expired or replayed browser requests return safe fixed errors without stopping another customer's admission.

## Concrete browser entry

Export `mountStripeSubscriptionMethod` from the SDK. A custom site supplies the authenticated operation locator, existing real SDK client/session, visible host, current-owner predicate and optional abort/locale/retirement callback. It supplies no action, client secret, selected session or injected driver.

The entry captures client authority, obtains the original branded action and selected BROWSER_CONFIRM_V1/SETUP session, then mounts the existing SETUP driver. It rejects PAYMENT/V2 and has no retry/current-attempt/legacy fallback. Keep the existing handoff guard active through handle retirement so hidden→visible, logout/session replacement, pagehide, removal and cancellation remain latched even during preflight. Readiness precedes enabling a site's explicit confirmation button; the existing driver spends once before its first async preflight and calls confirmSetup at most once. Resolved browser work returns the honest pending operation, never a successful payment/method fact.

This explicit reusable entry avoids changing the general manager's reserved-operation behavior or clock-owned query permissions. Existing controller DTOs provide the prepared server wire; future isolated composition can explicitly construct these controllers with the new ports. No native harness or launch command is added.

## Verification and limits

Pure tests exercise actual enrollment/service logic and SDK client→selection→driver behavior with inert dependencies, including wrong session, expired/late/replayed/concurrent requests, corrupted proof/GET, cancellation, hidden-host retirement, one-shot submit, flow confusion, redaction and rollback. Genuine hosted JDBC/FactService composition inserts the browser boundary into the existing prepared-method chain and preserves the independent provider proofs. Include current/future-time, direct-bind and METHOD bypass, consumption concurrency, release loss, ordinary/V29 denial and non-enrolled regressions.

No local Docker/Sandbox/provider account call/financial operation/Python/install/SQL/application/server/browser/socket/old operation/DISPATCHING/key/operational archive access. No default Maven discovery. Explicit audited pure tests locally; fresh hosted integration is a separate authorized CI gate. All edits use apply_patch; root owns Git, writers are sequential, reports/worktree are preserved. No merge, PR or cleanup. Native method confirmation and G/G+1 grace lineage remain unexecuted; full 5B remains NOT_ACCEPTED.
