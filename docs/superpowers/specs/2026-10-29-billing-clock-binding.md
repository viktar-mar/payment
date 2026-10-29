# Owned Billing TestClock and fresh Customer binding

## Purpose and scope

Prepare the next finite Stage 5B backend component: durable original TestClock creation, one exact READY observation, and fresh Customer creation bound to that clock. This is offline implementation and synthetic verification, not permission to contact Stripe or run local services. Native Billing stays NOT_RUN; full 5B stays NOT_ACCEPTED. The existing INITIAL_NON_CLOCK runner is unchanged.

The backend creates the clock itself. Caller-supplied clock IDs, matching metadata, names or timestamps cannot grant ownership. No existing Customer attachment, clock advance/delete/list/search, Subscription creation on clocks, financial operations or generic diagnostic framework belongs to this slice.

## Durable ownership and transactions

Use the existing provider operation ledger with a new TEST_CLOCK_CREATE kind and one additive V30 migration. Preserve all historical keys, fingerprints, migration content and Customer bindings. A narrowly typed persistent clock store owns an intent for one exact Account/Site/Customer/run, generated intent UUID, pinned external account/credential alias/API, frozen epoch, deterministic name/key/fingerprint and UTC admission time. Intent identity is immutable. A separate READY receipt proves the exact acknowledged original clock operation, returned reference and provider fields, exact initial frozen epoch, UTC observation and clock-specific Customer fingerprint. Enforce original-owner and original-ACK relations through SQL constraints, and protect immutable evidence.

Admission exclusively locks the Customer under the existing Account → Site → Customer owner order and refuses any existing ordinary CUSTOMER_CREATE operation (including RESERVED/UNKNOWN), binding or incompatible intent. Ordinary reservation rechecks intent absence after its existing owner locks. This serializes admission with ordinary creation; pre-read followed by ordinary reserve is insufficient. Do not call another REQUIRES_NEW service while holding these owner locks. HTTP never runs in a transaction.

Add a typed reserveOwnedBillingCustomer(Account, ReadyClock) entry. In its own short transaction reload exact persisted provenance and original ACK, require active ownership, derive the unchanged Customer operation key and the stored clock fingerprint, then reserve the original CUSTOMER_CREATE resource. Ordinary reserve cannot select the owned lane. Claim/ACK/binding reconstruct provenance from persisted identity, not current flags. Acknowledged replay may finish only the deterministic local binding, without another provider request.

TEST_CLOCK_CREATE and clock-owned CUSTOMER_CREATE permit only the first dispatch claim. UNKNOWN, expired DISPATCHING and crash-after-claim cannot authorize another POST, even inside the ordinary 23-hour retry window. Ordinary operation replay is unchanged. Generic/manual bindRecovered cannot recover either owned kind. One-way persisted stopped state records a finite safe reason after any adapter failure and denies subsequent dispatch/observation; it cannot alter identity or erase receipts. No restart recovery of a failed run is added.

## SDK and Customer behavior

A non-auto-registered internal TestClock adapter is constructed only by a dedicated test fixture. It admits an exact fresh owner, uses the existing credential resolver, account verifier and zero-retry StripeClientFactory, and dispatches one pinned-API create with frozen_time and name. It reads only the clock ID from its own acknowledged original operation. A single exact retrieval must report READY, false livemode, expected object/ID/name/frozen time, absent advancing target and unchanged provider created/deletes_after fields. Malformed/live/deleted/foreign/advancing/failed responses stop the run; there is no polling loop. Provider timestamps remain evidence, not an assumed UTC/frozen-time contract.

Use an explicit internal owned-Customer method; the ordinary ensureCustomer method refuses admitted clock owners. The owned request supplies test_clock only from the persisted READY receipt, retaining the ordinary key/resource identity. New fingerprint tag CUSTOMER_CREATE_OWNED_CLOCK_V1 binds owner/run/intent/original clock operation/reference/frozen epoch/API and existing metadata inputs. Clock creation uses TEST_CLOCK_CREATE_V1. Ordinary fingerprint bytes remain unchanged. Validate exact test_clock on owned response and null on ordinary response, with all existing ownership/mode checks. Owned Customer event recovery is unsupported and must reject before provider HTTP.

All operational leases, retry bounds and audit times use real UTC. Do not change SubscriptionBillingTimePort activation, UTC inWindow checks, Fake clock injection, security lifetimes or provider timestamp interpretation.

## Downstream fence and activation

Admitted clock owners cannot enter ordinary Subscription/Setup creation or provider dispatch. Enforce this in the common Billing ownership boundary and the durable provider store before reservation/claim; inspect direct inbox/observation entry points for bypass. Existing native Subscription and trial guards must not be weakened. Production exposes no clock controller, route, config/environment switch or generic clock registry. The test-only orchestration exercises real stores/adapters and the official SDK using an inert HttpClient; native startup is future work requiring separate runtime authority.

## Verification and acceptance

Local checks are exact audited standalone pure JUnit and cached offline compilation only. No local Docker, DB/SQL, Spring application, Testcontainers, browser, Python, installs, provider/Sandbox requests or private env/archive/recovery reads. Hosted CI is authorized to run existing synthetic application/PostgreSQL/Fake/browser fixtures.

Prove original ACK/owner constraints, immutable evidence, same-run idempotent local reads, conflicting owner/run denial, ordinary reservation versus admission races in both orders, single clock/Customer claim under contention, UNKNOWN/no redispatch, expired and late ACK denial, stopped-run denial and local binding completion from a known ACK. Add socket-free official SDK serialization/deserialization coverage and meaningful negative response tests. Hosted end-to-end coverage must compose actual proxied stores, adapters, SDK fixture and SQL: clock create → exact READY → Customer → immutable binding, plus ordinary behavior and downstream refusal. Keep literal migration inventories exact. Authored/compiled tests are not reported as executed until hosted CI succeeds.

Independent review per task and a final whole-slice review precede ordinary publication and exact-head CI. Preserve all prior reports and protected historical payment/refund/donation records.
