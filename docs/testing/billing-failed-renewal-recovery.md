# Failed renewal, fixed grace and recovery — offline guide

Status at the offline pre-publication checkpoint on 2026-11-06: the internal owned-recovery backend has the source-bound hosted verification recorded [below](#owned-failed-renewal-backend-checkpoint-offline). The later persisted failed-renewal observer is prepared and verified with local in-memory SDK tests; its new JDBC composition is authored and compiled, with **hosted execution PENDING at that checkpoint**. Subsequent exact-source CI results are recorded separately in the observer plan ledger and publication receipt. Native failed-renewal recovery is **NOT_RUN** and full Stage 5B remains **NOT_ACCEPTED**. The earlier test-only component at source `28acc7c0438d61f36962c37a3a3d3a0f18cbd943` and its synthetic fixture retain their historical scope. This guide grants no Sandbox invocation or financial authority.

The [design](../superpowers/specs/2026-11-05-failed-renewal-recovery-offline-design.md), [acceptance matrix](../stage-5b-acceptance.md#current-checkpoint--2026-11-05) and [operator checkpoint](../local-development.md#current-operator-checkpoint--2026-11-05) define the scope. The earlier successful combined initial/first-renewal run is separate: [safe receipt](../../.superpowers/sdd/2026-11-05-prepare-cleanup-publication/native-after-storage-107/e7080356-9c8c-4dd8-9ed4-b9ee94e57039/result.json), run `e7080356-9c8c-4dd8-9ed4-b9ee94e57039`, source `a59dad7fd979cea774537210e76ed98f95f334a8`, exit 0, 2000 EUR minor units (EUR20) for each invoice, cash/journal/signed-callback gates verified, cleanup confirmed and all seven containers stopped. Its `full5bAccepted` is false. That receipt covers neither failed renewal nor grace, setup or recovery. Historical failed runs and consumed operations remain preserved and nonretryable.

## Historical test-only API and proof boundary

[OwnedBillingFailedRenewalScenario](../../backend/src/test/java/dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalScenario.java) is a package-private, test-source Java class in `dev.genericpayments.acceptance.runtime`. Its API is:

```java
enum Stage {
    INITIAL_PAID, FAILED_RENEWAL, REPEATED_FAILURE, GRACE_LAST_SECOND,
    GRACE_EXPIRED, METHOD_READY, RECOVERY_REQUESTED, RECOVERED
}
enum Authority { NONE, FRESH, LIVE, UNKNOWN, DISPATCHING }
record Payment(UUID id, UUID billId, String invoice, String paymentIntent,
               long amountMinor, String currency) {}
record Observation(Stage stage, UUID owner, String source, Subscription subscription,
                   BillingRecord initialBill, BillingRecord renewalBill,
                   List<Payment> payments, UUID allocation, String paymentIntent,
                   int quantity, Instant businessAt, SubscriptionOperation operation,
                   Authority authority) {}
synchronized void observe(Observation observation);
synchronized boolean complete();
```

Construct a fresh instance and supply the eight observations once, in order. The payment list is defensively copied. Accepted business time cannot decrease; equal-time expiry/setup/request observations are permitted. Invalid or out-of-order input consumes the instance; a later valid observation cannot resume it. Calls are synchronized and replay after completion refuses. `complete()` reports only that the supplied offline transcript passed. It returns no cash, journal, signed-callback, provider, native or full-5B verification flags.

The validator uses the real `Subscription.renewalFailed`, `Subscription.confirmPaid`, `SubscriptionAccessPolicy` and `RecoveryRules`. It pins the supplied owner, 40-character lowercase hexadecimal source identity, original bill/payment, subscription lineage and terms, renewal bill/invoice/period, allocation and recovery PaymentIntent. Terms are EUR20 (2000 minor units) MONTH, quantity 1, trial 0; terms and original positive payment remain unchanged. These are consistency checks over synthetic inputs, not authenticated external facts. In particular, the fixture's source string is a synthetic identity, not the executable commit above.

METHOD_READY is only a labelled observation of unchanged debt, paidThrough and denied access. There is no setup receipt or method object in this API, so it does **not** verify method setup, readiness or ownership. Likewise, supplied `Authority.FRESH` is synthetic; it is neither authenticated permission nor a financial budget. LIVE, UNKNOWN and DISPATCHING authority cannot authorize this offline recovery transition. Recovery fingerprint checking binds its local shape to the same bill; it does not authenticate the digest.

The class has no runtime adapter, endpoint, Spring registration, provider SDK call, launcher or production export. Existing native runtime packaging does not export it. There was no Docker, Stripe, application, database/SQL, browser or network execution for this preparation, and no native recovery acceptance is claimed.

## Historical synthetic fixture business times

The [executable test fixture](../../backend/src/test/java/dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalScenarioTest.java) uses these constants. They are synthetic test times, not approved epochs for a future native run. Original paid period is `[F,E)` and debt period is `[E,N)`; `N > G+1 second`. Operational audit UTC `U` stays `2025-01-01T00:00:00Z` independently of every business observation. Security, authentication, leases and delivery clocks must not be advanced to simulate grace.

| Symbol or observation | Exact UTC fixture time | Required state |
| --- | --- | --- |
| F / INITIAL_PAID | `2025-02-01T00:00:00Z` | ACTIVE, paidThrough E; initial bill CONFIRMED_CASH; one supplied EUR20 (2000 minor units) payment; renewal bill OPEN |
| E | `2025-03-01T00:00:00Z` | Original paid-through and renewal-period start |
| FAILED_RENEWAL / E+3600 | `2025-03-01T01:00:00Z` | PAST_DUE; unchanged paidThrough E and entitlement; same OPEN debt bill; no new cash; GRACE access |
| REPEATED_FAILURE / E+7200 | `2025-03-01T02:00:00Z` | Same debt/bill/invoice/period, paidThrough and captured grace, even with later policy 99 days; no new cash |
| GRACE_LAST_SECOND / G−1 second | `2025-03-03T23:59:59Z` | GRACE with validUntil exactly G |
| G / GRACE_EXPIRED | `2025-03-04T00:00:00Z` | Access denied at the exact boundary; no cash or paid-through extension |
| METHOD_READY / G | `2025-03-04T00:00:00Z` | Same open debt and denied access; label only, no verified setup |
| RECOVERY_REQUESTED / G | `2025-03-04T00:00:00Z` | One supplied fresh RESERVED RECOVER operation; same debt, cash and denied access |
| RECOVERED / G+1 second | `2025-03-04T00:00:01Z` | Same renewal bill CONFIRMED_CASH; one new supplied EUR20 (2000 minor units) payment on its original invoice/PI; debt cleared, ACTIVE, paidThrough N and PAID access |
| N | `2025-04-01T00:00:00Z` | Recovered paid-through; separate late-capture test at N+1 second (`2025-04-01T00:00:01Z`) yields EXPIRED access |

Grace is fixed at `G = E + 259200 seconds`, captured by the first three-day failure reducer. Repeated failure must not move it when the later policy is 99 days. The successful fixture selects E+7200 for the repeat; the validator permits an ordered repeat while access is GRACE, rather than requiring that exact repeat timestamp. INITIAL_PAID uses F in the fixture; the validator requires PAID access there. Other boundary observations in the table are pinned by the stage checks.

RECOVERY_REQUESTED requires a RESERVED RECOVER operation for the pinned Site/Customer/Subscription and expected revision, with unchanged UTC audit fields and fingerprint `v1.recover.<same renewal bill UUID>.<64 lowercase hex>`. Recovery retains that operation, the same renewal allocation and PI, and exactly the original payment plus one new distinct payment. This narrow transcript supplies no new invoice, subscription, third positive payment or generic `invoice.pay` fallback. The separate late-capture domain test demonstrates that recording the debt-period payment after N cannot restore current access; it does not change the fixed G+1 successful stage.

## Historical offline transcript verification

The [Task 1 report](../../.superpowers/sdd/2026-11-05-failed-renewal-recovery-offline/task-1-report.md) records an initial failing transcript test before implementation, then GREEN. Independent review requested two regressions: wrong-bill recovery fingerprint and rollback of accepted business time. Both were observed RED on the prior implementation (7 passed / 2 failed), then the corrected selected scenario/access-policy/recovery-rule run passed **34/34**. The [scoped independent re-review](../../.superpowers/sdd/2026-11-05-failed-renewal-recovery-offline/task-1-fix1-review.md) approved both fixes; it was source review, not a separate test execution.

Root's later fresh pure compilation and selected run passed **72/72**, with zero failures, skips or aborted tests. The [verification record](../../.superpowers/sdd/2026-11-05-failed-renewal-recovery-offline/final-pure-v2-verification.json) pins source hashes, compile/test exits 0 and these eight explicit selectors: the new scenario plus existing `PlanChangeRulesTest`, `RecoveryRulesTest`, `SubscriptionAccessPolicyTest`, `SubscriptionBillingTimeTest`, `SubscriptionCancellationRulesTest`, `SubscriptionPolicyTest` and `SubscriptionTest`. The [test log](../../.superpowers/sdd/2026-11-05-failed-renewal-recovery-offline/final-pure-v2-tests.log) records 72 started and successful. Those checks compiled the pure domain source closure and tests against cached dependencies, without default Maven discovery or Testcontainers. No tests were rerun for this prose update. These results are neither a full hosted suite nor native acceptance.

## Historical native blockers at the test-only source

At source `28acc7c0438d61f36962c37a3a3d3a0f18cbd943`, the following work was identified as necessary before a native scenario. The later backend checkpoint below supersedes the implementation-status claims in items 1 and 2; these items remain historical, not a current instruction to reimplement that backend:

1. **Durable owned admission and publication.** Persist failure/debt/grace provenance and one-use recovery ownership, with atomic publication and stop/fence rules. The successful first-renewal lane requires draft then Paid, two advances and exactly two paid invoices; its existing success contract must stay closed to debt/recovery. Do not adopt retained historical resources, payments, refunds, CREATE or DISPATCHING operations.
2. **Scoped business time and explicit recovery permission.** Implement a later-time selector for this owned debt lifecycle and authenticated one-use recovery authority, retaining UTC operational/security clocks, exact source/owner binding, bounded reads/advances/payment budget and refusal of uncertain/live operations. Ordinary Stripe ownership rejects owned TestClock customers; the isolated projection hides financial commands and isolated facts reject recovery observations. Calling an ordinary recovery path is not a supported workaround. Fresh explicit authorization must bind any future native source, run, resources, epochs and financial budget.
3. **Verified failure and method transitions.** Establish an actual owned decline/no-new-cash observation, fixed grace through repeated failure and policy change, and verified method setup/ownership without paying debt or regaining access. Then require a separate explicit recovery action for the original bill/invoice/allocation/PI; synthetic METHOD_READY and FRESH inputs supply none of this proof.
4. **Source-bound runtime and end-to-end evidence.** Add and verify packaging/export/preflight and appropriate adapters before launch. Produce actual provider and SQL evidence for both original and recovered payments, unchanged original cash, no extra cash during failure/setup/request, debt/grace/access boundaries and recovery publication. Require the actual operation journal and signature-verified delivered callbacks, plus bounded cleanup and stopped-runtime proof. No fake-driver booleans may stand in for these gates.

## Native blockers

A future native scenario still needs raw provider failure and recovery producers, a native exact-owner time/readiness producer, verified method and customer confirmation, runtime integration and end-to-end evidence. The implemented backend and its migration now have the hosted PostgreSQL verification recorded below; this does not establish native provider behavior or delivered signed callbacks. There is no runnable Sandbox command for this component. The previously accepted first-payment/first-renewal receipt remains its own narrow evidence; full Stage 5B and native failed-renewal recovery remain unaccepted.

## Owned failed-renewal backend checkpoint (offline)

The internal owned-recovery backend now includes a JDBC store, an explicit failure/recovery orchestrator, and an exact-owner isolated billing-time proof. The ordinary FactService constructor denies this recovery seam by default; no runtime registration, provider adapter, browser flow, exporter or native launcher is enabled by this work. The generic backend binds the original published terms; the hosted source fixture retains its genuine EUR 1000-minor initial payment and renewal terms. The future native EUR20 case is a separate acceptance scenario.

The original offline pure-backend checkpoint passed **113/113**, with zero failures or skips. Subsequently, source `f9e85685da95c6dd79deedc3623de067b1b89333` passed [hosted CI42122504000](https://github.com/viktar-mar/payment/actions/runs/42122504000): backend **4914 tests, zero failures/errors, one existing skip**, including **10/10** `OwnedFailedRenewalIntegrationTest` cases against PostgreSQL. These cases exercise real JDBC/FactService/cash/event/outbox composition, branch exclusion, rollback and stop behavior on fresh test fixtures. The [dedicated renewal gate42122504028](https://github.com/viktar-mar/payment/actions/runs/42122504028) passed **379/379**, with no failures/errors/skips. Frontend, infrastructure and both runtime packaging checks also passed; packaging did not launch a runtime. No local Docker, database/application context or Stripe/Sandbox execution was performed for these CI compatibility checks. Hosted outbox assertions do not establish delivered native signed callbacks.

Raw provider failure/recovery observations, a native exact-owner time/readiness producer, verified method setup/customer confirmation, runtime integration and native acceptance evidence remain missing. Hosted SQL composition is **VERIFIED** at the source above; native provider recovery and native runtime are **NOT_RUN**, and full Stage 5B remains **NOT_ACCEPTED**. The earlier pure transcript and accepted initial/first-successful-renewal receipt retain their historical scope.

The subsequent offline mapper preparation supplied a package-private descriptive failed-graph read: it joins the open default zero-paid allocation to a declined card PaymentIntent and failed Charge and retains original CASH. That mapper alone does not authenticate a persisted read claim, clock/time readiness, method ownership or recovery authority. The persisted observer described next now consumes that read; the existing `f9e8568` hosted 10/10 backend result covers neither this later mapper nor the observer and proves no native wire behavior.

This mapper preparation passed a fresh **213/213** explicitly selected in-memory Java regression run, with zero failures/skips/aborts, and independent task/whole-change review. This is local source/contract verification only; no provider, local Docker or application/database runtime was started.

## Persisted failed-renewal observer preparation — 2026-11-06

[StripeBillingOwnedFailedRenewalObserver](../../backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/stripe/billing/StripeBillingOwnedFailedRenewalObserver.java) explicitly claims one fresh persisted FAILED read and returns the actual atomic failure receipt from `OwnedFailedRenewalService`. Caller-thread transactions authenticate the full account/subscription/binding/exact isolated READY2 billing sample before and after every complete SDK GET and again at publication. The fact service independently compares its fresh billing sample with the expected sample before consuming the fence or writing effects. The observer verifies both exact READY2 clocks and runs the declined mapper once. It is unregistered and has no route, scheduler, launcher or runtime export.

The shared executor caps the entire observation at 100 complete SDK GETs including clocks, keeps mapper page limits, and enforces two-second per-call limits plus the persisted UTC deadline and both actual/injected 30-second monotonic limits. It applies explicit API `2026-09-30.endive`, connect/read timeouts and zero retries. SDK IO runs only in cancellable virtual workers; database inspections stay on the caller. Stops follow transaction unwind once; begin/publication retain their service-owned stops, safe public diagnostics contain no provider payload, and committed success is returned without a post-commit deadline failure.

Local tests use the official SDK with an inert memory transport, real orchestration and real FactService/domain projection. The positive fixture asserts all 15 GETs, the persisted debt receipt, unchanged original cash reference and no new financial operation. Its original settled bill and idempotent cash port are memory preconditions, not genuine initial-payment provenance. Negative tests cover claim/context/time/fence/clock drift, provider errors, budget/page limits, actual cancellation and interruption, rollback, safe stop failure and delayed commit. Exact commands, counts and test-first limits are recorded in the [Task 2 report](../../.superpowers/sdd/2026-11-06-owned-failed-renewal-observer/task-2-report.md).

The extended [hosted composition](../../backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/persistence/OwnedFailedRenewalIntegrationTest.java) is compiled locally only. Its fresh genuine initial-payment → BOUNDARY pending → FINALIZATION fixture now feeds a coherent synthetic decline through the observer, JDBC store and real FactService, with debt/journal/outbox and rollback assertions. At this offline preparation checkpoint the new hosted cases were compiled only; execution is a separate publication gate, recorded for the exact commit in the plan ledger and its publication receipt. The older hosted counts above do not cover these new cases. No local Docker, database/application context, provider/Sandbox or default test discovery was run. Native exact-owner time/readiness production, method/customer confirmation, recovered-cash observation, runtime integration and delivered native callbacks remain outstanding. Native failed-renewal recovery is **NOT_RUN**; full Stage 5B is **NOT_ACCEPTED**.
