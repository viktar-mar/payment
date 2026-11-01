# Owned €20 initial payment and €20 first renewal — offline scenario

Date: 2026-11-01. This prepares a future, separately authorized run; it grants no native authority and does not accept stage 5B.

## Goal and scope

Provide one separate runnable acceptance scenario for a fresh sandbox subscription: genuine original server-confirm-v2 payment €20, then exactly one unchanged-terms €20 renewal. Reuse the existing initial-payment browser component and durable renewal consumers. The initial-only launcher, default Fake, and ordinary application retain their behavior.

Use Java/Maven and existing Node.js dependencies. Pin stripe-java 34.0.0 and API 2026-09-30.endive. One fresh Account/Site/Customer/Subscription, MONTH, quantity 1, trial 0, EUR2000 minor per period, card/automatic/classic, inclusive catalog terms without provider tax adjustments. No discount, credit, debt, recovery, grace, terms change, extra period or automatic financial retry.

During this work: no local Docker/daemon/container interaction, Stripe, Python, installation, application/SQL/Spring/Testcontainers/browser launch, source archives or secret reading. Do not touch old payments, retained €13, donations, or consumed/UNKNOWN/DISPATCHING operations. Cached compilation and audited pure/inert tests are allowed; hosted CI may use its fresh synthetic PostgreSQL and inert SDK/browser fixtures. Commit and push the existing codex/stripe-billing-5b branch and await the actual full CI result.

V30–V33, SubscriptionFactService and original OwnedSubscriptionBillingTime remain byte-identical. Operational UTC for authentication, leases and callback delivery never moves. Preserve worktree and evidence.

## Java composition and private wire contract

Add a test-runtime-only OwnedBillingFirstRenewalScenario wrapping OwnedBillingFirstPaymentScenario. Delegate its original prepare/confirm/observe/status contracts unchanged. The wrapper is single use: boundary is permitted only after the initial delegate returns SETTLED_LOCAL; finalization is permitted only after the exact persisted boundary. Burn each stage before any effect. Duplicate, concurrent, missing, late, foreign or failed calls stop the run; there is no resume/reset/retry route.

The wrapper obtains the receipt only for the delegate's retained account/subscription and checks run/source/owner identity. It constructs existing consumers explicitly:

1. OwnedFirstRenewalAdmissionService.admit(receipt, sourceSha).
2. StripeBillingFirstRenewalAdvanceAdapter.advance(admission, BOUNDARY), then StripeBillingFirstRenewalAdapter.observe; require Pending and its exact persisted boundary.
3. advance(admission, FINALIZATION), then observe; require Paid, then OwnedFirstRenewalPublicationService.publish.
4. Read authenticated subscription/access and require ACTIVE, unchanged terms, provider-derived period [E,N), paidThrough=N, evaluatedAt=T=E+3600 and validUntil=T+300.

No additional observe loop, advance, confirmation, invoice.pay/finalize, generic mutation, local synthetic cash insertion or operational-clock replacement. Existing durable adapter limits and fences remain binding. BOUNDARY uses B=E; FINALIZATION uses T=E+3600; N comes from the provider, never calendar arithmetic.

Add OwnedBillingFirstRenewalRuntime outside production scanning and jar. Reuse original startup ownership/unwind and original initial HTTP Handler where possible. Do not expand the original runtime's opt-in authority. New gate requires only PAYMENT_STRIPE_BILLING_FIRST_RENEWAL=true and PAYMENT_STRIPE_SANDBOX_ENABLED=true, rejects simultaneous legacy/native/debug flags and uses the corresponding FIRST_RENEWAL_RUN_ID/SOURCE_SHA/FROZEN_TIME/CONTROL_KEY/CONTROL_PORT/CONTROL_BIND environment names. Source must equal PAYMENT_RUNTIME_SOURCE_SHA; canonical fresh run ID and existing frozen-time/port/key rules apply. Initial-only gates must reject the new flag. Fresh run/source/ledger uniqueness is mandatory before any native effect.

Intercept only POST /first-renewal/boundary and POST /first-renewal/finalization before delegating /initial-payment/* to the original Handler. Same bearer authentication, strict exact empty JSON object, request size/deadline/no-store rules. No caller target, ordinal, provider ID, subscription ID or arbitrary command. Invalid calls abort; only closed safe stage codes may leave the runtime, never provider exception text/tokens.

Responses include original identity keys phase, code, run_id, account_id, site_id, customer_id, price_id, subscription_id, operation_id, and:

- Boundary: phase=RENEWAL_BOUNDARY, renewal_admission_id, boundary_time, period_start, period_end; times are ISO instants, boundary_time=period_start=E and E<T<period_end.
- Finalization: phase=RENEWAL_SETTLED_LOCAL, renewal_admission_id, publication_id, period_start, paid_through, access_evaluated_at, access_valid_until; period_start=E and access_evaluated_at=T.

publication_id denotes the existing V33 publication row primary key, admission_id, so publication_id=renewal_admission_id. Final evidence must prove that committed row exists; the matching identifier alone is not publication evidence. No new ID/table/migration is introduced.

Use a distinct explicit primary OwnedFirstRenewalBillingTime wired to the unchanged original sampler, never concurrently import the old primary configuration. Before renewal it delegates to original F; after admission without valid READY it refuses; READY1/READY2 use B/T. The new exported runtime closure contains no JUnit/Mockito/Testcontainers/Spring-test or shared integration fixtures. Export named classes only; normal production jar contains neither runtime nor sampler. Separate Dockerfile selects the exact new entrypoint but is only statically verified here.

## Node owner and environment

Add strict separate first-renewal options with selectedCase=initial_and_first_renewal, protocol=SERVER_CONFIRM_V2, budget={clock:1,customer:1,create:1,confirm:1,advance:2}. Existing account/API binding, run/source/topology and control-port rules remain; old run IDs are rejected. No environment keys containing credentials are copied to public resources or diagnostics.

Share the initial owner's lifecycle through a closed internal INITIAL versus FIRST_RENEWAL discriminator, not an injectable post-payment hook or a general executor. createOwnedBillingFirstPayment retains its initial-only surface and result. createOwnedBillingFirstRenewal exposes the same browser port, runs original initial evidence before renewal, then exactly boundary, finalization and final evidence before cleanup. New resources use scope=OWNED_INITIAL_AND_FIRST_RENEWAL, initialAccepted and renewalAccepted, full5bAccepted=false; accepted flags become true only after their evidence succeeds and final cleanup succeeds. An incomplete/uncertain run consumes its authority and cannot be reused.

Share private runtime client code while keeping old public methods initial-only. New fixed methods boundary(limits) and finalization(limits) POST {} once each; no externally supplied targets or identities. Identity and exact response shape/time relationships are checked. Each fixed route fits the existing 120-second request bound; retain overall 45-minute deadline. Errors and cleanup do not grant a fresh budget.

Bootstrap, preflight, CLI and ownership add explicit closed first-renewal variants using the new validator, runtime env, exact new Dockerfile/entrypoint and private accessor. CLI retains the restricted first-payment capability shape; it exposes no generic clock/graph/advance function. Reuse the fresh €20 MONTH fixture and original browser. Add a dedicated opt-in Playwright entry/config, excluded from ordinary/offline Playwright and inert upon import; do not enable it in default CI.

## Evidence

Run existing initial SQL/callback proof before renewal admission: its one-payment/no-advance assertions must not be weakened. New final read-only SQL and validator prove the same owned original plus exactly two cash bills/payments totaling EUR4000, INITIAL and RENEWAL each EUR2000, distinct invoice/PI, unchanged account/site/customer/catalog and CREATE SUCCEEDED. Prove exactly two advances at B/T with ACK/READY, two read claims, one boundary and one committed publication; exact source/run, original immutable receipt/confirmation lineage, no foreign/unresolved/retry/third advance.

Final state is provider-derived [E,N), paidThrough N, authenticated access at T. Require two payment events, two PERIOD_PAID events, exactly four signed delivered callbacks, correct identities/payload periods and no duplicates. Validate each initial/renewal pair with existing callback machinery, plus the complete receiver inspection is exactly their union. Do not reuse the old two-callback validator wholesale on four callbacks. Credentials stay inside the existing bounded read-only callback; reports contain only closed fields and booleans.

The existing callback validator pins INITIAL. Reuse a private common implementation with two fixed exported entry points (the existing INITIAL entry remains strict and a new RENEWAL entry pins RENEWAL). Do not rewrite received payloads, relabel renewal as INITIAL, or add a caller-selected kind to the existing public contract. Each pair must contain exactly its payment and lifecycle event, and aggregate inspection proves their exact union.

The existing INITIAL entry also returns its provider graph, including its durably stored allocation reference; keep that contract intact. The new RENEWAL callback entry validates payload/signature/delivery only and returns closed verification flags, without inventing a provider graph or allocation identifier. The frozen publication schema stores renewal invoice/PI, not its allocation ID; the existing strict renewal consumer validates allocation→PI→Charge before publication. Final evidence binds that committed publication and its persisted monetary lineage. No synthetic/reused allocation value or schema change is allowed to satisfy callback validation.

## Verification and delivery

Pure tests cover configuration exclusion, fixed stage ordering, exact shape/identity, duplicates/concurrency/deadlines, failure cleanup, no renewal after failed initial proof and no finalization after failed boundary. Static packaging tests prove named export closure, production isolation and exact entrypoint. Synthetic hosted composition executes the new Java wrapper with genuine original server confirmation, real durable services/facts, inert pinned SDK and fresh database, shipped SQL and signed callbacks: two actual €20 facts, no intermediate access extension, unchanged original evidence and exactly two advances. Failure cases include lost ACK/unknown, pending finalization, stale fence/source drift and no third advance. Do not manufacture RED by running prohibited runtime or weakening an existing consumer; missing behavior may be demonstrated by an audited pure assertion, with genuine composition verified in hosted CI.

Independent task compliance/quality review and final whole-slice review precede delivery. Update stage-5B matrix and local-development instructions honestly: OFFLINE_PREPARED, not NATIVE_ACCEPTED. Push and wait for all existing Offline CI jobs on the exact final source; retain an external completion receipt rather than repeatedly editing docs solely for the CI SHA.
