# One-Time Payments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a complete provider-neutral one-time-payment vertical slice: catalog checkout, scoped Partner API payments, asynchronous fake-provider processing, reconciliation, partial/full refunds, signed callbacks, an admin operations UI, a framework-agnostic browser SDK, and a static integration demo.

**Architecture:** Extend the existing Spring Boot modular monolith with hexagonal `payments` and `webhooks` modules. Mutable Payment projections are kept in PostgreSQL beside an append-only PaymentEvent journal; provider events enter through a deduplicated inbox and confirmed domain transitions create callback work through a transactional outbox. Provider calls happen outside database transactions. The Next.js admin remains a separate deployable, while the SDK is an independent ESM package usable from any site.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Maven Wrapper 3.9.11, PostgreSQL 18, Flyway, Spring Data JPA/JdbcTemplate, Spring Security, Java HTTP Client, Micrometer, Bouncy Castle Argon2id, springdoc-openapi 3.1.0, JUnit 5, AssertJ, Testcontainers, ArchUnit, Cucumber-JVM 7.34.8, Node.js 24, pnpm 11.1.3, Next.js 16.3.3, React 19.3.0, TypeScript 5.9, Tailwind CSS 4.3, Vitest, Testing Library, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-04-one-time-payments-design.md`

## Global Constraints

- Work only in this local workspace. Do not initialize Git, create commits, branches, tags, or push anything.
- Preserve all Stage 1 API contracts and data. Existing `/api/admin/v1/**` routes stay available; new Stage 2 admin routes use the approved `/api/v1/admin/**` contract.
- Keep `backend/`, `frontend/admin/`, `frontend/sdk/`, and `examples/demo-site/` independently buildable.
- Domain packages must not import Spring, Jakarta Persistence, Jackson, HTTP, database, or provider SDK classes. Keep `ArchitectureRulesTest` green.
- Use UUIDs internally, positive integer minor units, uppercase ISO-4217 currencies, and UTC timestamps supplied through injected `Clock`.
- Never trust amount, currency, Site identity, Origin, or completion state supplied by a browser. Catalog amount comes from a published Price; dynamic amount is accepted only from an authenticated Partner API key.
- Provider calls and callback HTTP calls must execute outside open database transactions.
- A synchronous provider response never changes Payment to `SUCCEEDED` or Refund to `SUCCEEDED`; only inbox processing or reconciliation may confirm money movement.
- `payment_events` is append-only. Projection, PaymentEvent, and OutboxEvent changes for one confirmed transition commit atomically.
- Every externally repeatable mutation uses `Idempotency-Key` with a canonical request fingerprint. Provider operation keys derive deterministically from internal object IDs.
- Store API keys only as Argon2id hashes; store checkout/handoff tokens only as SHA-256 hashes; encrypt callback secrets with AES-256-GCM.
- Do not log raw authorization headers, tokens, API keys, callback secrets, provider client secrets, full provider payloads, email, or payment data.
- Default fake behavior is asynchronous and manual (`PAYMENT_FAKE_AUTO_SUCCESS=false`). Auto-success may enqueue an event but may not update Payment directly.
- Use red-green-refactor for every production change. Each task begins with a focused failing test and ends with a local verification checkpoint.
- Update `PostgresIntegrationTest.clearDatabase()` and the Cucumber reset order whenever a migration adds foreign-key-related tables.
- Because Git is prohibited, capture task status in `.superpowers/sdd/2026-10-04-one-time-payments/progress.md`; never substitute a commit command for a checkpoint.

## Review Focus

- State monotonicity under duplicate, delayed, and out-of-order provider events.
- Exactly one Payment/Attempt/Refund for idempotent and concurrent requests.
- Provider and callback network boundaries are outside transactions and do not leak secrets.
- Tenant isolation: every Partner/admin query and callback is scoped by Site and PaymentAccount.
- Refund row locking and calculation of reserved, succeeded, and remaining refundable amounts.
- Atomic projection + journal + outbox writes and safe multi-worker claims.
- One-time secret/token reveal behavior, expiry, rotation overlap, and replay behavior.
- Dynamic CORS/Origin validation and Partner scope enforcement.
- Browser SDK never exposes Partner credentials and never treats a browser success event as an entitlement authority.
- Stage 1 auth, catalog, schema, and frontend flows remain green.

---

## Target File Map

### Backend payment core

- `backend/src/main/java/dev/genericpayments/platform/payments/domain/*` — Payment, PaymentAttempt, Refund, immutable PaymentEvent, IDs, statuses, snapshots, and transition rules.
- `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/*` — catalog checkout, dynamic payment, confirmation, query, refund, reconciliation, handoff, and fake-control use cases.
- `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/*` — persistence, locking, idempotency, tokens, provider, inbox, event journal, and outbox ports.
- `backend/src/main/java/dev/genericpayments/platform/payments/application/service/*` — orchestration split into short transactional phases around provider calls.
- `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/*` — JPA/JdbcTemplate implementations and worker claims.
- `backend/src/main/java/dev/genericpayments/platform/payments/adapter/in/web/*` — Public, Partner, and Admin payment controllers and DTOs.
- `backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/fake/*` — fake payment/refund state, deterministic references, outcomes, and event publication.

### Backend credentials and callbacks

- `backend/src/main/java/dev/genericpayments/platform/partners/*` — Site API-key lifecycle, Argon2id verification, scopes, filter, and authenticated Site principal.
- `backend/src/main/java/dev/genericpayments/platform/webhooks/domain/*` — callback configuration, secret versions, outbox status, and delivery facts.
- `backend/src/main/java/dev/genericpayments/platform/webhooks/application/*` — callback configuration, signing, delivery, retry, and manual retry use cases.
- `backend/src/main/java/dev/genericpayments/platform/webhooks/adapter/out/*` — AES-GCM secrets, PostgreSQL outbox, SSRF-safe HTTP delivery, and scheduled workers.
- `backend/src/main/java/dev/genericpayments/platform/shared/adapter/out/security/*` — secure random tokens, SHA-256, canonical fingerprints, and shared encryption utilities.
- `backend/src/main/resources/db/migration/V4__one_time_payments.sql` — payment, idempotency, inbox/outbox, Partner-key, callback, and fake-provider schema.
- `backend/src/main/resources/application.yml` — non-secret Stage 2 defaults.

### Frontend, SDK, example, and runtime

- `frontend/admin/src/app/payments/*`, `frontend/admin/src/components/payments/*` — list, detail, timeline, refunds, reconcile, retry, and fake simulation.
- `frontend/admin/src/app/sites/[siteId]/integration/page.tsx`, `frontend/admin/src/components/integration/*` — API keys and callback configuration.
- `frontend/admin/src/lib/api/schema.d.ts` — regenerated OpenAPI types; never hand-edit.
- `frontend/sdk/*` — standalone ESM SDK, provider driver, polling/recovery, Web Component, styles, and tests.
- `examples/demo-site/*` — static HTML/CSS/TypeScript integration and Playwright tests.
- `infrastructure/callback-receiver.mjs` — dependency-free smoke callback verifier.
- `infrastructure/smoke-stage-2.sh` — repeatable Stage 2 API and callback smoke journey.
- `infrastructure/compose.yaml`, `infrastructure/.env.example` — backend settings, demo service, and smoke profile.
- `docs/local-development.md` — exact Stage 2 setup, flows, verification, and limitations.
- `.superpowers/sdd/2026-10-04-one-time-payments/progress.md` — local task checkpoints and verification evidence.

---

### Task 1: Implement the payment, attempt, refund, and event domain model

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/domain/PaymentId.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/domain/PaymentSource.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/domain/PaymentStatus.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/domain/PriceSnapshot.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/domain/Payment.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/domain/PaymentAttemptId.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/domain/PaymentAttemptStatus.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/domain/PaymentAttempt.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/domain/RefundId.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/domain/RefundStatus.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/domain/Refund.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/domain/PaymentEvent.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/domain/PaymentEventType.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/domain/PaymentEventSource.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/domain/PaymentTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/domain/PaymentAttemptTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/domain/RefundTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/domain/PaymentEventTest.java`
- Create: `.superpowers/sdd/2026-10-04-one-time-payments/progress.md`

**Interfaces:**
- Produces immutable, framework-free aggregates whose methods return a new state or throw `DomainValidationException`.
- Produces one state-transition policy used by API confirmation, inbox processing, and reconciliation.
- Consumes existing `Money`, `TaxMode`, `PriceId`, `SiteId`, and `PaymentAccountId` value types.

- [ ] **Step 1: Write failing tests for Payment construction and monotonic transitions**

Cover catalog snapshot immutability, dynamic-payment shape, terminal failure, recoverable decline, success, partial/full refund transitions, and rejection of regressions. Use fixed IDs and `PostgresIntegrationTest.INITIAL_TIME` only as a time constant; these remain pure unit tests.

```java
@Test
void recoverableDeclineRequiresAnotherMethodWithoutTerminatingPayment() {
    Payment payment = catalogPayment().requiresPaymentMethod(INITIAL_TIME);

    Payment declined = payment.recoverableDecline(INITIAL_TIME.plusSeconds(10));

    assertThat(declined.status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
    assertThat(declined.canStartAttempt()).isTrue();
}

@Test
void succeededPaymentCannotRegressToProcessing() {
    Payment succeeded = catalogPayment().requiresPaymentMethod(INITIAL_TIME)
        .processing(INITIAL_TIME.plusSeconds(1))
        .succeeded(INITIAL_TIME.plusSeconds(2));

    assertThatThrownBy(() -> succeeded.processing(INITIAL_TIME.plusSeconds(3)))
        .isInstanceOf(DomainValidationException.class)
        .hasMessageContaining("cannot transition");
}

@Test
void refundFactsMovePaymentOnlyAfterConfirmedAmounts() {
    Payment succeeded = succeededPayment(2_000);
    assertThat(succeeded.refundSucceeded(500, INITIAL_TIME.plusSeconds(5)).status())
        .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    assertThat(succeeded.refundSucceeded(2_000, INITIAL_TIME.plusSeconds(5)).status())
        .isEqualTo(PaymentStatus.REFUNDED);
}
```

- [ ] **Step 2: Run the domain tests and observe the intentional compilation failure**

Run:

```bash
cd backend
./mvnw -q -Dtest='dev.genericpayments.platform.payments.domain.*Test' test
```

Expected: test compilation fails because the payment-domain classes do not exist.

- [ ] **Step 3: Implement exact status sets and aggregate invariants**

Use these enums unchanged so database checks, provider mappings, API schemas, SDK states, and callbacks share one vocabulary:

```java
public enum PaymentStatus {
    CREATED, REQUIRES_PAYMENT_METHOD, REQUIRES_ACTION, PROCESSING,
    SUCCEEDED, FAILED, CANCELLED, PARTIALLY_REFUNDED, REFUNDED
}

public enum PaymentAttemptStatus {
    CREATED, REQUIRES_PAYMENT_METHOD, REQUIRES_ACTION, PROCESSING,
    SUCCEEDED, FAILED, UNKNOWN, CANCELLED
}

public enum RefundStatus {
    PENDING, PROCESSING, SUCCEEDED, FAILED, UNKNOWN, CANCELLED
}

public enum PaymentEventSource { API, PROVIDER_EVENT, RECONCILIATION, ADMIN }
```

`Payment` must expose named transitions, not a public arbitrary-status setter. `SUCCEEDED` may move only to refund-related states; `REFUNDED`, `FAILED`, and `CANCELLED` are terminal for new attempts. A recoverable decline leaves Payment in `REQUIRES_PAYMENT_METHOD`, while a terminal decline sets `FAILED`. Validate catalog/dynamic field combinations and copy technical metadata with `Map.copyOf`.

- [ ] **Step 4: Implement PaymentAttempt and Refund invariants**

`PaymentAttempt.start` requires a positive sequence and deterministic provider operation key. Only `UNKNOWN` blocks a subsequent attempt pending reconciliation. `Refund.request` requires matching currency, a positive amount, and a Payment in `SUCCEEDED` or `PARTIALLY_REFUNDED`.

```java
public static Refund request(RefundId id, Payment payment, Money amount,
                             String providerOperationKey, String reasonCode,
                             String technicalNote, Instant now) {
    if (!payment.money().currency().equals(amount.currency())) {
        throw new DomainValidationException("refund currency must match payment currency");
    }
    if (!payment.canRefund()) {
        throw new DomainValidationException("payment is not refundable");
    }
    return new Refund(id, payment.id(), amount, RefundStatus.PENDING, null,
        providerOperationKey, reasonCode, technicalNote, now, now);
}
```

- [ ] **Step 5: Implement immutable PaymentEvent construction**

Require event ID, Payment/Site/account IDs, positive sequence, event type/source, UTC-compatible instants, request ID, SHA-256 payload hash, and bounded safe metadata. Reject metadata keys/values over the limits defined in the spec-facing validation tests. Event types are exactly the 13 types in section 6.4 of the design.

- [ ] **Step 6: Run domain and architecture tests**

Run:

```bash
cd backend
./mvnw -q -Dtest='dev.genericpayments.platform.payments.domain.*Test,ArchitectureRulesTest,ArchitectureRulesRuleTest' test
```

Expected: all selected tests pass and no payment-domain class has a forbidden dependency.

- [ ] **Step 7: Local checkpoint**

Record domain test count and the final transition table in `.superpowers/sdd/2026-10-04-one-time-payments/progress.md`.

---

### Task 2: Add the Stage 2 schema and PostgreSQL persistence adapters

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__one_time_payments.sql`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/LoadPaymentPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/SavePaymentPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/LoadPaymentAttemptPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/SavePaymentAttemptPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/LoadRefundPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/SaveRefundPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/AppendPaymentEventPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/LoadPaymentEventPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/*`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/adapter/out/persistence/PaymentPersistenceIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/adapter/out/persistence/PaymentEventAppendOnlyIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/adapter/out/persistence/PaymentConstraintsIntegrationTest.java`
- Modify: `backend/src/test/java/dev/genericpayments/platform/support/PostgresIntegrationTest.java`
- Modify: `backend/src/test/java/dev/genericpayments/platform/acceptance/FoundationCatalogSteps.java`

**Interfaces:**
- Produces mutable projection repositories plus insert-only event journal operations.
- Produces `findByIdForUpdate` for Payment and worker-claim operations using `FOR UPDATE SKIP LOCKED`.
- Does not expose update/delete operations for PaymentEvent.

- [ ] **Step 1: Write failing PostgreSQL tests before the migration**

Test round trips, optimistic locking, Site-isolated queries, unique dynamic external orders, unique attempt numbers, refund currency/amount checks, inbox deduplication, outbox uniqueness, and database rejection of event update/delete.

```java
@Test
void paymentEventsCannotBeChangedOrDeleted() {
    UUID eventId = fixtures.persistPaymentEvent();

    assertThatThrownBy(() -> jdbc.update(
        "update payment_events set event_type='PAYMENT_FAILED' where id=?", eventId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update(
        "delete from payment_events where id=?", eventId))
        .isInstanceOf(DataIntegrityViolationException.class);
}
```

- [ ] **Step 2: Run the focused integration tests and observe Flyway/schema failure**

Run:

```bash
cd backend
./mvnw -q -Dtest='dev.genericpayments.platform.payments.adapter.out.persistence.*IntegrationTest' test
```

Expected: tests fail because `V4__one_time_payments.sql` and adapters are absent.

- [ ] **Step 3: Create the V4 migration with all approved tables and constraints**

The migration must create these tables in dependency order:

```text
payments
payment_attempts
refunds
payment_event_sequences
payment_events
idempotency_records
checkout_sessions
site_api_keys
site_callback_secret_versions
site_callback_configs
provider_inbox
outbox_events
outbox_deliveries
fake_provider_objects
```

Use explicit `check` constraints for every enum and amount. Key schema decisions:

```sql
create unique index uq_payments_site_external_order
  on payments(site_id, external_order_id)
  where external_order_id is not null;

create unique index uq_payment_attempt_number
  on payment_attempts(payment_id, attempt_number);

create unique index uq_provider_inbox_event
  on provider_inbox(provider_type, payment_account_id, provider_event_id);

create unique index uq_idempotency_scope_key
  on idempotency_records(scope, idempotency_key);

create unique index uq_payment_event_sequence
  on payment_events(payment_id, sequence_number);
```

Model `checkout_sessions.session_type` as `CHECKOUT` or `HANDOFF`; both store only `token_hash char(64)`. HANDOFF rows additionally require `origin` and `consumed_at` remains null until exchange. `outbox_events` stores immutable `payload jsonb`, endpoint snapshot, and callback-secret-version reference. `fake_provider_objects` is adapter-only state, separate from Payment projection, keyed by provider reference and provider operation key. Add a unique `(id, currency)` key on `payments` and a composite `(payment_id, currency)` foreign key from `refunds` so PostgreSQL enforces matching currency. Add a durable reconciliation lease owner/expiry to Payment persistence; attempt creation rejects a live lease and workers may recover an expired lease.

Use this data dictionary when writing the full DDL:

- `payments`: IDs for Payment/Site/nullable Customer/PaymentAccount/nullable Price; source; complete nullable Price snapshot; nullable external order and return URL; amount/currency/tax fields; sanitized metadata JSON; status; nullable provider reference; refundable/refunded minor units; reconciliation lease owner/expiry; timestamps; optimistic version.
- `payment_attempts`: attempt/Payment IDs; positive attempt number; unique provider operation key; nullable provider reference/method family/result code; status; timestamps; optimistic version.
- `refunds`: refund/Payment IDs; amount/currency; status; unique provider operation key; nullable provider refund reference; reason/note; timestamps; optimistic version.
- `payment_event_sequences`: Payment ID and the last allocated positive sequence.
- `payment_events`: event/Payment/Site/account IDs; sequence; type/source; nullable amount/currency/provider IDs/idempotency key; occurred/received timestamps; request ID; payload hash; sanitized metadata JSON.
- `idempotency_records`: ID; scope/key/fingerprint; `IN_PROGRESS` or `COMPLETED`; resource type/ID and response status; nullable encrypted response secret/nonce/key version/secret expiry; record expiry; timestamps; optimistic version.
- `checkout_sessions`: session ID; `CHECKOUT` or `HANDOFF`; Payment/Site IDs; token hash; origin; scopes; expiry; nullable consumed timestamp; created timestamp.
- `site_api_keys`: key/Site IDs; unique prefix; Argon2id hash; scopes; `ACTIVE` or `REVOKED`; created/expiry/last-used/revoked timestamps; optimistic version.
- `site_callback_secret_versions`: secret-version/Site IDs; positive Site-local version; AES-GCM ciphertext/nonce/master-key version; created/retire-after timestamps.
- `site_callback_configs`: Site primary key; endpoint URL; active secret version; enabled flag; timestamps; optimistic version.
- `provider_inbox`: row ID; provider/account/event/object IDs; event type; occurred/received timestamps; payload hash and safe fake payload JSON; `PENDING`, `PROCESSING`, `PROCESSED`, or `FAILED`; lease/error/attempt fields.
- `outbox_events`: ID equal to the source PaymentEvent ID; Site/Payment IDs; callback type; immutable payload JSON; endpoint and secret-version snapshots; `PENDING`, `DELIVERED`, or `DEAD`; next-attempt/lease/attempt-count fields; timestamps; optimistic version.
- `outbox_deliveries`: delivery/outbox IDs; positive attempt number; `AUTOMATIC`, `SNAPSHOT_RETRY`, or `CURRENT_CONFIG_RETRY`; `SUCCEEDED` or `FAILED`; nullable HTTP status/safe error category; start/finish timestamps; unique `(outbox_event_id, attempt_number)`.
- `fake_provider_objects`: provider reference and unique operation key; object type `PAYMENT` or `REFUND`; internal object ID/account ID; fake state/result code; monotonic fake event counter; timestamps; optimistic version.

Create a guard trigger owned by the migration owner and callable by the runtime role:

```sql
create function prevent_payment_event_mutation() returns trigger
language plpgsql as $$
begin
  raise exception 'payment_events are append-only';
end;
$$;

create trigger trg_payment_events_append_only
before update or delete on payment_events
for each row execute function prevent_payment_event_mutation();
```

- [ ] **Step 4: Implement projection adapters and explicit domain mappers**

Follow the existing `PriceJpaEntity` pattern for Payment, Attempt, and Refund projections. Use `@Version` on mutable rows. Do not put JPA annotations on domain records. Use repository methods that include `siteId` for tenant-scoped reads.

- [ ] **Step 5: Implement journal sequence allocation and row locking with JdbcTemplate**

Allocate per-Payment sequence numbers atomically:

```sql
insert into payment_event_sequences(payment_id, next_sequence)
values (?, 1)
on conflict (payment_id)
do update set next_sequence = payment_event_sequences.next_sequence + 1
returning next_sequence;
```

The application receives the allocated number only inside the same transaction that inserts the event. Implement `findByIdForUpdate` and refund reservation reads as concrete repository or `JdbcTemplate` queries whose final locking clause is `FOR UPDATE`; keep every selected column explicit in the implementation.

- [ ] **Step 6: Extend test cleanup without weakening the append-only guard**

The row trigger deliberately rejects `DELETE FROM payment_events`, so Testcontainers cleanup must use this explicit test-only statement:

```sql
truncate table outbox_deliveries, outbox_events, provider_inbox, fake_provider_objects,
  checkout_sessions, idempotency_records, site_callback_configs,
  site_callback_secret_versions, site_api_keys, payment_events, payment_event_sequences,
  refunds, payment_attempts, payments, admin_sessions, admin_users, audit_log,
  provider_price_mappings, provider_product_mappings, prices, price_version_counters,
  products, site_allowed_payment_methods, site_authentication_modes,
  site_allowed_origins, sites, payment_accounts cascade;
```

PostgreSQL row-level update/delete triggers do not run for TRUNCATE. Retain a separate integration test proving ordinary update/delete still fails. Production application code receives no truncate port or endpoint.

- [ ] **Step 7: Run migration, persistence, and Stage 1 regression tests**

Run:

```bash
cd backend
./mvnw -q -Dtest='dev.genericpayments.platform.payments.adapter.out.persistence.*IntegrationTest,dev.genericpayments.platform.catalog.adapter.out.persistence.*Test,dev.genericpayments.platform.sites.adapter.out.persistence.*Test' test
```

Expected: all selected tests pass against PostgreSQL 18 Testcontainers.

- [ ] **Step 8: Local checkpoint**

Record the V4 table/constraint list and focused test result in the progress file.

---

### Task 3: Implement token hashing, idempotency, and encrypted secret primitives

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/shared/application/port/out/RandomTokenPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/shared/application/port/out/DigestPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/shared/application/port/out/EncryptedSecretPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/shared/application/port/out/Stage2TelemetryPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/shared/adapter/out/security/SecureRandomTokenAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/shared/adapter/out/security/Sha256DigestAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/shared/adapter/out/security/AesGcmEncryptedSecretAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/shared/adapter/out/security/DataEncryptionProperties.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/shared/adapter/out/observability/MicrometerStage2TelemetryAdapter.java`
- Create: `backend/src/test/resources/application.yml`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/model/IdempotencyRecord.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/IdempotencyPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/IdempotencyService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/IdempotencyPersistenceAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/IdempotencyCleanupJob.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/shared/adapter/out/security/AesGcmEncryptedSecretAdapterTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/shared/adapter/out/observability/MicrometerStage2TelemetryAdapterTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/application/service/IdempotencyServiceTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/adapter/out/persistence/IdempotencyConcurrencyIntegrationTest.java`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- `RandomTokenPort.issue(int bytes)` returns Base64url without padding; call with 32 bytes for checkout, handoff, API-key secret, and callback secret material.
- `DigestPort.sha256Hex(String)` is used for tokens and payload hashes.
- `EncryptedSecretPort.encrypt/decrypt` handles versioned ciphertext without exposing the master key.
- `IdempotencyService.begin/replay/complete` distinguishes owner, in-progress replay, completed replay, expired secret replay, and fingerprint conflict.

- [ ] **Step 1: Write failing crypto and idempotency tests**

Assert unique 256-bit tokens, constant-shape SHA-256 hex, AES-GCM round trip with random nonces, tamper rejection, canonical fingerprints independent of JSON property order, same-key replay, different-fingerprint conflict, concurrent single ownership, and exact idempotency replay/conflict counters with bounded tags.

```java
@Test
void canonicalFingerprintIgnoresJsonPropertyOrder() {
    String first = service.fingerprint(Map.of("amount_minor", 1999, "currency", "EUR"));
    var reversed = new LinkedHashMap<String, Object>();
    reversed.put("currency", "EUR");
    reversed.put("amount_minor", 1999);

    assertThat(service.fingerprint(reversed)).isEqualTo(first);
}
```

- [ ] **Step 2: Run focused tests and observe the intentional failure**

Run:

```bash
cd backend
./mvnw -q -Dtest='AesGcmEncryptedSecretAdapterTest,MicrometerStage2TelemetryAdapterTest,IdempotencyServiceTest,IdempotencyConcurrencyIntegrationTest' test
```

Expected: compilation fails because the security and idempotency types do not exist.

- [ ] **Step 3: Implement secure token, digest, and AES-256-GCM adapters**

Decode `PAYMENT_DATA_ENCRYPTION_KEY` as exactly 32 Base64 bytes. Store a random 12-byte nonce, ciphertext/tag, and key version separately. `backend/src/test/resources/application.yml` supplies an explicit test-only 32-byte Base64 key so every existing Spring test context remains deterministic; non-local application startup fails when the variable is absent. Never generate a replacement master key during startup.

```yaml
payment:
  data-encryption:
    key-base64: ${PAYMENT_DATA_ENCRYPTION_KEY:}
    key-version: ${PAYMENT_DATA_ENCRYPTION_KEY_VERSION:local-v1}
```

Bind profiles so the local/test profile may use an explicit test bean, while any other running profile validates the configured key before accepting traffic.

- [ ] **Step 4: Implement canonical business-field fingerprints**

Serialize a sorted map with Jackson only in the adapter/application layer, normalize currencies and IDs before fingerprinting, and exclude headers, request IDs, token plaintext, and non-business transport fields. Hash the canonical UTF-8 bytes with SHA-256.

- [ ] **Step 5: Implement database-backed idempotency ownership and replay**

Insert `IN_PROGRESS`; on unique conflict reload `(scope, key)` under lock and compare fingerprint. `complete` stores status/resource and encrypts only the response secret fragment until token expiry. Completed records expire after 30 days. A daily cleanup job deletes only completed expired rows; in-progress rows are retained for reconciliation/recovery. Tests call the job directly with the fixed Clock. Emit replay and fingerprint-conflict counters through the typed `Stage2TelemetryPort`; callers cannot provide arbitrary meter names or tags.

- [ ] **Step 6: Run crypto/idempotency and architecture tests**

Run:

```bash
cd backend
./mvnw -q -Dtest='AesGcmEncryptedSecretAdapterTest,MicrometerStage2TelemetryAdapterTest,IdempotencyServiceTest,IdempotencyConcurrencyIntegrationTest,ArchitectureRulesTest' test
```

Expected: all selected tests pass; concurrent callers observe one owner and one replay.

- [ ] **Step 7: Local checkpoint**

Record test evidence, key configuration names, and the canonical fingerprint field sets in the progress file without recording any key/token value.

---

### Task 4: Add the provider port, deterministic fake provider, and transaction-split orchestration

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/PaymentProviderPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/model/ProviderPaymentRequest.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/model/ProviderConfirmationRequest.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/model/ProviderRefundRequest.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/model/ProviderPaymentLookup.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/model/ProviderRefundLookup.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/model/ProviderResult.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/model/ProviderState.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/model/PaymentProviderCapabilities.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/PaymentProviderRegistry.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/PaymentCreationTransaction.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/PaymentPreparationCoordinator.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/PaymentAttemptTransaction.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/PaymentConfirmationCoordinator.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/fake/FakePaymentProviderAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/fake/FakeProviderStatePersistenceAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/fake/FakePaymentProperties.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/provider/adapter/out/fake/FakeProviderConfiguration.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/provider/adapter/out/fake/FakePaymentProviderAdapterTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/application/service/PaymentPreparationCoordinatorIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/application/service/PaymentConfirmationCoordinatorIntegrationTest.java`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Provider-neutral port prepares payment objects, confirms attempts, requests refunds, and retrieves actual state.
- Provider result is a closed hierarchy: accepted/processing, requires action, recoverable decline, terminal decline, temporarily unavailable, and unknown result.
- Transactional phase beans persist before and after the provider call; the coordinator itself is not transactional.

- [ ] **Step 1: Write failing fake-provider and orchestration tests**

Cover deterministic references/idempotency, manual success/decline/action/processing/timeout, duplicate/out-of-order event generation, auto-success enqueueing, retry with the same provider operation key, payment-created/transition telemetry, provider call duration/result/unknown telemetry, and a probe asserting `TransactionSynchronizationManager.isActualTransactionActive()` is false inside every provider call.

```java
@Test
void providerCallHappensOutsideTransaction() {
    AtomicBoolean transactionSeen = new AtomicBoolean();
    PaymentProviderPort probe = mock(PaymentProviderPort.class);
    when(probe.capabilities(any())).thenReturn(PaymentProviderCapabilities.fake());
    when(probe.preparePayment(any())).thenAnswer(invocation -> {
        transactionSeen.set(TransactionSynchronizationManager.isActualTransactionActive());
        return ProviderResult.processing("fake_pi_123");
    });

    coordinatorWith(probe).prepare(command());

    assertThat(transactionSeen).isFalse();
}
```

- [ ] **Step 2: Run the focused tests and observe compilation failure**

Run:

```bash
cd backend
./mvnw -q -Dtest='FakePaymentProviderAdapterTest,PaymentPreparationCoordinatorIntegrationTest,PaymentConfirmationCoordinatorIntegrationTest' test
```

Expected: compilation fails because provider payment contracts and coordinators do not exist.

- [ ] **Step 3: Define the provider-neutral contract**

Use one complete interface; no fake or Stripe DTO crosses the port:

```java
public interface PaymentProviderPort {
    PaymentProviderCapabilities capabilities(PaymentAccount account);
    ProviderResult preparePayment(ProviderPaymentRequest request);
    ProviderResult confirmPayment(ProviderConfirmationRequest request);
    ProviderResult requestRefund(ProviderRefundRequest request);
    ProviderState retrievePayment(ProviderPaymentLookup lookup);
    ProviderState retrieveRefund(ProviderRefundLookup lookup);
}
```

All request records include PaymentAccount identity and a deterministic operation key. Safe provider result codes are enums owned by the application layer. `PaymentProviderRegistry` selects by the PaymentAccount's `PaymentProviderType`; Stage 2 registers FAKE and fails closed with `PAYMENT_CAPABILITY_UNAVAILABLE` for STRIPE until its adapter exists. Capabilities expose supported tax modes, payment-method families, and confirmation mode.

- [ ] **Step 4: Implement persistent fake-provider state separate from Payment**

Use `fake_provider_objects`, never the Payment projection, as the fake provider's source of truth. Generate `fake_pi_<payment-id>`, `fake_re_<refund-id>`, and deterministic provider event IDs from command IDs plus event sequence. Repeating the same operation key returns the same provider reference and does not create a second fake operation.

- [ ] **Step 5: Implement prepare/confirm as split transactions**

Preparation sequence:

```text
PaymentCreationTransaction.createLocal
PaymentProviderPort.preparePayment
PaymentCreationTransaction.recordPreparedOrUnknown
```

Confirmation sequence:

```text
PaymentAttemptTransaction.startAttempt
PaymentProviderPort.confirmPayment
PaymentAttemptTransaction.recordProviderAcknowledgementOrUnknown
```

The first transaction persists Payment/Attempt and journal facts. The provider response may set `REQUIRES_PAYMENT_METHOD`, `REQUIRES_ACTION`, `PROCESSING`, a recoverable attempt failure, or `UNKNOWN`; it may not set Payment `SUCCEEDED`. `UNKNOWN` prevents starting another attempt. Emit payment-created/transition and timed provider-result telemetry through `Stage2TelemetryPort` only after the relevant local transaction outcome is known.

- [ ] **Step 6: Configure fake behavior with safe defaults**

```yaml
payment:
  fake:
    auto-success: ${PAYMENT_FAKE_AUTO_SUCCESS:false}
```

When true, the adapter inserts a normalized success event into `provider_inbox`; it still returns a non-terminal synchronous result.

- [ ] **Step 7: Run provider and transaction-boundary tests**

Run:

```bash
cd backend
./mvnw -q -Dtest='FakePaymentProviderAdapterTest,PaymentPreparationCoordinatorIntegrationTest,PaymentConfirmationCoordinatorIntegrationTest' test
```

Expected: all selected tests pass, including same-key replay and no provider call inside a transaction.

- [ ] **Step 8: Local checkpoint**

Record provider contract, deterministic reference rules, and transaction-boundary evidence in the progress file.

---

### Task 5: Build catalog checkout sessions and the Public API

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/CreateCatalogCheckoutCommand.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/CreateCatalogCheckoutUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/ConfirmCheckoutCommand.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/ConfirmCheckoutUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/GetCheckoutUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/model/CheckoutSession.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/model/CheckoutView.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/CheckoutSessionPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/RateLimitPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/CatalogCheckoutService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/CheckoutAuthorizationService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/CheckoutSessionPersistenceAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/in/web/PublicCheckoutController.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/in/web/PublicCheckoutDtos.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/in/web/DynamicSiteCorsConfigurationSource.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/in/web/PublicRateLimitFilter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/in/web/InMemoryTokenBucketRateLimitAdapter.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/application/service/CatalogCheckoutServiceTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/adapter/in/web/PublicCheckoutApiIntegrationTest.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/admin/adapter/in/web/SecurityConfiguration.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/shared/adapter/in/web/ApiExceptionHandler.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/shared/application/PaymentApplicationException.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/catalog/application/port/out/LoadPricePort.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/catalog/adapter/out/persistence/PriceJpaRepository.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/catalog/adapter/out/persistence/PricePersistenceAdapter.java`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Exposes `POST /api/v1/public/sites/{siteKey}/checkouts` (`createCatalogCheckout`), `POST /api/v1/public/handoffs` (`exchangeCheckoutHandoff`), `POST /api/v1/public/checkouts/{checkoutId}/confirm` (`confirmCheckout`), and `GET /api/v1/public/checkouts/{checkoutId}` (`getCheckout`). Task 5 implements every route except handoff exchange, which Task 6 completes against the same contract.
- Requires exact allowed Origin and `Idempotency-Key` on creation/confirmation.
- Requires checkout bearer token for confirm/read; responses are `Cache-Control: no-store`.

- [ ] **Step 1: Write failing catalog use-case tests**

Cover active Site, published one-time Price, immutable price snapshot, `NONE`/`INCLUSIVE`, rejection of subscription/draft/unsupported tax modes, idempotent replay, checkout expiry, and no browser amount/currency fields.

```java
@Test
void catalogCheckoutCopiesThePublishedPriceAndIgnoresLaterCatalogChanges() {
    CheckoutCreation created = service.create(new CreateCatalogCheckoutCommand(
        "demo-site", "lifetime", URI.create("https://shop.example"), "create-1", "request-1"));

    assertThat(created.view().amountMinor()).isEqualTo(4_999);
    assertThat(created.view().currency()).isEqualTo("EUR");
    assertThat(created.view().priceSnapshot().version()).isEqualTo(1);
}
```

- [ ] **Step 2: Write failing HTTP tests for security and strict request shape**

Test the exact routes, missing/wrong Origin, disabled Site, expired/invalid bearer token, missing idempotency key, CORS preflight, response `no-store`, snake_case JSON, Problem Details codes, and rejection of extra `amount_minor`/`currency` properties.

- [ ] **Step 3: Run focused tests and observe failure**

Run:

```bash
cd backend
./mvnw -q -Dtest='CatalogCheckoutServiceTest,PublicCheckoutApiIntegrationTest' test
```

Expected: compilation fails because checkout use cases and routes do not exist.

- [ ] **Step 4: Implement checkout creation and 24-hour token persistence**

Resolve Site by `SiteKey`, exact-match the normalized Origin, load the currently published one-time Price by `(siteId, priceKey)`, validate tax capability before creating Payment, and delegate provider preparation to Task 4. Generate a 32-byte token; return plaintext once and persist only SHA-256.

The response record has these stable fields:

```java
public record CheckoutCreationResponse(
    UUID checkoutId,
    String checkoutToken,
    UUID paymentId,
    long amountMinor,
    String currency,
    PriceSnapshotResponse price,
    String providerType,
    String confirmationMode,
    Map<String, Object> providerClientPayload,
    String status,
    Instant expiresAt
) {}
```

Enable strict JSON request binding (`spring.jackson.deserialization.fail-on-unknown-properties=true`) and add a regression test for an existing Stage 1 DTO so the browser cannot smuggle amount/currency or silently misspell fields.

- [ ] **Step 5: Implement bearer authorization, confirm, and status read**

Accept exactly one `Authorization: Bearer <token>` header, hash and constant-time compare it, verify session/payment relationship and expiry, then call Task 4 confirmation. Confirm idempotency scope includes checkout ID and operation. GET returns only safe state and never returns the token again.

- [ ] **Step 6: Add Public API security, dynamic CORS, and bounded local rate limiting**

Permit `/api/v1/public/**`, ignore CSRF there, and keep admin rules unchanged. CORS must derive Site from the route and allow one exact configured Origin, required headers `Authorization`, `Content-Type`, `Idempotency-Key`, and no credentials. Implement a bounded in-memory token bucket behind a `RateLimitPort` keyed by Site/IP/operation; defaults are 20 checkout creates, 30 confirmations/handoff exchanges, and 120 reads per minute. Tests inject deterministic capacity/time. Return `429` with `Retry-After` and stable Problem Details. Do not trust forwarding headers in this stage.

- [ ] **Step 7: Add stable application exceptions and error mapping**

Introduce a safe `PaymentApplicationException(code, status, title)` outside domain and map all approved error codes without returning provider messages. Add validation field errors to Problem Details while preserving existing fields.

- [ ] **Step 8: Run Public API, security, OpenAPI, and Stage 1 auth tests**

Run:

```bash
cd backend
./mvnw -q -Dtest='CatalogCheckoutServiceTest,PublicCheckoutApiIntegrationTest,AdminAuthenticationIntegrationTest,AdminCatalogApiIntegrationTest' test
```

Expected: all selected tests pass; Stage 1 admin/CSRF behavior is unchanged.

- [ ] **Step 9: Local checkpoint**

Record Public API operation IDs, CORS decisions, error codes, and focused results in the progress file.

---

### Task 6: Add scoped Site API keys, dynamic Partner payments, and handoff exchange

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/partners/domain/SiteApiKey.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/partners/domain/SiteApiKeyId.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/partners/domain/PartnerScope.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/partners/application/port/in/ManageSiteApiKeysUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/partners/application/port/out/SiteApiKeyPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/partners/application/service/SiteApiKeyService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/partners/adapter/out/security/Argon2SiteApiKeyAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/partners/adapter/out/persistence/SiteApiKeyPersistenceAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/partners/adapter/in/web/PartnerAuthenticationFilter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/partners/adapter/in/web/AuthenticatedSite.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/partners/adapter/in/web/SiteApiKeyAdminController.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/CreateDynamicPaymentCommand.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/CreateDynamicPaymentUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/IssueHandoffUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/ExchangeHandoffUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/DynamicPaymentService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/HandoffService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/in/web/PartnerPaymentController.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/in/web/PublicHandoffController.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/partners/application/service/SiteApiKeyServiceTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/partners/adapter/in/web/PartnerAuthenticationIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/adapter/in/web/PartnerPaymentApiIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/application/service/HandoffServiceIntegrationTest.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/admin/adapter/in/web/SecurityConfiguration.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/admin/domain/AuditAction.java`

**Interfaces:**
- Exposes `POST /api/v1/partner/payments` (`createPartnerPayment`), `GET /api/v1/partner/payments/{paymentId}` (`getPartnerPayment`), and `POST /api/v1/partner/payments/{paymentId}/handoffs` (`issuePartnerPaymentHandoff`).
- Exposes `POST /api/v1/admin/sites/{siteId}/api-keys` (`createAdminSiteApiKey`), `POST /api/v1/admin/sites/{siteId}/api-keys/{keyId}/rotate` (`rotateAdminSiteApiKey`), and `POST /api/v1/admin/sites/{siteId}/api-keys/{keyId}/revoke` (`revokeAdminSiteApiKey`).
- Browser handoff exchange consumes a one-time token and returns a normal checkout token.

- [ ] **Step 1: Write failing Site API-key lifecycle and authentication tests**

Test prefix lookup, Argon2id secret hash, dummy verification for unknown prefix, scopes, expiry, revoke, 24-hour rotation overlap, one-time secret reveal, throttled `last_used_at`, and no credential in logs/DTO readback.

Use key shape `gpk_<12-char-prefix>_<43-char-base64url-secret>` and split only after strict full-format validation.

- [ ] **Step 2: Write failing Partner payment and handoff tests**

Cover Site identity from principal, positive amount/currency, metadata limits, return URL Origin policy, unique external order within Site, same external order allowed across Sites, `payments:create` vs `payments:read`, 10-minute handoff expiry, Origin binding, one-time consumption, and idempotent issue/exchange.

- [ ] **Step 3: Run focused tests and observe failure**

Run:

```bash
cd backend
./mvnw -q -Dtest='SiteApiKeyServiceTest,PartnerAuthenticationIntegrationTest,PartnerPaymentApiIntegrationTest,HandoffServiceIntegrationTest' test
```

Expected: compilation fails because Partner credential and dynamic payment components do not exist.

- [ ] **Step 4: Implement Site API-key hashing and lifecycle**

Reuse the established Bouncy Castle Argon2id parameters from admin passwords unless profiling proves a separate setting necessary. Store prefix, hash, scopes, status, created/expiry/last-used timestamps only. Rotation creates a new key and caps the old key expiry at `now + 24h`; revoke invalidates immediately. Update `last_used_at` at most once per five minutes per key. Extend `AuditAction` with `SITE_API_KEY_CREATED`, `SITE_API_KEY_ROTATED`, and `SITE_API_KEY_REVOKED`, and record the matching admin audit atomically with each mutation.

- [ ] **Step 5: Implement Partner authentication and scope checks**

`PartnerAuthenticationFilter` parses one bearer key, performs prefix lookup plus Argon2 verification, installs an `AuthenticatedSite(siteId, keyId, scopes)` principal, and returns stable `SITE_API_KEY_INVALID` without exposing lookup outcome. Add method-level or controller-level scope guards for create/read.

Apply the same `RateLimitPort` per credential/Site/operation with configurable defaults of 60 payment creates, 120 handoff issues, and 300 reads per minute. Rate-limit rejection happens after credential verification, returns `429`, and does not update `last_used_at`.

- [ ] **Step 6: Implement dynamic Payment creation**

Canonical idempotency scope is `partner:<siteId>:payments:create`. Validate metadata as a flat string map with at most 20 entries, 64-character keys, 500-character values, and 4 KiB canonical JSON. Derive Site from the principal and use the unique `(site_id, external_order_id)` database guarantee. If present, the return URL must have no user-info or fragment and its normalized origin must exactly match the Site allowlist; path/query may be application-specific. The response includes Payment summary plus a one-time handoff token, never a checkout token or API key.

```java
public record DynamicPaymentResponse(
    UUID paymentId,
    String externalOrderId,
    long amountMinor,
    String currency,
    String status,
    String handoffToken,
    Instant handoffExpiresAt
) {}
```

- [ ] **Step 7: Implement handoff issue and public exchange**

Persist handoff token hash, Payment/Site/Origin, 10-minute expiry, and consumed timestamp. Exchange acquires the row lock, verifies exact Origin and digest, marks it consumed, then issues a normal 24-hour checkout session. Concurrent exchanges yield one success and one `CHECKOUT_TOKEN_INVALID` response.

- [ ] **Step 8: Wire Spring Security without weakening admin CSRF**

Permit public handoff exchange, authenticate Partner routes with the new filter, protect `/api/v1/admin/**` with existing admin session/CSRF, and keep `/api/admin/v1/**` behavior identical. Ignore CSRF only for Public/Partner routes.

- [ ] **Step 9: Run Partner/Public/security regression tests**

Run:

```bash
cd backend
./mvnw -q -Dtest='SiteApiKeyServiceTest,PartnerAuthenticationIntegrationTest,PartnerPaymentApiIntegrationTest,HandoffServiceIntegrationTest,PublicCheckoutApiIntegrationTest,AdminAuthenticationIntegrationTest' test
```

Expected: all selected tests pass, including cross-Site isolation and one-time handoff consumption.

- [ ] **Step 10: Local checkpoint**

Record credential format, scopes, rotation rules, and focused test evidence without storing plaintext keys.

---

### Task 7: Process provider inbox events and implement manual reconciliation

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/model/ProviderEventEnvelope.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/ProviderInboxPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/PaymentTransitionPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/PaymentOutboxPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/ReconcilePaymentUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/ProviderInboxService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/ProviderInboxWorker.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/PaymentTransitionService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/ReconcilePaymentService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/ProviderInboxPersistenceAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/PaymentTransitionPersistenceAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/PaymentOutboxPersistenceAdapter.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/application/service/ProviderInboxServiceIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/application/service/ReconcilePaymentServiceIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/adapter/out/persistence/ProviderInboxClaimIntegrationTest.java`

**Interfaces:**
- Inbox insert is deduplicated by provider/account/event ID and returns quickly.
- One transaction applies projection + PaymentEvent + OutboxEvent and marks inbox processed.
- Reconciliation uses the same normalized transition engine as inbox processing.

- [ ] **Step 1: Write failing duplicate, out-of-order, atomicity, and claim tests**

Test duplicate insert, concurrent `SKIP LOCKED` workers, success after processing, late processing after success, terminal failure, unknown resolution, rollback when journal/outbox insert fails, and no outbox event for a no-op transition.

```java
@Test
void delayedProcessingCannotRegressSucceededPayment() {
    fixtures.process(providerEvent("evt-success", "PAYMENT_SUCCEEDED", 20));
    fixtures.process(providerEvent("evt-processing", "PAYMENT_PROCESSING", 10));

    assertThat(payments.findById(paymentId).orElseThrow().status())
        .isEqualTo(PaymentStatus.SUCCEEDED);
    assertThat(events.findByPayment(paymentId)).extracting(PaymentEvent::type)
        .doesNotContainSubsequence(PaymentEventType.PAYMENT_SUCCEEDED,
            PaymentEventType.PAYMENT_PROCESSING);
}
```

- [ ] **Step 2: Run the focused tests and observe failure**

Run:

```bash
cd backend
./mvnw -q -Dtest='ProviderInboxServiceIntegrationTest,ReconcilePaymentServiceIntegrationTest,ProviderInboxClaimIntegrationTest' test
```

Expected: compilation fails because inbox/transition services do not exist.

- [ ] **Step 3: Implement normalized insert-if-absent and worker claiming**

Validate the envelope, hash its safe payload, and insert with `ON CONFLICT DO NOTHING`. Claim bounded batches with `FOR UPDATE SKIP LOCKED`, set a lease owner/expiry, and recover abandoned leases. The fake adapter writes through this port; future Stripe webhook parsing will do the same.

- [ ] **Step 4: Implement one transactional transition kernel**

Under Payment row lock:

```text
normalize actual provider fact
apply monotonic domain transition
update Payment/Attempt/Refund projection when changed
append one PaymentEvent when changed
insert one immutable OutboxEvent when callback type exists
mark provider_inbox processed
```

Create the OutboxEvent only when the Site has an active callback configuration; the atomicity integration test configures one explicitly. If no callback is configured, the PaymentEvent remains the durable business fact and no undeliverable outbox row is fabricated. If the transition is stale/no-op, mark inbox processed but do not append a regressive event or outbox row. On any write failure, none of the five writes commit.

- [ ] **Step 5: Implement manual reconciliation**

Create an idempotent use case whose first short transaction acquires a durable reconciliation lease on Payment. Attempt creation checks this lease. Call `retrievePayment` outside the transaction, then send the normalized result through the transition kernel with source `RECONCILIATION` and clear the lease in the completing transaction. A failure leaves an expiring lease that can be safely recovered. Reconciliation of the already-known state is a successful no-op.

- [ ] **Step 6: Add scheduling and metrics**

Schedule small inbox batches with configurable delay; tests call the worker directly. Emit received/duplicate/processed/failed/lag and provider result telemetry through the `Stage2TelemetryPort` established in Task 3. Labels are limited to bounded result/provider values and the configured Site key; never label by Payment ID, Refund ID, Attempt ID, request ID, provider object ID, or event ID.

- [ ] **Step 7: Run inbox, reconciliation, persistence, and domain tests**

Run:

```bash
cd backend
./mvnw -q -Dtest='ProviderInboxServiceIntegrationTest,ReconcilePaymentServiceIntegrationTest,ProviderInboxClaimIntegrationTest,PaymentTest,PaymentEventAppendOnlyIntegrationTest' test
```

Expected: all selected tests pass and stale events create no callbacks.

- [ ] **Step 8: Local checkpoint**

Record claim strategy, transition atomicity proof, and selected metrics in the progress file.

---

### Task 8: Configure encrypted callbacks and deliver the transactional outbox

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/webhooks/domain/CallbackConfiguration.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/webhooks/domain/CallbackSecretVersion.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/webhooks/domain/OutboxEvent.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/webhooks/domain/OutboxStatus.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/webhooks/domain/DeliveryAttempt.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/webhooks/application/port/in/ConfigureCallbackUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/webhooks/application/port/in/RotateCallbackSecretUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/webhooks/application/port/in/RetryCallbackUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/webhooks/application/port/out/CallbackConfigurationPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/webhooks/application/port/out/CallbackHttpPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/webhooks/application/service/CallbackConfigurationService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/webhooks/application/service/CallbackSigner.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/webhooks/application/service/CallbackDeliveryService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/webhooks/application/service/OutboxWorker.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/webhooks/adapter/out/persistence/CallbackPersistenceAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/webhooks/adapter/out/http/SafeCallbackHttpAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/webhooks/adapter/in/web/CallbackAdminController.java`
- Create: `backend/src/main/resources/application-local.yml`
- Create: `backend/src/test/java/dev/genericpayments/platform/webhooks/application/service/CallbackSignerTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/webhooks/application/service/CallbackDeliveryServiceIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/webhooks/adapter/out/http/SafeCallbackHttpAdapterTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/webhooks/adapter/out/persistence/CallbackSecretPersistenceIntegrationTest.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/admin/domain/AuditAction.java`

**Interfaces:**
- One active callback endpoint per Site with versioned encrypted signing secrets.
- Signs exact bytes as `<unix-seconds>.<raw-body>` with HMAC-SHA256.
- Automatic delivery uses immutable endpoint/secret snapshots; manual retry explicitly selects snapshot or current configuration.
- Exposes `PUT /api/v1/admin/sites/{siteId}/callback` (`configureAdminSiteCallback`), `POST /api/v1/admin/sites/{siteId}/callback/rotate-secret` (`rotateAdminSiteCallbackSecret`), and `POST /api/v1/admin/outbox/{eventId}/retry` (`retryAdminOutboxEvent`).

- [ ] **Step 1: Write failing signer, retry schedule, encryption, and SSRF tests**

Cover deterministic signature vectors, raw-byte sensitivity, exact headers, 2xx success, redirect rejection, timeout/network/non-2xx failures, retry times `0, 1m, 5m, 30m, 2h, 12h`, transition to `DEAD`, outbox pending/delivered/retried/dead/lag telemetry, secret rotation overlap, and encrypted-at-rest assertions.

SSRF tests reject HTTP in non-local profiles, user-info, fragments, disallowed ports, loopback, private, link-local, multicast, IPv4-mapped IPv6, and common metadata endpoints. The local profile permits HTTP/private resolution only for an explicit hostname allowlist; configure `localhost`, `127.0.0.1`, and Compose-only `callback-receiver`. The production/default profile ignores that local allowlist.

- [ ] **Step 2: Run focused tests and observe failure**

Run:

```bash
cd backend
./mvnw -q -Dtest='CallbackSignerTest,CallbackDeliveryServiceIntegrationTest,SafeCallbackHttpAdapterTest,CallbackSecretPersistenceIntegrationTest' test
```

Expected: compilation fails because webhook domain and delivery components do not exist.

- [ ] **Step 3: Implement callback configuration and secret rotation**

Validate URL on configuration and again before each delivery. Generate a 32-byte callback secret, encrypt through `EncryptedSecretPort`, persist nonce/ciphertext/key version, and reveal plaintext only in the create/rotate response. Keep a previous version for at least 24 hours and while any unfinished outbox event references it. Add a daily cleanup that deletes an expired version only when no pending or retryable outbox event references it.

- [ ] **Step 4: Implement canonical outbox payload and HMAC signing**

Serialize the payload exactly once when creating OutboxEvent, persist the raw UTF-8 JSON bytes or a canonical JSON representation, and sign the identical bytes sent. Headers are exactly:

```text
Payment-Event-Id
Payment-Timestamp
Payment-Signature: v1=<lowercase-hex-hmac-sha256>
Payment-Secret-Version
Content-Type: application/json
```

- [ ] **Step 5: Implement SSRF-safe Java HTTP adapter**

Use Java `HttpClient` with redirect policy `NEVER`, bounded connect/request timeouts, a restricted method/body/header set, DNS resolution validation immediately before sending, and response-body discard with a small hard limit. Revalidate every retry. Never include response bodies in errors/logs.

- [ ] **Step 6: Implement outbox claims, delivery attempts, retries, and manual retry**

Claim with a lease and `SKIP LOCKED`, increment one durable attempt number, record safe result category/status, and schedule the next approved delay. After six total attempts set `DEAD`. Manual retry requires `Idempotency-Key` and creates a new attempt for the same event ID using the administrator-selected configuration mode. Publish pending as a gauge and delivery/retry/dead/lag facts through `Stage2TelemetryPort`; callback target failure must not change application readiness.

- [ ] **Step 7: Expose admin callback configuration and audit mutations**

Implement `PUT /api/v1/admin/sites/{siteId}/callback`, secret rotation, and manual outbox retry. Return full secret only from create/rotation DTOs. Require current admin session and CSRF, preserve safe read DTOs, and extend `AuditAction` with `SITE_CALLBACK_CONFIGURED`, `SITE_CALLBACK_SECRET_ROTATED`, and `CALLBACK_RETRY_REQUESTED`. Audit the matching mutation without secret data.

- [ ] **Step 8: Run callback and transaction regression tests**

Run:

```bash
cd backend
./mvnw -q -Dtest='CallbackSignerTest,CallbackDeliveryServiceIntegrationTest,SafeCallbackHttpAdapterTest,CallbackSecretPersistenceIntegrationTest,ProviderInboxServiceIntegrationTest,AdminAuthenticationIntegrationTest' test
```

Expected: all selected tests pass; redirects and forbidden addresses are rejected; delivery failure does not affect readiness.

- [ ] **Step 9: Local checkpoint**

Record retry timestamps, accepted URL policy, and encryption-at-rest evidence without recording a callback secret.

---

### Task 9: Implement refunds and the complete Admin payment API

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/CreateRefundCommand.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/CreateRefundUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/ListPaymentsUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/GetPaymentDetailsUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/SimulateFakeEventUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/RefundReservationTransaction.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/RefundCoordinator.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/PaymentQueryService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/FakeEventSimulationService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/in/web/PaymentAdminController.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/in/web/PaymentAdminDtos.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/application/service/RefundCoordinatorIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/adapter/in/web/PaymentAdminApiIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/adapter/in/web/PaymentOpenApiIntegrationTest.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/admin/domain/AuditAction.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/admin/adapter/in/web/SecurityConfiguration.java`

**Interfaces:**
- Exposes `GET /api/v1/admin/payments` (`listAdminPayments`), `GET /api/v1/admin/payments/{paymentId}` (`getAdminPayment`), `POST /api/v1/admin/payments/{paymentId}/refunds` (`createAdminRefund`), `POST /api/v1/admin/payments/{paymentId}/reconcile` (`reconcileAdminPayment`), and `POST /api/v1/admin/payments/{paymentId}/fake-events` (`simulateAdminFakeEvent`). Task 6 owns API-key routes and Task 8 owns callback/outbox routes, together completing section 10.3.
- Query response supplies projection, attempts, immutable event timeline, refunds, outbox, and delivery attempts.
- Refund provider call is outside the row-lock transaction and final money status comes from inbox/reconciliation.

- [ ] **Step 1: Write failing concurrent refund and unknown-result tests**

Test full/partial refunds, pending reservation accounting, over-refund rejection, currency matching, concurrent requests serialized under Payment row lock, provider timeout to Refund `UNKNOWN`, same-key replay, exact refund-request/result telemetry, and confirmed refund events moving Payment to partial/full states.

```java
@Test
void concurrentRefundsCannotReserveMoreThanPaidAmount() throws Exception {
    Payment payment = fixtures.succeededPayment(1_000);

    List<RefundResult> results = runConcurrently(
        () -> refunds.create(command(payment.id(), 700, "refund-a")),
        () -> refunds.create(command(payment.id(), 700, "refund-b")));

    assertThat(results).filteredOn(RefundResult::accepted).hasSize(1);
    assertThat(results).filteredOn(result -> !result.accepted()).hasSize(1);
}
```

- [ ] **Step 2: Write failing Admin HTTP/OpenAPI tests**

Cover filters by Site/status/order/payment/provider/date, Site isolation, details shape/order, session/CSRF, idempotency headers, safe error codes, admin audits, fake controls only for FAKE accounts, and every specified operation ID/path in `/v3/api-docs`.

- [ ] **Step 3: Run focused tests and observe failure**

Run:

```bash
cd backend
./mvnw -q -Dtest='RefundCoordinatorIntegrationTest,PaymentAdminApiIntegrationTest,PaymentOpenApiIntegrationTest' test
```

Expected: compilation fails because refund and Admin payment use cases do not exist.

- [ ] **Step 4: Implement refund reservation and provider orchestration**

First transaction locks Payment, sums `PENDING`, `PROCESSING`, `UNKNOWN`, and `SUCCEEDED` refunds, checks available amount, inserts Refund plus `REFUND_REQUESTED`, and completes idempotency ownership. Call provider outside the transaction. Second transaction stores acknowledgement or `UNKNOWN`; confirmed success still arrives through inbox/reconciliation. Emit refund-request/result telemetry only after the corresponding local transaction commits.

- [ ] **Step 5: Implement payment list/detail queries**

Use explicit query objects with validated pagination and sort, default newest-first, bounded page size, and optional filters. The detail timeline is ordered by sequence number, attempts by attempt number, refunds by created time, and deliveries by durable attempt number. Never expose encrypted response artifacts, hashes, secret versions' ciphertext, provider payloads, or authorization data.

- [ ] **Step 6: Implement admin mutations and fake simulation controls**

Expose refund, reconciliation, fake event, and callback retry through the approved routes. Fake actions insert provider events; they do not directly mutate Payment. Require confirmation-relevant request fields and `Idempotency-Key`. Extend `AuditAction` with `PAYMENT_REFUND_REQUESTED`, `PAYMENT_RECONCILIATION_REQUESTED`, and `FAKE_PROVIDER_EVENT_SIMULATED`; record the matching success audit in the same transaction as the local mutation.

- [ ] **Step 7: Extend OpenAPI and regenerate stable error schemas**

Give every operation an explicit operation ID and document required headers, Problem Details, enum values, one-time secret fields, and pagination. Preserve Stage 1 operation IDs and response shapes.

- [ ] **Step 8: Run all backend tests**

Run:

```bash
cd backend
./mvnw test
```

Expected: the complete backend suite passes, including Stage 1, architecture, persistence, Public, Partner, Admin, and Cucumber tests currently present.

- [ ] **Step 9: Local checkpoint**

Record backend test totals, refund concurrency result, and OpenAPI route inventory in the progress file.

---

### Task 10: Build the Admin payments and Site integration UI

**Files:**
- Modify: `frontend/admin/src/lib/api/client.ts`
- Regenerate: `frontend/admin/src/lib/api/schema.d.ts`
- Modify: `frontend/admin/src/components/AppShell.tsx`
- Modify: `frontend/admin/src/proxy.ts`
- Modify: `frontend/admin/src/app/globals.css`
- Create: `frontend/admin/src/app/payments/page.tsx`
- Create: `frontend/admin/src/app/payments/[paymentId]/page.tsx`
- Create: `frontend/admin/src/app/sites/[siteId]/integration/page.tsx`
- Create: `frontend/admin/src/components/payments/PaymentFilters.tsx`
- Create: `frontend/admin/src/components/payments/PaymentList.tsx`
- Create: `frontend/admin/src/components/payments/PaymentDetails.tsx`
- Create: `frontend/admin/src/components/payments/PaymentTimeline.tsx`
- Create: `frontend/admin/src/components/payments/RefundDialog.tsx`
- Create: `frontend/admin/src/components/payments/PaymentActions.tsx`
- Create: `frontend/admin/src/components/integration/SiteApiKeys.tsx`
- Create: `frontend/admin/src/components/integration/CallbackConfiguration.tsx`
- Create: `frontend/admin/src/components/integration/OneTimeSecret.tsx`
- Create: `frontend/admin/src/components/payments/PaymentList.test.tsx`
- Create: `frontend/admin/src/components/payments/PaymentDetails.test.tsx`
- Create: `frontend/admin/src/components/payments/RefundDialog.test.tsx`
- Create: `frontend/admin/src/components/integration/SiteIntegration.test.tsx`
- Modify: `frontend/admin/src/components/AppShell.test.tsx`
- Modify: `frontend/admin/src/proxy.test.ts`

**Interfaces:**
- Admin browser continues to call backend only through `/api/backend/**` and receives session/CSRF protection from existing BFF behavior.
- Every financial/retry action creates a fresh browser idempotency key once per user intent and reuses it for network retry.
- Full API-key/callback secret exists only in component state until explicit dismissal/navigation.

- [ ] **Step 1: Write failing list, detail, action, and integration-setting component tests**

Cover filter serialization, abort-on-unmount, loading/empty/error states, ordered timeline, attempt/refund/delivery display, confirmation focus management, double-submit prevention, one-time secret reveal/copy/dismiss, scope selection, callback rotation, and safe error titles.

```tsx
it("reuses one idempotency key when a refund request is retried", async () => {
  const requests: RequestInit[] = [];
  vi.stubGlobal("fetch", async (_url: string, init: RequestInit) => {
    requests.push(init);
    if (requests.length === 1) throw new TypeError("network");
    return Response.json({ refund_id: "refund-1", status: "PENDING" });
  });

  render(<RefundDialog payment={payment} onClose={() => {}} onCreated={() => {}} />);
  await submitRefundAndRetry();

  const keys = requests.map((request) => new Headers(request.headers).get("Idempotency-Key"));
  expect(keys[0]).toBeTruthy();
  expect(keys[1]).toBe(keys[0]);
});
```

- [ ] **Step 2: Run focused frontend tests and observe failure**

Run:

```bash
cd frontend
pnpm --filter admin test --run src/components/payments src/components/integration
```

Expected: test discovery/import fails because the Stage 2 components do not exist.

- [ ] **Step 3: Extend the typed client for query parameters and idempotency**

Keep path-segment validation and add separate `URLSearchParams` construction. Extend options with `query`, `idempotencyKey`, and caller-supplied safe headers limited to approved names. Do not let callers set `Cookie`, `Authorization`, `Origin`, or forwarding headers. Preserve CSRF injection and `no-store`.

- [ ] **Step 4: Regenerate OpenAPI types from the running backend**

Start the backend with the local environment, then run:

```bash
cd frontend
pnpm --filter admin generate:api
```

Inspect the diff-equivalent local changes and verify Stage 1 operations remain while Stage 2 payment/key/callback DTOs appear. Never hand-edit `schema.d.ts`.

- [ ] **Step 5: Implement payments navigation, filters, and list**

Add `Payments` to `AppShell`, guard `/payments/:path*` in `proxy.ts`, reflect filters in URL search params, and render bounded pagination. Use semantic table markup on wide screens with a labelled stacked representation on narrow screens.

- [ ] **Step 6: Implement payment details and guarded actions**

Render amount/source/Site/account/snapshot, state badge, attempts, event timeline, refunds, outbox, and deliveries. Refund/reconcile/retry/fake controls require a confirmation dialog, visible pending state, disabled duplicate submission, and focus restoration. Only show fake controls for FAKE provider accounts.

- [ ] **Step 7: Implement Site integration settings and one-time reveals**

Add a link from the Site list/catalog to `/sites/{siteId}/integration`. API-key and callback-secret creation/rotation responses render through `OneTimeSecret`; dismissal clears component state. Never store plaintext in URL, localStorage, sessionStorage, query cache, or console.

- [ ] **Step 8: Add status styling and accessibility behavior**

Add status classes for all Payment/Attempt/Refund/Outbox states, keyboard-operable dialogs, labelled form errors, live regions for completion, visible focus, and reduced-motion support. Keep English UI copy consistent with Stage 1.

- [ ] **Step 9: Run admin verification**

Run:

```bash
cd frontend
pnpm --filter admin lint
pnpm --filter admin typecheck
pnpm --filter admin test --run
pnpm --filter admin build
```

Expected: lint, typecheck, all component/client/proxy tests, and Next.js production build pass.

- [ ] **Step 10: Local checkpoint**

Record admin test count, production build result, and manual one-time-secret storage inspection in the progress file.

---

### Task 11: Build the framework-agnostic TypeScript SDK and Web Component

**Files:**
- Modify: `frontend/pnpm-workspace.yaml`
- Modify: `frontend/pnpm-lock.yaml`
- Create: `frontend/sdk/package.json`
- Create: `frontend/sdk/tsconfig.json`
- Create: `frontend/sdk/vitest.config.ts`
- Create: `frontend/sdk/src/index.ts`
- Create: `frontend/sdk/src/client.ts`
- Create: `frontend/sdk/src/contracts.ts`
- Create: `frontend/sdk/src/errors.ts`
- Create: `frontend/sdk/src/storage.ts`
- Create: `frontend/sdk/src/polling.ts`
- Create: `frontend/sdk/src/providers/provider-driver.ts`
- Create: `frontend/sdk/src/providers/fake-driver.ts`
- Create: `frontend/sdk/src/widget/generic-payment-widget.ts`
- Create: `frontend/sdk/src/widget/styles.ts`
- Create: `frontend/sdk/src/client.test.ts`
- Create: `frontend/sdk/src/polling.test.ts`
- Create: `frontend/sdk/src/storage.test.ts`
- Create: `frontend/sdk/src/providers/fake-driver.test.ts`
- Create: `frontend/sdk/src/widget/generic-payment-widget.test.ts`
- Create: `frontend/sdk/src/test/setup.ts`
- Create: `frontend/sdk/README.md`

**Interfaces:**
- Package name: `@generic-payments/sdk`; output: ESM plus declarations; no React, Next.js, or Node runtime dependency.
- Public API: imperative `GenericPaymentClient`, `defineGenericPaymentWidget()`, and `<generic-payment-widget>`.
- Provider driver is internal and replaceable; fake driver calls the server-confirmation endpoint.

- [ ] **Step 1: Add the SDK workspace manifest and write failing client/polling tests**

The package scripts are exact:

```json
{
  "name": "@generic-payments/sdk",
  "version": "0.1.0",
  "type": "module",
  "private": true,
  "exports": { ".": { "types": "./dist/index.d.ts", "import": "./dist/index.js" } },
  "files": ["dist", "README.md"],
  "scripts": {
    "build": "tsc -p tsconfig.json",
    "typecheck": "tsc -p tsconfig.json --noEmit",
    "test": "vitest"
  }
}
```

Add Vitest/TypeScript/testing-library development dependencies through pnpm so exact resolved versions enter `frontend/pnpm-lock.yaml`.

- [ ] **Step 2: Write failing Web Component accessibility, state, event, and theming tests**

Test all nine widget states, keyboard activation, labelled controls, focus, reduced motion, CSS variables, one `succeeded` event per transition, safe event details, disconnected cancellation, and no Partner credential attribute/property.

- [ ] **Step 3: Run SDK tests and observe failure**

Run:

```bash
cd frontend
pnpm --filter @generic-payments/sdk test --run
```

Expected: test compilation fails because SDK source modules do not exist.

- [ ] **Step 4: Implement the imperative client and exact transport rules**

Expose:

```ts
export interface GenericPaymentClient {
  createCatalogCheckout(input: CatalogCheckoutInput): Promise<CheckoutSession>;
  exchangeHandoff(input: HandoffInput): Promise<CheckoutSession>;
  confirm(session: CheckoutSession): Promise<CheckoutState>;
  get(session: CheckoutSession, signal?: AbortSignal): Promise<CheckoutState>;
  watch(session: CheckoutSession, options?: WatchOptions): AsyncIterable<CheckoutState>;
  clear(session: CheckoutSession): void;
}
```

Generate idempotency keys with `crypto.randomUUID()`, send token only in Authorization, set Origin naturally through browser fetch, reject redirects, surface stable safe errors, and never put token in a URL.

- [ ] **Step 5: Implement bounded polling, cancellation, and session recovery**

Use delays `500ms, 1s, 2s, 4s, 5s` capped at 5 seconds with jitter disabled in deterministic tests and enabled in production. Stop on terminal/expired state, AbortSignal, disconnect, or 10-minute watch deadline. Save only checkout ID, token, expiry, API base, Site/Price identity, and last safe state in `sessionStorage`; never use localStorage.

- [ ] **Step 6: Implement provider driver and fake confirmation**

The driver receives safe `confirmationMode` plus provider client payload. Fake driver calls Public confirm. Unknown provider types fail closed with a typed SDK error. Browser success is documented and emitted only as UX state, never as proof of entitlement.

- [ ] **Step 7: Implement the Web Component with Shadow DOM**

Support attributes/properties for `site-key`, `price-key`, `api-base-url`, `locale`, and theme CSS variables. Render isolated styles, safe text-only messages, accessible buttons/status regions, and lifecycle events `ready`, `processing`, `requires-action`, `succeeded`, `failed`, `unknown`, and `expired`. Event detail may contain Payment ID/status but no checkout token or provider secret.

- [ ] **Step 8: Document SDK integration and security boundary**

README examples show script import, imperative client, Web Component, handoff exchange, theme variables, recovery, and callback/Partner API as the trusted fulfillment signal. Explicitly state that dynamic amount creation belongs on the site's backend.

- [ ] **Step 9: Run SDK and admin workspace verification**

Run:

```bash
cd frontend
pnpm --filter @generic-payments/sdk typecheck
pnpm --filter @generic-payments/sdk test --run
pnpm --filter @generic-payments/sdk build
pnpm --filter admin test --run
```

Expected: SDK tests/typecheck/build and admin regression tests pass; `dist` contains ESM JavaScript and declarations.

- [ ] **Step 10: Local checkpoint**

Record SDK test count, bundle file list/size, exported symbols, and secret-storage review in the progress file.

---

### Task 12: Create the static demo site and Playwright browser journeys

**Files:**
- Create: `examples/demo-site/package.json`
- Create: `examples/demo-site/pnpm-lock.yaml`
- Create: `examples/demo-site/tsconfig.json`
- Create: `examples/demo-site/vite.config.ts`
- Create: `examples/demo-site/playwright.config.ts`
- Create: `examples/demo-site/index.html`
- Create: `examples/demo-site/src/main.ts`
- Create: `examples/demo-site/src/styles.css`
- Create: `examples/demo-site/tests/checkout.spec.ts`
- Create: `examples/demo-site/tests/support/admin-api.ts`
- Create: `examples/demo-site/Dockerfile`
- Create: `examples/demo-site/nginx.conf`
- Create: `.dockerignore`
- Modify: `frontend/sdk/package.json`

**Interfaces:**
- Static browser site imports the built SDK and contains no backend secret or Partner API key.
- Configuration is non-secret build/runtime data: Public API base, Site key, Price key, locale, and theme.
- Playwright verifies the browser flow against the real backend/fake provider/PostgreSQL stack.

- [ ] **Step 1: Create the standalone demo manifest and failing Playwright tests**

Use a local file dependency on `../../frontend/sdk`, Vite for static build, and Playwright for browser tests. Pin exact resolved versions in `examples/demo-site/pnpm-lock.yaml`.

Write journeys for success, recoverable decline then success, processing, unknown then reconciliation, failure, and reload recovery. Test helpers may use admin endpoints with credentials supplied only through test environment; browser page context must never receive them.

- [ ] **Step 2: Run the demo build/test commands and observe failure**

Run:

```bash
cd examples/demo-site
corepack pnpm install --frozen-lockfile
corepack pnpm build
corepack pnpm exec playwright test
```

Expected: build or tests fail because the demo source/server and Stage 2 stack are not yet wired.

- [ ] **Step 3: Implement a minimal integration page**

Render a product summary, `<generic-payment-widget>`, safe state explanation, reload-recovery notice, and links to integration documentation. Configure through `data-*` values populated at build/runtime; do not accept amount/currency from the page as authoritative.

- [ ] **Step 4: Add accessible theme and responsive layout**

Use demo-owned CSS around the Shadow DOM widget plus documented CSS custom properties. Verify 320px width, keyboard-only operation, status announcements, visible focus, and reduced motion.

- [ ] **Step 5: Build a non-root static container**

Because the demo Docker build uses the repository root as context to reach both `frontend/sdk` and `examples/demo-site`, add a root `.dockerignore` excluding every `node_modules`, `.next`, `dist`, `target`, `.git`, IDE data, and `.env` except safe `.env.example` templates. Build SDK and demo in Node 24 stages, then copy only static assets to an unprivileged nginx image/config listening on port 8080. The image must contain no source `.env`, admin credentials, lockfile auth, or Partner keys.

- [ ] **Step 6: Implement Playwright setup through trusted test helpers**

Provision a unique fake account/Site/one-time Price through the admin API, configure the demo Site Origin, and control fake events through authenticated admin calls. Assertions inspect rendered browser state, sessionStorage recovery, network requests without URL tokens, and absence of Partner credentials.

- [ ] **Step 7: Run demo verification against the local Stage 2 stack**

Run:

```bash
cd examples/demo-site
corepack pnpm build
corepack pnpm exec playwright test
```

Expected: all six journeys pass with screenshots/traces retained only on failure.

- [ ] **Step 8: Local checkpoint**

Record demo build output, Playwright counts, tested browsers, and browser storage/network security inspection in the progress file.

---

### Task 13: Add Cucumber journeys, observability, Compose services, signed-callback smoke, and final verification

**Files:**
- Create: `backend/src/test/resources/features/catalog_payments.feature`
- Create: `backend/src/test/resources/features/partner_idempotency.feature`
- Create: `backend/src/test/resources/features/provider_consistency.feature`
- Create: `backend/src/test/resources/features/refunds_callbacks.feature`
- Create: `backend/src/test/java/dev/genericpayments/platform/acceptance/OneTimePaymentSteps.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/shared/adapter/in/web/RequestCorrelationFilter.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/shared/adapter/out/observability/Stage2ObservabilityIntegrationTest.java`
- Modify: `backend/src/test/java/dev/genericpayments/platform/acceptance/AcceptanceWorld.java`
- Modify: `backend/src/test/java/dev/genericpayments/platform/acceptance/FoundationCatalogSteps.java`
- Create: `infrastructure/callback-receiver.mjs`
- Create: `infrastructure/smoke-stage-2.sh`
- Modify: `infrastructure/compose.yaml`
- Modify: `infrastructure/.env.example`
- Modify: `docs/local-development.md`
- Modify: `.superpowers/sdd/2026-10-04-one-time-payments/progress.md`

**Interfaces:**
- Cucumber covers the 14 approved business scenarios through real Spring context, PostgreSQL Testcontainer, and FakePaymentProvider.
- Micrometer exposes the complete low-cardinality Stage 2 metric contract; request logs carry a validated/generated correlation ID and safe domain IDs.
- Compose keeps project name `payment-stage-1` so the existing named PostgreSQL volume migrates in place to V4.
- Smoke proves idempotent success, append-only journal, and verified callback without exposing receiver on a host port.

- [ ] **Step 1: Write the four failing Cucumber feature files and observability contract test**

Cover exactly:

```text
successful catalog payment
declined catalog payment
retry after recoverable decline on the same Payment
dynamic Partner payment
same idempotency key and same request
same idempotency key and different request
duplicate provider event
out-of-order provider event
unknown result and reconciliation
full refund
partial refunds and over-refund rejection
callback success
callback retries followed by DEAD
PaymentAccount routing
```

Use business language and observable outcomes; keep SQL details in step definitions rather than feature prose.

In `Stage2ObservabilityIntegrationTest`, assert exact meter names and bounded label sets for payment creation/transitions, provider call duration/result, inbox received/duplicate/failed/lag, outbox pending/delivered/retried/dead/lag, refund requested/result, and idempotency replay/conflict. Also assert `X-Request-Id` propagation/generation, MDC cleanup after each request, database-aware readiness, and that callback delivery failure does not make the backend unready.

- [ ] **Step 2: Run Cucumber and observe undefined steps**

Run both commands separately:

```bash
cd backend
./mvnw -q -Dtest=RunCucumberTest test
./mvnw -q -Dtest=Stage2ObservabilityIntegrationTest test
```

Expected: the new scenarios fail as undefined or unimplemented, and the observability test fails because the correlation filter and complete cross-service instrumentation do not exist yet.

- [ ] **Step 3: Implement scenario state and steps without bypassing use cases**

Extend `AcceptanceWorld` with per-scenario Payment/checkout/key/refund/event IDs and responses. Use HTTP APIs for public/partner/admin behavior and fake provider controls for provider events. Direct SQL is allowed only for assertions about append-only/atomic persistence or advancing worker due times. Before every scenario, call the shared `PostgresIntegrationTest.clearDatabase()` test helper from Task 2; its explicit test-only truncation statement is required because ordinary deletes from the append-only journal must continue to fail.

- [ ] **Step 4: Implement the dependency-free callback receiver**

Use Node 24 built-ins only. Endpoints:

```text
POST /configure   internal smoke setup of secret version/value
POST /callback    verify timestamp/HMAC and deduplicate Payment-Event-Id
GET  /inspection  return safe received IDs/types/counts
GET  /health      readiness
```

Use `crypto.timingSafeEqual`, reject stale timestamps, cap request body size, store only event IDs/types in memory, and never log/configuration-echo the secret. Bind only to the Compose network with no published host port.

- [ ] **Step 5: Extend Compose without replacing the persisted Stage 1 database**

Keep `name: payment-stage-1`, the existing `payment-postgres` volume, and localhost-only published ports. Add:

```yaml
  demo-site:
    build:
      context: ..
      dockerfile: examples/demo-site/Dockerfile
    depends_on:
      backend:
        condition: service_healthy
    ports:
      - "127.0.0.1:3001:8080"

  callback-receiver:
    image: node:24-alpine
    profiles: ["smoke"]
    user: node
    working_dir: /app
    command: ["node", "/app/callback-receiver.mjs"]
    volumes:
      - ./callback-receiver.mjs:/app/callback-receiver.mjs:ro
    healthcheck:
      test: ["CMD", "node", "-e", "fetch('http://127.0.0.1:8080/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"]
```

Run backend with `SPRING_PROFILES_ACTIVE=local`; pass `PAYMENT_CALLBACK_LOCAL_ALLOWED_HOSTS=localhost,127.0.0.1,callback-receiver`, `PAYMENT_DATA_ENCRYPTION_KEY`, key version, and `${PAYMENT_FAKE_AUTO_SUCCESS:-false}`. Add `PAYMENT_DATA_ENCRYPTION_KEY=bG9jYWwtZGV2LXBheW1lbnQta2V5LTMyLWJ5dGVzISE=` as the explicit local-only 32-byte example in `.env.example`; never reuse it outside local development.

- [ ] **Step 6: Write the Stage 2 smoke workflow before running it**

The script must:

```text
wait for readiness
authenticate admin and obtain CSRF
create unique FAKE account, Site, product, and published ONE_TIME price
configure callback and pass its one-time secret to the internal receiver over stdin
create catalog checkout with Idempotency-Key
confirm with a second Idempotency-Key
poll until SUCCEEDED
assert one PAYMENT_SUCCEEDED journal fact
assert one verified callback delivery
repeat create/confirm with the same keys
assert no second Payment or Attempt
check backend/receiver output for the known token/key/secret values
print a safe PASS JSON containing IDs only
```

Use `mktemp` cookie/token files and an EXIT trap. Do not place secret values in command arguments, terminal output, or generated URLs.

- [ ] **Step 7: Complete and verify the Stage 2 observability contract**

Use the typed shared output port and Micrometer adapter established in Task 3; business services must call explicit methods rather than supplying arbitrary metric names/tags. Complete any missing instrumentation and publish:

```text
payment.created
payment.transition
payment.provider.call
payment.provider.unknown
payment.inbox.received
payment.inbox.duplicate
payment.inbox.failed
payment.inbox.lag
payment.outbox.pending
payment.outbox.delivered
payment.outbox.retried
payment.outbox.dead
payment.outbox.lag
payment.refund.requested
payment.refund.result
payment.idempotency.replay
payment.idempotency.conflict
```

Only bounded status/result/operation/provider labels and the configured Site key are allowed; never label by Payment, Refund, Attempt, request, provider-object, or event ID. `RequestCorrelationFilter` accepts only a bounded safe `X-Request-Id` shape, otherwise generates one, adds it to response/MDC, and always clears MDC in `finally`. Structured logs may include correlation plus safe Payment/Refund/Event UUIDs, but no secrets or provider payloads. Keep database connectivity in readiness and do not add callback endpoint health as a readiness dependency.

Run:

```bash
cd backend
./mvnw -q -Dtest=Stage2ObservabilityIntegrationTest test
```

Expected: exact meters/tags, correlation lifecycle, and health semantics pass.

- [ ] **Step 8: Run Cucumber and the complete native test suites**

Run:

```bash
(cd backend && ./mvnw test)
(cd frontend && pnpm --filter admin lint && pnpm --filter admin typecheck && pnpm --filter admin test --run && pnpm --filter admin build)
(cd frontend && pnpm --filter @generic-payments/sdk typecheck && pnpm --filter @generic-payments/sdk test --run && pnpm --filter @generic-payments/sdk build)
(cd examples/demo-site && corepack pnpm build)
```

Expected: all commands exit zero. Capture exact test/scenario/step totals.

- [ ] **Step 9: Build and start the Compose stack with smoke profile**

Run:

```bash
PAYMENT_FAKE_AUTO_SUCCESS=true docker compose --profile smoke --env-file infrastructure/.env -f infrastructure/compose.yaml up --build -d --wait
set -a
source infrastructure/.env
set +a
bash infrastructure/smoke-stage-2.sh
docker compose --profile smoke --env-file infrastructure/.env -f infrastructure/compose.yaml ps
```

Expected: PostgreSQL, backend, admin, demo-site, and callback receiver are healthy/running; smoke prints `"result":"PASS"`; published ports remain on `127.0.0.1` only.

- [ ] **Step 10: Run Playwright against Compose and inspect runtime security**

Run:

```bash
cd examples/demo-site
corepack pnpm exec playwright test
```

Then verify containers run non-root where supported, callback receiver has no host port, no redirect is followed, no secret appears in backend/admin/demo logs, and browser requests never put checkout/handoff tokens in URLs.

- [ ] **Step 11: Update local-development documentation**

Document environment variables, default manual fake behavior, optional auto-success, admin/payment/SDK/demo journeys, API-key and callback-secret one-time display, dynamic backend-to-backend example, smoke commands, callback signature verification, metric names/labels, correlation behavior, destructive reset warning, retained volume name, and clear Stage 2 exclusions: real Stripe, Customer/OAuth, donations, subscriptions execution, periodic fleet reconciliation, and multiple callback endpoints.

- [ ] **Step 12: Stop services while retaining data unless the user explicitly wants them running**

Run:

```bash
docker compose --profile smoke --env-file infrastructure/.env -f infrastructure/compose.yaml down
```

Expected: project containers/network stop, and `payment-stage-1_payment-postgres` remains. Do not use `--volumes`.

- [ ] **Step 13: Final local completion checkpoint**

Update the progress file with exact backend/frontend/SDK/Cucumber/Playwright totals, Compose health/binding evidence, smoke IDs/result, known limitations, and confirmation that no Git operation occurred.

---

## Execution Handoff

Recommended execution mode: **subagent-driven development**. This plan crosses payment state machines, concurrency, security, browser packaging, and runtime delivery; a fresh reviewer after each task is worth the extra passes. Native inline execution remains viable if minimizing coordination overhead is more important, but should still retain the per-task test/checkpoint boundaries and one fresh final review.

Before implementation, choose one:

1. **Subagent-driven in this session** — one fresh implementer and reviewer per task, with fixes and re-review before advancing.
2. **Native inline in this session** — implement the tasks sequentially here, then run one independent final review over the complete Stage 2 change.
