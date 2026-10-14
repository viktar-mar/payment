# Stage 3B Donations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Site-owned one-time DonationCampaigns and Donations on top of the accepted Stage 3A Customer/session foundation, reuse the existing asynchronous Payment/Refund/Reconciliation pipeline, provide anonymous and authenticated donation flows, protect/redact donor messages, add callback schema version 2 for donation events only, and deliver Admin, SDK, Web Component, demo, Cucumber, Playwright, and Compose coverage.

**Architecture:** Add a hexagonal `donations` module that owns campaign policy, Donation/message lifecycle, and donation-checkout orchestration. It creates Payments only through a narrow `payments` application port inside the same local transaction; it never reaches payment repositories. `payments` gains the mutually exclusive `DONATION` source and an immutable campaign snapshot while preserving catalog/dynamic behavior. Existing provider processing, inbox, refunds, reconciliation, event journal, and outbox remain the financial state machine. Callback payload construction becomes source- and version-aware: catalog/dynamic stay byte-compatible schema v1, while donation events use schema v2 without PII or donor messages.

**Tech Stack:** The accepted Stage 3A stack: Java 25, Spring Boot 4.1.1, Maven Wrapper 3.9.11, PostgreSQL 18, Flyway, Spring Data JPA/JdbcTemplate, Spring Security, Micrometer, existing versioned AES-256-GCM encryption and HMAC-SHA-256 digests, JUnit 5, AssertJ, Testcontainers, ArchUnit, Cucumber-JVM 7.34.8, Node.js 24, pnpm 11.1.3, Next.js 16.3.3, React 19.3.0, TypeScript 5.9, Tailwind CSS 4.3, Vitest, Testing Library, Playwright, Docker Compose, Mailpit.

**Spec:** `docs/superpowers/specs/2026-10-10-customer-access-donations-design.md`, especially sections 3.2, 4, 5.2–5.4, 11–25.

**Required Precondition:** `.superpowers/sdd/2026-10-10-customer-access/progress.md` contains `STAGE_3A_GATE=PASSED`, the complete Stage 3A verification matrix is green, and its independent review has no open Critical or Important findings. If this is false, stop and finish `docs/superpowers/plans/2026-10-10-customer-access-implementation.md` first.

## Global Constraints

- Work only in this local workspace. Do not initialize or inspect Git, and do not create commits, branches, tags, worktrees, remotes, or pushes.
- Preserve all Stage 1–3A contracts and data. Catalog/dynamic callbacks remain schema version 1 with the exact existing field set; donation callbacks alone use schema version 2.
- Keep `backend/`, `frontend/admin/`, `frontend/sdk/`, and `examples/demo-site/` independently buildable.
- Domain packages must not import Spring, JPA, Jackson, HTTP, JDBC, PostgreSQL, encryption implementations, or provider SDK classes. Keep `ArchitectureRulesTest` green.
- Stage 3B supports one-time donations only. Do not add recurring policies, subscriptions, donor accounts, goals, public totals/progress, tax calculation, or real provider/OAuth integration.
- DonationCampaign and Donation always belong to one Site; composite persistence constraints and Site-scoped application queries enforce this.
- Browser requests may supply only `site_key`, `campaign_key`, `amount_minor`, optional donor message, and idempotency key. Currency, tax, Customer, Site ID, PaymentAccount, provider fields, and final state are server-resolved.
- A valid Customer session links the Donation Payment. No session is anonymous only when the Campaign permits it. Any explicitly supplied invalid session fails and never falls back to anonymous.
- Donor message is optional, NFC-normalized plain text of at most 500 Unicode code points, encrypted at rest, escaped in Admin, and absent from events, callbacks, metrics, logs, audit, SDK storage, and browser state after submission.
- Donation, Payment projection, initial PaymentEvent, checkout session, campaign snapshot, and idempotency completion are created atomically. Provider calls remain outside this transaction.
- Provider inbox/reconciliation, not synchronous browser/provider response, determines final financial state.
- Use the existing Payment/Refund state machine and append-only PaymentEvent journal. Do not create a parallel donation financial status machine.
- Use injected `Clock`, UUID IDs, positive minor units, uppercase ISO-4217 currency, exact Origin, Site ownership, optimistic locking, and server-side amount policy.
- Use red-green-refactor for every production change. Each task begins with a focused failing test and ends with a local verification checkpoint.
- Update `PostgresIntegrationTest.clearDatabase()` and Cucumber reset order with V6 foreign-key tables.
- Record all task status and evidence in `.superpowers/sdd/2026-10-10-donations/progress.md`.

## Review Focus

- Campaign lifecycle, immutable currency after first activation, allowed amount rules, anonymous policy, and immutable Payment snapshots.
- Exactly one Donation and one Payment under replay, concurrency, and fingerprint conflicts across Customer/anonymous context and donor-message content.
- Atomic Donation/Payment/Event/checkout/idempotency creation without provider calls inside the transaction.
- Database source-shape constraints make CATALOG, DYNAMIC, and DONATION mutually exclusive and preserve existing rows.
- Invalid sessions cannot become anonymous donations; all ownership queries include Site.
- Message encryption, Unicode validation, Admin escaping/redaction, Customer anonymization cleanup, and absence from immutable/event/callback/log/metric surfaces.
- Existing provider success/failure/retry/refund/reconciliation behavior works for DONATION without source-specific shortcuts.
- Callback v1 byte/field compatibility and v2 canonical serialization/signature/deduplication.
- Customer/Partner/Admin history and filtering cannot cross Site/Customer boundaries.
- SDK session scoping, component accessibility, abort behavior, and no browser persistence of messages.
- Full Stage 1–3A regression remains green.

---

## Exact API Contract

Public operations require an allowed non-null Origin. Creation requires `Idempotency-Key` and optionally accepts the Stage 3A SDK Bearer session:

| Method and path | OpenAPI operation ID | Purpose |
|---|---|---|
| `GET /api/v1/public/sites/{siteKey}/donation-campaigns/{campaignKey}` | `getPublicDonationCampaign` | Safe ACTIVE campaign view for headless/widget rendering. |
| `POST /api/v1/public/sites/{siteKey}/donation-campaigns/{campaignKey}/checkouts` | `createDonationCheckout` | Atomically create Donation + Payment + checkout. |

The public campaign view contains key, display name, purpose, currency, ordered suggested amounts, min/max, `custom_amount_allowed`, and `anonymous_allowed`; it exposes no internal ID/version or financial totals. Checkout body contains only `amount_minor` and optional `message`; the URL supplies Site/Campaign keys.

Partner additions use existing Site API-key authentication:

| Method and path | Scope | OpenAPI operation ID |
|---|---|---|
| `GET /api/v1/partner/donations/{donationId}` | `DONATIONS_READ` | `getPartnerDonation` |

Admin additions use existing Admin session authentication:

| Method and path | OpenAPI operation ID |
|---|---|
| `GET/POST /api/v1/admin/sites/{siteId}/donation-campaigns` | `listDonationCampaigns` / `createDonationCampaign` |
| `GET/PUT /api/v1/admin/sites/{siteId}/donation-campaigns/{campaignId}` | `getDonationCampaign` / `updateDonationCampaign` |
| `POST /api/v1/admin/sites/{siteId}/donation-campaigns/{campaignId}/status` | `changeDonationCampaignStatus` |
| `GET /api/v1/admin/donations` | `listAdminDonations` |
| `GET /api/v1/admin/donations/{donationId}` | `getAdminDonation` |
| `POST /api/v1/admin/donations/{donationId}/message-redactions` | `redactDonationMessage` |

Campaign/status/message mutations require `Idempotency-Key`; update requires expected optimistic `version`; message redaction body requires `confirmation` equal to the Donation UUID. Existing Admin Payment list gains optional `campaign_id`, `donation_id`, and `customer_id` filters without changing existing defaults.

Customer operation `GET /api/v1/customer/donations?cursor=&limit=` keeps the Stage 3A operation ID `listCurrentCustomerDonations` and now returns owned Donation projections ordered by `payments.created_at DESC,payments.id DESC`.

---

## Target File Map

### Donation core

- `backend/src/main/java/dev/genericpayments/platform/donations/domain/*` — DonationCampaign, Donation, IDs, snapshots, statuses, amount policy, message state, and lifecycle rules.
- `backend/src/main/java/dev/genericpayments/platform/donations/application/model/*` — public/admin/partner safe views and donation checkout commands/results.
- `backend/src/main/java/dev/genericpayments/platform/donations/application/port/in/*` — campaign management, public read/checkout, Donation queries, and message redaction.
- `backend/src/main/java/dev/genericpayments/platform/donations/application/port/out/*` — campaign/Donation persistence, Payment creation, encryption, Customer context, idempotency, audit, and telemetry.
- `backend/src/main/java/dev/genericpayments/platform/donations/application/service/*` — campaign lifecycle, transaction-safe checkout, queries, and redaction.
- `backend/src/main/java/dev/genericpayments/platform/donations/adapter/out/persistence/*` — JPA/JdbcTemplate entities, mappings, Site ownership, locks, and keyset queries.
- `backend/src/main/java/dev/genericpayments/platform/donations/adapter/in/web/*` — Public, Partner, and Admin controllers/DTOs.
- `backend/src/main/java/dev/genericpayments/platform/donations/adapter/in/configuration/*` — application wiring and transaction adapters.

### Payment, callback, and Customer extensions

- `backend/src/main/java/dev/genericpayments/platform/payments/domain/PaymentSource.java`, `Payment.java`, and new `DonationSnapshot.java` — DONATION source and mutually exclusive shape.
- `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/CreateDonationPaymentUseCase.java` — narrow transaction-participating Payment creation port consumed by Donations.
- `backend/src/main/java/dev/genericpayments/platform/payments/application/service/PaymentCreationTransaction.java` — Donation projection/event/checkout creation without provider calls.
- `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/*` — V6 columns, mapping, filters, Customer donation history, and callback context.
- `backend/src/main/java/dev/genericpayments/platform/webhooks/adapter/out/persistence/CanonicalCallbackPayloadAdapter.java` — strict v1/v2 canonical validation.
- `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/PaymentOutboxPersistenceAdapter.java` — source-aware snapshot schema selection.
- `backend/src/main/java/dev/genericpayments/platform/donations/adapter/out/persistence/DonationCustomerDataRedactionAdapter.java` — implement the Stage 3A Customer redaction port without making Customers depend on Donations.
- `backend/src/main/java/dev/genericpayments/platform/partners/domain/PartnerScope.java` — add `DONATIONS_READ`.
- `backend/src/main/resources/db/migration/V6__donations.sql` — Campaign/Donation schema and Payment source/snapshot extensions.
- `backend/src/test/java/dev/genericpayments/platform/support/PostgresIntegrationTest.java` — V6 cleanup order.

### Admin, SDK, demo, acceptance, and runtime

- `frontend/admin/src/app/sites/[siteId]/donation-campaigns/*` and `frontend/admin/src/components/donations/campaigns/*` — Campaign CRUD/lifecycle and amount preview.
- `frontend/admin/src/app/donations/*` and `frontend/admin/src/components/donations/*` — Donation list/details/message redaction and links to existing Payment actions.
- `frontend/admin/src/components/payments/PaymentFilters.tsx` and related types/tests — Customer/Campaign/Donation filters.
- `frontend/admin/src/lib/api/schema.d.ts` — regenerated OpenAPI types; never hand-edit.
- `frontend/sdk/src/donations/*` — campaign/read/checkout methods, Customer history extension, watcher, and accessible `<generic-donation>` component.
- `examples/demo-site/*` — anonymous and authenticated donation journeys.
- `backend/src/test/resources/features/donations.feature` and `backend/src/test/java/dev/genericpayments/platform/acceptance/DonationSteps.java` — Stage 3B Cucumber scenarios.
- `infrastructure/callback-receiver.mjs` and tests — strict callback schemas v1 and v2.
- `infrastructure/smoke-stage-3b.mjs` and tests — complete donation journey.
- `infrastructure/compose.yaml`, `infrastructure/.env.example`, and `docs/local-development.md` — Stage 3B runtime/documentation.
- `.superpowers/sdd/2026-10-10-donations/progress.md` and `review.md` — gate evidence.

---

### Task 1: Implement DonationCampaign and Donation domain models

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/domain/DonationCampaignId.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/domain/DonationId.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/domain/CampaignStatus.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/domain/DonorMessageState.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/domain/DonationCampaign.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/domain/Donation.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/domain/DonationCampaignSnapshot.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/domain/DonorMessage.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/donations/domain/DonationCampaignTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/donations/domain/DonationTest.java`
- Create: `.superpowers/sdd/2026-10-10-donations/progress.md`

**Interfaces:**
- Campaign owns key/display/purpose/currency/ordered suggested amounts/min/max/custom/anonymous policy/status/version/timestamps.
- Donation owns Site/Campaign IDs, encrypted-message reference/state, and timestamps; Customer ownership remains only on the linked Payment.
- DonorMessage validation happens before encryption but its `toString()` is always redacted.

- [ ] **Step 1: Write failing Campaign invariant and lifecycle tests**

Cover safe key, ISO currency, positive min/max, min <= max, ordered unique suggested amounts in range, suggested-only policy, ACTIVE checkout requirement, `DRAFT -> ACTIVE`, `ACTIVE <-> PAUSED`, archive from non-archived states, terminal ARCHIVED, and currency immutability after first activation.

```java
@Test
void pausedCampaignCanResumeButArchivedCampaignIsTerminal() {
    DonationCampaign resumed = activeCampaign().pause(NOW).activate(NOW.plusSeconds(1));
    assertThat(resumed.status()).isEqualTo(CampaignStatus.ACTIVE);
    assertThatThrownBy(() -> resumed.archive(NOW.plusSeconds(2)).activate(NOW.plusSeconds(3)))
        .isInstanceOf(DomainValidationException.class);
}

@Test
void customDisabledAcceptsOnlySuggestedAmount() {
    DonationCampaign campaign = campaign(false, List.of(500L, 1_000L));
    assertThatThrownBy(() -> campaign.validateAmount(750L))
        .isInstanceOf(DomainValidationException.class);
}
```

- [ ] **Step 2: Write failing Donation/message tests**

Cover ABSENT/PRESENT/REDACTED transitions, idempotent redaction, NFC normalization, 500 Unicode code-point boundary, rejection of ISO controls and unpaired surrogates, no HTML interpretation, and redacted string representation.

- [ ] **Step 3: Run domain tests red**

Run:

```bash
cd backend
./mvnw -q -Dtest='dev.genericpayments.platform.donations.domain.*Test' test
```

Expected: compilation fails because donation domain types do not exist.

- [ ] **Step 4: Implement immutable Campaign and snapshot rules**

Use named lifecycle/update methods. Preserve a boolean `activatedOnce` so currency cannot change after pause. Snapshot includes Campaign ID/key/version/currency only and is captured before checkout creation.

- [ ] **Step 5: Implement Donation and message validation**

Normalize with `Normalizer.Form.NFC`, count `codePoints()`, reject control/format/surrogate misuse, and keep raw message out of the Donation aggregate after encryption. Domain stores an opaque ciphertext reference plus state.

- [ ] **Step 6: Extend architecture fixtures and run green**

Run:

```bash
cd backend
./mvnw -q -Dtest='dev.genericpayments.platform.donations.domain.*Test,ArchitectureRulesTest,ArchitectureRulesRuleTest' test
```

Expected: all selected tests pass and the donation domain has no forbidden dependency.

- [ ] **Step 7: Local checkpoint and reviewer gate**

Record lifecycle/amount/message matrices and test count. Review that no financial completion status exists in Donation domain.

---

### Task 2: Add V6 Campaign/Donation persistence and DONATION Payment shape

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__donations.sql`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/port/out/DonationCampaignStore.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/port/out/DonationStore.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/adapter/out/persistence/DonationCampaignJpaEntity.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/adapter/out/persistence/DonationJpaEntity.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/adapter/out/persistence/DonationPersistenceAdapter.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/domain/PaymentSource.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/domain/DonationSnapshot.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/domain/Payment.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/PaymentJpaEntity.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/PaymentPersistenceMapper.java`
- Modify: `backend/src/test/java/dev/genericpayments/platform/support/PostgresIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/donations/adapter/out/persistence/DonationPersistenceIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/donations/adapter/out/persistence/DonationMigrationIntegrationTest.java`
- Modify: `backend/src/test/java/dev/genericpayments/platform/payments/domain/PaymentTest.java`

**Interfaces:**
- Campaign lookup and locks always use `(site_id,id)` or `(site_id,campaign_key)`.
- Donation row has composite ownership to Campaign and a versioned encrypted message triple; it does not duplicate Customer ID.
- Payment DONATION shape requires Donation and Campaign snapshot and forbids Price/external-order fields.

- [ ] **Step 1: Write failing migration/persistence/source-shape tests**

Test Site ownership, campaign-key uniqueness per Site, ordered suggested amounts, optimistic updates, ciphertext-only message storage, catalog/dynamic row preservation, and database rejection of every mixed source shape.

- [ ] **Step 2: Run focused tests red**

Run:

```bash
cd backend
./mvnw -q -Dtest='DonationMigrationIntegrationTest,DonationPersistenceIntegrationTest,PaymentTest' test
```

Expected: V6 and DONATION source are absent.

- [ ] **Step 3: Create Campaign and Donation tables**

Create `donation_campaigns`, `donation_campaign_suggested_amounts`, and `donations`. Add `UNIQUE(id,site_id)` and `UNIQUE(id,site_id,campaign_id)` ownership keys as required by composite references. Suggested amounts use `(campaign_id,position)` primary order plus unique `(campaign_id,amount_minor)`.

Replace `ck_api_keys_scopes` so the Stage 3A values plus `donations:read` are accepted, while preserving all existing key rows and the non-empty JSON-array check.

- [ ] **Step 4: Extend Payments with explicit donation snapshot columns**

Add nullable `donation_id`, `donation_campaign_id`, `snapshot_campaign_key`, `snapshot_campaign_version`, and `snapshot_campaign_currency`; add same-Site composite foreign keys. Replace `ck_payments_source` and `ck_payments_shape` atomically:

```sql
ALTER TABLE payments ADD CONSTRAINT ck_payments_source
  CHECK (source IN ('CATALOG','DYNAMIC','DONATION'));

-- DONATION branch requires donation/campaign snapshot, tax_mode='NONE',
-- no price snapshot, and no external_order_id; other branches require
-- every donation column to be NULL.
```

Keep all existing catalog/dynamic rows valid without backfill.

- [ ] **Step 5: Implement persistence adapters and Payment mapping**

Map ordered amounts deterministically and use existing versioned encryption tuple for messages. `Payment.donation(...)` requires `TaxMode.NONE`, DonationSnapshot currency equal to Money currency, and nullable Customer UUID.

- [ ] **Step 6: Update cleanup order and run database checks**

Delete Payment children/outbox/events before Payments, Donations before Campaigns, suggested amounts before Campaigns, and Campaigns before Sites. Run:

```bash
cd backend
./mvnw -q -Dtest='DonationMigrationIntegrationTest,DonationPersistenceIntegrationTest,PaymentTest,PaymentPersistence*Test,ArchitectureRulesTest' test
```

Expected: all selected tests pass and Stage 1–3A rows migrate unchanged.

- [ ] **Step 7: Local checkpoint and reviewer gate**

Record V6 constraints/indexes and test count. Independently review all three source-shape branches and composite Site ownership.

---

### Task 3: Implement Campaign Admin API and UI

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/port/in/ManageDonationCampaignUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/port/in/GetPublicDonationCampaignUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/service/DonationCampaignService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/adapter/in/web/DonationCampaignAdminController.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/adapter/in/web/PublicDonationCampaignController.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/donations/adapter/in/web/DonationCampaignAdminApiIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/donations/adapter/in/web/PublicDonationCampaignApiIntegrationTest.java`
- Create: `frontend/admin/src/app/sites/[siteId]/donation-campaigns/page.tsx`
- Create: `frontend/admin/src/app/sites/[siteId]/donation-campaigns/[campaignId]/page.tsx`
- Create: `frontend/admin/src/components/donations/campaigns/CampaignList.tsx`
- Create: `frontend/admin/src/components/donations/campaigns/CampaignForm.tsx`
- Create: `frontend/admin/src/components/donations/campaigns/CampaignLifecycleActions.tsx`
- Create: `frontend/admin/src/components/donations/campaigns/CampaignForm.test.tsx`
- Create: `frontend/admin/src/components/donations/campaigns/CampaignLifecycleActions.test.tsx`
- Regenerate: `frontend/admin/src/lib/api/schema.d.ts`

**Interfaces:**
- Admin writes use Site path ownership, optimistic version, idempotency, and safe audit.
- Public read returns ACTIVE Campaign safe configuration only and validates Site status plus exact allowed Origin.
- Public response has no Campaign UUID/version or aggregate totals.

- [ ] **Step 1: Write failing Admin/public API tests**

Cover create/update/list/detail/lifecycle, duplicate key per Site vs same key on another Site, optimistic conflicts, idempotent replay/conflict, archived terminal behavior, audit, public ACTIVE-only read, disabled Site, cross-Origin, and safe field set.

- [ ] **Step 2: Run backend tests red**

Run:

```bash
cd backend
./mvnw -q -Dtest='DonationCampaignAdminApiIntegrationTest,PublicDonationCampaignApiIntegrationTest' test
```

Expected: endpoints/services are absent.

- [ ] **Step 3: Implement Campaign service and exact API contract**

Lock for lifecycle/update, validate expected version and idempotency fingerprint, persist, then record safe audit. Public lookup requires ACTIVE Site/Campaign and uses only Site/campaign keys plus Origin.

- [ ] **Step 4: Run backend API tests green and regenerate OpenAPI types**

Run the focused tests, then regenerate Admin `schema.d.ts` from the running test-configured backend and stop that owned process. Verify operation IDs from the contract table.

- [ ] **Step 5: Write failing Admin component tests**

Cover ordered unique amount editing, min/max/custom policy preview, currency lock after activation, lifecycle pending/error states, conflict refresh, idempotent retry, keyboard/focus, and server-error announcements.

- [ ] **Step 6: Run Admin component tests red**

Run:

```bash
cd frontend
pnpm --filter admin test --run src/components/donations/campaigns
```

Expected: the tests fail because the Campaign components are absent.

- [ ] **Step 7: Implement Campaign pages/components**

Client preview mirrors server rules only for immediate feedback; server response remains authoritative. Disable currency after first activation, retain one idempotency key per user intent, and escape display/purpose text.

- [ ] **Step 8: Run Admin verification**

Run:

```bash
cd frontend
pnpm --filter admin typecheck
pnpm --filter admin test --run
pnpm --filter admin build
```

Expected: typecheck, complete Admin suite, and build pass.

- [ ] **Step 9: Local checkpoint and reviewer gate**

Record backend/frontend counts and operation IDs. Review public response data minimization and lifecycle/currency rules.

---

### Task 4: Create Donation checkout atomically through the Payment application port

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/port/in/CreateDonationCheckoutUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/model/CreateDonationCheckoutCommand.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/port/out/DonationPaymentCreationPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/port/out/DonorMessageEncryptionPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/port/out/DonationMessageFingerprintPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/service/DonationCheckoutService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/service/DonationCheckoutTransaction.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/adapter/out/integration/PaymentDonationCreationAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/adapter/out/security/AesGcmDonorMessageEncryptionAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/adapter/out/security/HmacDonationMessageFingerprintAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/adapter/in/web/PublicDonationCheckoutController.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/CreateDonationPaymentUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/CreateDonationPaymentCommand.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/PaymentCreationTransaction.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/in/web/PublicCheckoutDtos.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/donations/application/service/DonationCheckoutIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/donations/adapter/in/web/PublicDonationCheckoutApiIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/donations/application/service/DonationCheckoutTransactionBoundaryTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/donations/adapter/out/security/DonorMessageSecurityAdapterTest.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application.yml`

**Interfaces:**
- Donations call `CreateDonationPaymentUseCase` with already validated Site/Campaign/Donation/Money/snapshot/Customer data; they never import a Payment persistence adapter.
- Payment use case joins the caller's transaction with mandatory propagation and creates Payment, initial event, and checkout without provider calls.
- Response reuses existing checkout fields and adds safe `donation` context.

- [ ] **Step 1: Write failing amount, anonymous/session, and idempotency tests**

Cover ACTIVE-only checkout, suggested/custom boundaries, currency server resolution, anonymous allowed/forbidden, valid Customer binding, absent session, every invalid-session variant, same-key replay, changed amount/message/Customer context conflict, and concurrent requests.

```java
@Test
void sameKeyWithDifferentIdentityContextConflicts() {
    createDonation(IDEMPOTENCY_KEY, Optional.empty(), 1_000, "hello");
    assertThatThrownBy(() -> createDonation(IDEMPOTENCY_KEY, Optional.of(CUSTOMER_ID), 1_000, "hello"))
        .isInstanceOf(IdempotencyConflictException.class);
}

@Test
void invalidSessionDoesNotUseAnonymousPolicy() {
    assertThatThrownBy(() -> createWithAuthorization("Bearer expired"))
        .isInstanceOf(AuthenticationFailedException.class);
    assertThat(donationCount()).isZero();
}
```

- [ ] **Step 2: Write failing atomicity and transaction-boundary tests**

Inject failure after Donation, Payment, PaymentEvent, or checkout creation and assert complete rollback. Instrument provider adapter and assert zero calls while the local transaction is active and during initial creation.

- [ ] **Step 3: Run focused tests red**

Run:

```bash
cd backend
./mvnw -q -Dtest='DonationCheckoutIntegrationTest,PublicDonationCheckoutApiIntegrationTest,DonationCheckoutTransactionBoundaryTest' test
```

Expected: checkout flow does not exist.

- [ ] **Step 4: Implement controller-side Site/Origin/session resolution**

Resolve Site/Campaign path keys and exact Origin. Use Stage 3A optional-session semantics: empty only for no header; any supplied invalid credential throws. Pass only verified optional Customer ID into the command.

- [ ] **Step 5: Implement donor-message security and canonical idempotency fingerprint**

Encrypt normalized text through a Donation-owned port backed by the existing versioned AES-GCM capability. Fingerprint message content through a separate HMAC-SHA-256 key/version and constant-time adapter, never the unkeyed content-hash port. Include Site ID, Campaign ID/version, amount, keyed message fingerprint or literal `message:absent`, and `customer:<uuid>` or `anonymous`. Never include raw session/message. A same-key repeat returns the original Donation/checkout result; a changed field conflicts.

- [ ] **Step 6: Implement one local creation transaction**

Lock/revalidate Campaign, amount, and anonymous policy; encrypt message; create Donation; call mandatory Payment creation port; append initial event/create checkout; complete idempotency. The Payment command fixes `TaxMode.NONE` and immutable campaign snapshot.

- [ ] **Step 7: Reuse the existing checkout/provider phases**

After commit, existing prepare/confirm endpoints operate on the returned checkout token. Do not add donation-specific confirmation or synchronous success logic.

- [ ] **Step 8: Run checkout, provider, and architecture tests**

Run:

```bash
cd backend
./mvnw -q -Dtest='DonationCheckout*Test,PublicDonationCheckoutApiIntegrationTest,PaymentPreparationCoordinatorIntegrationTest,PaymentConfirmationCoordinatorIntegrationTest,ArchitectureRulesTest' test
```

Expected: all selected tests pass and transaction-boundary assertions prove no provider call occurs inside creation.

- [ ] **Step 9: Local checkpoint and reviewer gate**

Record fingerprint fields, transaction contents, and test count. Review rollback/replay/concurrency and invalid-session behavior independently.

---

### Task 5: Reuse provider/refund/reconciliation flows and add callback schema v2

**Files:**
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/PaymentPhaseSupport.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/PaymentTransitionService.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/PaymentOutboxPersistenceAdapter.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/webhooks/adapter/out/persistence/CanonicalCallbackPayloadAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/adapter/out/persistence/DonationCustomerDataRedactionAdapter.java`
- Remove after replacement: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/persistence/NoOpCustomerDataRedactionAdapter.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/donations/application/service/DonationPaymentLifecycleIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/webhooks/application/service/DonationCallbackIntegrationTest.java`
- Modify: `backend/src/test/java/dev/genericpayments/platform/webhooks/application/service/CallbackSignerTest.java`
- Modify: `backend/src/test/java/dev/genericpayments/platform/webhooks/adapter/out/persistence/CallbackSecretPersistenceIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/application/service/DonationMessageAnonymizationIntegrationTest.java`
- Modify: `infrastructure/callback-receiver.mjs`
- Modify: `infrastructure/callback-receiver.test.mjs`

**Interfaces:**
- All Payment sources use the same provider preparation/confirmation/inbox/reconciliation/refund transitions.
- Outbox snapshot chooses schema from immutable Payment source: CATALOG/DYNAMIC => v1, DONATION => v2.
- v2 exact fields are the ten v1 fields plus `donation_id`, `campaign_key`, and nullable `customer_id`; `external_order_id` remains present and null for donations.

- [ ] **Step 1: Write failing Donation financial-lifecycle tests**

Cover prepare/confirm, recoverable decline, action required, processing, provider success/failure, duplicate/out-of-order inbox events, unknown state/reconciliation, partial/full refunds, and no Donation-specific status divergence.

- [ ] **Step 2: Add v1 golden-compatibility and failing v2 callback tests**

Use a fixed v1 payload/signature fixture from Stage 2 and require identical canonical bytes. For v2 require the exact 13-field set, donation/campaign/nullable Customer context, stable lexical canonical order, correct signature, retry deduplication, and rejection of extra/missing/type-invalid fields.

```java
private static final Set<String> V2_FIELDS = Set.of(
    "schema_version", "event_id", "event_type", "site_id", "payment_id",
    "external_order_id", "payment_status", "amount_minor", "currency", "occurred_at",
    "donation_id", "campaign_key", "customer_id");
```

- [ ] **Step 3: Add failing message-leak and anonymization tests**

Use distinctive message/email/subject fixtures; scan PaymentEvent safe facts, outbox JSON, canonical bytes, receiver output, metrics, audit, and captured logs. Anonymizing an authenticated donor must set related Donation messages to REDACTED/null ciphertext while anonymous Donation messages remain unchanged.

- [ ] **Step 4: Run focused tests red**

Run:

```bash
cd backend
./mvnw -q -Dtest='DonationPaymentLifecycleIntegrationTest,DonationCallbackIntegrationTest,DonationMessageAnonymizationIntegrationTest,CallbackSignerTest' test
```

Expected: DONATION callback/redaction behavior is absent or incomplete.

- [ ] **Step 5: Generalize source-neutral Payment phases**

Remove any two-value source assumption while preserving existing Price/external-order behavior. Provider request uses Payment amount/currency/account and never needs donor message. Run source-parameterized tests across CATALOG, DYNAMIC, and DONATION.

- [ ] **Step 6: Snapshot strict versioned callbacks**

At the confirmed transition, load Donation/campaign snapshot context through a same-transaction safe query. Build v1 exactly as before or v2 with the fixed fields. Store immutable payload in existing outbox; retries sign the stored snapshot and never reconstruct from mutable campaign/message data.

- [ ] **Step 7: Validate and canonicalize v1/v2 independently**

Split strict field/schema validation by `schema_version`, then canonicalize a sorted map. Reject versions other than 1/2. Preserve v1 nullable/amount/event semantics byte-for-byte. Never add v2 fields to v1.

- [ ] **Step 8: Replace the Stage 3A redaction no-op**

Implement `CustomerDataRedactionPort` with one Site/Customer-scoped update joining Payments to Donations. Set PRESENT messages to REDACTED, null ciphertext/nonce/key version, and leave ABSENT/REDACTED plus anonymous Donations unchanged. Participate in the existing anonymization transaction.

- [ ] **Step 9: Update the dependency-free callback receiver**

Accept only exact v1 or v2 schema, verify signature over raw/canonical contract as already defined, deduplicate by event ID, and print safe IDs/status only. Explicitly reject/log-safe payloads containing message or PII field names.

- [ ] **Step 10: Run lifecycle/callback/redaction regression**

Run:

```bash
cd backend
./mvnw -q -Dtest='DonationPaymentLifecycleIntegrationTest,DonationCallbackIntegrationTest,DonationMessageAnonymizationIntegrationTest,CallbackSignerTest,ProviderInboxServiceIntegrationTest,RefundCoordinatorIntegrationTest,ReconcilePaymentServiceIntegrationTest' test
cd ..
node --test infrastructure/callback-receiver.test.mjs
```

Expected: all selected tests pass; v1 golden bytes stay unchanged; no message/PII appears in financial facts or callbacks.

- [ ] **Step 11: Local checkpoint and reviewer gate**

Record v1/v2 field sets, signatures, lifecycle matrix, and counts. Independently review callback compatibility and anonymization transaction behavior.

---

### Task 6: Add Customer, Partner, and Admin Donation queries

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/port/in/ListCustomerDonationsUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/port/in/GetPartnerDonationUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/port/in/QueryAdminDonationsUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/port/in/RedactDonationMessageUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/model/DonationViews.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/port/out/DonationHistoryCursorPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/service/DonationQueryService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/service/DonationMessageRedactionService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/adapter/in/web/PartnerDonationController.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/adapter/in/web/DonationAdminController.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/in/web/CustomerController.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/partners/domain/PartnerScope.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/application/model/AdminPaymentViews.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/AdminPaymentReadAdapter.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/in/web/PaymentAdminController.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/donations/adapter/in/web/CustomerDonationHistoryIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/donations/adapter/in/web/PartnerDonationApiIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/donations/adapter/in/web/DonationAdminApiIntegrationTest.java`

**Interfaces:**
- Customer history ownership comes only from verified `CustomerSessionContext`; cursor is opaque/authenticated and bound to Site, Customer, and query shape.
- Partner result is same-Site and safe: Donation/Payment/campaign/amount/status/Customer presence, but never donor message.
- Admin detail alone may decrypt a PRESENT message; list returns message state and `has_message`, not plaintext.

- [ ] **Step 1: Write failing Customer history tests**

Cover only own authenticated Donations, exclusion of anonymous/other Customer/Site records, stable `(payment.created_at,payment.id)` keyset ordering, limit 1–50, tampered/cross-context cursor, safe fields, and absence of message/provider/admin data.

- [ ] **Step 2: Write failing Partner/Admin query and redaction tests**

Cover `DONATIONS_READ`, cross-Site indistinguishability, safe Partner field set, Admin Site/Campaign/Customer/Donation/Payment/status/date filters, list without message plaintext, escaped detail source, one-time/idempotent redaction, confirmation mismatch, and audit without plaintext.

- [ ] **Step 3: Run focused tests red**

Run:

```bash
cd backend
./mvnw -q -Dtest='CustomerDonationHistoryIntegrationTest,PartnerDonationApiIntegrationTest,DonationAdminApiIntegrationTest' test
```

Expected: Stage 3A empty history and missing Partner/Admin endpoints fail assertions.

- [ ] **Step 4: Implement Customer history and replace the 3A empty response**

Use a join on Donation -> Payment constrained by exact Site and Customer. Encode Site/Customer/last Payment timestamp+ID/query version in the signed cursor. Return Donation/Payment IDs, immutable campaign key context, amount/currency/public Payment status, message state only, and timestamps.

- [ ] **Step 5: Implement Partner safe view**

Add `DONATIONS_READ("donations:read")` to `PartnerScope`. Resolve Site from API-key principal, require that scope, and query by `(site_id,donation_id)`. Return nullable Customer ID because Partner already owns the Site context; omit message and internal provider/event data.

- [ ] **Step 6: Implement Admin list/detail/message redaction**

Apply bounded filters and page size. Decrypt message only after Admin authorization and exact Donation load; place plaintext only in the detail response object, with redacted `toString()`. Redaction locks Donation, nulls encrypted material, sets REDACTED, and audits safe IDs/outcome.

- [ ] **Step 7: Extend Payment Admin filters/details**

Add optional Customer/Campaign/Donation filters and safe Donation context. Existing unfiltered ordering/page/default response fields remain compatible.

- [ ] **Step 8: Run query, security, and Admin Payment tests**

Run:

```bash
cd backend
./mvnw -q -Dtest='CustomerDonationHistoryIntegrationTest,PartnerDonationApiIntegrationTest,DonationAdminApiIntegrationTest,PaymentAdminApiIntegrationTest,*CustomerAuthenticationIntegrationTest' test
```

Expected: all selected tests pass and list/Partner/Customer outputs never contain donor message.

- [ ] **Step 9: Local checkpoint and reviewer gate**

Record projection fields, cursor binding, scopes, and count. Review every query for Site/Customer isolation and every response for message exposure.

---

### Task 7: Build Donation and Payment-filter Admin UI

**Files:**
- Create: `frontend/admin/src/app/donations/page.tsx`
- Create: `frontend/admin/src/app/donations/[donationId]/page.tsx`
- Create: `frontend/admin/src/components/donations/DonationList.tsx`
- Create: `frontend/admin/src/components/donations/DonationFilters.tsx`
- Create: `frontend/admin/src/components/donations/DonationDetails.tsx`
- Create: `frontend/admin/src/components/donations/RedactMessageDialog.tsx`
- Create: `frontend/admin/src/components/donations/DonationList.test.tsx`
- Create: `frontend/admin/src/components/donations/DonationDetails.test.tsx`
- Create: `frontend/admin/src/components/donations/RedactMessageDialog.test.tsx`
- Modify: `frontend/admin/src/components/payments/PaymentFilters.tsx`
- Modify: `frontend/admin/src/components/payments/PaymentDetails.tsx`
- Modify: `frontend/admin/src/components/payments/PaymentList.test.tsx`
- Modify: `frontend/admin/src/components/payments/PaymentDetails.test.tsx`
- Modify: `frontend/admin/src/components/AppShell.tsx`
- Regenerate: `frontend/admin/src/lib/api/schema.d.ts`

**Interfaces:**
- Donation list filters Site, Campaign, Customer, Donation, Payment/status, and date range; it displays message state only.
- Detail renders decrypted plaintext as text, never HTML, and links to existing Payment refund/reconcile actions.
- Message redaction is confirmation/idempotency protected and distinct from Payment refund.

- [ ] **Step 1: Write failing list/filter/detail and redaction-dialog tests**

Cover every filter, Customer presence, message state, safe empty/error/loading states, Payment link/status/refund action reuse, plain-text escaping of `<script>`-like content, and absence of `dangerouslySetInnerHTML`.

Cover exact Donation UUID confirmation, irreversible warning, pending double-submit protection, one idempotency key per intent/retry, cancel, success state update, API error announcement, keyboard behavior, and focus restoration.

- [ ] **Step 2: Run Donation Admin component tests red**

Run:

```bash
cd frontend
pnpm --filter admin test --run src/components/donations
```

Expected: the tests fail because Donation list/detail/redaction components are absent.

- [ ] **Step 3: Regenerate OpenAPI types and verify Donation operation IDs**

Generate from the test-configured backend, stop the owned process, and ensure all exact API operations plus Payment filter fields are represented. Do not hand-edit generated types.

- [ ] **Step 4: Implement list, filters, and details**

Follow existing Payment UI patterns and API client. Render message in a normal text node with whitespace preservation. Keep list payload message-free and request detail only on the authorized detail page.

- [ ] **Step 5: Implement message redaction and Payment filters**

After success replace visible message with REDACTED immediately and discard the response object containing plaintext. Add Customer/Campaign/Donation filters to Payment screens without changing old default query behavior.

- [ ] **Step 6: Add navigation and run Admin verification**

Run:

```bash
cd frontend
pnpm --filter admin typecheck
pnpm --filter admin test --run
pnpm --filter admin build
rg -n 'dangerouslySetInnerHTML' admin/src/components/donations
```

Expected: typecheck/tests/build pass and the scan returns no Donation component match.

- [ ] **Step 7: Local checkpoint and reviewer gate**

Record counts/build result. Review plaintext lifetime, escaping, confirmation UX, and reuse of existing financial actions.

---

### Task 8: Extend the SDK, add `<generic-donation>`, and update the demo

**Files:**
- Create: `frontend/sdk/src/donations/contracts.ts`
- Create: `frontend/sdk/src/donations/donation-client.test.ts`
- Create: `frontend/sdk/src/donations/generic-donation.ts`
- Create: `frontend/sdk/src/donations/generic-donation.test.ts`
- Create: `frontend/sdk/src/donations/styles.ts`
- Modify: `frontend/sdk/src/contracts.ts`
- Modify: `frontend/sdk/src/client.ts`
- Modify: `frontend/sdk/src/index.ts`
- Modify: `frontend/sdk/README.md`
- Modify: `examples/demo-site/index.html`
- Modify: `examples/demo-site/src/main.ts`
- Modify: `examples/demo-site/src/styles.css`
- Create: `examples/demo-site/tests/donations.spec.ts`

**Interfaces:**
- Add `getDonationCampaign`, `createDonationCheckout`, and real `listCustomerDonations`; existing checkout `confirm/get/watch/clear` operates on the returned checkout session.
- Valid same-context Customer session attaches automatically; no session is anonymous; invalid stored/supplied session fails without anonymous retry.
- Component receives API base/Site/Campaign and optional locale/design tokens, fetches safe campaign config, and never persists donor message.

- [ ] **Step 1: Write failing headless SDK tests**

Cover strict public campaign parsing, exact create body, generated/reused idempotency key per intent, same-context Customer authorization, deliberate anonymous mode, expired/invalid session failure, additive Donation checkout context, watcher abort, Customer donation history cursor, and response-size/redirect/error handling.

- [ ] **Step 2: Run headless SDK tests red**

Run:

```bash
cd frontend
pnpm --filter @generic-payments/sdk test --run src/donations/donation-client.test.ts
```

Expected: the test fails because the Donation contracts and headless methods are absent.

- [ ] **Step 3: Implement strict contracts and headless methods**

Reuse the explicit authorization capability from Stage 3A. Do not accept currency, Customer ID, tax, or provider values in `DonationCheckoutInput`:

```typescript
export interface DonationCheckoutInput {
  siteKey: string;
  campaignKey: string;
  amountMinor: number;
  message?: string;
  customerSession?: "auto" | "omit";
  idempotencyKey?: string;
  signal?: AbortSignal;
}
```

Validate positive safe integers, 500 Unicode code-point maximum, and strict response shapes; server remains authoritative for NFC/policy/currency.

- [ ] **Step 4: Write failing Web Component tests**

Cover loading Campaign, ordered suggested buttons, custom allowed/forbidden, min/max hints, anonymous policy, optional message counter, accessible labels/fieldset/errors/`aria-live`, pending state, keyboard/focus, locale/design tokens, success/financial-state events, abort on disconnect, and no message/session storage leakage.

- [ ] **Step 5: Run Web Component tests red**

Run:

```bash
cd frontend
pnpm --filter @generic-payments/sdk test --run src/donations/generic-donation.test.ts
```

Expected: the test fails because `<generic-donation>` is absent.

- [ ] **Step 6: Implement `<generic-donation>`**

Use isolated styles and the headless client. Clear message input/state immediately after successful local checkout creation. Emit safe composed events with Donation/Payment/checkout IDs and status, never message/session token. Continue to treat provider completion asynchronously through existing watcher.

- [ ] **Step 7: Extend the static demo and Playwright tests**

Demonstrate anonymous donation, email-OTP authenticated donation, Customer donation history, and existing catalog flow. Playwright also covers invalid cross-Site session, `sessionStorage` behavior, amount errors, keyboard/focus/error announcements, and no browser persistence containing the distinctive donor message.

- [ ] **Step 8: Run SDK and demo verification**

Run:

```bash
cd frontend
pnpm --filter @generic-payments/sdk test --run
pnpm --filter @generic-payments/sdk typecheck
pnpm --filter @generic-payments/sdk build
cd ../examples/demo-site
pnpm build
pnpm test:e2e
```

Expected: complete SDK tests/typecheck/build and all Playwright journeys pass.

- [ ] **Step 9: Run forbidden persistence scan**

Search production SDK/demo sources for donor-message writes to storage and inspect every match:

```bash
rg -n 'localStorage|sessionStorage|setItem|message' frontend/sdk/src/donations examples/demo-site/src
```

Expected: no code persists donor message; session storage use remains confined to the approved Customer/checkout session modules.

- [ ] **Step 10: Local checkpoint and reviewer gate**

Record public exports, component events, test counts, and storage-scan result. Review asynchronous completion and invalid-session behavior.

---

### Task 9: Add observability/acceptance/smoke and close the Stage 3B gate

**Files:**
- Modify: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/CustomerTelemetryPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/application/port/out/DonationTelemetryPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/donations/adapter/out/observability/MicrometerDonationTelemetryAdapter.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/donations/adapter/out/observability/DonationObservabilityIntegrationTest.java`
- Create: `backend/src/test/resources/features/donations.feature`
- Create: `backend/src/test/java/dev/genericpayments/platform/acceptance/DonationSteps.java`
- Modify: `backend/src/test/java/dev/genericpayments/platform/acceptance/AcceptanceWorld.java`
- Modify: `backend/src/test/java/dev/genericpayments/platform/acceptance/RunCucumberTest.java`
- Create: `infrastructure/smoke-stage-3b.mjs`
- Create: `infrastructure/smoke-stage-3b.test.mjs`
- Modify: `infrastructure/compose.yaml`
- Modify: `infrastructure/.env.example`
- Modify: `docs/local-development.md`
- Modify: `.superpowers/sdd/2026-10-10-donations/progress.md`
- Create: `.superpowers/sdd/2026-10-10-donations/review.md`

**Interfaces:**
- Donation metric labels are safe bounded outcome/source enums only; no Site/Customer/Campaign/Donation IDs, campaign key, Origin, message, email, or amount labels.
- Cucumber uses real Spring HTTP/PostgreSQL/provider/callback boundaries.
- Smoke starts/stops only this Compose project's resources and preserves PostgreSQL volume.

- [ ] **Step 1: Write failing observability and leak-scan tests**

Assert donation checkout outcome counters/latency and message-redaction outcomes with an allowlisted tag set. Scan logs, audit, metrics, PaymentEvents, outbox payloads, callback receiver output, Customer/Partner history, and SDK storage for a distinctive message fixture.

- [ ] **Step 2: Run observability tests red**

Run:

```bash
cd backend
./mvnw -q -Dtest=DonationObservabilityIntegrationTest test
```

Expected: the test fails because the Donation telemetry adapter and leak scan are absent.

- [ ] **Step 3: Implement bounded donation telemetry**

Record safe outcomes such as `created`, `replayed`, `rejected_amount`, `rejected_anonymous`, `rejected_session`, and `conflict`; do not label campaign/source IDs or amount. Preserve Stage 3A metric contracts.

- [ ] **Step 4: Add Stage 3B Cucumber scenarios**

Cover Campaign lifecycle; suggested/custom validation; anonymous allowed/forbidden; authenticated binding; invalid session no anonymous fallback; identity-aware idempotency replay/conflict; atomic Donation/Payment creation; provider success/failure/retry/refund/reconciliation; message encryption/redaction/anonymization; Customer/Partner/Admin isolation; callback v1 unchanged/v2 signed/deduplicated/message-free.

- [ ] **Step 5: Unit-test the smoke script**

The script must create/configure Campaigns, run anonymous and OTP-authenticated donations, drive fake provider success, inspect Customer history/Admin state, verify v2 receiver data, refund through existing API, anonymize Customer, confirm message deletion/financial retention, and verify a catalog v1 callback remains unchanged.

Run:

```bash
node --test infrastructure/smoke-stage-3b.test.mjs
```

Expected: script contract tests pass without starting containers.

- [ ] **Step 6: Run the complete backend regression**

Run:

```bash
cd backend
./mvnw clean verify
```

Expected: every Stage 1–3B unit, integration, architecture, and Cucumber test passes. Compare totals with the recorded Stage 3A gate; investigate any unexplained decrease.

- [ ] **Step 7: Run complete frontend/infrastructure regression**

Run:

```bash
cd frontend
pnpm --filter admin typecheck
pnpm --filter admin test --run
pnpm --filter admin build
pnpm --filter @generic-payments/sdk typecheck
pnpm --filter @generic-payments/sdk test --run
pnpm --filter @generic-payments/sdk build
cd ../examples/demo-site
pnpm build
pnpm test:e2e
cd ../..
node --test infrastructure/callback-receiver.test.mjs infrastructure/smoke-stage-2.test.mjs infrastructure/smoke-stage-3a.test.mjs infrastructure/smoke-stage-3b.test.mjs
```

Expected: all commands pass with no unexplained test-count decrease from Stage 3A.

- [ ] **Step 8: Run the Stage 3B Compose smoke from a stopped state**

Run:

```bash
docker compose -f infrastructure/compose.yaml up -d --build
node infrastructure/smoke-stage-3b.mjs
docker compose -f infrastructure/compose.yaml stop backend admin demo callback-receiver mailpit postgres
```

Expected: complete journey passes; owned services stop; PostgreSQL volume remains; no unrelated container or volume changes.

- [ ] **Step 9: Perform independent review and fix significant findings**

Review all Stage 3B changes against the Review Focus and approved spec. Classify exact file/line findings as Critical, Important, or Minor. Fix Critical/Important items using focused red-green-refactor and repeat affected suites. Record accepted Minor items with rationale.

- [ ] **Step 10: Complete the Stage 3B coverage matrix**

Record evidence in `review.md`:

| Requirement | Evidence required |
|---|---|
| Campaign lifecycle/amount/anonymous policy | domain + persistence + API + UI tests |
| Atomic Donation/Payment/idempotency | rollback + concurrency + HTTP tests |
| Invalid session never becomes anonymous | backend + SDK + Cucumber + Playwright tests |
| Provider/refund/reconciliation reuse | source-parameterized integration tests |
| Message confidentiality/redaction | DB + anonymization + Admin + leak scans |
| Callback v1 compatibility/v2 correctness | golden bytes + signing + receiver + smoke |
| Customer/Partner/Admin isolation | query/cursor/scope/filter tests |
| SDK/component accessibility/storage | Vitest + Playwright + scans |
| Stage 1–3A compatibility | complete backend/frontend/infrastructure regression |
| Runtime cleanup | stopped owned services + retained volume evidence |

- [ ] **Step 11: Close the gate**

Record final commands/counts, review resolution, smoke evidence, stopped resources, and retained volume. Write `STAGE_3B_GATE=PASSED` only when every command and coverage row passes with no open Critical/Important finding; otherwise write `STAGE_3B_GATE=BLOCKED` and list exact failures.

---

## Plan Self-Review Checklist

- [ ] The Stage 3A pass marker is a hard precondition, not an informal assumption.
- [ ] Every Stage 3B requirement maps to a task and a final evidence row.
- [ ] Public, Partner, Customer, and Admin paths/operation IDs are fixed and non-conflicting.
- [ ] Donation orchestration uses a Payment application port and no Donation service accesses Payment persistence directly.
- [ ] All three Payment source shapes are mutually exclusive in domain and database.
- [ ] Donor message has explicit validation, encryption, exposure, redaction, anonymization, and leak-test rules.
- [ ] Customer/anonymous context and message digest participate in idempotency without storing raw secrets/content.
- [ ] Callback v1 compatibility and strict v2 fields are independently tested.
- [ ] Existing provider, refund, reconciliation, Admin action, and checkout machinery is reused rather than duplicated.
- [ ] Every task starts red, ends with focused verification, and has a reviewer gate.
- [ ] Full regression, Compose cleanup, and independent review are required before completion.
- [ ] No Git command or Git-dependent workflow is required.
