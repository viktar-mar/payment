# Local development through Stage 4

## Stage 4 subscription rollout and acceptance

Stage 4 is local Fake-provider functionality, not Stripe readiness. Enable subscriptions for the Site, publish a subscription Price, define explicit same-Product/currency transitions, then authenticate Customers again. Existing persisted sessions and Partner keys retain their old scopes; use new Customer sessions and new least-privilege `subscriptions:read` (including access checks) and/or `subscriptions:manage` Partner keys. Deploy the strict current SDK with its matching backend; do not infer authority from browser events or access hints. The SDK guide shows the Customer manager and trusted Partner access integration.

Published prices, billing snapshots and the grace deadline attached to a bill are immutable. Grace defaults to three 24-hour UTC days; zero disables it. Optional trial without a method pauses without debt; later setup alone does not resume it. Required setup must complete before trial access. Setup, recovery of existing debt, and explicit resume are different commands. Upgrade preview is not approval or payment. Refund and cancellation are independent. Unknown/timeout results retain the original operation for read-only refresh/reconciliation; never blindly repeat a financial POST. Disabling the Site's subscription feature is forbidden while non-terminal subscriptions or unresolved create/cancel exist.

Callbacks preserve v1 catalog cash and v2 donation cash, add v3 subscription-linked positive cash/refund context and v4 lifecycle/access invalidations. Verify exact bytes, signature timestamp/version and envelope shape; deduplicate event IDs, reject foreign identity and regressive revisions. Only a fresh Partner access response grants cached authority. Expire at the exact `valid_until`, at an earlier entitlement boundary, and at most five minutes; a late callback never extends authority.

For deterministic billing acceptance use the explicitly opted-in launcher under `backend/src/test/java/dev/genericpayments/acceptance/runtime`, outside the production artifact. Its business clock is distinct from real UTC session/OTP/JWT/signature clocks. The separate owned control channel requires an ephemeral ENV capability. Never expose or deploy it. Ordinary local Admin Fake ADVANCE alone changes provider time, not application time. Fake scenarios and simulated Portal prove local flows only.

The launcher requires `PAYMENT_STAGE4_TEST_RUNTIME=true`, a fresh 43-character base64url `PAYMENT_STAGE4_CONTROL_KEY`, an owned control port, and `PAYMENT_STAGE4_TIME_FILE` in an explicit dedicated `task17-time-*` directory. That file contains only the business instant: durable atomic writes precede clock advancement, and restart loads it before workers start. Keep its directory/volume with the owned database; malformed or unreadable state must fail closed. Mount only the launcher and its two compiled nested classes in an isolated package tree, never the whole test output (unrelated test configurations must not enter component scanning). Application encryption/fingerprint/customer digest/history keys use standard Base64, independently generated; the control capability uses Base64URL. The test-only receiver consumes `PAYMENT_STAGE4_BACKEND_URL` and `PAYMENT_STAGE4_CONTROL_URL`; it never advances business time or relaxes real signature freshness.

Stage4 smoke additionally requires loopback backend/receiver/Mailpit/database URLs, the control URL/key, Admin credentials, two fresh telemetry-allowlisted Site keys, `PAYMENT_E2E_COMPOSE_PROJECT=task17-…`, and exact `PAYMENT_STAGE4_BACKEND_OWNER`, `PAYMENT_SMOKE_RECEIVER_OWNER`, `PAYMENT_SMOKE_POSTGRES_OWNER` IDs. Configure these in the owning process, not command arguments or saved reports. The smoke restarts that backend once and verifies durable provider receipts, retained business time, original cancellation identity, no duplicate cash and strict real-time signed delivery. Its output is only fixed checks/counts. Local test artifacts are not a production launch mechanism.

Run backend `./mvnw verify`; SDK typecheck/test/build; Admin lint/typecheck/test/build; demo build and complete Playwright with `--retries=0`; then all five infrastructure Node test files (receiver, Stage2, Stage3A, Stage3B, Stage4). Runtime acceptance executes the existing Stage1–3 entrypoints and `node infrastructure/smoke-stage-4.mjs` against a unique owned Compose project. Supply ephemeral credentials only via ENV, `PAYMENT_SMOKE_ENV_ONLY=true`, and an explicit empty Compose `--env-file`. Establish free loopback ports and exact container IDs/Compose project+service labels before any stop/restart. Legacy Mailpit outage tests now require `PAYMENT_E2E_MAILPIT_OWNER` and `PAYMENT_E2E_COMPOSE_PROJECT`; they fail closed without owned authority and restore only that verified ID. Keep traces/video/screenshots off for auth flows. Stop only this run's services and retain volumes; never use `down -v` or wipe user data.

Stage 5 prerequisites include a separately reviewed real provider adapter, Stripe Sandbox credentials and webhook verification, real client action/Payment Element integration, real Portal lifecycle, supported payment-method/account capabilities, provider tax and billing edge cases, operational reconciliation/alerts and a production deployment/security review. No real charge, production/legal assurance or payment-method availability is established by Stage 4.

Run from the project root. Use Docker Engine/Desktop with Compose v2, Bash, Node 24 built-ins, JDK 25 and pnpm 11.1.3. Maven 3.9.11 is provided by `backend/mvnw`. The Stage 1 catalog/authentication remains available; Stage 2 adds one-time payments, refunds, callbacks, Partner APIs, SDK and demo.

## Environment and startup

Copy `infrastructure/.env.example` to `infrastructure/.env` only if you do not already have a local configuration. Preserve existing credentials and the database. Bootstrap credentials initialize the first administrator; changing the file does not rotate an existing administrator's password.

After a fresh clone, create the local-only Customer issuer fixture before starting Compose or running backend integration tests. Run this from the project root with Node.js. The command preserves an existing fixture, writes new private material with owner-only permissions, and never prints keys:

```bash
node --input-type=module <<'NODE'
import { generateKeyPairSync } from 'node:crypto';
import { existsSync, writeFileSync } from 'node:fs';

const file = 'infrastructure/fixtures/customer-issuer-private.jwks.json';
if (existsSync(file)) {
  console.log('Existing local issuer fixture preserved.');
} else {
  const rsa = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const ec = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const keys = [
    { ...rsa.privateKey.export({ format: 'jwk' }), kid: 'local-fixture-rs', alg: 'RS256', use: 'sig' },
    { ...ec.privateKey.export({ format: 'jwk' }), kid: 'local-fixture-es', alg: 'ES256', use: 'sig' },
  ];
  writeFileSync(file, JSON.stringify({ keys }, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
  console.log('Local-only issuer fixture created.');
}
NODE
```

The fixture and real `.env` files are intentionally ignored by Git. Do not force-add them or reuse local fixture/example keys in production. Public issuer keys are derived by the protected local endpoint; no checked-in private or public fixture file is required.

| Variable | Purpose |
| --- | --- |
| PAYMENT_DB_PASSWORD | Local PostgreSQL credential |
| PAYMENT_ADMIN_BOOTSTRAP_USERNAME / PAYMENT_ADMIN_BOOTSTRAP_PASSWORD | First administrator |
| PAYMENT_DATA_ENCRYPTION_KEY | Base64 32-byte local encryption key; the explicit public example in .env.example is local-only and must never be reused outside local development |
| PAYMENT_DATA_ENCRYPTION_KEY_VERSION | Persisted key identifier, default local-v1; retain the matching key for encrypted records |
| PAYMENT_DONATION_MESSAGE_FINGERPRINT_KEY | Required Base64 32-byte Donation-message HMAC key, distinct from the encryption key; the public example is local-only |
| PAYMENT_DONATION_MESSAGE_FINGERPRINT_VERSION | HMAC purpose/version identifier, default v1; retain the same key/version while durable Donation checkout receipts remain replayable (no rotation keyring yet) |
| PAYMENT_FAKE_AUTO_SUCCESS | Default false: manual provider truth; true: confirm enqueues a success event for the inbox worker |
| PAYMENT_TELEMETRY_SITEKEYS | Finite comma-separated allowlist of every deployed Site key; missing membership rejects payment work before durable side effects |
| PAYMENT_POSTGRES_HOST_PORT | Default 5432 |
| PAYMENT_BACKEND_HOST_PORT / PAYMENT_ADMIN_HOST_PORT / PAYMENT_DEMO_HOST_PORT | Defaults 8080 / 3000 / 3001 |
| PAYMENT_SMOKE_SITE_KEY | Unique Site key chosen before smoke startup and included in PAYMENT_TELEMETRY_SITEKEYS |
| PAYMENT_SMOKE_BACKEND_URL / PAYMENT_SMOKE_ORIGIN | Optional smoke target/origin; default loopback backend and http://localhost:3000 |
| BACKEND_INTERNAL_URL | Admin BFF target, http://backend:8080 inside Compose |

All published bindings are loopback-only. If a port belongs to an unrelated process/container, leave it alone and change the matching host-port setting. Container traffic still uses postgres:5432 and backend:8080. The callback receiver publishes no host port.

Compose sets the local Spring profile, the local callback host list (localhost, 127.0.0.1, callback-receiver), FAKE provider and insecure cookies only for local HTTP. The callback URL policy independently enforces that fixed local-only list and approved ports; arbitrary additional hosts are not trusted. Outside local development use private keys/credentials, HTTPS and secure cookies. Environment files are excluded from image contexts; do not put secrets in shell tracing, command arguments, URLs, browser code, logs or reports.

```bash
docker compose --env-file infrastructure/.env -f infrastructure/compose.yaml up --build -d --wait
docker compose --env-file infrastructure/.env -f infrastructure/compose.yaml ps
```

Admin: http://localhost:3000. API: http://localhost:8080. Demo: http://localhost:3001. Readiness includes database connectivity; an unavailable callback destination does not make the backend unready.

## Admin, SDK and payment journeys

1. Authenticate in the admin and select an existing FAKE/SANDBOX account (or provision one through the admin account API).
2. Create a Site with a unique key, allowed demo origin, SITE_TOKEN and AUTOMATIC policy. Add its key to the finite telemetry setting and recreate the backend before payment use.
3. For the preserved one-time journey, create a Product and a ONE_TIME Price with positive integer minor units, uppercase currency, trial_days 0 and tax mode; publish it. For subscriptions use the Stage4 rollout above.
4. Open the demo and enter the API base URL, Site key and published price key. The SDK creates a checkout, confirms CARD, polls provider-confirmed truth, and retains only its recovery capability in sessionStorage. Amount/currency come from the published Price, not the browser. Secrets never enter browser URLs; server keys never reach the demo.
5. Default fake confirmation is PROCESSING. In admin Payment details enqueue fake events or reconcile actual fake-provider truth; a synchronous acknowledgement is not success. Recoverable decline retries keep the same Payment and create a new Attempt. UNKNOWN blocks another Attempt until reconciliation resolves it.
6. Use Payment details to inspect ordered journal, Attempts, Refunds, outbox and deliveries. Request full or partial refunds; provider confirmation changes money state. Concurrent reservations and confirmed refunds prevent over-refund.
7. Configure one callback destination or manage scoped Site API keys. New API keys and callback secrets display once; copy them to trusted server storage. They are not readable later, persisted in browser storage, or placed in query strings. Callback secret rotation retains at least 24 hours of overlap.
8. Sign out to revoke the admin session and delete its cookie. Admin BFF calls remain same-origin with cookie/CSRF protection.

For dynamic payments, only a trusted backend sends POST /api/v1/partner/payments with Authorization: Bearer <server-held Site API key>, Idempotency-Key, and JSON such as:

```json
{"external_order_id":"order-123","amount_minor":735,"currency":"EUR","checkout_origin":"http://localhost:3001","return_url":"http://localhost:3001/done","metadata":{"channel":"server"}}
```

The key needs payments:create (and payments:read for safe summaries). Treat the returned one-time handoff token as a capability: transfer in a POST body to the SDK/public handoff exchange, never a URL. The exchange needs the exact allowed Origin and returns the checkout capability once.

## Signed callback smoke

Choose a unique safe Site key and configure it before startup. The following exports only values from your local environment file; never enable shell tracing.

```bash
set -a
source infrastructure/.env
set +a
export PAYMENT_SMOKE_SITE_KEY="smoke-$(date +%s)"
export PAYMENT_TELEMETRY_SITEKEYS="demo,$PAYMENT_SMOKE_SITE_KEY"
export PAYMENT_FAKE_AUTO_SUCCESS=true
docker compose --profile smoke --env-file infrastructure/.env -f infrastructure/compose.yaml up --build -d --wait
bash infrastructure/smoke-stage-2.sh
```

The dependency-free smoke uses private mktemp session/capability files and removes them through an EXIT trap. It authenticates with CSRF, provisions a unique account/Site/Product/published ONE_TIME Price, privately configures the internal receiver, creates/confirms with distinct idempotency keys, waits for SUCCEEDED, checks one success journal fact and one verified callback, replays both requests and proves one Payment/Attempt. It scans backend/receiver output for known in-memory credentials without printing them. Only safe IDs appear in PASS JSON. Fetch redirects fail closed. The old smoke-stage-1.sh is a historical catalog-only workflow, not Stage 2 verification.

In owned `PAYMENT_SMOKE_ENV_ONLY=true` mode Stage2 never invokes Compose or reads an env-file. Supply `PAYMENT_SMOKE_RECEIVER_URL` as an explicit loopback HTTP origin, exact `PAYMENT_SMOKE_BACKEND_OWNER`/`PAYMENT_SMOKE_RECEIVER_OWNER` container IDs, `PAYMENT_E2E_COMPOSE_PROJECT`, and `PAYMENT_SMOKE_LOG_SINCE` as an ISO timestamp from this owned start. Both ID/project/service tuples are verified before bounded ID-only log reads. When the owned Fake runtime explicitly uses `PAYMENT_FAKE_AUTO_SUCCESS=false`, the smoke sends one idempotent Admin local Fake success event after confirmation, retaining its single-attempt/cash/callback checks. The standalone Compose fallback is used only outside ENV-only mode.

Callbacks use Payment-Event-Id, Payment-Timestamp (Unix seconds), Payment-Secret-Version and Payment-Signature: v1=<lowercase SHA-256 hex>. Verify HMAC-SHA256 using the decoded base64url secret over ASCII timestamp, a literal period, then the exact raw UTF-8 body. Check timestamp freshness (receiver ±300 seconds), exact signature length/encoding, constant-time MAC byte equality, safe matching IDs and the finite event-type vocabulary. Deduplicate event IDs: delivery is at-least-once. The smoke receiver caps the raw body at 16 KiB, rejects malformed UTF-8/media/headers, retains IDs/types/counts only, and has internal configure, inspection and health endpoints. It is local test infrastructure, not a deployed webhook gateway.

Automatic callback retry delays are 60, 300, 1800, 7200 and 43200 seconds; sixth failure becomes DEAD. Admin manual retry is explicit and does not reset the automatic schedule. A callback failure cannot regress Payment truth or readiness.

## Metrics and correlation

Business services use the typed Stage2TelemetryPort. Approved meter names:

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

The existing compatible payment.inbox.processed counter is also retained. Names are fixed, not caller supplied. Only configured Site key plus finite provider/status/operation/result tags are allowed (site, provider, from, to, operation, result). Never tag Payment/Refund/Attempt/request/provider-object/event IDs, credentials or payloads. Health is the only publicly exposed Actuator endpoint.

One highest-precedence RequestCorrelationFilter preserves the RequestIdFilter access facade. Incoming X-Request-Id must match [A-Za-z0-9._:-]{1,64}; otherwise generate a UUID. The same ID enters the request attribute, response and request_id MDC; finally restores a previous worker value or removes it, including failures. Structured correlation logging includes that ID, never bodies/headers/secrets; safe domain UUIDs may be logged.

## Native and browser verification

```bash
(cd backend && ./mvnw clean verify)
(cd frontend && pnpm --filter admin lint && pnpm --filter admin typecheck && pnpm --filter admin test --run --maxWorkers=1 && pnpm --filter admin build)
(cd frontend && pnpm --filter @generic-payments/sdk typecheck && pnpm --filter @generic-payments/sdk test --run && pnpm --filter @generic-payments/sdk build)
(cd examples/demo-site && corepack pnpm build)
node --test infrastructure/callback-receiver.test.mjs infrastructure/smoke-stage-2.test.mjs infrastructure/smoke-stage-3a.test.mjs
```

Backend integration and Cucumber journeys require real PostgreSQL 18 Testcontainers/Docker. The historical Stage 2 selection has sixteen journeys (fourteen plus two foundation journeys); the current Stage 3A totals and browser setup are below. Run heavy checks serially on constrained hosts.

For the historical guest-only Playwright selection, recreate backend with PAYMENT_FAKE_AUTO_SUCCESS=false. Set PAYMENT_E2E_BACKEND_URL, PAYMENT_E2E_DEMO_ORIGIN to the Compose demo origin, PAYMENT_E2E_ADMIN_USERNAME/PASSWORD and PAYMENT_E2E_DATABASE_URL through a private environment (never argv); the PostgreSQL URL is test-only and not browser input. Select a fresh PAYMENT_E2E_SITE_RUN (2–20 lowercase/digit/hyphen characters) and preconfigure all twelve t12-<run>-<success|decline|processing|unknown|failure|reload>-r<0|1>-site telemetry keys. For the complete current suite use the Stage 3A setup below. The guest-only selection is:

```bash
(cd examples/demo-site && corepack pnpm exec playwright test tests/checkout.spec.ts)
```

When PAYMENT_E2E_DEMO_ORIGIN is set, Playwright uses that running Compose service rather than starting Vite. Exactly six accepted journeys check success, recoverable decline, processing, UNKNOWN/reconciliation, terminal failure and 320px reload recovery, with request/URL/console/storage/DOM sink audits. The private fixture's fake-state SQL control is test-only; browser code has no database access.

## Stop and persisted data

```bash
docker compose --profile smoke --env-file infrastructure/.env -f infrastructure/compose.yaml down
```

This stops only project containers/network and retains payment-stage-1_payment-postgres. The project name intentionally remains payment-stage-1 for in-place V4 migration and prior data. PostgreSQL 18 mounts /var/lib/postgresql and uses version-aware PGDATA beneath it. Never use down --volumes or volume removal to solve port conflicts. A destructive database reset erases all payment/catalog/admin/journal data and requires explicit authorization and a recoverable backup; it is not part of ordinary stop or this verification.

Stage 1 historical execution reports remain under .superpowers/sdd/2026-09-12-foundation-catalog-implementation; their old counts/running-stack claims are historical, not current runtime state. Fresh Stage 3A verification and the preserved Stage 1–2 compatibility evidence are recorded in .superpowers/sdd/2026-10-10-customer-access/task-13-report.md and review.md; final gate status belongs to the controller-owned progress ledger.

## Stage 3A Customer acceptance

Customer access adds Site-scoped SITE_TOKEN, OIDC_TOKEN and EMAIL_OTP without changing guest checkout or callback schema v1. The host Site owns OAuth buttons and redirects; the demo deliberately supplies no imitation Google/Apple screens. Use the reviewed SDK email component for Mailpit sign-in, same-tab restore, owned checkout/history, explicit guest omission and logout. History defaults to 20 entries and accepts 1..100. Customer tokens are opaque absolute-TTL capabilities in sessionStorage only; invalid supplied credentials never become guest.

Compose requires `PAYMENT_CUSTOMER_DIGEST_KEY_V1` and `PAYMENT_CUSTOMER_HISTORY_CURSOR_KEY_V1`, with active-version values `PAYMENT_CUSTOMER_DIGEST_ACTIVE_VERSION=v1` and `PAYMENT_CUSTOMER_HISTORY_CURSOR_ACTIVE_VERSION=v1`. The example file contains explicitly public local-only 32-byte keys; never reuse them outside local development. Preserve existing `.env` and persisted encryption/digest key versions. Mailpit uses SMTP `mailpit:1025`, API host port `PAYMENT_MAILPIT_HOST_PORT` (28025 by default), and optional loopback SMTP host port `PAYMENT_MAILPIT_SMTP_HOST_PORT` (21025). Its healthcheck is passive; backend readiness includes configured mail capability, not a delivery probe.

The local RS256/ES256 fixture is disabled by application default; the local Compose configuration explicitly opts in. Set `PAYMENT_CUSTOMER_LOCAL_ISSUER_ENABLED=true` only under the local/test profile. Compose mounts generated, Git-ignored non-production private fixture keys read-only; create them with the command in Environment and startup after a fresh clone. `PAYMENT_CUSTOMER_LOCAL_ISSUER_PRIVATE_JWK_SET` points to that file. The Admin-session/CSRF-protected `/api/v1/admin/local/customer-issuer/public-keys` and `/tokens` routes expose public keys or issue two-minute fixture tokens respectively. Production, mixed local/production, and absent profiles reject enabled/private-key configuration. These are trusted local test controls, never browser signing authority or real provider accounts.

Customer metrics have no Site/Customer/issuer/Origin/IP identifiers: `customer.auth.exchange` and `customer.auth.latency` use `mode`/`outcome`; `customer.otp`, `customer.identity.link`, `customer.anonymization` use `outcome`; `customer.mail.delivery` uses `mail_outcome`; `customer.session` uses `operation`. `customer.mail.pending` and `customer.mail.active` are unlabelled global database aggregates. All values come from bounded application enums; authentication bodies and raw sensitive values must never enter audit/log output.

The full backend suite now includes 28 real PostgreSQL/MockMvc Cucumber scenarios (16 preserved plus 12 Customer scenarios); OTP scenarios use actual SMTP and a dedicated Mailpit Testcontainer. Run the smoke unit checks independently:

```bash
node --test infrastructure/smoke-stage-3a.test.mjs
(cd backend && ./mvnw -q -Dtest=RunCucumberTest test)
```

For live smoke, include stable `stage3a-smoke-one,stage3a-smoke-two` in `PAYMENT_TELEMETRY_SITEKEYS` before backend startup. Export your existing local environment privately and choose free loopback ports, for example PostgreSQL 25432, backend 28080, Admin 23000, demo 23001 and Mailpit 28025. Set `PAYMENT_SMOKE_BACKEND_URL=http://127.0.0.1:28080`, `PAYMENT_SMOKE_MAILPIT_URL=http://127.0.0.1:28025`, and `PAYMENT_SMOKE_ORIGIN=http://127.0.0.1:4173`, then run `node infrastructure/smoke-stage-3a.mjs`. It reuses the two Sites and creates fresh local Customers/catalog fixtures; it verifies actual receipt, ownership through history, rejection after logout and unchanged financial facts after anonymization. Only fixed safe PASS/FAIL JSON is printed; redirects and oversized responses fail closed.

The complete Playwright suite has 23 journeys: six retained guest journeys plus seventeen Customer journeys against real backend/Mailpit/Admin. The seven newer-session regressions cover obsolete checkout/confirm/history/logout success, history/logout failure and busy-state ownership after restoring Customer A and authenticating B. Choose a fresh bounded `PAYMENT_E2E_SITE_RUN`, then allowlist `t12-<run>-<journey>-r<0|1>-site` for each journey: success, decline, processing, unknown, failure, reload, customer, scope, second, expiry, unavailable, pending, admin, storage, config, delivery, racecheckout, raceconfirm, racehistory, racelogout, racehistoryerror, racelogouterror, racebusy. With `--retries=0`, only r0 is needed. Export backend/Admin/Mailpit URLs and Admin/database credentials privately; leave `PAYMENT_E2E_DEMO_ORIGIN` unset to use the isolated Vite server on 127.0.0.1:4173. Browser test inputs never receive Admin/database credentials. A full loopback acceptance run issues more than ten OTP requests; use a task-local backend override `PAYMENT_CUSTOMER_EMAIL_OTP_RATE_LIMIT_SOURCE_IP_REQUESTS=100` for that run only, preserving the production default and all per-address limits. Run `PLAYWRIGHT_NO_COPY_PROMPT=1 pnpm test:e2e --retries=0` from the demo. Authentication journeys explicitly disable traces/screenshots/video; the no-copy-prompt setting suppresses automatic DOM error-context snapshots, not assertion/source context files. Preserve any such diagnostic context and classify it privately without printing credentials, OTPs or mail bodies. Do not enable sensitive artifacts for auth debugging.

Admin acceptance covers real login, protected reads, stale-version 409/refresh, terminal provider disable, exact UUID anonymization, keyboard focus/trapping/escape and retained Payment facts. On constrained hosts use `pnpm test --run --maxWorkers=1` in `frontend/admin`; investigate and retain any earlier timeout evidence rather than assuming a product cause or hiding a failure.

Inspect runtime ownership before startup. At completion stop only services this run started, using their resolved exact container names. For the default project these may be `payment-stage-1-backend-1`, `payment-stage-1-admin-1`, `payment-stage-1-demo-site-1`, `payment-stage-1-mailpit-1`, and `payment-stage-1-postgres-1`. Do not stop unrelated infrastructure, do not remove volumes, and preserve `payment-stage-1_payment-postgres`.

## Stage 3A exclusions

These were the historical Stage 3A exclusions: no donations execution, real Stripe charges, real OAuth accounts/host screens, subscriptions execution, periodic fleet reconciliation, multiple callback endpoints or deployment automation. The Stage 3A gate was a prerequisite for Stage 3B below; current gate acceptance remains controller-owned. At that stage Subscription Prices described catalog data only; Stage4 adds local subscription execution. FAKE is deterministic local provider truth; never treat it as real payment settlement.

## Stage 3B Donation verification

Donation telemetry is separate from unchanged Payment/Customer metrics: `donation.checkout`, `donation.checkout.latency`, and `donation.message.redaction` have only a finite `outcome` tag. Checkout outcomes include created/replayed, amount/anonymous/session rejection and conflict; redaction records committed changes/replays/unchanged outcomes and failures. No Site/Customer/Campaign/Donation IDs, keys, Origin, message, email, or amount enter these labels. The integration suite inspects the real MeterRegistry; live runtime metrics are **not exposed/not scanned** because Actuator remains health-only.

The additive Donation acceptance scenarios exercise lifecycle/policy, identity-aware replay, atomic rollback, encryption, provider finality/retry/refund/reconciliation, signed callback v2, ownership, redaction and anonymization. Catalog callback v1 remains unchanged. Run all infrastructure contracts together:

```bash
node --test infrastructure/callback-receiver.test.mjs infrastructure/smoke-stage-2.test.mjs infrastructure/smoke-stage-3a.test.mjs infrastructure/smoke-stage-3b.test.mjs
```

Legacy Stage2/3A failure fixtures explicitly set `PAYMENT_SMOKE_ENV_ONLY=true` and preload a harmless private-file read trap. Standalone fallback behavior without that opt-out is unchanged. Stage3B smoke is always ENV-only: never source or print a private configuration file. It requires existing local Admin/database credentials in child ENV, `PAYMENT_SMOKE_BACKEND_URL`, `PAYMENT_SMOKE_MAILPIT_URL`, `PAYMENT_SMOKE_RECEIVER_URL`, `PAYMENT_SMOKE_DATABASE_URL`, two fresh `PAYMENT_SMOKE_SITE_KEY`/`PAYMENT_SMOKE_SECOND_SITE_KEY` values already in `PAYMENT_TELEMETRY_SITEKEYS`, exact resolved `PAYMENT_SMOKE_BACKEND_OWNER`/`PAYMENT_SMOKE_RECEIVER_OWNER` container IDs and a bounded `PAYMENT_SMOKE_LOG_SINCE` timestamp. Endpoints must be loopback. Publish the existing receiver's internal 8080 only on an explicitly checked loopback test port; preserve its `/configure`, `/callback`, and `/inspection` contracts. Run `node infrastructure/smoke-stage-3b.mjs` only after the owned stopped-to-live inventory and key continuity checks.

The smoke uses anonymous and actual Mailpit OTP donors, real fake-provider settlement and signed receiver delivery/deduplication, Customer/Partner/Admin isolation, existing refund/reconcile APIs, anonymization with full financial-row equality, and a catalog v1 callback. Output contains bounded phases/counts/booleans only. A finite in-memory fixture scan checks named database/history/receiver/runtime-log sinks before shutdown; it is not universal privacy certification. The runtime does not read private files or put secrets into argv/files. Keep original AES/digest/cursor/fingerprint key bytes and versions for retained Donation receipts.

The browser matrix additionally exercises Admin Campaign creation/lifecycle focus, conflict refresh/navigation/session protection, literal-text donor rendering and UUID-confirmed redaction with double-submit exclusion and same-intent retry. Actual Admin login uses the shared private-entry helper; database credentials never enter the browser. Keep debug logging, media and trace capture off, payload-safe boolean assertions and supported no-retention settings. The no-copy-prompt setting does not disable every matcher/source context. Customer authentication restoration and catalog recovery remain supported; Donation checkout is memory-only and is not promised to survive reload.

Run configured Admin lint separately; the SDK has no lint script/toolchain (NOT CONFIGURED/NOT RUN). Use separate demo `pnpm exec tsc --noEmit` and `pnpm build` gates, plus full browser `pnpm test:e2e --retries=0`. The configured Compose Admin entrypoint is standalone `node admin/server.js`, not `next start`. Stop only exact resources started for verification and retain the PostgreSQL volume and unrelated listeners.
