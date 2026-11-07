# Original owned method application

## Goal and baseline

Replace the remaining hand-written browser-path METHOD provider update with an explicitly constructed server adapter. It consumes the original authenticated reservation, committed browser consumption and independently bound SETUP proof, makes one original subscription default-method update, and returns its genuine ACK. Existing authenticated RESERVED → PROCESSING and independent observer → FactService completion remain separate.

Baseline `dc3c470df9058a89f5e92388d72cb03c3a34743e` passed [full CI](https://github.com/viktar-mar/payment/actions/runs/42390500538): 5,420 backend cases, zero failures/errors, one existing skip, all 92 failed-renewal composition cases, frontend/infrastructure and both packaging checks. [Dedicated renewal CI](https://github.com/viktar-mar/payment/actions/runs/42390500550) passed 379/379. Native failed renewal/method/recovery remain NOT_RUN and full5B NOT_ACCEPTED.

## Constraints

Continue Java/hexagonal architecture and existing dependencies. No local Docker, Sandbox/Stripe/provider request, new financial execution, old operation/DISPATCHING adoption or retry, Python/install, operational key/archive access, local SQL/Spring/application/browser/server/socket. Local verification is cached explicit pure JUnit only; genuine hosted composition is compiled locally and executed by GitHub CI. Preserve worktree and reports. Root owns commits/push/CI. Use subagents, tests first, independent task reviews and one final whole-change review; prior uninterrupted-execution preference removes routine approval gates.

No migration or change to V29/V34/V35/V36, ordinary provider admission, public route, bean registration, SDK, native runner/exporter, time/grace/tax or recovery payment. This adapter consumes an independently bound proof; it does not produce the succeeded-SETUP/card observations or perform new GETs/binding itself. Keep those real SDK validations and proof binding in the hosted fixture. Native integration and an owned binding caller remain distinct unfinished work, not evidence inferred from this component.

## Authenticated pre-METHOD state

Current `inspectBrowserSetupPreparation` already authenticates the original live management session/key, unchanged debt/operation/binding/sample and enrollment without requiring unbound proof. Reuse its transaction body privately rather than nesting a new public fresh transaction. Neither browser pending (requires method null) nor prepared METHOD (requires both ACKs) can authorize this earlier phase.

Add nested descriptive records to `OwnedFailedRenewalMethod`, all required fields non-null and redacted `toString`:

```java
record BoundSetup(OwnedMethodBrowser.Enrollment enrollment,
    SubscriptionBillingProofStore.Setup setupProof, ProviderOperation setupStep) {}
record MethodPreparationContext(SetupPreparationContext original,
    SubscriptionBillingProofStore.Setup setupProof, ProviderOperation setupStep) {}
```

Add public `OwnedFailedRenewalMethodService.inspectBrowserMethodPreparation(Reservation, SubscriptionCommandContext)`. Refuse ambient transactions, foreign/admin/expired contexts and changed original key before effects. In one fresh transaction reuse the original authenticated inspection body and new default-denied `OwnedFailedRenewalMethodStore.requireBrowserBoundSetupInTransaction(Reservation, CustomerSessionContext, Instant)`. Compare its enrollment with the current original and prove returned proof/step identities, then revalidate management authority/session after the last potentially blocking work. Return the enriched descriptive context; it is not a cached dispatch permit. Invalid clients cause no stop or writes.

The JDBC store must independently require durable original identity, original debt delta0/RESERVED v0, bound `pm_…` proof of the original operation/subscription/account, original SETUP ACK/v2 and exact original key/fingerprint/reference/account/credential identity, first dispatch and original 23h window, null lease, plus `owned_method_browser_permit`. Reuse unchanged `requireStepResource` for SUBSCRIPTION_UPDATE and existing canonical SQL fingerprint, then read back the proof/ACK and verify exact v2 shape. Finally call the existing enrollment check with current supplied UTC so fresh DB time and exact original session/scopes/expiry still apply after locks. No METHOD ACK or METHOD absence belongs in this inspection: the adapter must re-inspect while its own METHOD claim exists. No SQL predicate or trigger is weakened.

## One original METHOD update

Add explicitly constructed, unregistered `StripeBillingOwnedFailedRenewalMethodAdapter`. Public constructor takes method service, ProviderOperationStore, StripeCredentialResolver, StripeClientFactory, DigestPort and UTC Clock; a package-private overload appends LongSupplier for inert tests. `public ProviderOperation apply(Reservation, SubscriptionCommandContext)` returns only the matching original METHOD ACK.

At entry inspect the actual service and refuse any existing original `operation.providerOperationKey()+":METHOD"` step, in every state. Reserve only `reserveOwnedFailedRenewalMethodStep(account,reservation,SUBSCRIPTION_UPDATE)`. Independently compare existing `StripeRequestFingerprint.of` encoding with fields:

```text
SUBSCRIPTION_METHOD_V1, operation.id, original :METHOD key,
operation.fingerprint, subscription.providerReference, binding.providerReference,
setupProof.step, setupProof.reference, setupProof.method, consent:true
```

Require fresh RESERVED/v0/no dispatch/reference/lease. Claim once with a fresh claimant and 60-second lease. Require exact original first DISPATCHING/v1 identity, owner, first time, lease and 23h write window before gaining cleanup ownership. Either concurrent inserter may win that first claim; a loser or malformed/foreign claim cannot stop the winner.

Reinspect and compare the complete enriched context around reserve/claim/typed dispatch, before SDK entry and acceptance. All inspections/steps stay on the caller in short independent transactions, none held across I/O. Use typed `requireOwnedFailedRenewalMethodDispatch` and `ProviderOperationGuard`. Do not call ordinary reserve, account verification, any GET, bindMethod, setup create/confirm, invoice pay, fact publication or lifecycle save.

One logical official SDK `subscriptions.update(originalSubscriptionReference, params, options)` sends **only** `default_payment_method=original bound proof.method`. Pin API `2026-09-30.endive`, original :METHOD idempotency key, zero SDK/application retries, connect/read/complete-call at most two seconds inside immutable entry UTC+30s, independent actual/injected monotonic30s and original lease. Worker entry checks interruption, all immutable deadlines and exact original enrollment expiry immediately before SDK invocation. A scheduling pause cannot extend known expiry. Cancellation preserves caller interruption. No single physical-wire-attempt guarantee is introduced.

Validate the real response: non-null original subscription ID, object subscription, sandbox, original bound customer and exact default method. No client secret is consumed or produced. Reinspect current context and typed original claim before genuine ACK of original subscription reference. Verify exact ACK/v2 identity, original first dispatch/window and cleared lease; return it without a post-commit session/time failure. A final ACK may commit after expiry/revocation following the last successful inspection; it is evidence, not browser/lifecycle authority. The subsequent existing `recordMethodUpdateReply` must reauthenticate and refuse a dead session before PROCESSING. Do not combine these independent transactions or automatically publish a fact.

After valid original claim ownership, any pre-ACK uncertainty is terminal: mark UNKNOWN only if that exact DISPATCHING claim still exists and stop once after transactions unwind. Preserve a real ACK if acknowledgment committed before throwing. Failed UNKNOWN/stop gives no replacement budget. Pre-claim client/duplicate/loser refusal never stops another holder. Return only fixed cause-free `OWNED_METHOD_APPLY_UNAVAILABLE`/409/`Owned method application unavailable`.

## Shared write mechanics

Avoid copying the already reviewed SETUP adapter's time, cancellation and terminal-cleanup blocks. Extract package-private `StripeBillingOwnedMethodWrite`: a per-invocation `Budget(Clock, LongSupplier)` with `remaining(ProviderOperation)`, and `<T>T call(ProviderOperation, Instant originalSessionExpiry, String dispatchKey, Call<T>)`, where `Call<T>.run(RequestOptions)` throws StripeException; plus caller-only static `terminate(methods,steps,reservation,claim)`. This helper is internal to these two original owned writes, not a new retry/transport/admission framework. It owns only immutable time/interruption/options/cancellable SDK mechanics and same-claim UNKNOWN/stop; business context, typed reserve/claim validation, fingerprints and response checks stay explicit in their adapters.

Refactor SETUP to use this helper without changing public constructors/prepare contract, request, inspections, first-claim ownership, fixed error, final proof semantics or runtime behavior. Preserve and run all existing SETUP/reader tests, including fixed-UTC monotonic and worker-entry/session-expiry regressions. Common call options remain pinned; caller creates SDK client/params, worker gets only SDK lambda plus local time checks. Do not register the helper or extend dependencies.

## Verification and hosted composition

Task1 tests actual service and inert ports, pre-METHOD success after genuine browser consumption/binding in hosted JDBC, missing/unbound/foreign/corrupt proof or SETUP ACK, absent/uncommitted consumption, second/revoked/expired session, changed key, exact expiry +1ns, post-lock expiry, ambient transaction and original debt/sequence/binding/sample drift. Success needs no METHOD step/ACK; inspection remains valid while an original METHOD claim exists and writes nothing.

Task2 official SDK memory transport tests use actual service authority, not mocked returned context. Assert one exact POST/body/key/options, typed reserve only, actual ACK, no business changes and preserved original SETUP proof/ACK. Cover every existing METHOD state, deterministic competing claim, malformed rows/claimants, all response identities, authority/proof drift at caller boundaries, timeout/interruption, fixed-UTC injected budget, immutable actual/UTC/call limits, worker entry at call and original session expiry, UNKNOWN/stop failures, ACK committed before failure and no repeat. A valid final ACK crossing session expiry/revocation returns evidence, while later real lifecycle transition refuses.

In hosted `preparedMethod(PendingMethod)` retain independent succeeded SetupIntent/card retrieval/validation and actual bindMethod. For browser path replace only manual METHOD reserve/claim/update/ACK with `apply`; retain actual `recordMethodUpdateReply` outside the adapter and observer → FactService chain. Non-browser manual producer remains unchanged. Add genuine no-consumption/preclaim refusal, one POST/options, uncertainty/no-repeat, retained ACK with rolled-back lifecycle save, and late ACK/dead-session controls. Author/compile locally only; execute with full exact-head GitHub CI. Update current guide/matrix with baseline dc3c470 hosted evidence and the new published-commit gate; keep full5B NOT_ACCEPTED and native gaps explicit.
