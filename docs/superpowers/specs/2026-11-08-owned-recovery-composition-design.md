# One-use original recovery scenario composition — offline preparation

## Intent and verified starting point

Finish the next useful piece of Stage5B by connecting the existing original-intent confirmer to the separate recovered observer in a reusable isolated scenario component. The previous source f8c35aa4fdf58565073332cfeefdf94c6852ff3c passed full CI42488046761: broad5648 cases plus genuine owned failed-renewal235, zero failures/errors and one existing opt-in generator skip; both runtime packaging gates passed. Dedicated42488046869 passed379/379. That evidence does not establish native interoperability or full Stage5B acceptance.

The user requested uninterrupted offline implementation with subagents, tests, independent review and publication/CI. Their prohibition on local Docker/Sandbox, old operations/DISPATCHING and new financial operations persists. This component is source preparation only.

## Scope and why

Existing genuine hosted tests manually call the unregistered original PaymentIntent confirmer and subsequently the independent recovered observer. Future isolated execution needs an explicit original-input component preventing repeat or concurrent phase entry and preserving a successful provider ACK if observation later fails. Implement it in test-support acceptance/runtime, not the production application or a generic callback orchestration framework.

Create public final `dev.genericpayments.acceptance.runtime.OwnedBillingFailedRenewalRecovery`. Its constructor fixes the exact original Reservation, Completion, Recovery, SubscriptionCommandContext and the concrete final StripeBillingOwnedFailedRenewalRecoveryConfirmAdapter and StripeBillingOwnedFailedRenewalMethodObserver. No user-selected identifiers, provider responses, suppliers, runnable hooks or arbitrary drivers enter its public API. Constructors require nonnull inputs but do not authenticate, read, mutate, resolve credentials or call providers; underlying real components own durable authentication.

Two explicit argument-free phases:
- `ConfirmationAck confirm()`: enters only from NEW, invokes the actual confirmer once with the original tuple, stores the returned committed ACK and reaches CONFIRMED. ACK remains provider-write evidence only.
- `OwnedFailedRenewalMethod.Publication observe()`: enters only from CONFIRMED, invokes the actual recovered observer once with the exact original Reservation/Recovery, stores the returned independently committed publication and reaches COMPLETE. It never synthesizes settlement from the ACK.
- `State state()` returns the closed enum NEW, CONFIRMING, CONFIRMED, OBSERVING, COMPLETE or FAILED. `Optional<ConfirmationAck> confirmation()` and `Optional<Publication> publication()` expose only actual returned receipts, never a reconstructed ACK or inferred success. `toString()` is fixed and redacted.

Each phase uses atomic compare-and-set entry; no monitor remains held while service/SDK work runs. An out-of-order, concurrent or repeated entry refuses before any dependency call and does not poison another in-flight or completed phase. There is no reset/resume/adopt/retry API. A new component instance supplies no durable replay authority: underlying actual confirmation/read receipt guards must still refuse an old operation without another POST/publication.

If an entered phase throws RuntimeException, expose one fixed cause-free PaymentApplicationException code OWNED_RECOVERY_SCENARIO_UNAVAILABLE/status409/message `Owned recovery scenario unavailable`; do not leak original exception/cause/suppressed payload, inputs, keys or remote details. An Error propagates unchanged after marking the phase FAILED; test/assertion/programming failures cannot be converted into apparent normal success. A failed observation retains the committed ACK and leaves publication empty. A failed confirmation leaves both empty. The component adds no cleanup/stop call: the actual adapter/observer own matching-claim cleanup, rollback and branch stop; it must not stop a foreign claim or erase a committed ACK.

No fresh outer security/billing/SDK deadline, polling, clock advance or postcommit rejection is introduced. Existing confirmer30sec invocation/2sec logical call and independent observer30sec read budget remain separate; this component promises no combined wall-clock, wire or synchronous persistence-cancellation guarantee. It returns the actual committed receipts even when their return is delayed. Original raw session authority, immutable first-claim identity, operation ownership, null-pay association, cash/journal/callback authority and per-call restrictions remain in the real underlying services.

## Verification contract

Use cached explicit pure JUnit with actual original service/rollback-aware memory stores and official SDK memory HttpClient only. Do not introduce mock authorities or public injection callbacks just to test the controller. Root must audit every selected execution closure; no default Maven/Spring/database/browser/socket runtime locally. Workers read/edit/report only, no execution/Git/network/children.

Pure tests pin NEW/no constructor side effects; out-of-order observation causes no GET/POST/read claim; actual confirmation through seventeen preflight GETs and one exact original POST reaches CONFIRMED with unchanged cash; repeated/concurrent confirm refuses before another SDK call; successful receipt remains after rejected observation entry; entered-phase failure freezes FAILED and does not retry or invent a publication; raw details stay private and Errors propagate. For actual observer success/failure, use real memory service composition where existing fixture can support it without rebuilding authority, otherwise the genuine hosted cases below are the authority. Never replace the observer by a fake to claim financial proof.

Extend genuine OwnedFailedRenewalIntegrationTest for both original browser variants to consume this component instead of separately invoking confirm/observe. Use the existing recoveredGraph setup between the two explicit phases, before the first recovered GET; no callback enters the public component. Assert persisted dedicated ACK and no financial mutation before observe; then actual independent observer/FactService original bill/PI settlement, exactly one extra original payment and unchanged initial cash, journal/events/callback enqueue semantics from existing genuine fixtures. Retain exact request, budgets, API and receipt checks. Add actual observer failure after committed ACK, duplicate/concurrent phase entry and fresh-instance replay controls as appropriate; read receipts and financial state prove no retry/erasure/duplicate publication.

Do not claim delivered signed callback or native acceptance from hosted enqueue evidence. Both existing runtime packaging gates remain required without registering/exporting this component into either historical runtime. Default production JAR contains no acceptance/test/JUnit dependency.

## Explicit exclusions and remaining work

No route, Spring bean, main/HTTP launcher, environment command, browser form, Docker/Compose input, SQL schema/production service change, dependency, native scenario execution or provider installation. No modifications to historical successful initial/first-renewal runtime behavior. No ordinary invoice.pay fallback, deadline widening, new permission or old resource adoption.

This small phase component is not a complete failed-renewal native launcher. Native decline/setup strategy and pinned off-session original-invoice compatibility, authenticated scenario admission/time/grace/G+1 integration, runtime packaging/admission and actual provider/cash/journal/delivered-callback evidence still need separate work and explicit native authorization. Full Stage5B remains NOT_ACCEPTED and native recovery/grace/G+1 NOT_RUN.

## Controller choices

Place this integration in acceptance test-support with concrete existing components and two explicit phases; this adds enforceable one-use sequencing without creating a production wrapper or opening a native command. If later launcher requirements differ, revise this narrow support component before registration. Keep existing durable guards as the cross-instance authority rather than adding a new persistence table for in-process sequencing; if a future resumable controller is required, it needs an explicit durable ownership design, not reuse of this object.
