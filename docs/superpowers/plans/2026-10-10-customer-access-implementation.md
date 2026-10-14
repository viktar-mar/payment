# Stage 3A Customer Access Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add tenant-isolated Customer identities, SITE_TOKEN/OIDC/email-OTP authentication, short-lived browser SDK sessions, explicit identity linking, anonymization, optional Customer ownership for existing catalog and Partner dynamic payments, and headless Customer payment history without introducing a central Customer cabinet.

**Architecture:** Add a hexagonal `customers` module to the existing Spring Boot modular monolith. Authentication evidence is validated at adapter boundaries, converted into provider-neutral verified identity proofs, and resolved transactionally to Site-owned Customer aggregates. PostgreSQL stores only encrypted PII, keyed digests, one-time/replay state, distributed rate limits, and leased mail-outbox work. Existing `payments` flows consume a narrow Customer-session/ownership port and remain guest-compatible when no credential is supplied. The Next.js admin, framework-agnostic SDK, static demo, and Compose runtime remain independently buildable.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Maven Wrapper 3.9.11, Spring Security, Spring OAuth2 JOSE/Nimbus, Spring Mail, PostgreSQL 18, Flyway, Spring Data JPA/JdbcTemplate, Java HTTP Client, Micrometer, existing AES-256-GCM encryption, HMAC-SHA-256 keyed digests, JUnit 5, AssertJ, Testcontainers, ArchUnit, Cucumber-JVM 7.34.8, Node.js 24, pnpm 11.1.3, Next.js 16.3.3, React 19.3.0, TypeScript 5.9, Tailwind CSS 4.3, Vitest, Testing Library, Playwright, Mailpit.

**Spec:** `docs/superpowers/specs/2026-10-10-customer-access-donations-design.md`, sections 1–11, 15.1–15.3, 15.5–15.6, 17–24.1, and the Stage 3A parts of sections 19–23 and 25.

## Global Constraints

- Work only in this local workspace. Do not initialize or inspect Git, and do not create commits, branches, tags, worktrees, remotes, or pushes.
- Stage 3B is blocked until every Stage 3A gate at the end of this plan passes and an independent review has no open Critical or Important findings.
- Preserve all Stage 1–2 contracts and data. Public catalog checkout stays guest-compatible when no SDK session is supplied; Stage 2 callback schema remains version 1.
- Keep `backend/`, `frontend/admin/`, `frontend/sdk/`, and `examples/demo-site/` independently buildable.
- Domain packages must not import Spring, Jakarta Persistence, Jackson, JOSE/Nimbus, JavaMail, HTTP, JDBC, PostgreSQL, or provider SDK classes. Keep `ArchitectureRulesTest` green.
- Customer, identity, session, Payment, and history ownership is always scoped by Site. Never infer ownership from email or a browser-supplied Customer ID.
- Production SITE_TOKEN and OIDC signatures accept only configured `ES256` or `RS256` keys. HMAC token fixtures are permitted only under local/test configuration and may not be enabled by production properties.
- Do not store or log raw source tokens, SDK sessions, OTP codes, subjects, email addresses, SMTP credentials, authorization headers, or request/response bodies for authentication endpoints.
- SDK sessions are opaque, absolute-TTL tokens stored only in `sessionStorage`; there is no refresh token, cookie, sliding extension, URL token, or `localStorage` fallback.
- An explicitly supplied invalid, expired, anonymized, cross-Site, or cross-Origin session fails closed. Only an absent credential has guest semantics.
- Identity linking is explicit. Matching email never merges Customers; a proof owned by another Customer returns conflict.
- Challenge/outbox writes, identity resolution, session issuance, linking, and anonymization use transactions with row locks or uniqueness constraints sufficient for concurrent requests.
- SMTP and remote JWKS network calls execute outside open database transactions and use bounded deadlines, response sizes, retries, and leases.
- Use injected `Clock`, cryptographically secure random generation, constant-time digest comparison, UUID internal IDs, UTC timestamps, and exact Origin matching.
- Use red-green-refactor for every production change. Each implementation task begins with a focused failing test and ends with a local verification checkpoint; the final release-gate task begins with verification and adds a failing regression test before any defect fix.
- Whenever migration V5 adds foreign-key-related tables, update `PostgresIntegrationTest.clearDatabase()` and Cucumber reset order in the same task.
- Because Git is prohibited, record task state and verification evidence in `.superpowers/sdd/2026-10-10-customer-access/progress.md`.

## Fixed Security and Runtime Defaults

- SDK session absolute TTL: 30 minutes; token: 32 random bytes, base64url without padding; scopes: `CUSTOMER_READ`, `CHECKOUT_CREATE`, `IDENTITY_LINK`.
- OIDC authentication-attempt TTL: 5 minutes; identity-link proof TTL: 5 minutes; clock skew: 60 seconds.
- SITE_TOKEN maximum lifetime: 5 minutes; consumed `jti` digest retained through `exp + 60 seconds`.
- OTP: six numeric digits, 10-minute TTL, five verification attempts, 60-second resend cooldown.
- OTP distributed windows: Site 60 requests/10 minutes, Site+email 5/10 minutes, Site+source-IP 10/10 minutes; verification remains additionally bounded by the challenge attempt counter.
- Remote JWKS: HTTPS only, no redirects/user-info/fragments, 2-second connect timeout, 3-second total request timeout, 256 KiB body maximum, 20-key maximum, 15-minute success TTL, 60-minute maximum stale window, and one forced refresh for an unknown `kid`.
- Customer payment history: default page size 20, maximum 50, ordered by `created_at DESC, id DESC`; cursor is authenticated and bound to Site, Customer, and query shape.
- Mail worker: batch 50, 60-second lease, eight total attempts, exponential retry capped at 30 minutes, encrypted payload deletion after success, and seven-day safe terminal-metadata retention.
- Trusted proxy processing is disabled by default. When configured, only exact CIDRs may contribute a forwarded source IP; otherwise the direct peer address is digested.

## Review Focus

- Algorithm confusion, issuer/audience/`azp`/time/nonce/`jti` validation, key rotation, and bounded unknown-`kid` refresh.
- JWKS SSRF protection across DNS resolution, connect address, redirects, IPv4-mapped addresses, metadata endpoints, timeouts, and body/key-count limits.
- Exactly one Customer/identity/session outcome under concurrent exchange, OTP verification, linking, logout, and anonymization.
- Site and exact-Origin isolation at every public, Customer, Partner, persistence, and cursor boundary.
- No silent guest fallback when any Customer credential is present but invalid.
- No raw credentials or PII in persistence, logs, audit, metrics, exceptions, OpenAPI examples, browser storage, or local progress notes.
- Transactional challenge/outbox creation and safe multi-worker mail claim/lease/retry/recovery.
- Composite Payment-to-Customer foreign key preserves existing guest rows and rejects legacy non-null orphans before constraint creation.
- Catalog and dynamic idempotency fingerprints distinguish Customer ownership from explicit guest context without containing raw session tokens.
- Anonymization is irreversible and idempotent, removes PII/identities/sessions/OTP data, retains financial facts, and exposes a Stage 3B donor-message redaction extension point.
- Existing Stage 1–2 auth, catalog, payments, refunds, callbacks, SDK, demo, and Compose behavior remains green.

---

## Exact API Contract

Public authentication operations use the existing safe error envelope and require an allowed non-null `Origin`:

| Method and path | OpenAPI operation ID | Purpose |
|---|---|---|
| `POST /api/v1/public/customer-auth/site-token/exchanges` | `exchangeCustomerSiteToken` | Validate one-time SITE_TOKEN and return a one-time SDK session. |
| `POST /api/v1/public/customer-auth/oidc/attempts` | `beginCustomerOidcAttempt` | Return `attempt_id`, `nonce`, and expiry for one provider and intent. |
| `POST /api/v1/public/customer-auth/oidc/exchanges` | `exchangeCustomerOidcToken` | Consume attempt, validate OIDC ID token, and return session or link proof. |
| `POST /api/v1/public/customer-auth/email-otp/challenges` | `requestCustomerEmailOtp` | Return uniform `202` with challenge ID and expiry. |
| `POST /api/v1/public/customer-auth/email-otp/challenges/{challengeId}/verify` | `verifyCustomerEmailOtp` | Consume code and return session or link proof. |
| `POST /api/v1/public/customer-auth/site-token/link-proofs` | `createSiteTokenIdentityLinkProof` | Validate SITE_TOKEN and return one-time link proof without resolving a Customer. |

`AuthenticationIntent` is `AUTHENTICATE` or `LINK`. OIDC attempt and email challenge store the intent; `LINK` exchanges return only a 32-byte opaque `proof_token` and expiry. SITE_TOKEN uses its dedicated link-proof endpoint so the session response is never a response union. Raw token fields are marked `writeOnly` in OpenAPI and excluded from body logging.

Customer operations require `Authorization: Bearer <sdk-session>` and the exact bound Origin:

| Method and path | OpenAPI operation ID |
|---|---|
| `GET /api/v1/customer/me` | `getCurrentCustomer` |
| `GET /api/v1/customer/payments?cursor=&limit=` | `listCurrentCustomerPayments` |
| `GET /api/v1/customer/donations?cursor=&limit=` | `listCurrentCustomerDonations` — returns an empty page in 3A and is extended in 3B. |
| `POST /api/v1/customer/identities` | `linkCurrentCustomerIdentity` |
| `DELETE /api/v1/customer/session` | `logoutCurrentCustomerSession` |

Partner additions use existing Site API-key authentication:

| Method and path | Scope | OpenAPI operation ID |
|---|---|---|
| `GET /api/v1/partner/customers/{customerId}` | `CUSTOMERS_READ` | `getPartnerCustomer` |
| `POST /api/v1/partner/customers/{customerId}/identities` | `CUSTOMERS_MANAGE` | `linkPartnerCustomerIdentity` |
| `POST /api/v1/partner/customers/{customerId}/anonymizations` | `CUSTOMERS_MANAGE` | `anonymizePartnerCustomer` |

Admin additions use existing Admin session authentication:

| Method and path | OpenAPI operation ID |
|---|---|
| `GET/POST /api/v1/admin/sites/{siteId}/identity-providers` | `listIdentityProviders` / `createIdentityProvider` |
| `GET/PUT /api/v1/admin/sites/{siteId}/identity-providers/{providerId}` | `getIdentityProvider` / `updateIdentityProvider` |
| `POST /api/v1/admin/sites/{siteId}/identity-providers/{providerId}/status` | `changeIdentityProviderStatus` |
| `GET /api/v1/admin/customers` | `listAdminCustomers` |
| `GET /api/v1/admin/customers/{customerId}` | `getAdminCustomer` |
| `POST /api/v1/admin/customers/{customerId}/anonymizations` | `anonymizeAdminCustomer` |

Admin and Partner mutations require `Idempotency-Key`; admin anonymization additionally requires body field `confirmation` equal to the Customer UUID. Provider lists/details expose only safe issuer, audience, algorithm, source type, public-key metadata, readiness, and refresh state.

---

## Target File Map

### Customer core and security adapters

- `backend/src/main/java/dev/genericpayments/platform/customers/domain/*` — Customer, CustomerIdentity, provider configuration, session, OTP, OIDC attempt, link proof, mail job, IDs, statuses, and invariants.
- `backend/src/main/java/dev/genericpayments/platform/customers/application/model/*` — verified identity evidence, session context, safe views, cursor pages, and mail claims.
- `backend/src/main/java/dev/genericpayments/platform/customers/application/port/in/*` — authentication, session, linking, anonymization, provider configuration, Customer query, and history use cases.
- `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/*` — persistence, locks, Site policy, token verification, keyed digest, encryption, random code, mail, rate limit, audit, telemetry, Customer payment query, and redaction ports.
- `backend/src/main/java/dev/genericpayments/platform/customers/application/service/*` — transaction-safe identity resolution, exchanges, OTP lifecycle, linking, logout, anonymization, and safe query orchestration.
- `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/persistence/*` — JPA/JdbcTemplate entities, composite ownership queries, atomic consumption, distributed limits, and mail claims.
- `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/security/*` — HMAC keyed digests, email normalization/masking, JOSE verification, remote/static JWKS, and authentication properties.
- `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/mail/*` — SMTP port adapter, encrypted rendering, failure classification, worker, and readiness.
- `backend/src/main/java/dev/genericpayments/platform/customers/adapter/in/web/*` — Public, Customer, Partner, and Admin controllers, DTOs, session filter, exact-Origin checks, and safe errors.
- `backend/src/main/java/dev/genericpayments/platform/customers/adapter/in/configuration/*` — beans, scheduler, Customer security chain integration, bounded properties, and local/test issuer fixture.

### Existing backend extensions

- `backend/pom.xml` — managed Spring OAuth2 JOSE and Mail starters.
- `backend/src/main/java/dev/genericpayments/platform/admin/adapter/in/web/SecurityConfiguration.java` — place Customer session filter without weakening Public/Admin/Partner chains.
- `backend/src/main/java/dev/genericpayments/platform/partners/domain/PartnerScope.java` — add `CUSTOMERS_READ` and `CUSTOMERS_MANAGE`.
- `backend/src/main/java/dev/genericpayments/platform/payments/domain/Payment.java` — catalog/dynamic factories accept optional resolved Customer ID.
- `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/CreateCatalogCheckoutCommand.java` — carry server-resolved optional Customer ID.
- `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/CreateDynamicPaymentCommand.java` — carry validated optional Customer ID.
- `backend/src/main/java/dev/genericpayments/platform/payments/application/service/CatalogCheckoutTransaction.java` and `DynamicPaymentTransaction.java` — ownership-aware fingerprints and creation.
- `backend/src/main/java/dev/genericpayments/platform/payments/adapter/in/web/PublicCheckoutController.java` — distinguish absent from invalid SDK authorization.
- `backend/src/main/java/dev/genericpayments/platform/payments/adapter/in/web/PartnerPaymentController.java` — optional `customer_id` and nullable Customer response field.
- `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/LoadPaymentPort.java` and `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/*` — Customer-scoped keyset history query.
- `backend/src/main/java/dev/genericpayments/platform/shared/application/PaymentApplicationException.java` and `backend/src/main/java/dev/genericpayments/platform/shared/adapter/in/web/ApiExceptionHandler.java` — bounded Stage 3A public codes and safe extension keys.
- `backend/src/main/java/dev/genericpayments/platform/shared/adapter/out/observability/*` — bounded auth/session/mail telemetry.
- `backend/src/main/java/dev/genericpayments/platform/admin/domain/AuditAction.java` — provider, linking, and anonymization actions without PII.
- `backend/src/main/resources/db/migration/V5__customer_access.sql` — Customer/auth/session/replay/rate-limit/mail schema and safe Payment Customer foreign key.
- `backend/src/main/resources/application.yml` and `backend/src/test/resources/application.yml` — bounded defaults and explicit local/test-only overrides.
- `backend/src/test/java/dev/genericpayments/platform/support/PostgresIntegrationTest.java` — V5 cleanup order and reusable fixtures.

### Admin, SDK, demo, acceptance, and runtime

- `frontend/admin/src/app/sites/[siteId]/authentication/page.tsx` and `frontend/admin/src/components/customers/authentication/*` — Site provider settings and readiness.
- `frontend/admin/src/app/customers/*` and `frontend/admin/src/components/customers/*` — Customer list, safe details, and anonymization confirmation.
- `frontend/admin/src/components/AppShell.tsx` — navigation only; no Customer cabinet.
- `frontend/admin/src/lib/api/schema.d.ts` — regenerated OpenAPI types; never hand-edit.
- `frontend/sdk/src/customer/*` — session store, auth methods, history, linking, logout, and email-OTP Web Component.
- `frontend/sdk/src/client.ts`, `frontend/sdk/src/contracts.ts`, `frontend/sdk/src/index.ts`, `frontend/sdk/src/storage.ts` — additive Customer API and automatic same-context checkout attachment.
- `examples/demo-site/src/main.ts`, `examples/demo-site/index.html`, `examples/demo-site/tests/customer-access.spec.ts` — email OTP, authenticated catalog history, logout/expiry, and cross-Site checks.
- `backend/src/test/resources/features/customer_access.feature`, `backend/src/test/java/dev/genericpayments/platform/acceptance/CustomerAccessSteps.java`, and `AcceptanceWorld.java` — Stage 3A HTTP/PostgreSQL scenarios.
- `infrastructure/compose.yaml`, `infrastructure/.env.example`, `infrastructure/smoke-stage-3a.mjs`, and `infrastructure/smoke-stage-3a.test.mjs` — Mailpit and repeatable Stage 3A journey.
- `docs/local-development.md` — exact auth, Mailpit, SDK, and verification instructions.
- `.superpowers/sdd/2026-10-10-customer-access/progress.md` — local checkpoints and review evidence.

---

### Task 1: Implement the framework-free Customer domain

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/CustomerId.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/CustomerStatus.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/Customer.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/CustomerIdentityId.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/CustomerAuthenticationMode.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/CustomerIdentity.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/IdentityProviderConfiguration.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/IdentityProviderStatus.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/JwksSource.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/domain/CustomerTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/domain/CustomerIdentityTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/domain/IdentityProviderConfigurationTest.java`
- Create: `.superpowers/sdd/2026-10-10-customer-access/progress.md`

**Interfaces:**
- Produces immutable framework-free aggregates with named transitions and redacted `toString()` behavior.
- Uses existing `SiteId`; maps `sites.domain.AuthenticationMode` only at the application boundary to avoid making Customer domain ownership ambiguous.
- A Customer may have no email; a verified email is profile data, never an automatic merge key.

- [ ] **Step 1: Write failing Customer and identity invariant tests**

Cover immutable Site ownership, ACTIVE-to-ANONYMIZED as the only status transition, removal of email references during anonymization, identity issuer/subject-digest validation, and rejection of attaching an identity to a different Customer.

```java
@Test
void anonymizationIsIrreversibleAndDropsProfileEmail() {
    Customer active = Customer.create(CUSTOMER_ID, SITE_ID, EMAIL_CIPHERTEXT_REF,
        EMAIL_DIGEST, INITIAL_TIME);

    Customer anonymized = active.anonymize(INITIAL_TIME.plusSeconds(1));

    assertThat(anonymized.status()).isEqualTo(CustomerStatus.ANONYMIZED);
    assertThat(anonymized.encryptedEmailRef()).isEmpty();
    assertThatThrownBy(() -> anonymized.attachEmail(EMAIL_CIPHERTEXT_REF, EMAIL_DIGEST,
        INITIAL_TIME.plusSeconds(2))).isInstanceOf(DomainValidationException.class);
}

@Test
void identityOwnershipCannotMoveBetweenCustomers() {
    CustomerIdentity identity = CustomerIdentity.create(IDENTITY_ID, SITE_ID,
        CUSTOMER_ID, CustomerAuthenticationMode.OIDC_TOKEN, "google",
        SUBJECT_DIGEST, INITIAL_TIME);

    assertThatThrownBy(() -> identity.assertOwnedBy(OTHER_CUSTOMER_ID))
        .isInstanceOf(DomainValidationException.class);
}
```

- [ ] **Step 2: Run the focused tests and observe the intentional compilation failure**

Run:

```bash
cd backend
./mvnw -q -Dtest='dev.genericpayments.platform.customers.domain.*Test' test
```

Expected: test compilation fails because the Customer domain does not exist.

- [ ] **Step 3: Implement IDs, statuses, and Customer transitions**

Use `record` IDs with non-null UUID validation. `Customer` exposes `create`, `touch`, `attachEmail`, and `anonymize`; it has no public arbitrary-status setter. Keep ciphertext/digest as opaque domain references rather than raw email values.

```java
public enum CustomerStatus { ACTIVE, ANONYMIZED }

public enum CustomerAuthenticationMode { SITE_TOKEN, OIDC_TOKEN, EMAIL_OTP }

public Customer anonymize(Instant now) {
    if (status == CustomerStatus.ANONYMIZED) return this;
    return new Customer(id, siteId, CustomerStatus.ANONYMIZED, null, null,
        createdAt, now, now, version);
}
```

- [ ] **Step 4: Implement CustomerIdentity and provider configuration invariants**

Identity uniqueness is represented by Site ID, stable bounded `issuerKey`, and versioned keyed subject digest. Configuration variants are `OidcTokenConfiguration`, `SiteTokenConfiguration`, and `EmailOtpConfiguration`; allowed algorithms are a non-empty subset of `ES256`/`RS256`, audiences are trimmed unique values, remote JWKS requires HTTPS, and provider keys use the existing safe-key pattern.

- [ ] **Step 5: Add architecture fixtures that reject framework/security dependencies in the new domain**

Extend `ForbiddenDomainDependencyFixtures` and `ArchitectureRulesRuleTest` to prove imports from Spring, JOSE/Nimbus, JavaMail, Jackson, JPA, JDBC, or HTTP fail the rule.

- [ ] **Step 6: Run domain and architecture tests**

Run:

```bash
cd backend
./mvnw -q -Dtest='dev.genericpayments.platform.customers.domain.*Test,ArchitectureRulesTest,ArchitectureRulesRuleTest' test
```

Expected: all selected tests pass.

- [ ] **Step 7: Local checkpoint and reviewer gate**

Record test count and domain transition tables in the progress file. Review that no raw email/subject/token type can enter a domain `toString()`, and do not start Task 2 until the focused suite is green.

---

### Task 2: Add V5 persistence, composite ownership, and safe Payment migration

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__customer_access.sql`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/persistence/CustomerJpaEntity.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/persistence/CustomerIdentityJpaEntity.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/persistence/IdentityProviderConfigurationJpaEntity.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/persistence/CustomerPersistenceAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/CustomerStore.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/CustomerIdentityStore.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/IdentityProviderConfigurationStore.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/adapter/out/persistence/CustomerPersistenceIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/adapter/out/persistence/CustomerMigrationIntegrationTest.java`
- Modify: `backend/src/test/java/dev/genericpayments/platform/support/PostgresIntegrationTest.java`

**Interfaces:**
- `CustomerStore` provides Site-scoped reads, a `FOR UPDATE` read, create/update, list, and safe email-digest lookup.
- `CustomerIdentityStore` resolves and locks by `(siteId, issuerKey, subjectDigest)` and never accepts a raw subject.
- V5 creates all Stage 3A tables now so later tasks add behavior without additional Stage 3A migrations.

- [ ] **Step 1: Write failing migration and persistence tests**

Test Site isolation, unique `(site_id,id)`, unique identity `(site_id,issuer_key,subject_digest,key_version)`, optimistic versions, encrypted email columns, guest Payment preservation, and rejection of a non-null Payment customer that has no same-Site Customer.

```java
@Test
void existingGuestPaymentsSurviveCustomerForeignKeyMigration() {
    UUID paymentId = insertLegacyPayment(null);
    migrateToV5();
    assertThat(readPaymentCustomerId(paymentId)).isNull();
}

@Test
void migrationFailsBeforeAddingConstraintWhenLegacyCustomerIsOrphaned() {
    insertLegacyPayment(UUID.randomUUID());
    assertThatThrownBy(this::migrateToV5)
        .hasMessageContaining("orphan payment customer_id");
}
```

- [ ] **Step 2: Run the migration tests and observe the missing V5 behavior**

Run:

```bash
cd backend
./mvnw -q -Dtest='CustomerMigrationIntegrationTest,CustomerPersistenceIntegrationTest' test
```

Expected: tests fail because the migration and adapters do not exist.

- [ ] **Step 3: Create the complete Stage 3A schema**

Create these tables with UUID primary keys, Site composite foreign keys, UTC timestamps, explicit checks, indexes, and optimistic versions:

```text
customers
customer_identities
identity_provider_configurations
customer_sessions
oidc_authentication_attempts
consumed_site_token_jtis
identity_link_proofs
email_otp_challenges
customer_rate_limit_buckets
customer_mail_outbox
customer_idempotency_records
```

Encrypted columns store `ciphertext`, `nonce`, and `key_version`; keyed digests store `digest` and `key_version`. Add partial indexes for active sessions/challenges, expiring replay data, claimable mail, and stable Customer list/history queries.

Replace `ck_api_keys_scopes` in the same migration so its allowlist becomes `payments:create`, `payments:read`, `customers:read`, and `customers:manage`; preserve every existing key row and require a non-empty JSON array as before.

- [ ] **Step 4: Add the safe composite Payment foreign key**

Before the DDL constraint, execute a PostgreSQL `DO` block that raises a named exception when any non-null `payments.customer_id` lacks `(customers.site_id,customers.id)`. Then add `UNIQUE (site_id,id)` to Customers and:

```sql
ALTER TABLE payments
  ADD CONSTRAINT fk_payments_customer_site
  FOREIGN KEY (site_id, customer_id)
  REFERENCES customers(site_id, id);
```

Do not backfill or fabricate Customers. The nullable composite preserves all guest rows.

- [ ] **Step 5: Implement mapping and Site-scoped persistence ports**

Use explicit mappers; do not expose JPA entities outside the adapter. Locking queries include Site ID. Catch uniqueness races only at the service boundary where they can be re-read as the same identity.

- [ ] **Step 6: Update integration cleanup order**

Delete child tables before identities/sessions/Customers and Customers before Sites. Keep existing Stage 1–2 reset order intact. Add an assertion that every V5 table is empty after `clearDatabase()`.

- [ ] **Step 7: Run persistence, migration, and full architecture checks**

Run:

```bash
cd backend
./mvnw -q -Dtest='CustomerMigrationIntegrationTest,CustomerPersistenceIntegrationTest,ArchitectureRulesTest' test
```

Expected: V5 migrates an existing Stage 2 database, guest rows remain unchanged, orphan precondition fails deterministically, and all selected tests pass.

- [ ] **Step 8: Local checkpoint and reviewer gate**

Record the exact table/constraint names and test count. Review every ownership query for a Site predicate and every secret/PII column for ciphertext or keyed-digest storage.

---

### Task 3: Implement keyed digests, email normalization, JOSE verification, and safe JWKS

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/KeyedDigestPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/IdentityTokenVerifierPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/model/VerifiedIdentityEvidence.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/security/HmacSha256KeyedDigestAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/security/CustomerEmailNormalizer.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/security/NimbusIdentityTokenVerifierAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/security/SafeJwksClient.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/security/JwksAddressPolicy.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/security/CustomerSecurityProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application.yml`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/adapter/out/security/HmacSha256KeyedDigestAdapterTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/adapter/out/security/CustomerEmailNormalizerTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/adapter/out/security/NimbusIdentityTokenVerifierAdapterTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/adapter/out/security/SafeJwksClientTest.java`

**Interfaces:**
- Key purposes are separate and versioned: `EMAIL_LOOKUP`, `IDENTITY_SUBJECT`, `SDK_SESSION`, `OTP_CODE`, `SOURCE_IP`, `SITE_TOKEN_JTI`, `LINK_PROOF`, and `HISTORY_CURSOR`.
- Token verifier accepts only a trusted configuration plus an expected nonce when required; it never accepts issuer/JWKS/algorithm data from token headers beyond `kid` selection.
- Returns canonical subject, stable issuer key, optional verified normalized email, `jti`, and validated timestamps; never returns or stores the raw token.

- [ ] **Step 1: Add failing digest, normalization, and token-claim tests**

Cover deterministic per-purpose digests, key versioning, constant-time match, Unicode NFC/trim/`Locale.ROOT` lowercase normalization, redacted masking, `none`/HS256 rejection, exact issuer, audience, multi-audience `azp`, subject, `exp`/`nbf`/`iat`, nonce, `jti`, `kid`, and RS256/ES256 success.

```java
@Test
void sameValueHasDifferentDigestAcrossPurposes() {
    assertThat(digests.digest(Purpose.EMAIL_LOOKUP, "value"))
        .isNotEqualTo(digests.digest(Purpose.IDENTITY_SUBJECT, "value"));
}

@Test
void oidcWithMultipleAudiencesRequiresMatchingAzp() {
    String token = fixture.idToken().audiences("web", "api").withoutAzp().sign();
    assertThatThrownBy(() -> verifier.verifyOidc(configuration, token, NONCE, NOW))
        .isInstanceOf(AuthenticationFailedException.class);
}
```

- [ ] **Step 2: Add the managed JOSE dependency and run focused tests red**

Add `spring-security-oauth2-jose` without a manual Nimbus version so Spring Boot manages compatibility. Run:

```bash
cd backend
./mvnw -q -Dtest='HmacSha256KeyedDigestAdapterTest,CustomerEmailNormalizerTest,NimbusIdentityTokenVerifierAdapterTest,SafeJwksClientTest' test
```

Expected: production classes are missing or focused assertions fail.

- [ ] **Step 3: Implement purpose-separated HMAC-SHA-256**

Load non-empty base64 keys and active key versions from environment-backed properties. Prefix the MAC input with an unambiguous versioned purpose frame and compare decoded bytes with `MessageDigest.isEqual`.

```java
byte[] framed = ("gp:v1:" + purpose.name() + ":" + value).getBytes(UTF_8);
byte[] digest = macFor(purpose).doFinal(framed);
return new DigestValue(HexFormat.of().formatHex(digest), activeVersion(purpose));
```

Never reuse `DigestPort` for low-entropy or lookup data; it remains appropriate only for existing non-secret SHA-256 content hashes.

- [ ] **Step 4: Implement token verification as an adapter**

Pin the configured algorithm before key selection, reject embedded JWK/JWKS/x5u headers, require a configured `kid`, apply the fixed clock skew, and normalize only a configured verified-email claim. SITE_TOKEN requires non-blank `jti`, `iat`, `exp`, and `exp - iat <= 5 minutes`.

- [ ] **Step 5: Implement safe static and remote JWKS resolution**

Resolve all addresses before connect and reject loopback, site-local/private, link-local, multicast, unspecified, IPv4-mapped private, and cloud metadata targets. Disable redirects, validate the connected/resolved address again, enforce deadlines/body/key bounds, cache only validated public signing keys, and allow one synchronized forced refresh for an unknown `kid`.

- [ ] **Step 6: Add DNS-change, redirect, timeout, body-size, and stale-window tests**

Use a fake resolver and controlled HTTP fixture. Prove a hostname that changes from public to private is rejected at connect time, a redirect is never followed, an oversized body is aborted, and keys beyond the 60-minute maximum stale window are not used.

- [ ] **Step 7: Run security, architecture, and dependency checks**

Run:

```bash
cd backend
./mvnw -q -Dtest='*KeyedDigestAdapterTest,*EmailNormalizerTest,*IdentityTokenVerifierAdapterTest,*JwksClientTest,ArchitectureRulesTest' test
./mvnw -q dependency:analyze
```

Expected: selected tests pass; dependency analysis has no newly introduced undeclared or unused JOSE dependency.

- [ ] **Step 8: Local checkpoint and reviewer gate**

Record algorithms, bounds, and tests. Review SSRF behavior independently before any endpoint can invoke remote JWKS.

---

### Task 4: Implement provider configuration and SITE_TOKEN/OIDC authentication

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/OidcAuthenticationAttempt.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/ConsumedSiteTokenJti.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/AuthenticationIntent.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/IdentityLinkProof.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/in/BeginOidcAuthenticationUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/in/ExchangeOidcTokenUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/in/ExchangeSiteTokenUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/in/ManageIdentityProviderConfigurationUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/AuthenticationAttemptStore.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/ConsumedSiteTokenStore.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/IdentityLinkProofStore.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/service/OidcAuthenticationService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/service/SiteTokenAuthenticationService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/service/IdentityResolutionService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/service/IdentityProviderConfigurationService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/in/web/IdentityProviderAdminController.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/persistence/AuthenticationAttemptPersistenceAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/persistence/IdentityLinkProofPersistenceAdapter.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/application/service/OidcAuthenticationServiceIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/application/service/SiteTokenAuthenticationServiceIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/adapter/in/web/IdentityProviderAdminApiIntegrationTest.java`

**Interfaces:**
- `IdentityResolutionService.resolve(SiteId, VerifiedIdentityEvidence, Instant)` returns the same ACTIVE Customer for an existing identity or atomically creates one Customer plus one identity.
- Authentication attempts are bound to Site, provider key, exact Origin, intent, nonce digest, and expiry and are consumed under a row lock.
- Consumed SITE_TOKEN `jti` values are keyed digests retained through token expiry plus skew.

- [ ] **Step 1: Write failing authentication and concurrency tests**

Cover active Site/mode/configuration, exact Origin, attempt/provider/intent binding, one-time nonce, one-time `jti`, disabled configuration, same identity replay, concurrent first login, equal emails on different Sites, and no email-based merge.

```java
@Test
void concurrentFirstExchangeCreatesOneCustomerAndOneIdentity() {
    var results = concurrently(2, () -> service.authenticate(validExchangeWithDistinctAttempt()));

    assertThat(results).extracting(AuthenticationResult::customerId).containsOnly(results.getFirst().customerId());
    assertThat(customerCount(SITE_ID)).isOne();
    assertThat(identityCount(SITE_ID, ISSUER_KEY, SUBJECT_DIGEST)).isOne();
}

@Test
void consumedSiteTokenCannotBeExchangedAgain() {
    service.authenticate(SITE_KEY, ORIGIN, signedSiteToken("jti-1"));
    assertThatThrownBy(() -> service.authenticate(SITE_KEY, ORIGIN, signedSiteToken("jti-1")))
        .isInstanceOf(AuthenticationFailedException.class);
}
```

- [ ] **Step 2: Run focused tests and observe missing services**

Run:

```bash
cd backend
./mvnw -q -Dtest='OidcAuthenticationServiceIntegrationTest,SiteTokenAuthenticationServiceIntegrationTest,IdentityProviderAdminApiIntegrationTest' test
```

Expected: compilation or endpoint failures because orchestration is absent.

- [ ] **Step 3: Implement provider configuration management**

Admin create/update/status operations validate Site mode, provider-key uniqueness, issuer/audience/algorithms, JWKS source, version, and readiness. Static JWK sets may contain public keys only. Update requires the expected optimistic version and records a safe audit action; list/details redact key material to `kid`, `kty`, algorithm, and public-key fingerprint.

- [ ] **Step 4: Implement OIDC attempt and exchange**

Generate random attempt ID and nonce, persist only the nonce keyed digest, and return raw nonce once. At exchange, lock the attempt, validate Site/provider/Origin/intent/expiry, verify the token, compare nonce digest, mark consumed, then resolve identity or issue a link proof. A failed signature/claim does not consume the attempt; a successfully verified token consumes it before identity/session result is returned.

- [ ] **Step 5: Implement SITE_TOKEN exchange and replay protection**

Verify first, then atomically insert `(site_id,issuer_key,jti_digest,key_version,expires_at)`. A uniqueness conflict becomes the uniform authentication failure. Resolve identity only after replay insertion succeeds. The link-proof endpoint follows the same verification and replay rule but never creates a Customer.

- [ ] **Step 6: Implement conflict-safe identity resolution**

On an absent identity, create Customer and identity in one transaction. If a unique constraint loses a race, roll back the candidate Customer, re-read the winning identity, and return its ACTIVE Customer. Never query by email to choose identity ownership.

- [ ] **Step 7: Expose the Admin contract and prepare safe public exchange results**

Expose the provider-configuration operation IDs from the contract table. Authentication services return either an authenticated Customer result or a one-time opaque link proof; Task 5 wraps authenticated results in SDK sessions and exposes the public controller. Map all credential failures to `AUTHENTICATION_FAILED`, expose configuration diagnostics only to Admin, and define the body-size/logging policy for `/api/v1/public/customer-auth/**` before those routes are enabled.

- [ ] **Step 8: Run authentication, API, architecture, and log-capture tests**

Run:

```bash
cd backend
./mvnw -q -Dtest='OidcAuthenticationServiceIntegrationTest,SiteTokenAuthenticationServiceIntegrationTest,IdentityProviderAdminApiIntegrationTest,*AuthenticationLogSafetyTest,ArchitectureRulesTest' test
```

Expected: all selected tests pass and captured logs contain none of the fixture token, nonce, subject, or email values.

- [ ] **Step 9: Local checkpoint and reviewer gate**

Record test count and the claim/replay matrix. Review OIDC and SITE_TOKEN paths independently before enabling session issuance.

---

### Task 5: Issue opaque SDK sessions and protect the Customer API

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/CustomerSessionId.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/CustomerSession.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/CustomerSessionScope.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/model/CustomerSessionContext.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/model/CustomerSessionCreation.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/in/GetCurrentCustomerUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/in/LogoutCustomerSessionUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/CustomerSessionStore.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/CustomerSessionResolver.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/service/CustomerSessionService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/in/web/CustomerSessionAuthenticationFilter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/in/web/PublicCustomerAuthenticationController.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/in/web/CustomerController.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/persistence/CustomerSessionPersistenceAdapter.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/admin/adapter/in/web/SecurityConfiguration.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/application/service/CustomerSessionServiceIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/adapter/in/web/CustomerAuthenticationIntegrationTest.java`

**Interfaces:**
- `CustomerSessionResolver.resolveRequired(rawToken, origin)` returns a verified context or a safe typed failure.
- `CustomerSessionResolver.resolveOptional(authorizationHeader, siteId, origin)` returns empty only when the header is absent; malformed or invalid credentials throw.
- The raw session token is returned once and never appears in the domain object, persistence entity, logs, or audit.

- [ ] **Step 1: Write failing session lifecycle and filter tests**

Cover 32-byte token issuance, keyed-digest-only persistence, exact Site/Origin binding, scopes, 30-minute absolute expiry, no sliding renewal, logout, anonymized Customer rejection, malformed Bearer input, absent optional header, and parallel logout/use behavior.

```java
@Test
void optionalResolverDistinguishesAbsentFromInvalid() {
    assertThat(resolver.resolveOptional(null, SITE_ID, ORIGIN)).isEmpty();
    assertThatThrownBy(() -> resolver.resolveOptional("Bearer invalid", SITE_ID, ORIGIN))
        .isInstanceOf(AuthenticationFailedException.class);
}

@Test
void successfulUseDoesNotExtendAbsoluteExpiry() {
    Instant expiry = issueAt(INITIAL_TIME).expiresAt();
    resolveAt(INITIAL_TIME.plusSeconds(600));
    assertThat(loadSession().expiresAt()).isEqualTo(expiry);
}
```

- [ ] **Step 2: Run focused tests red**

Run:

```bash
cd backend
./mvnw -q -Dtest='CustomerSessionServiceIntegrationTest,CustomerAuthenticationIntegrationTest' test
```

Expected: missing session behavior or unauthorized endpoints.

- [ ] **Step 3: Implement opaque session issuance and lookup**

Generate the raw token through `RandomTokenPort`, store only `KeyedDigestPort` output and version, persist exact Origin/scopes/expiry, and return the raw token in `CustomerSessionCreation`. Resolution joins or separately validates ACTIVE Customer and active Site, checks constant-time digest, exact Origin, scope, expiry, and revocation.

- [ ] **Step 4: Integrate issuance with Task 4 exchanges and expose public token endpoints**

An `AUTHENTICATE` exchange resolves identity and issues one session in the same application transaction boundary. Expose SITE_TOKEN exchange, OIDC attempt/exchange, and SITE_TOKEN link-proof paths with the exact operation IDs above. Return `{session_token, expires_at, scopes, customer}` exactly once. If serialization fails after commit, replay of the source OIDC attempt or SITE_TOKEN remains rejected rather than issuing an unbounded second session. Enforce request-size limits and disable body tracing on every route.

- [ ] **Step 5: Add the Customer security filter and principal**

Filter only `/api/v1/customer/**`; never interpret SDK tokens as Admin/Partner credentials. Require non-null allowed Origin before token lookup. Place an immutable `CustomerSessionContext` in the Spring Security context with a dedicated `ROLE_CUSTOMER` authority.

- [ ] **Step 6: Implement `GET /me` and logout**

Return Customer ID, status, masked email, authentication modes, and session expiry; omit issuer subject, digests, ciphertext, and internal versions. `DELETE /session` locks and revokes the current session idempotently and clears the security context for the request.

- [ ] **Step 7: Run selected security and regression tests**

Run:

```bash
cd backend
./mvnw -q -Dtest='CustomerSessionServiceIntegrationTest,CustomerAuthenticationIntegrationTest,AdminAuthenticationIntegrationTest,PartnerAuthenticationIntegrationTest' test
```

Expected: Customer routes require a valid bound session while Admin and Partner behavior is unchanged.

- [ ] **Step 8: Local checkpoint and reviewer gate**

Record the session contract and filter order. Search captured persistence/log fixtures for the raw token and review absent-versus-invalid semantics.

---

### Task 6: Implement email OTP, distributed limits, and transactional mail outbox

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/EmailOtpChallenge.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/EmailOtpChallengeId.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/domain/CustomerMailJob.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/in/RequestEmailOtpUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/in/VerifyEmailOtpUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/EmailOtpChallengeStore.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/DistributedCustomerRateLimitPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/CustomerMailOutboxPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/CustomerMailDeliveryPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/service/EmailOtpService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/service/CustomerMailDeliveryService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/persistence/EmailOtpPersistenceAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/persistence/PostgresCustomerRateLimitAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/persistence/CustomerMailOutboxPersistenceAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/mail/SmtpCustomerMailAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/mail/CustomerMailWorker.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/in/web/PublicCustomerAuthenticationController.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application.yml`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/application/service/EmailOtpServiceIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/adapter/out/persistence/CustomerMailOutboxIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/adapter/out/mail/SmtpCustomerMailAdapterTest.java`

**Interfaces:**
- Request always returns the same `202` shape after a syntactically valid, allowed-Site request; rate limit uses safe `429` without Customer-existence hints.
- Challenge creation and encrypted outbox insertion commit atomically. SMTP executes only after a worker claim commits.
- Raw OTP, normalized email, recipient, and rendered body exist in memory only where strictly needed; persistence uses keyed digests and versioned AES-GCM ciphertext.

- [ ] **Step 1: Write failing OTP lifecycle, enumeration, and rate-limit tests**

Cover TTL, five attempts, constant-time matching, consumed/invalidated states, resend cooldown, old-challenge invalidation after a successful resend, uniform response for new/existing Customer, equal email on two Sites, Site/email/IP windows, trusted-proxy behavior, and concurrent correct verification.

```java
@Test
void concurrentCorrectVerificationCreatesOneIdentityAndOneSession() {
    EmailOtpChallengeId id = requestOtp();
    var outcomes = concurrently(2, () -> verify(id, capturedCode()));

    assertThat(outcomes.stream().filter(VerificationResult::succeeded)).hasSize(1);
    assertThat(sessionCountForChallenge(id)).isOne();
    assertThat(identityCountForEmail()).isOne();
}

@Test
void responsesDoNotRevealWhetherEmailAlreadyExists() {
    assertThat(request(existingEmail()).safeBody()).isEqualTo(request(newEmail()).safeBody());
}
```

- [ ] **Step 2: Write failing mail claim, lease, retry, and payload-destruction tests**

Cover two workers claiming disjoint jobs with `SKIP LOCKED`, abandoned lease recovery, transient exponential retry, permanent terminal state, eight-attempt cap, ciphertext-only storage, and encrypted payload nulling after success.

- [ ] **Step 3: Add Spring Mail and run focused tests red**

Add `spring-boot-starter-mail` without a manual JavaMail version. Run:

```bash
cd backend
./mvnw -q -Dtest='EmailOtpServiceIntegrationTest,CustomerMailOutboxIntegrationTest,SmtpCustomerMailAdapterTest' test
```

Expected: missing domain/services/adapters or assertions fail.

- [ ] **Step 4: Implement request transaction and distributed limits**

Normalize in memory, derive email/IP digests, consume fixed-window PostgreSQL buckets atomically, enforce resend cooldown, create challenge with keyed OTP digest, encrypt recipient plus fully rendered message, and insert outbox in one transaction. Use a cryptographically unbiased six-digit generator.

- [ ] **Step 5: Implement verification under row lock**

Lock challenge; reject invalidated/consumed/expired/exhausted uniformly; increment failed attempt count atomically; on success resolve/create email identity, consume challenge, and issue either session or one-time link proof according to stored intent. Persist no raw code.

- [ ] **Step 6: Implement mail claim/delivery/finalization phases**

Claim a bounded batch and commit lease ownership, decrypt/send outside transaction, then finalize success or classified retry in a new transaction. Zero mutable decrypted buffers where practical and never interpolate recipient or OTP into logs/exceptions.

- [ ] **Step 7: Implement fail-closed readiness**

In production, an active EMAIL_OTP configuration with missing SMTP host/TLS/sender/credential capability makes the OTP capability unavailable and readiness unhealthy. Local profile points to Mailpit explicitly; there is no silent fake sender.

- [ ] **Step 8: Run OTP/mail integration and log-safety tests**

Run:

```bash
cd backend
./mvnw -q -Dtest='EmailOtpServiceIntegrationTest,CustomerMailOutboxIntegrationTest,SmtpCustomerMailAdapterTest,*MailLogSafetyTest,*CustomerRateLimit*Test' test
```

Expected: all selected tests pass and fixture email/code/message strings are absent from logs and stored plaintext columns.

- [ ] **Step 9: Local checkpoint and reviewer gate**

Record default limits, SMTP failure classification, and test count. Review transaction boundaries and enumeration resistance before adding frontend flows.

---

### Task 7: Implement one-time identity linking

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/in/LinkCustomerIdentityUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/in/LinkPartnerCustomerIdentityUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/service/CustomerIdentityLinkService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/service/PartnerCustomerIdentityLinkService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/in/web/PartnerCustomerController.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/in/web/CustomerController.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/partners/domain/PartnerScope.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/application/service/CustomerIdentityLinkServiceIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/adapter/in/web/PartnerCustomerApiIntegrationTest.java`

**Interfaces:**
- Browser linking consumes a five-minute opaque proof bound to Site, Origin, issuer key, subject digest, authentication mode, and verified-at time.
- Partner linking accepts trusted canonical issuer/subject claims only with `CUSTOMERS_MANAGE` and `Idempotency-Key`; it converts subject to a digest immediately.
- Same Customer + same identity is idempotent success; a different owner is `IDENTITY_LINK_CONFLICT`; no merge exists.

- [ ] **Step 1: Write failing proof and linking tests**

Cover expired/used/cross-Site/cross-Origin proof, inactive target Customer, unowned identity, same-owner replay, other-owner conflict, email match without merge, Partner scope/idempotency, and concurrent proof consumption.

- [ ] **Step 2: Run focused tests red**

Run:

```bash
cd backend
./mvnw -q -Dtest='CustomerIdentityLinkServiceIntegrationTest,PartnerCustomerApiIntegrationTest' test
```

Expected: link operations are unavailable.

- [ ] **Step 3: Persist opaque one-time link proofs**

Return a raw 32-byte proof token once; store only keyed digest, verified identity fields as digests/safe enums, Site, Origin, expiry, and consumption timestamp. Do not store the source token or raw subject.

- [ ] **Step 4: Implement browser linking transaction**

Lock ACTIVE Customer and proof; verify session Site/Origin/scope, proof freshness and ownership context; lock identity key; attach if absent, succeed if already owned by the target, conflict otherwise; consume proof and write safe audit in the same transaction.

```java
return identityStore.findForUpdate(key)
    .map(existing -> existing.customerId().equals(target.id())
        ? LinkIdentityResult.alreadyLinked(existing.id())
        : LinkIdentityResult.conflict())
    .orElseGet(() -> LinkIdentityResult.linked(identityStore.create(target, proof)));
```

- [ ] **Step 5: Implement Partner trusted linking and Customer scopes**

Add `CUSTOMERS_READ("customers:read")` and `CUSTOMERS_MANAGE("customers:manage")` to `PartnerScope`. Resolve Site from the API-key principal, validate target ownership/status and bounded issuer/subject contract, include Customer/mode/issuer/subject digest in the idempotency fingerprint, and audit only safe IDs/outcome.

- [ ] **Step 6: Expose Customer and Partner endpoints**

`POST /api/v1/customer/identities` accepts only `proof_token`. Partner endpoint accepts `mode`, `issuer_key`, and `subject`; mark subject `writeOnly`, cap request size, and ensure request logging is disabled.

- [ ] **Step 7: Run linking, scope, audit, and log-safety tests**

Run:

```bash
cd backend
./mvnw -q -Dtest='CustomerIdentityLinkServiceIntegrationTest,PartnerCustomerApiIntegrationTest,*CustomerAudit*Test,*AuthenticationLogSafetyTest' test
```

Expected: all selected tests pass, conflicts never merge Customers, and raw proof/subject values are absent from logs/audit.

- [ ] **Step 8: Local checkpoint and reviewer gate**

Record conflict/idempotency cases and test count. Review both browser and Partner entry paths for equivalent ownership rules.

---

### Task 8: Implement safe Customer queries and irreversible anonymization

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/in/AnonymizeCustomerUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/in/QueryAdminCustomersUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/in/GetPartnerCustomerUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/CustomerDataRedactionPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/service/CustomerAnonymizationService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/service/CustomerQueryService.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/persistence/NoOpCustomerDataRedactionAdapter.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/in/web/CustomerAdminController.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/in/web/PartnerCustomerController.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/admin/domain/AuditAction.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/application/service/CustomerAnonymizationServiceIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/adapter/in/web/CustomerAdminApiIntegrationTest.java`

**Interfaces:**
- `CustomerDataRedactionPort.redactCustomerData(SiteId, CustomerId, Instant)` is a transaction-participating extension point. Stage 3A supplies a no-op adapter; Stage 3B replaces it with donor-message redaction.
- Operation preserves Customer row and Payment `customer_id`, but removes Customer PII and identity/session/OTP capability.
- Admin and Partner requests use the existing idempotency pattern and return the same safe result on replay.

- [ ] **Step 1: Write failing anonymization tests**

Cover PII/digest removal, identity deletion, all-session revocation, active OTP invalidation, link-proof invalidation, retained Payment/customer UUID, repeated call, concurrent session use, extension-port invocation, cross-Site rejection, confirmation mismatch, and safe audit.

```java
@Test
void anonymizationRemovesAccessDataButPreservesFinancialOwnership() {
    anonymize(CUSTOMER_ID);

    assertThat(loadCustomer().status()).isEqualTo(CustomerStatus.ANONYMIZED);
    assertThat(identityCount(CUSTOMER_ID)).isZero();
    assertThat(activeSessionCount(CUSTOMER_ID)).isZero();
    assertThat(loadPayment(PAYMENT_ID).customerId()).contains(CUSTOMER_ID);
}
```

- [ ] **Step 2: Run focused tests red**

Run:

```bash
cd backend
./mvnw -q -Dtest='CustomerAnonymizationServiceIntegrationTest,CustomerAdminApiIntegrationTest' test
```

Expected: anonymization use case/endpoints are absent.

- [ ] **Step 3: Implement one locked anonymization transaction**

Lock same-Site Customer; if already ANONYMIZED return the prior safe result. Otherwise change status, null email ciphertext/digest, delete identities, revoke sessions, invalidate active challenges/link proofs, call `CustomerDataRedactionPort`, and append safe audit before commit.

- [ ] **Step 4: Make concurrent authentication fail closed**

Identity/session issuance rechecks ACTIVE Customer under the same lock/constraint path before commit. Add a concurrency test where anonymization wins before issuance and prove no active session or identity survives.

- [ ] **Step 5: Expose safe Admin/Partner queries and anonymization contracts**

Implement Admin list/details with Site/status/Customer-ID/masked-email filters and identity mode/issuer/timestamps only; implement Partner same-Site safe view under `CUSTOMERS_READ`. Admin anonymization body must contain exact Customer UUID confirmation; disable duplicate submit at API idempotency level as well as later in UI. Partner anonymization requires same-Site `CUSTOMERS_MANAGE`. Unknown, anonymized, and cross-Site public shapes do not reveal ownership details.

- [ ] **Step 6: Run anonymization and payment-regression tests**

Run:

```bash
cd backend
./mvnw -q -Dtest='CustomerAnonymizationServiceIntegrationTest,CustomerAdminApiIntegrationTest,PartnerCustomerApiIntegrationTest,PaymentAdminApiIntegrationTest' test
```

Expected: all selected tests pass; existing Payment facts and Admin payment views still load.

- [ ] **Step 7: Local checkpoint and reviewer gate**

Record the deletion/revocation matrix and test count. Review rollback atomicity and verify Stage 3B can replace the no-op redaction adapter without changing Customer service code.

---

### Task 9: Link Customers to catalog/dynamic Payments and expose owned history

**Files:**
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/domain/Payment.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/CreateCatalogCheckoutCommand.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/CreateDynamicPaymentCommand.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/in/ListCustomerPaymentsUseCase.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/model/CustomerPaymentHistory.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/CustomerPaymentHistoryCursorPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/CustomerPaymentHistoryService.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/CatalogCheckoutTransaction.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/application/service/DynamicPaymentTransaction.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/in/web/PublicCheckoutController.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/in/web/PartnerPaymentController.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/application/port/out/LoadPaymentPort.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/PaymentPersistenceAdapter.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/PaymentJpaEntity.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/persistence/PaymentPersistenceMapper.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/payments/adapter/out/security/HmacCustomerPaymentHistoryCursorAdapter.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/in/web/CustomerController.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/shared/application/PaymentApplicationException.java`
- Modify: `backend/src/main/java/dev/genericpayments/platform/shared/adapter/in/web/ApiExceptionHandler.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/application/service/CustomerPaymentLinkageIntegrationTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/application/service/CustomerPaymentHistoryIntegrationTest.java`
- Modify: `backend/src/test/java/dev/genericpayments/platform/payments/application/service/CatalogCheckoutServiceTest.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/payments/adapter/in/web/PartnerCustomerPaymentIntegrationTest.java`

**Interfaces:**
- Public controller resolves optional SDK authorization before constructing `CreateCatalogCheckoutCommand`; the application command contains only server-verified `UUID customerId` or empty.
- Partner controller validates optional `customer_id` through a Customer application port before constructing `CreateDynamicPaymentCommand`.
- History use case accepts verified Site/Customer IDs from `CustomerSessionContext`, never from query parameters, and returns safe immutable projections.

- [ ] **Step 1: Write failing domain and catalog-linkage tests**

Cover catalog factory with/without Customer, valid same-Site/same-Origin session, absent session as guest, malformed/expired/revoked/anonymized/cross-Site/cross-Origin session failures, and idempotency conflict when the same key changes guest/Customer context.

```java
@Test
void invalidSuppliedSessionNeverFallsBackToGuest() {
    assertThatThrownBy(() -> publicApi.createCatalogCheckout("Bearer expired", request()))
        .isInstanceOf(AuthenticationFailedException.class);
    assertThat(paymentCount()).isZero();
}

@Test
void customerContextParticipatesInCatalogFingerprint() {
    createCatalog(IDEMPOTENCY_KEY, Optional.of(CUSTOMER_ID));
    assertThatThrownBy(() -> createCatalog(IDEMPOTENCY_KEY, Optional.empty()))
        .isInstanceOf(IdempotencyConflictException.class);
}
```

- [ ] **Step 2: Write failing Partner dynamic and history tests**

Cover active same-Site Customer, absent customer as guest, unknown/anonymized/cross-Site rejection, response `customer_id`, ownership-aware idempotency, history isolation, status/source fields, stable keyset ordering with equal timestamps, maximum page size, tampered cursor, and cursor reuse by another Customer/Site/query.

- [ ] **Step 3: Run the focused tests red**

Run:

```bash
cd backend
./mvnw -q -Dtest='CustomerPaymentLinkageIntegrationTest,CustomerPaymentHistoryIntegrationTest,PartnerCustomerPaymentIntegrationTest,CatalogCheckoutServiceTest' test
```

Expected: Customer context is not yet carried or history endpoints are absent.

- [ ] **Step 4: Extend Payment creation without changing guest behavior**

Keep the existing nullable UUID representation to avoid a `payments.domain` dependency on the Customer module. Add optional Customer parameters to factories and command records:

```java
public static Payment catalog(PaymentId id, SiteId siteId, UUID customerId,
                              PaymentAccountId accountId, PriceSnapshot snapshot,
                              Map<String, String> metadata, Instant now) { /* invariants */ }

public record CreateCatalogCheckoutCommand(
    String siteKey, String priceKey, URI origin, UUID customerId,
    String idempotencyKey, String requestId) {}
```

Represent ownership in canonical fingerprints as `customer:<uuid>` or the literal `guest`; never include the SDK token.

- [ ] **Step 5: Resolve optional public authorization before checkout creation**

Read zero or one Authorization header. Zero yields guest; duplicate/malformed header fails. Resolve Site from `siteKey`, validate exact Origin and session scope `CHECKOUT_CREATE`, then pass only the verified Customer UUID. Keep checkout-token authorization for confirm/read unchanged.

- [ ] **Step 6: Validate Partner `customer_id` and extend responses**

Add optional UUID to the request DTO. Resolve the Customer under the API-key Site and require ACTIVE status. Add nullable `customerId` to create/read response DTOs without renaming existing fields or operation IDs.

- [ ] **Step 7: Implement opaque keyset history**

Query by exact `(site_id,customer_id)` and keyset `(created_at,id)` using `created_at DESC,id DESC`. Encode version, Site, Customer, last timestamp/ID, and query shape; authenticate the serialized bytes with the cursor key and reject mismatch uniformly. Return only payment ID, source, amount, currency, status, created/updated timestamps, and safe catalog context.

- [ ] **Step 8: Expose Customer payment and empty donation history**

`GET /api/v1/customer/payments` derives ownership entirely from the principal. `GET /api/v1/customer/donations` returns `{items:[],next_cursor:null}` in 3A using the same limit validation; Task 6 of the Stage 3B plan replaces the empty implementation.

- [ ] **Step 9: Run focused, migration, and Stage 2 checkout regressions**

Run:

```bash
cd backend
./mvnw -q -Dtest='CustomerPaymentLinkageIntegrationTest,CustomerPaymentHistoryIntegrationTest,PartnerCustomerPaymentIntegrationTest,CatalogCheckoutServiceTest,PaymentAdminApiIntegrationTest,PaymentPersistence*Test' test
```

Expected: all selected tests pass, existing guest checkout response remains compatible, and Partner responses add only nullable `customer_id`.

- [ ] **Step 10: Local checkpoint and reviewer gate**

Record the fingerprint fields and history projection. Independently review every absent-versus-invalid branch and every Site/Customer predicate.

---

### Task 10: Build Site authentication and Customer Admin UI

**Files:**
- Create: `frontend/admin/src/app/sites/[siteId]/authentication/page.tsx`
- Create: `frontend/admin/src/app/customers/page.tsx`
- Create: `frontend/admin/src/app/customers/[customerId]/page.tsx`
- Create: `frontend/admin/src/components/customers/authentication/AuthenticationSettings.tsx`
- Create: `frontend/admin/src/components/customers/authentication/IdentityProviderForm.tsx`
- Create: `frontend/admin/src/components/customers/authentication/ProviderReadiness.tsx`
- Create: `frontend/admin/src/components/customers/CustomerList.tsx`
- Create: `frontend/admin/src/components/customers/CustomerDetails.tsx`
- Create: `frontend/admin/src/components/customers/AnonymizeCustomerDialog.tsx`
- Create: `frontend/admin/src/components/customers/AuthenticationSettings.test.tsx`
- Create: `frontend/admin/src/components/customers/CustomerList.test.tsx`
- Create: `frontend/admin/src/components/customers/CustomerDetails.test.tsx`
- Create: `frontend/admin/src/components/customers/AnonymizeCustomerDialog.test.tsx`
- Modify: `frontend/admin/src/components/AppShell.tsx`
- Regenerate: `frontend/admin/src/lib/api/schema.d.ts`

**Interfaces:**
- UI consumes generated OpenAPI contracts and existing same-origin Admin forwarding; it does not duplicate backend validation or handle raw private keys/tokens/SMTP credentials.
- Customer list supports Site, status, Customer ID, and masked-email lookup. Details show only safe identity mode/issuer/timestamps and financial-history links.
- Anonymization dialog is destructive, irreversible, confirmation-gated, keyboard accessible, and restores focus.

- [ ] **Step 1: Write failing authentication-settings and Customer component tests**

Cover list/readiness, public-key metadata only, create/update validation, optimistic conflict, enable/disable pending state, safe failure text, focus, and no secret-value rendering.

Also cover Site/status/ID/masked-email filters, no raw identity subject/email, empty states, retained financial-facts warning, exact UUID confirmation, double-submit prevention, API error announcement, cancel, and focus restoration.

- [ ] **Step 2: Run the new Admin tests red**

Run:

```bash
cd frontend
pnpm --filter admin test --run src/components/customers
```

Expected: tests fail because the Customer/authentication components and routes do not exist.

- [ ] **Step 3: Generate OpenAPI without manual schema edits**

Start the test-configured backend only for schema generation, use `pnpm --filter admin generate:api`, and regenerate `schema.d.ts`. Stop the owned backend process immediately afterward. Verify operation IDs from this plan are present exactly once.

- [ ] **Step 4: Implement Site authentication settings**

Use separate forms for OIDC_TOKEN, SITE_TOKEN, and EMAIL_OTP. Accept static public JWK JSON or remote HTTPS JWKS URL, audiences, algorithms, and claim mapping; never offer symmetric production algorithms. Show last successful refresh and safe readiness state.

- [ ] **Step 5: Implement Customer list and details**

Reuse existing Admin API client conventions. Escape all returned strings through React rendering, keep filters in bounded query parameters, and do not add any public/Customer central-account route.

- [ ] **Step 6: Implement anonymization confirmation UX**

Require typed Customer UUID, disable controls while pending, generate one idempotency key per user intent, retain it for safe retry, and show the irreversible PII/message deletion plus financial-retention warning.

- [ ] **Step 7: Add AppShell navigation and run Admin tests**

Run:

```bash
cd frontend
pnpm --filter admin typecheck
pnpm --filter admin test --run
pnpm --filter admin build
```

Expected: typecheck, full Admin Vitest suite, and production build pass.

- [ ] **Step 8: Local checkpoint and reviewer gate**

Record test count/build result. Review the rendered pages for accidental secret fields and the dialog for keyboard/focus behavior.

---

### Task 11: Extend the browser SDK and add the email-OTP Web Component

**Files:**
- Create: `frontend/sdk/src/customer/contracts.ts`
- Create: `frontend/sdk/src/customer/session-storage.ts`
- Create: `frontend/sdk/src/customer/session-storage.test.ts`
- Create: `frontend/sdk/src/customer/customer-client.test.ts`
- Create: `frontend/sdk/src/customer/generic-email-otp.ts`
- Create: `frontend/sdk/src/customer/generic-email-otp.test.ts`
- Create: `frontend/sdk/src/customer/styles.ts`
- Modify: `frontend/sdk/src/contracts.ts`
- Modify: `frontend/sdk/src/client.ts`
- Modify: `frontend/sdk/src/storage.ts`
- Modify: `frontend/sdk/src/index.ts`
- Modify: `frontend/sdk/README.md`

**Interfaces:**
- Add `exchangeSiteToken`, `beginOidcAuthentication`, `exchangeOidcToken`, `requestEmailOtp`, `verifyEmailOtp`, `getCurrentCustomer`, `listCustomerPayments`, `listCustomerDonations`, `linkIdentity`, and `logoutCustomerSession`.
- Store Customer sessions under API-base + Site + exact browser Origin; never store source token, OTP, nonce, or link proof after use.
- Automatically attach a valid Customer session only to catalog creation for the same API base/Site/Origin; checkout confirmation/read continue to use their own checkout token.

- [ ] **Step 1: Write failing Customer session-storage tests**

Cover `sessionStorage` only, namespaced versioned key, strict parsing/size cap, API/Site/Origin separation, expiry deletion, logout deletion, unavailable storage, and explicit proof that `localStorage` is untouched.

```typescript
expect(localStorage.length).toBe(0);
expect(sessionStorage.getItem(customerStorageKey(identity))).toContain('sessionToken');
expect(recoverCustomerSession({ ...identity, siteKey: 'other' })).toBeNull();
```

- [ ] **Step 2: Write failing headless client contract tests**

Mock fetch and prove exact paths/methods, abort propagation, no redirects/cookies, source token used only in exchange request, response size/shape validation, session attachment to matching catalog creation, no attachment to another Site/API, expired-session failure without guest retry, history cursor handling, and clear-on-logout/session-expired/anonymized.

- [ ] **Step 3: Run storage and headless client tests red**

Run:

```bash
cd frontend
pnpm --filter @generic-payments/sdk test --run src/customer/session-storage.test.ts src/customer/customer-client.test.ts
```

Expected: tests fail because Customer SDK behavior is absent.

- [ ] **Step 4: Refactor request authorization into explicit capability types**

Replace the helper's ambiguous checkout-session parameter with an internal discriminated authorization type:

```typescript
type RequestAuthorization =
  | { kind: "checkout"; token: string }
  | { kind: "customer"; token: string }
  | { kind: "none" };
```

The SDK must never substitute a Customer token where a checkout token is required.

- [ ] **Step 5: Implement strict Customer contracts and methods**

Reject additional/missing fields, invalid UUID/timestamps/status/scopes, oversized bodies, and mismatched Customer/Site metadata. Keep source identity credentials in local variables only and do not include them in thrown errors.

- [ ] **Step 6: Implement automatic catalog-session attachment**

Resolve session before the create request. If a matching stored record exists but is expired/corrupt, clear it and throw `SESSION_EXPIRED`/`INVALID_SESSION`; do not retry as guest. Absence remains guest. Expose an explicit `customerSession: "omit"` option for a deliberate guest checkout without deleting the stored session.

- [ ] **Step 7: Write failing email-OTP component tests**

Cover accessible labels, email submit, uniform challenge state, six-digit input, resend cooldown, rate-limit/error announcements, success event without token detail, keyboard flow, abort on disconnect, locale strings, design tokens, and no OTP persistence.

- [ ] **Step 8: Run the email-OTP component test red**

Run:

```bash
cd frontend
pnpm --filter @generic-payments/sdk test --run src/customer/generic-email-otp.test.ts
```

Expected: the test fails because the Web Component is absent.

- [ ] **Step 9: Implement `<generic-email-otp>`**

Use isolated styles, translated strings, `aria-live`, visible focus, disabled pending controls, and composed custom events containing only safe Customer/session-state metadata. Allow complete UI replacement through the headless methods.

- [ ] **Step 10: Run SDK tests, typecheck, build, and forbidden-storage scan**

Run:

```bash
cd frontend
pnpm --filter @generic-payments/sdk test --run
pnpm --filter @generic-payments/sdk typecheck
pnpm --filter @generic-payments/sdk build
rg -n 'localStorage|document\.cookie|location\.(hash|search)' sdk/src
```

Expected: tests/typecheck/build pass; any scan match is an explicit negative test or comment, not production storage/transport behavior.

- [ ] **Step 11: Local checkpoint and reviewer gate**

Record test count and public exports. Review every request to prove Customer capability never crosses API/Site/Origin boundaries.

---

### Task 12: Add observability, Cucumber, Mailpit demo, Playwright, and Compose smoke

**Files:**
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/application/port/out/CustomerTelemetryPort.java`
- Create: `backend/src/main/java/dev/genericpayments/platform/customers/adapter/out/observability/MicrometerCustomerTelemetryAdapter.java`
- Create: `backend/src/test/java/dev/genericpayments/platform/customers/adapter/out/observability/CustomerObservabilityIntegrationTest.java`
- Create: `backend/src/test/resources/features/customer_access.feature`
- Create: `backend/src/test/java/dev/genericpayments/platform/acceptance/CustomerAccessSteps.java`
- Modify: `backend/src/test/java/dev/genericpayments/platform/acceptance/AcceptanceWorld.java`
- Modify: `backend/src/test/java/dev/genericpayments/platform/acceptance/RunCucumberTest.java`
- Modify: `examples/demo-site/index.html`
- Modify: `examples/demo-site/src/main.ts`
- Modify: `examples/demo-site/src/styles.css`
- Create: `examples/demo-site/tests/customer-access.spec.ts`
- Modify: `infrastructure/compose.yaml`
- Modify: `infrastructure/.env.example`
- Create: `infrastructure/smoke-stage-3a.mjs`
- Create: `infrastructure/smoke-stage-3a.test.mjs`
- Modify: `docs/local-development.md`

**Interfaces:**
- Metrics labels are restricted to authentication mode, safe outcome, mail outcome, and session operation; never label email, Customer/Campaign ID, issuer URL, IP, subject, token ID, or Origin.
- Cucumber scenarios use real Spring HTTP boundaries and PostgreSQL. Email scenarios retrieve the code through Mailpit's local test API, never through a production backdoor.
- Compose adds Mailpit on non-conflicting local ports and preserves the existing PostgreSQL volume unless an explicitly destructive reset is requested.

- [ ] **Step 1: Write failing observability tests**

Assert auth exchange latency/counters, OTP requested/verified/rejected/rate-limited, mail pending/delivery, session issue/revoke/expire, link outcomes, and anonymization outcomes. Enumerate allowed tag keys and reject high-cardinality tags.

- [ ] **Step 2: Run observability tests red**

Run:

```bash
cd backend
./mvnw -q -Dtest=CustomerObservabilityIntegrationTest test
```

Expected: the test fails because the Customer telemetry adapter and safe audit coverage are absent.

- [ ] **Step 3: Implement bounded telemetry and safe audit coverage**

Add no-op/default and Micrometer adapters. Metrics receive safe enums only. Add an integration scan that serializes audit rows and captured log lines and fails if fixture email, subject, OTP, session, source token, or raw IP appears.

- [ ] **Step 4: Add Stage 3A Cucumber scenarios**

Implement scenarios for SITE_TOKEN exchange, OIDC attempt/exchange, email OTP through Mailpit, identical email on two Sites, cross-Site and cross-Origin session rejection, explicit linking, linking conflict without merge, authenticated/guest/invalid-session catalog checkout, isolated paginated history, and anonymization retaining Payment facts.

- [ ] **Step 5: Extend the demo without imitating Google/Apple UI**

Add email OTP sign-in, safe current-Customer summary, authenticated catalog checkout, payment-history list, and logout. Keep OAuth buttons/redirects absent; document that the host Site owns those screens.

- [ ] **Step 6: Add Playwright journeys**

Test Mailpit-driven OTP login, authenticated catalog payment appearing in history, cross-Site session rejection, same-tab session restore, logout/expiry removal, Admin provider settings, Customer anonymization confirmation/focus, and existing guest checkout regression.

- [ ] **Step 7: Add Mailpit and deterministic local issuer fixture to Compose**

Configure Mailpit SMTP/API explicitly. Run the signed issuer fixture inside the backend local profile using checked-in test public/private fixture material that is rejected by production configuration. Add healthchecks and make backend readiness reflect mail capability.

- [ ] **Step 8: Unit-test and run the Stage 3A smoke script**

The smoke journey creates two Sites, configures auth, requests/reads Mailpit OTP, authenticates, creates an owned catalog Payment, reads history, logs out, verifies rejection, anonymizes, and confirms Payment facts remain. Script cleanup stops only containers started by this Compose project and does not delete volumes.

Run:

```bash
node --test infrastructure/smoke-stage-3a.test.mjs
docker compose -f infrastructure/compose.yaml up -d --build
node infrastructure/smoke-stage-3a.mjs
docker compose -f infrastructure/compose.yaml stop backend admin demo callback-receiver mailpit postgres
```

Expected: unit test and smoke journey pass; owned services are stopped; the PostgreSQL volume remains.

- [ ] **Step 9: Run Cucumber and browser suites**

Run:

```bash
cd backend
./mvnw -q -Dtest=RunCucumberTest test
cd ../examples/demo-site
pnpm build
pnpm test:e2e
```

Expected: all Stage 1–3A scenarios and Playwright journeys pass.

- [ ] **Step 10: Local checkpoint and reviewer gate**

Record scenario/test counts, smoke output, owned container names stopped, and retained volume name. Review audit/metrics/log output for forbidden values.

---

### Task 13: Run the Stage 3A release gate and independent review

**Files:**
- Modify: `docs/local-development.md`
- Modify: `.superpowers/sdd/2026-10-10-customer-access/progress.md`
- Create: `.superpowers/sdd/2026-10-10-customer-access/review.md`

**Interfaces:**
- This task changes production code only if verification or review finds a defect; every defect fix repeats red-green-refactor and the affected focused suite.
- Stage 3B may begin only after the complete matrix passes and review has no open Critical or Important finding.

- [ ] **Step 1: Verify no incomplete markers, secret fixtures, or generated artifacts are inconsistent**

Run scoped scans:

```bash
rg -n 'TO''DO|TB''D|FIX''ME|implement la''ter' backend/src frontend/admin/src frontend/sdk/src examples/demo-site/src infrastructure docs/local-development.md
rg -n 'BEGIN (RSA |EC )?PRIVATE KEY|smtp.*password|customer.*token' backend/src/main/resources frontend/admin/src frontend/sdk/src
```

Expected: no incomplete implementation marker; any private-key match is confined to explicit local/test fixture resources and cannot load under production profile; no real credential is present.

- [ ] **Step 2: Run the complete backend regression suite**

Run:

```bash
cd backend
./mvnw clean verify
```

Expected: all unit, integration, architecture, and Cucumber tests pass. Record total tests/suites and compare against the Stage 2 baseline of 727 backend tests across 72 suites and 16 Cucumber scenarios; investigate any unexplained decrease.

- [ ] **Step 3: Run complete frontend and infrastructure tests**

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
node --test infrastructure/callback-receiver.test.mjs infrastructure/smoke-stage-2.test.mjs infrastructure/smoke-stage-3a.test.mjs
```

Expected: all commands pass. Compare against Stage 2 baselines of 112 Admin tests, 134 SDK tests, 9 callback-receiver tests, and 6 Playwright tests; investigate any unexplained decrease.

- [ ] **Step 4: Re-run the Compose smoke from a stopped state**

Run the Task 12 smoke sequence. Confirm readiness, Mailpit delivery, exact-Origin/session isolation, owned catalog history, logout, anonymization, and retained financial facts. Stop only owned services afterward and preserve the database volume.

- [ ] **Step 5: Perform independent security and architecture review**

Review all Stage 3A changes against the Review Focus and spec. Classify findings as Critical, Important, or Minor with exact file/line evidence. Fix Critical/Important findings and repeat affected suites; record accepted Minor items with rationale.

- [ ] **Step 6: Verify Stage 3A coverage matrix**

Mark every row below with test/report evidence in `review.md`:

| Requirement | Evidence required |
|---|---|
| Customer/identity Site isolation | domain + PostgreSQL + HTTP tests |
| SITE_TOKEN/OIDC validation and replay | JOSE + integration + Cucumber tests |
| SDK session Origin/scope/expiry/revocation | service + filter + SDK + browser tests |
| Email OTP and mail outbox | concurrency + rate-limit + lease + Mailpit tests |
| Explicit linking/no merge | Customer + Partner + Cucumber tests |
| Irreversible anonymization | integration + Admin/browser + Payment retention tests |
| Catalog/dynamic optional Customer linkage | public + Partner + idempotency tests |
| Customer-owned history | keyset/cursor/isolation + browser tests |
| PII/credential safety | persistence + logs + audit + metrics + storage scans |
| Stage 1–2 compatibility | complete backend/frontend/callback/Playwright regression |

- [ ] **Step 7: Close the gate**

Record final commands, counts, review resolution, stopped runtime resources, retained volume, and the explicit statement `STAGE_3A_GATE=PASSED` in the progress file. If any command or Critical/Important review item remains open, record `STAGE_3A_GATE=BLOCKED` and do not start the Donations plan.

---

## Plan Self-Review Checklist

- [ ] Every Stage 3A requirement in the approved spec maps to a task and a gate row.
- [ ] Public, Customer, Partner, and Admin paths and OpenAPI operation IDs are fixed and non-conflicting.
- [ ] Domain/application types remain provider/framework-neutral; adapter-specific types do not leak inward.
- [ ] All source credentials, SDK sessions, OTPs, PII, and lookup values have explicit storage/logging rules.
- [ ] Concurrency behavior is specified for first login, OTP verification, replay consumption, linking, logout, and anonymization.
- [ ] V5 preserves guest Payments and fails before adding a foreign key when legacy non-null orphan values exist.
- [ ] Catalog/dynamic guest compatibility and invalid-credential fail-closed behavior are both tested.
- [ ] Every implementation task starts red and ends with focused verification; the release-gate task requires a failing regression test before any defect fix.
- [ ] Full regression, Compose cleanup, and independent review are required before Stage 3B.
- [ ] No Git command or Git-dependent workflow is required.
