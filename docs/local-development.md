# Local development through Stage 4

## Stage 5A offline regression and explicit Sandbox acceptance

The default Maven suite includes `@stripe-sandbox` Cucumber scenarios using real PostgreSQL,
application HTTP boundaries and the official Stripe SDK against an owned loopback HTTP fixture.
Their generated synthetic credentials cannot establish real Stripe interoperability. Default
Playwright excludes both `stripe-sandbox.spec.ts` and `stripe-sandbox-remaining.spec.ts`, and native support tests; Fake payment, Donation and subscription coverage and
the built nginx privacy suite remain in default discovery. Fresh clones need only the local issuer
generation described below, installed project dependencies and Docker—not ignored execution helpers.

Final offline checks are backend `./mvnw verify`; SDK `pnpm test --run`, `pnpm typecheck`,
`pnpm build`; Admin `pnpm test --run`, `pnpm typecheck`, `pnpm lint`, `pnpm build`; demo
`pnpm exec vitest run src/payment-return.test.ts src/vite-return-privacy.test.ts`, `pnpm build`,
`pnpm test:e2e --workers=1 --retries=0`; root
`node --test --test-timeout=30000 --test-concurrency=1 infrastructure/*.test.mjs`.
Default browser acceptance needs its own Fake backend/PostgreSQL/Mailpit/Admin/demo and explicit
Stage 4 test clock inputs described below. A listening localhost port is not evidence of ownership.

The [dated 2026-10-28 acceptance matrix](stage-5a-acceptance.md) records Stage 5A as
**technically ACCEPTED** on executable source `9143e01937b9d0e06d2e2809eec88cf0c965ea3a`,
combining separate bound Sandbox runs and offline/Fake regression. Independent documentation and
risk-based branch reviews are complete with no actionable/material findings and the limits stated
in the matrix. Final staged audit, commit/push and external exact-documentation-commit CI
verification remain the publication gate at this write-time. The matrix preserves older
unknown outcomes and narrow report limits. Key readiness and this guide authorize no new financial
operation. Any future real run requires fresh explicit scope and local process flags
`PAYMENT_STRIPE_SANDBOX_ENABLED=true` and `PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS=true`.
The tooling never reads a Stripe env file, calls Stripe directly, installs/logs into the CLI, starts a
listener, creates catalog/Account/Site fixtures, or changes an account's API version. Server keys stay
in the backend. Keep `DEBUG`, `NODE_DEBUG` and `PWDEBUG` disabled.

Before an authorized real run, prepare one owned Compose project named `stripe-…`, with backend,
demo-site and callback-receiver published on distinct `http://127.0.0.1:<port>` origins. The receiver
needs an explicit loopback host binding for its internal port 8080; default Compose does not publish it.
Record each full container ID; the runner checks exact project/service labels, running state and host
port mappings before sending credentials. Never reuse an unrelated listener. This
acceptance tooling supports local POSIX Docker only: it rejects remote contexts/endpoints and pins
each inspection to the validated local Unix socket without inherited Docker endpoint overrides.
This prevents accidental remote-context confusion, not same-user OS-level socket impersonation. The Site
callback points to that receiver's `/callback`, and its one-time returned signing secret is delivered
to `/configure` in memory only. Do not rotate/recreate it merely to make a resumed run convenient.

### Separate owned Sandbox provisioner

`infrastructure/stripe-sandbox-provision.mjs` is an inert capability for the separately approved
owned run. Construct `createOwnedSandboxProvisioner({env, bootstrap, verifyProviderBinding})`
with both explicit Sandbox flags, the reviewed live bootstrap capability and the reviewed
provider-binding callback. Only `provision({signal, deadline})` performs I/O; it requires a finite
absolute deadline, freshly verifies ownership before each HTTP request, and uses generated Admin
credentials only after releasing the bootstrap callback. Backend session cookies and CSRF stay
on the backend origin; the receiver gets only its one-time callback secret in memory.

This separate capability creates the Account, Site, return policy, EUR 20.00 (2000 minor units) CARD catalog Price,
EMAIL_OTP configuration, fixed EUR 5.00 (500 minor units) Donation campaign and four anonymous catalog checkouts
(`success`, `decline`, `sca`, `missed`). Its owner-only run directory retains `provisioning.json`
with durable DISPATCHING records before every write and safe returned identities afterwards.
The Site POST includes optional `email_otp_provider` with `provider_key`, `sender_ref` and
`template_key`; EMAIL_OTP must be enabled on the Site. The existing Admin transaction commits
the Site, its READY provider and both creation audits together, or rolls everything back.
Omitting the field preserves legacy creation, including readiness failure for an incomplete
EMAIL_OTP Site. The provisioner records one `site-and-email-provider` intent, then discovers
and checks the exact provider through existing list/get routes under normal ownership and
readiness verification; it sends no separate provider POST.
Existing ledgers/locks and uncertain outcomes permanently refuse replay; preserve their evidence
and original keys. Price publication is the first Stripe catalog write; Account creation already
crosses the fixture mutation boundary. No payment confirmation or Donation checkout occurs here.

After all four known checkout responses and final binding checks, it invokes the existing v2
state initializer with the original three catalog cases. `resources()` exposes frozen safe
identities. Synchronous exact-true callbacks `withCatalogEnvironment` and `withMissedCheckout`
hand off the generated Admin/three catalog tokens and original fourth token respectively; never
serialize or log these snapshots. Runtime startup, listener ownership and financial/browser work
remain separate. There is no automatic entrypoint, resume, retry or replacement operation key.

Provision through existing protected Admin APIs after separate authorization:

1. Before each first write, record its stable resource key/name and idempotency key in an owner-only,
   Git-ignored `test-results/stripe-sandbox/provisioning.json` ledger. Record returned safe IDs only
   after a known response. Account/Site creation returns server-assigned UUIDs and does not offer a
   general caller-ID/idempotency contract. An unknown outcome therefore requires inspection by the
   original key; do not blindly retry or replace the key. Never store credentials, tokens or PII here.
2. Create a STRIPE/SANDBOX Account using `stripe-sandbox-default`, the expected Sandbox `acct_…`
   and server identity verification; create an active Site with the owned demo origin in its allowlist
   and telemetry allowlist. Set its exact `/payment-return.html` URL through protected return-URL
   policy with the current expected version. Publish one EUR 2000-minor-unit ONE_TIME Price, NONE/INCLUSIVE tax.
3. Configure the owned callback receiver once. Create three distinct catalog checkouts using fixed
   persisted creation keys: `success`, `decline`, `sca`. Record their safe Payment/checkout UUIDs.
   Keep their checkout tokens only in the owning process environment; unknown create stops for
   inspection of the SAME operation. Do not use a fresh checkout to bypass an uncertain result.
4. Initialize the runner state once with the safe metadata below. The explicit initialization only
   writes local state; it does not provision anything. It refuses an existing state. Supply the JSON
   through `PAYMENT_SANDBOX_FIXTURE_JSON` (public fixture identity only), and a fresh UUID through
   `PAYMENT_STRIPE_SANDBOX_RUN_ID`. Metadata is exactly:
   `{runId, owners:{project,backend,demo,receiver}, endpoints:{backend,demo,receiver},
   account:{id,key,externalId}, site:{id,key}, price:{id,key},
   cases:{success:{paymentId,checkoutId},decline:{paymentId,checkoutId},sca:{paymentId,checkoutId}}`.

```bash
# Repository root; both explicit opt-in flags already set in this process.
node --input-type=module <<'NODE'
import {initializeStripeSandboxState} from './infrastructure/smoke-stage-5a.mjs';
await initializeStripeSandboxState(JSON.parse(process.env.PAYMENT_SANDBOX_FIXTURE_JSON));
console.log('Sandbox fixture state initialized; no platform operation executed.');
NODE
```

Supply `PAYMENT_SANDBOX_ADMIN_USERNAME`, `PAYMENT_SANDBOX_ADMIN_PASSWORD` and
`PAYMENT_SANDBOX_SUCCESS_CHECKOUT_TOKEN`, `PAYMENT_SANDBOX_DECLINE_CHECKOUT_TOKEN`,
`PAYMENT_SANDBOX_SCA_CHECKOUT_TOKEN` through the explicit process environment. Never put values in
argv, chat, URL, source, logs or the state file. State is fixed at repository-root
`test-results/stripe-sandbox/<run UUID>/state.json`, mode 0600 in an owner-only directory. It retains
only safe identity, fixed refund keys, safe refund IDs, phases and server-derived decline proof. Node and browser share its exclusive
lock; no concurrent run and no automatic stale-lock removal. After a crash, establish that the old
owner has stopped and inspect the state before manually releasing its lock; preserve all keys/phases.

```bash
# examples/demo-site, against the explicitly owned, already running platform
pnpm exec playwright test --config playwright.stripe-sandbox.config.ts
# repository root, after browser/server confirmation
node infrastructure/smoke-stage-5a.mjs
```

The separate browser configuration uses one worker, zero retries, no trace/screenshots/video,
no retained output, and a fixed-line reporter that never prints errors, iframe URLs or payloads.
It uses real Stripe-hosted fields and [official Stripe test cards](https://docs.stripe.com/testing):
success, decline followed by success on the SAME intent, and 3DS. Selector or challenge changes fail
as manual-check/PENDING; no mocked success fallback. Browser recovery uses the existing authorized
catalog read/explicit Resume; its checkout token is only in the ephemeral browser context. PREPARING
is persisted before navigation/client-session, CONFIRMING/RETRYING before each Pay. Interrupted phases
permit server proof inspection only; they never automatically repeat preparation or confirmation.

State version 2 records the decline journey's original protected `PAYMENT_PREPARE` operation ID/intent
reference and unique current attempt ID/number before the first Pay. That same nonterminal attempt must
become server-verified FAILED before retry is authorized; historical failed attempts do not count.
Every non-null attempt reference and the final success journal reference must match the original intent,
including proof-only restarts. Completion requires durable verified-decline evidence and RETRYING or
SERVER_CONFIRMED phase; success after an interrupted first Pay is not a completed decline/retry journey.
Old version 1 state is rejected before ownership checks or login, with no automatic migration/reset.
Keep it and any original keys/IDs for manual PENDING inspection; do not recreate fixtures or generate new
keys to work around an uncertain prior action. No client secret is parsed or retained for this proof.

The Node runner checks original Account/Site/source/Price/EUR 2000 minor units and joins each success journal ID
to the identical delivered outbox ID, successful delivery and signature-verified receiver event ID/type.
Receiver transport duplicates are allowed; the financial fact remains exactly one. Only then may it
submit the fixed 700 partial and 1300 remaining refunds, each with its previously persisted key and a
durable DISPATCHING marker. A 202 response is not success. Each individual Refund reference, money,
journal/callback and the final 2000 aggregate must be confirmed. Unknown dispatch without a recorded
refund UUID stays PENDING: inspect the original operation and manually record only a verified owned
UUID; never infer binding from amount or invent another key. Known IDs resume read-only. All reads
refuse redirects, cap bodies at 64 KiB and complete within 10s; polling is at most 30 probes/45s per phase.
Successful output contains only `paymentConfirmed`, `refundConfirmed`, `callbackVerified` booleans.

External acceptance also requires separately recorded real anonymous/identified Donation evidence,
matching publishable/server key behavior, Dashboard Site/source/amount attribution, and a genuinely
missed webhook recovered by the existing Admin reconcile command. For that recovery evidence,
record an initially absent success journal, intentionally controlled listener interruption, verified
provider success and the subsequent reconciliation journal/callback; an already-succeeded no-op
reconcile proves nothing. Listener handling and these financial fixtures need explicit authorization.
These items remain PENDING when only the three catalog tests or offline fixtures were run.

Before starting an authorized listener, verify its Sandbox/default snapshot API version is exactly
`2026-09-30.endive`; stop if it differs. The [CLI listen reference](https://docs.stripe.com/cli/listen)
does not supply an exact-version pin flag; `--latest` is not verification. Never change a production
account/default version for this check. Use only the approved filtered events and Account-key URL
documented below. Preserve fixtures, database volumes and operation state; stop only this run's
recorded services/listener. No Sandbox result establishes live-payment readiness.

### Remaining-only 5A Sandbox mode

`playwright.stripe-sandbox-remaining.config.ts` selects one serial owned run for the
remaining lifecycle proofs. Its separately authorized run on 2026-10-28 passed on
source `9143e01937b9d0e06d2e2809eec88cf0c965ea3a`: new payment and EUR7+EUR13
refunds, anonymous and EMAIL_OTP donations, missed-webhook reconciliation and confirmed
owned cleanup. The [canonical matrix](stage-5a-acceptance.md#bound-sandbox-evidence-and-current-source-ci--2026-10-28)
records the exact run and report limits. No new run is required for the current closeout.
For any future use of the command below, green hosted CI for its exact reviewed source,
fresh credential-free source/daemon/port admission and fresh explicit runtime
authorization remain required. Admission is not a port reservation or financial
authorization. Use a fresh run UUID, empty new owned run state and newly
provisioned Site/catalog/campaign; this mode accepts no old-payment target or imported
historical evidence. Both branches of the older Sandbox entry reject this selector.

From `examples/demo-site`, only after those prerequisites and with the approved local
Unix daemon and six distinct free loopback ports, the future command is:

```bash
PAYMENT_STRIPE_SANDBOX_ENABLED=true \
PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS=true \
PAYMENT_STRIPE_SANDBOX_OWNED_RUN=true \
PAYMENT_STRIPE_SANDBOX_REMAINING_ONLY=true \
PAYMENT_STRIPE_SANDBOX_RUN_ID="<FRESH_RUN_UUID>" \
PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE="<REVIEWED_EXACT_COMMIT_SHA>" \
PAYMENT_STRIPE_SANDBOX_DAEMON="<APPROVED_UNIX_DAEMON>" \
PAYMENT_STRIPE_SANDBOX_PORT_BACKEND="<BACKEND_PORT>" \
PAYMENT_STRIPE_SANDBOX_PORT_DEMO="<DEMO_PORT>" \
PAYMENT_STRIPE_SANDBOX_PORT_RECEIVER="<RECEIVER_PORT>" \
PAYMENT_STRIPE_SANDBOX_PORT_POSTGRES="<POSTGRES_PORT>" \
PAYMENT_STRIPE_SANDBOX_PORT_MAILPITHTTP="<MAILPIT_HTTP_PORT>" \
PAYMENT_STRIPE_SANDBOX_PORT_MAILPITSMTP="<MAILPIT_SMTP_PORT>" \
pnpm exec playwright test --config playwright.stripe-sandbox-remaining.config.ts
```

All four opt-ins must be exact `true` own enumerable environment values. Leave
`PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND`, `_MANUAL_REFUND_REMAINDER`, `_FULL_RUN`,
`_FORM_DIAGNOSTIC`, `_MANUAL_FORM_DIAGNOSTIC`, `_ALLOW_PREPARE_ONLY` and `_MANUAL_PAYMENT`
unset or `false` (every suffix uses the full `PAYMENT_STRIPE_SANDBOX` prefix).
Keep `DEBUG`, `NODE_DEBUG`, `PWDEBUG` and `PW_TRACE` disabled. The entry validates the
raw flags before setup and the owner validates them independently. The existing owned
setup retains its human CLI authorization, original source/account/API/ownership checks,
45-minute total deadline and up to two minutes for owned cleanup; no automatic web server,
installation or default-CI execution is added. Browser trace, screenshot and video stay
off, output is not retained, and the existing redacting reporter remains in use.

The strict order and total budget are four new successful payments: one EUR20 catalog
payment, EUR5 anonymous donation, EUR5 EMAIL_OTP donation, then a separate EUR20 catalog
payment for missed-webhook recovery. Refund only the first payment: EUR7 once with its
durable key; require exact successful provider-reference/event/journal/signed-delivered-
callback and 700-refunded proof before EUR13 once with its separate durable key. Final
proof requires REFUNDED/2000 and exactly those two refunds. Every payment retains the
existing account/site/source/operation/payment/signed-webhook/journal/outbox/delivered-
callback joins; donation customer/campaign/Mailpit OTP proof and the missed journey's
negative baselines, owned listener stop, provider capture, single stable-key reconcile
and new unique RECONCILIATION fact remain mandatory. A raced webhook fails closed.
Unused provisioned decline/SCA checkouts are never paid to satisfy full-mode predicates.

Stop on the first failure or unknown/pending/lost acknowledgement. Never retry, resume,
rotate keys, recreate fixtures, delete evidence or apply this workflow to the retained
payment. A successful narrow resource report is `mode: 'REMAINING_5A'` with
`remainingAccepted: true` only after every proof and confirmed cleanup; `accepted: false`
and `proof.cards: false` always remain. `proof.payment` records only the new success
payment. This supplies no new decline/3DS proof and cannot alone claim full 5A acceptance.
The [2026-10-28 matrix](stage-5a-acceptance.md) combines this narrow result with earlier
bound card/3DS and offline regression evidence; independent reviews are complete, and
its publication gate remains separate. The old EUR13/local-state issue and prior anonymous DISPATCHING donation remain
unresolved and excluded from replay, import, rekey or state reset.

## Stripe Sandbox credential binding (Stage 5A)

Stripe remains disabled by default. Opt in with `PAYMENT_STRIPE_SANDBOX_ENABLED=true` and the fixed
`stripe-sandbox-default` credential reference. Supply `PAYMENT_STRIPE_SANDBOX_SECRET_KEY`,
`PAYMENT_STRIPE_SANDBOX_PUBLISHABLE_KEY`, and `PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET` through the
owning process environment. The only allowed active profiles are `local` and/or `test`, with the
optional `stripe-sandbox` marker. Every other active profile, including `prod` and `production`,
fails startup when enabled. Test/restricted-test server keys and test publishable keys are required;
key syntax validation does not prove validity or permissions. Never commit private credentials.

Creating a Stripe Sandbox Payment Account reads `/v1/account` and checks its identity before saving.
This does not establish that the publishable key matches: verify that separately in the opted-in
Sandbox/UI checks. The runtime endpoint is fixed to `https://api.stripe.com`, redirects are refused,
the SDK is `stripe-java:34.0.0` with its default API `2026-09-30.endive`, and automatic SDK retries
are disabled. Account verification suspends the existing Admin save/audit transaction during the
network read.

A restricted key needs Account read, Products/Prices read+write, PaymentIntents read+write,
Charges read, Refunds read+write, and Events read for manual recovery. Treat these permissions as
unverified until those operations have passed the explicit real Sandbox checks; successful account
identity lookup alone does not make a restricted key ready for payment operations.

## Stage 4 subscription rollout and acceptance

Stage 4 is local Fake-provider functionality, not Stripe readiness. Enable subscriptions for the Site, publish a subscription Price, define explicit same-Product/currency transitions, then authenticate Customers again. Existing persisted sessions and Partner keys retain their old scopes; use new Customer sessions and new least-privilege `subscriptions:read` (including access checks) and/or `subscriptions:manage` Partner keys. Deploy the strict current SDK with its matching backend; do not infer authority from browser events or access hints. The SDK guide shows the Customer manager and trusted Partner access integration.

Published prices, billing snapshots and the grace deadline attached to a bill are immutable. Grace defaults to three 24-hour UTC days; zero disables it. Optional trial without a method pauses without debt; later setup alone does not resume it. Required setup must complete before trial access. Setup, recovery of existing debt, and explicit resume are different commands. Upgrade preview is not approval or payment. Refund and cancellation are independent. Unknown/timeout results retain the original operation for read-only refresh/reconciliation; never blindly repeat a financial POST. Disabling the Site's subscription feature is forbidden while non-terminal subscriptions or unresolved create/cancel exist.

Callbacks preserve v1 catalog cash and v2 donation cash, add v3 subscription-linked positive cash/refund context and v4 lifecycle/access invalidations. Verify exact bytes, signature timestamp/version and envelope shape; deduplicate event IDs, reject foreign identity and regressive revisions. Only a fresh Partner access response grants cached authority. Expire at the exact `valid_until`, at an earlier entitlement boundary, and at most five minutes; a late callback never extends authority.

For deterministic billing acceptance use the explicitly opted-in launcher under `backend/src/test/java/dev/genericpayments/acceptance/runtime`, outside the production artifact. Its business clock is distinct from real UTC session/OTP/JWT/signature clocks. The separate owned control channel requires an ephemeral ENV capability. Never expose or deploy it. Ordinary local Admin Fake ADVANCE alone changes provider time, not application time. Fake scenarios and simulated Portal prove local flows only.

The launcher requires `PAYMENT_STAGE4_TEST_RUNTIME=true`, a fresh 43-character base64url `PAYMENT_STAGE4_CONTROL_KEY`, an owned control port, and `PAYMENT_STAGE4_TIME_FILE` in an explicit dedicated `task17-time-*` directory. That file contains only the business instant: durable atomic writes precede clock advancement, and restart loads it before workers start. Keep its directory/volume with the owned database; malformed or unreadable state must fail closed. Mount only the launcher and its two compiled nested classes in an isolated package tree, never the whole test output (unrelated test configurations must not enter component scanning). Application encryption/fingerprint/customer digest/history keys use standard Base64, independently generated; the control capability uses Base64URL. The test-only receiver consumes `PAYMENT_STAGE4_BACKEND_URL` and `PAYMENT_STAGE4_CONTROL_URL`; it never advances business time or relaxes real signature freshness.

Stage4 smoke additionally requires loopback backend/receiver/Mailpit/database URLs, the control URL/key, Admin credentials, two fresh telemetry-allowlisted Site keys, `PAYMENT_E2E_COMPOSE_PROJECT=task17-…`, and exact `PAYMENT_STAGE4_BACKEND_OWNER`, `PAYMENT_SMOKE_RECEIVER_OWNER`, `PAYMENT_SMOKE_POSTGRES_OWNER` IDs. Configure these in the owning process, not command arguments or saved reports. The smoke restarts that backend once and verifies durable provider receipts, retained business time, original cancellation identity, no duplicate cash and strict real-time signed delivery. Its output is only fixed checks/counts. Local test artifacts are not a production launch mechanism.

Run backend `./mvnw verify`; SDK typecheck/test/build; Admin lint/typecheck/test/build; demo build and complete Playwright with `--retries=0`; then all five infrastructure Node test files (receiver, Stage2, Stage3A, Stage3B, Stage4). Runtime acceptance executes the existing Stage1–3 entrypoints and `node infrastructure/smoke-stage-4.mjs` against a unique owned Compose project. Supply ephemeral credentials only via ENV, `PAYMENT_SMOKE_ENV_ONLY=true`, and an explicit empty Compose `--env-file`. Establish free loopback ports and exact container IDs/Compose project+service labels before any stop/restart. Legacy Mailpit outage tests now require `PAYMENT_E2E_MAILPIT_OWNER` and `PAYMENT_E2E_COMPOSE_PROJECT`; they fail closed without owned authority and restore only that verified ID. Keep traces/video/screenshots off for auth flows. Stop only this run's services and retain volumes; never use `down -v` or wipe user data.

Stage 4 alone establishes local Fake behavior. The [Stage 5A matrix](stage-5a-acceptance.md) records accepted one-time Sandbox evidence; the [Stage 5B matrix](stage-5b-acceptance.md) separately records implemented Billing/Elements/Portal paths, current verification and remaining gaps. Billing provider acceptance, operational reconciliation/alerts and production deployment/security review remain separate gates. The Stage 4 business-clock launcher does not establish owned native Stripe TestClock support.

Run from the project root. Use Docker Engine/Desktop with Compose v2, Bash, Node 24 built-ins, JDK 25 and pnpm 11.1.3. Maven 3.9.11 is provided by `backend/mvnw`. The Stage 1 catalog/authentication remains available; Stage 2 adds one-time payments, refunds, callbacks, Partner APIs, SDK and demo.

## Environment and startup

Copy `infrastructure/.env.example` to `infrastructure/.env` only if you do not already have a local configuration. Preserve existing credentials and the database. Bootstrap credentials initialize the first administrator; changing the file does not rotate an existing administrator's password.

After a fresh clone, create the local-only Customer issuer fixture before starting Compose or running backend integration tests. Run this from the project root with Node.js. The helper resolves its fixture destination from its own location, preserves an existing regular fixture, rejects symlinks, writes new private material with owner-only permissions, and never prints keys:

```bash
node infrastructure/init-local-customer-issuer.mjs
```

The fixture and real `.env` files are intentionally ignored by Git. Do not force-add them or reuse local fixture/example keys in production. Public issuer keys are derived by the protected local endpoint; no checked-in private or public fixture file is required.

### Secret-free CI coverage

[Offline CI](../.github/workflows/ci.yml) runs on pushes, pull requests and manual dispatch with
Node 24, JDK 25, pnpm 11.1.3 and Docker. Its three bounded jobs run default Maven verification
(including synthetic Stripe adapter/Cucumber fixtures), SDK and Admin tests/typechecks/builds,
Admin lint, demo return-unit tests/build, native Node synthetic support tests, the separate
offline Chromium return/privacy suite and all infrastructure Node tests. Dependencies use frozen
lockfiles; fresh backend jobs create their own ignored local issuer fixture. No repository secrets
or private env files are needed. Traces, screenshots, video and artifact uploads are disabled.

The browser job uses only `playwright.offline.config.ts`; it does not run full Fake backend
acceptance, which requires the separately owned Stage 4 clock/runtime harness described above.
Real Stripe Sandbox/live acceptance, listener setup, financial operations, deployment and package
publication remain outside ordinary CI. A local checkpoint does not prove that GitHub-hosted
execution has passed; the workflow must run remotely to establish that result.

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

For default local startup on Linux or macOS, use the [local Compose launcher](../infrastructure/local-compose.mjs). It adds a local-only overlay changing only the backend user to the current process's POSIX UID/GID, allowing it to read the host-owned mode0600 issuer fixture without changing the host file or its permissions. It derives IDs rather than trusting inherited `PAYMENT_LOCAL_UID`/`PAYMENT_LOCAL_GID`, and selects the repository's `.env`, base Compose and local user overlay from any working directory. Use your ordinary non-root user without sudo; root and Windows are unsupported. The default provider remains Fake. `up` builds, detaches and waits for readiness:

```bash
node infrastructure/local-compose.mjs up
docker compose --env-file infrastructure/.env -f infrastructure/compose.yaml ps
```

Admin: http://localhost:3000. API: http://localhost:8080. Demo: http://localhost:3001. Readiness includes database connectivity; an unavailable callback destination does not make the backend unready.

## Limited form-load diagnostic (offline implementation; separate launch approval)

The separate `playwright.form-diagnostic.config.ts` / `tests/form-load-diagnostic.spec.ts`
entry observes one genuine SDK widget load, not payment acceptance. It is excluded
from ordinary Playwright discovery. The diagnostic demo is built only in Vite mode
`form-diagnostic` (Docker build arg `PAYMENT_DEMO_BUILD_MODE=form-diagnostic`);
the normal demo entry and default production build are unchanged. Its constructor
and internal observer share the SDK source graph; foreign instrumentation fails closed.

All three opt-ins must be exactly `true`: `PAYMENT_STRIPE_SANDBOX_ENABLED`,
`PAYMENT_STRIPE_SANDBOX_FORM_DIAGNOSTIC`, `PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY`.
Full-run/allow-test-operations and diagnostic flags cannot be mixed; debugging is
rejected before private/runtime access. Do not enable them as part of ordinary builds.
No actual diagnostic run is authorized by its source, tests, commit or this guide.

A future separately authorized attempt owns fresh resources and one original
15-minute active deadline, with cleanup capped at that original deadline +2 minutes.
Cold preparation is limited to one Product/Price, anonymous EUR20 ONE_TIME/NONE
Checkout/Intent and the first client-session Attempt. There is no CLI login/listener,
card entry, Pay, confirmation, retry, refunds, campaigns, OTP delivery or reconciliation.
Private test keys stay in ignored `infrastructure/stripe-sandbox.env.local`; the mode
does not print them or change the user's webhook secret. A private unregistered
verifier can satisfy backend configuration; it is not a listener/delivery proof.
Bounded `/v1/account` checks the secret's approved account/default API version without
Connect/version overrides; it does not establish publishable-key/account pairing.

The driver clicks Resume once, observes at most60 seconds (15-second checkpoint)
within the original active window, and emits at most two strict finite frames.
The diagnostic-only wire format is version3, a breaking change: the reporter
rejects version1/version2, mixed versions and incomplete frames. Historical evidence stays
in its original format. Internal SDK samples remain version1. Each wire frame is
at most4096 UTF-8 bytes and contains only validated stage/proof fields, ten
finite environment fields and `observation_end`; an opaque failure without a
fresh complete sample has no stage fields.

Before navigation, independent collectors observe CSP violations of the owned
top-level document and resource events of its owned page. A post-navigation
baseline retains available environment evidence even when SDK bootstrap is absent.
Resume requires a valid handshake and both sources OBSERVED with SAMPLED evidence;
partial instrumentation ends without creating a client session.

`csp_enforced_mask` and `csp_report_only_mask` use SCRIPT=1, FRAME=2, CONNECT=4,
OTHER=8. Script directives include `script-src`, `script-src-elem`, `script-src-attr`;
frame directives include `frame-src` and `child-src`. Report-only bits record
reports, not enforced blocking. This collector does not inspect CSP within a
cross-origin iframe or prove the complete applied policy.

`resource_transport_failure_mask`, `resource_http_4xx_mask` and
`resource_http_5xx_mask` are separate observations: SCRIPT=1, DOCUMENT=2,
FETCH_XHR=4, OTHER=8. They cover events available for the whole owned page,
including its subframes and navigation before Resume. For active failed fetch/xhr
only, the collector reads URL and failure once each, synchronously, and keeps
only the latest same-event pair of finite categories. No raw metadata is retained
or printed. Script/document/other failures and HTTP responses do not read these
metadata. Both exact canonical demo/backend loopback origins are validated and
frozen before browser launch.

`fetch_failure_destination` is OWN_APP for either exact owned origin, STRIPE_API
for HTTPS `api.stripe.com` on the default port, STRIPE_JS for HTTPS `js.stripe.com`
or its subdomains on the default port, OTHER for other valid HTTP(S) destinations,
and UNKNOWN for invalid/unreadable metadata. `fetch_failure_code` uses only exact
Chromium error strings to yield ABORTED, NAME_NOT_RESOLVED, CONNECTION_REFUSED,
CONNECTION_RESET, TIMED_OUT, CERTIFICATE, BLOCKED_BY_CLIENT, GENERIC_FAILED or
UNKNOWN. A failure reading one category leaves the other independently available.
GENERIC_FAILED does not establish CORS, CSP, a Stripe fault or a bad key.

The pair is NONE/NONE when an OBSERVED source has no FETCH_XHR transport bit,
and UNAVAILABLE/UNAVAILABLE when the resource source is unavailable. UNKNOWN
means the relevant metadata could not be classified. Later failures replace both
categories together; earlier frozen checkpoints retain their original pair.
This is not a request history, request count, causal diagnosis, iframe attribution,
Stripe ownership assertion for OTHER, or publishable-key/account pairing check.

`csp_observation` and `resource_observation` are OBSERVED only when that collector
is installed and its snapshot is available. Zero masks mean no recorded bit at
the snapshot, not a healthy environment. UNAVAILABLE source masks are zero.
`environment_freshness` is SAMPLED for a composite observation within the original
window, CACHED for retained evidence after failure or the local limit, and
UNAVAILABLE when both sources are unavailable. The composite is not an atomic
causal measurement. A checkpoint owns its environment evidence; later masks do
not rewrite it. Cached/partial evidence cannot establish an ordinary stage or
readiness. Nonzero masks do not cancel actual proved readiness or override a
primary operation/control/financial/cleanup failure. NONE/UNKNOWN and zero masks
do not prove a healthy network.

`observation_end` describes why observation ended independently of final `result`
and cleanup/financial proofs: FORM_READY, SDK_TERMINAL, LOCAL_LIMIT, ABORTED,
ACTIVE_DEADLINE, INSTRUMENTATION_UNAVAILABLE or BROWSER_OPERATION_FAILED.
BROWSER_NOT_STARTED means the parent never called browser observation (for
example, preparation failed). The first pending checkpoint uses OBSERVING;
the final frame uses the actual end reason. LOCAL_LIMIT distinguishes the original
60-second window from an opaque failed browser action/read. A later financial,
active-deadline or closure failure can make `result` fail while retaining
FORM_READY as the historical observation reason. No category extends a deadline,
adds browser operations or grants a new launch authority.

Node reads and resource callbacks stop at abort, the local limit, the original
deadline or teardown. The CSP listener's private bounded bits can continue until
the original deadline or document teardown; no subsequent page read or report
publication uses them. This does not claim external-operation cancellation.
These collectors and their synthetic offline tests do not authorize runtime
validation. A future prepare-only Sandbox attempt still needs separate approval
for its fresh provider writes. Without new positive evidence, the cause remains
UNKNOWN; zero masks do not localize the cause to Stripe or justify CSP changes.

If no complete observation can settle before the strict local ceiling, the last
finite flags may be retained as AT_LIMIT with an opaque operation failure; they
are not a fresh pending-state or readiness proof at/after60 seconds. A delayed
visibility response cannot retroactively count as readiness before15 seconds.
`FRAME` is only an outer iframe-presence observation. `WAITING_ELEMENT_READY` and
`WAITING_PRESENTATION_CATCHUP` identify distinct pending boundaries, not a verified
network/CSP/provider cause. Snapshot flags cannot replace visible+enabled Pay proof.
Confirmation is latched off in the actual view and Stripe handle even after observer
closure. No provider events, frame contents, input, URLs, errors, trace, screenshot,
video, HAR or console payloads are collected in the diagnostic output.

Exit0 requires FORM_READY_BEFORE_15S/FORM_READY_AFTER_15S **and** independent no-charge,
browser/context closure and owned-runtime stop proofs. Other results require manual
inspection, never an automatic repeat. Financial/cleanup uncertainty is failure;
ready does not mean payment succeeded or Stage5A accepted. Retain all containers,
volumes, networks, provider objects, data and audit evidence. This implementation
does not reinterpret historical failed attempts or their unconfirmed cleanup.

Cleanup starts the single owned context-close attempt before browser closure and
allows independent completion strictly before a grace cutoff of at most five
seconds, shortened to reserve ten seconds for attempting browser closure within
the original cleanup ceiling. Rejection, expiry or no remaining grace latches
context cleanup uncertainty and triggers browser fallback. Late fulfillment or
browser-driven context closure cannot restore that independent proof; the grace
and reserve do not extend the ceiling or guarantee successful closure. A context
already closing or closed when its owned attempt dispatches remains unconfirmed;
its no-op close cannot establish independent closure.

## Stripe Sandbox configuration (Stage 5A preparation)

The recovery helper `infrastructure/stripe-sandbox-bootstrap.mjs` exports
`createOwnedSandboxBootstrap(options, dependencies)` without an entrypoint. An operator
supplies the approved fresh lowercase run ID, exact committed source SHA, pinned Unix
Docker daemon, six distinct loopback ports, account/API binding and both explicit opt-ins.
It never generates or adopts a run ID. Its private run tree is
`test-results/stripe-sandbox/<runId>`; generated credentials, issuer keys and runtime
evidence stay there. It preserves existing local configuration and resources.

For a separately approved BUILD-only diagnosis, call the same inert factory's
`buildOnly({deadline, signal})` once. Inputs are explicit public values:
`{runId, expectedSource, daemon, ports:{backend,demo,receiver,postgres,mailpitHttp,mailpitSmtp},
approvedBinding:{externalAccountId:'acct_1UPiMpELswAd8tpR',apiVersion:'2026-09-30.endive'},
env:{PAYMENT_STRIPE_SANDBOX_ENABLED:'true',PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS:'true'}}`.
Use a fresh lowercase UUID, a clean checkout at the exact committed source SHA, the
approved local Unix daemon, six distinct available loopback ports in 1024–65535,
and the original finite absolute Unix-millisecond deadline; optional `signal` is an
AbortSignal. The fixed binding is public fixture identity, with no provider validation.
Do not supply keys, home configuration or inherited process environment. Construction
performs no I/O; admission reuses the credential-free source/daemon/port checks.

R3 Docker dispatches keep outer `--config /private/var/empty` and explicitly set
`DOCKER_CONFIG=/private/var/empty` for nested plugins plus
`BUILDX_CONFIG=<owned-run>/buildx`. The run and its exclusively created Buildx directory
are canonical, nonsymlink, current-UID, mode 0700 directories; the Buildx directory is
synced before dispatch and its original identity is proved again before every R3 Docker
command, including the build boundary. PATH/language are fixed; HOME, global Docker
auth/config, Stripe, DEBUG and proxy environment are never inherited. R1 retains its
separate reviewed empty-config arguments.

After admission and absence checks, `buildOnly` dispatches exactly one synthetic
backend+demo-site Compose build, then proves both resulting images. It stops before
Postgres/Mailpit/Node pulls, random credentials, issuer/JWK/Admin generation, service
creation/start, private Stripe env reads, CLI/login/listener, provisioning, browser,
mail, provider, financial or reconcile work. The existing 20-minute build command
ceiling is clipped to the original remaining deadline; output is bounded to 64 KiB,
and a callback alone cannot settle the operation before actual child close.

The call is terminal for this object/run on success, failure or uncertainty. Success
returns `true` with status `BUILD_ONLY_COMPLETE`, no webhook/stack/financial readiness,
and a safe `buildDiagnostic` projection in `resources()` and mode-0600 `runtime.json`.
Its fixed stages distinguish admission/private preparation/build command/backend image
proof/demo image proof/final recording; results are RUNNING/SUCCEEDED/FAILED/UNCONFIRMED.
Categories are NONE/UNKNOWN/OUTPUT_LIMIT/TIMEOUT/ABORT/NONZERO_EXIT/SPAWN_FAILURE/
INVALID_IMAGE_PROOF, optionally with an integer exit code. No raw error, captured
output or configuration text is retained in this projection. Unknown dependency
rejections stay UNKNOWN; this does not establish the original failed BUILD's cause.
If recording, cancellation or child close cannot be confirmed, retain PENDING/manual
inspection status; a last RUNNING ledger is unconfirmed evidence. Final uncertainty
is recorded only within the original remaining budget, never by extending it.

BUILD-only completion is decided at the final durable ledger record's last deadline/
cancellation check, after its directory fsync. That check must pass on the original
budget, then synchronously marks the diagnostic complete. No further filesystem or
child I/O follows. A clock tick or cancellation after this confirmed boundary does
not reverse the successful API result or the matching SUCCEEDED ledger; only this
completed diagnostic skips the generic later post-body check. Expiry/cancellation
before that boundary still refuses completion without renewing the deadline. Other
bootstrap operations retain their existing post-body check.

Only the held owner.lock handle is closed; its file, build.env, runtime evidence,
Buildx state, images and any cache effects remain. Subsequent build/prepare/start/
persist/forward/Admin/verify/stop calls dispatch no I/O; there is no retry, adoption,
cleanup or automatic promotion to a financial run. A successful BUILD-only diagnosis
is not full Sandbox acceptance and supplies no authorization for a later run.

The controlled invocation order is `prepare({signal, deadline})`, then construct the
existing CLI adapter from `resources().cliDirectory`, `verifyForwardTarget` and
`persistWebhookSecret`. The root operator separately performs the approved login,
`validateBinding`, and `startListener`; only then call bootstrap `start` and `verify`
with their own finite absolute Unix-millisecond deadlines. The two CLI callbacks and
`withAdminCredentials(callback)` retain the original preparation deadline and signal,
including the later human login budget; expiry never extends the run. The admin callback
receives only `{username, password}`, requires verified live ownership, and must return
exactly `true` after its separately bounded work. Bootstrap performs no authentication,
provisioning, browser or financial actions.

Before startup, the forwarding callback proves the complete recorded stopped topology
and a currently free backend port. This is planned ownership evidence, not a socket
reservation or readiness; the port can change after the check. The placeholder backend
never starts. A never-started container may have an empty operational network ID only
with the exact configured network name/mode and a fresh full owned-network proof.
After the listener secret is durably stored in both the fixed private
Stripe env file and final run env, one verified pre-live replacement installs that env.
Live verification then requires the final running IDs, images, network, mounts, health
and all six exact `127.0.0.1` bindings, including both Mailpit ports. Same-user socket
impersonation is not solved by point-in-time checks.

Every uncertain result latches `PENDING`; do not repeat preparation, replace services,
rotate a conflicting webhook secret or retry financial work. Stop the exact CLI listener
first, then call bootstrap `stop({signal, deadline})`. Cleanup stops only recorded,
freshly verified containers and preserves containers, volumes, network and private audit
files. It never uses Compose down, prune, default Docker configuration or global stops.
Synthetic tests establish local behavior only. The [dated acceptance matrix](stage-5a-acceptance.md)
records the completed separately authorized runtime evidence and current technical acceptance;
that decision grants no new runtime or financial authority.

An operation failure keeps bootstrap resources and the durable ledger `PENDING` even
when `stop` subsequently returns `true`. That status retains the failure latch and
never permits another start or verify. The pure `priorRuntimeInventory` helper checks
parsed metadata against an exact trusted run/source/daemon/project, expected
`PENDING` or `STOPPED` status, role set and full container IDs. Its frozen records
identify unverified resources; they prove neither stopped state nor closure or READY.
An unrelated `PENDING` row still fails a `STOPPED` expectation. Live admission still
requires process absence, every exact-ID state/PID/ownership check, private-file and
capacity checks, and native preflight. The 2026-10-27 correction was tested offline
only; current runtime state remains unverified and a new attempt needs separate
explicit authorization with a fresh candidate/source.

The separate `playwright.stripe-sandbox.config.ts` retains its manual three-card workflow
unless `PAYMENT_STRIPE_SANDBOX_OWNED_RUN=true`. The owned mode uses
`openOwnedSandboxAcceptance({env,runId,expectedSource,daemon,ports}, {deadline,signal})`
in `beforeAll`, before browser creation. After independent review, the root operator
must supply both existing opt-ins, that owned-mode flag, a fresh
`PAYMENT_STRIPE_SANDBOX_RUN_ID`, final committed `PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE`,
the approved Unix `PAYMENT_STRIPE_SANDBOX_DAEMON`, and six distinct approved port values:
`PAYMENT_STRIPE_SANDBOX_PORT_BACKEND`, `_DEMO`, `_RECEIVER`, `_POSTGRES`, `_MAILPITHTTP`,
`_MAILPITSMTP` (each suffix has the same `PAYMENT_STRIPE_SANDBOX_PORT` prefix).
Then invoke `pnpm exec playwright test --config playwright.stripe-sandbox.config.ts`
from `examples/demo-site`. Do not enable DEBUG, NODE_DEBUG or PWDEBUG.

One safe terminal handoff displays the genuine Stripe authorization URL and code for
human completion; the parent executes only the fixed isolated CLI descriptors. The
initial `login` descriptor allows up to three minutes locally; `login --complete-device`
allows up to ten minutes. Both waits are clipped to the original remaining setup
deadline. The longer completion wait does not extend Stripe's authorization-code
lifetime or approve another attempt after failure or expiry. The executor owns the
per-child timer and abort controller; the competing native process timeout is disabled.
It requires both the process callback and actual child close before confirming completion.
Missing acknowledgement is bounded by a ten-second grace and leaves cleanup unconfirmed;
late callbacks or closes cannot restore that proof. Timers and parent abort listeners
are disposed on settlement. An unclosed uncertain child retains only its owned error
drain and close handler until actual close, then removes both.
The original 45-minute budget includes build, human authorization, setup and all journeys;
later stages cannot renew it. Up to two further minutes allow cleanup only. The sequence
is three original card journeys, their existing EUR 7.00 + 13.00 refunds, anonymous and
EMAIL_OTP EUR 5.00 donations, then the original fourth EUR 20.00 missed-webhook journey.
Financial success requires protected server proof and confirmed owned cleanup. Any
unknown outcome stops later writes and retains evidence; never rerun or change keys.
The browser uses genuine SDK requests and forwards donation create once with its durable
original operation key. SDK browser cancellation/32 KiB bounds and enclosing context
termination remain distinct from the parent’s fresh-ownership/10-second/64 KiB Node
transport bounds. Browser artifacts remain off. Demo output is `examples/demo-site/test-results`;
durable owned evidence remains repository-root `test-results/stripe-sandbox/<runId>`.

After an admitted owned run fails, the parent emits at most one bounded diagnostic after
cleanup. The fixed reporter displays only the first failed phase, an allowlisted failure
code, a proven child exit code (1–255) or `none`, and `cleanup=confirmed|unconfirmed`.
Unknown dependency failures remain `OPERATION_FAILED`; callback/spawn failures remain
`PROCESS_FAILED` unless actual close arguments or the owned abort/deadline prove more.
A termination signal alone is `PROCESS_SIGNALLED`, never evidence of a timeout.
`PROCESS_TIMEOUT` is emitted only when the executor's own per-child timer fires while
the child is still open. It has exit `none` and no browser fields. Original deadline
expiry or abort takes precedence over this local timeout. Foreign error fields and
elapsed-clock guesses cannot establish timeout provenance; existing proven nonzero
close diagnostics retain their established behavior outside the local-timeout path.
Raw errors, process output, arguments, paths, browser URLs and private fields are excluded
from diagnostics. The separate public authorization handoff still appears immediately.
Donation browser failures use `browser_case=anonymous|emailOtp` only in `DONATIONS`.
The finite steps distinguish `DONATION_DISPATCH` (original create/forwarding),
`DONATION_RESPONSE` (response receipt and complete validation), and `DONATION_ACK`
(the safe acknowledgement). `DONATION_SETUP`, `DONATION_PAYMENT` and
`DONATION_CLOSE_CONTEXT` identify failures outside creation. The first trusted failure
survives bounded waits, forwarding rejection and rejecting cleanup. These labels do not
identify an HTTP/backend cause or establish whether a dispatched write took effect.
Historical cause remains unknown: persisted `OPEN`/`DISPATCHING` and an in-memory
`PENDING` result cannot resolve it. Retain that uncertainty; do not clear `DISPATCHING`,
replay the write, substitute a partial response or ACK it. Offline synthetic verification
does not certify a live correction or authorize a new Sandbox run.
Before each original/retry card fill, the driver waits separately for an enabled visible
Pay action (`PAYMENT_FORM_READY`), the existing exact iframe (`CARD_FRAME`) and its visible
number field (`CARD_NUMBER_READY`); retry labels use `RETRY_`. Each condition is bounded
at 15 seconds and does not renew the original run deadline. Resume's click only starts
asynchronous mounting; it is not provider-readiness proof. A failed condition stops later
field writes, Pay and subsequent journeys. UI readiness is never financial confirmation.
These labels distinguish form readiness, frame visibility, field visibility and the
existing `CARD_NUMBER` fill/actionability failure without exposing DOM, URLs or values.
Selectors and card values remain unchanged. Offline tests do not establish Stripe's
current iframe DOM or uniquely explain an earlier Sandbox failure; real acceptance still
requires a separately approved run, with no automatic retry of retained operations.
Only a failed original/retry `PAYMENT_FORM_READY` may additionally report three finite
observations: `widget_state` (the public SDK state or `UNAVAILABLE`), `element_state`
(`NO_HOST`, `EMPTY`, `FRAME`, `UNAVAILABLE`) and `pay_state` (`ABSENT`, `HIDDEN`,
`DISABLED`, `ENABLED`, `UNAVAILABLE`). The one best-effort snapshot is capped at one second,
does not renew the original deadline, and cannot replace the primary failure if its own
read fails, returns malformed data, or finishes late. Successful gates collect nothing.
It reads only SDK-owned DOM presence and native button flags, never markup/text, URLs,
frame contents, input values, provider responses or error objects. `ENABLED` does not
prove CSS visibility, current readiness, or payment; this is a snapshot after failure.
For example, `loading/FRAME/ABSENT` means an outer iframe exists while Pay is unavailable;
it does not prove whether the driver's `ready` wait is still pending or a presentation
change during initialization is awaiting its post-ready catch-up. Both paths can produce
that snapshot in offline characterization tests. The observation itself does not record
either event, so it cannot select between them in an actual failed run. `FRAME` means any
iframe under the owned host, not proof of the titled card frame or accessible card inputs.
`checking/EMPTY/ABSENT` is compatible with multiple initialization failures, not a unique
loader/client-session/provider diagnosis. Earlier failures cannot be reconstructed from it.
No production SDK/backend/payment behavior or existing 15-second readiness wait is changed.
Malformed, duplicate, oversized or unfinished diagnostics produce the fixed
`sandbox_diagnostic_failed_manual_check` marker and force final failure; a valid diagnostic
also forces failure. Successful runs emit no diagnostic. These observations neither
establish account/payment success nor authorize a retry, and do not diagnose historical
failures. No new Sandbox execution is authorized by this instrumentation.

Recognized card-driver failures may append the literal pair `browser_case` and
`browser_step`, for example `sandbox_diagnostic phase=CARDS code=OPERATION_FAILED
exit=none cleanup=unconfirmed browser_case=success browser_step=NAVIGATE`.
Only `CARDS` (success/decline/sca) or `MISSED` (success) operation failures carry
this pair. The first recognized step survives cleanup failures; deadlines and
aborts retain their original classification. Legacy diagnostics keep their exact
format. Browser exceptions remain opaque, failed journeys keep cleanup unconfirmed,
and this observation supplies no financial proof or cause for a historical failure.

The optional Sandbox overlay prepares backend configuration; configuration alone is not provider acceptance. The [Stage 5A matrix](stage-5a-acceptance.md) records the accepted one-time adapter evidence. The default Compose file continues to use Fake without Stripe keys; the overlay also retains `PAYMENT_PROVIDER=fake` while enabling the separate Sandbox marker and `local` profile. Fake subscriptions continue to work. Stripe Billing paths are implemented but [Stage 5B remains unaccepted](stage-5b-acceptance.md), with actual Billing provider scenarios NOT_RUN. This setup never enables live payments, Connect, Managed Payments, or business activation for real money.

Create the separate private file from the project root:

```bash
node infrastructure/init-stripe-sandbox-env.mjs
```

The initializer prints only `created` or `preserved`, creates new files exclusively with mode `0600`, and preserves existing regular files byte for byte. Symlinks and other file types are rejected. If an existing file has broader permissions, it warns without changing them; restrict permissions to `0600` before adding or loading keys. The destination is fixed relative to the initializer, independent of the current directory. This command never reads or changes `infrastructure/.env`.

Fill in `infrastructure/stripe-sandbox.env.local` using a local editor: `PAYMENT_STRIPE_SANDBOX_SECRET_KEY` (Sandbox secret key), `PAYMENT_STRIPE_SANDBOX_PUBLISHABLE_KEY` (Sandbox publishable key), and `PAYMENT_STRIPE_SANDBOX_WEBHOOK_SECRET` (Sandbox webhook signing secret). Use only test credentials from the intended Stripe Sandbox. Never send values in chat, commit the file, or expose keys through shell tracing, arguments, URLs, browser code, logs, or reports. The file is ignored by Git and excluded from every Docker build context, along with local issuer fixtures; example templates remain publishable.

After the adapter is available, explicitly opt in by supplying both environment files and the overlay. Preserve your existing base configuration:

```bash
docker compose --env-file infrastructure/.env --env-file infrastructure/stripe-sandbox.env.local -f infrastructure/compose.yaml -f infrastructure/compose.stripe-sandbox.yaml config --quiet
```

All three Sandbox values are required and passed only to the backend; Admin and demo receive none. `config --quiet` validates without printing an expanded configuration. Do not run plain `config` with private credentials. Starting the Sandbox stack or executing Stripe smoke requires the separately reviewed adapter and an explicit opt-in with agreed test fixtures.

## Manual Stripe form diagnostic (implementation only)

This mode is implemented and verified offline only. No Sandbox launch, Docker/Stripe
operation or real browser attempt is authorized by this guide, its tests or a commit.
The original Sandbox missing-ready cause remains **UNKNOWN**; this mode does not
establish Stage 5A acceptance. A future attempt requires separate explicit permission,
fresh source/ownership checks and one fresh anonymous EUR 20.00 ONE_TIME/NONE fixture:
one unconfirmed Intent and at most one first Attempt. Zero Attempts can prove no
confirmation but cannot prove form readiness. There is no automatic repeat, retry,
reconciliation, refund, replacement port or reuse of an earlier fixture.

The offline packaging regression is in
`examples/demo-site/tests/support/form-load-manual-build.test.ts`. It stages the demo
Dockerfile's build-stage COPY inputs with the current `.dockerignore`, links only
already installed dependencies, rebuilds the staged SDK and runs real TypeScript/Vite
checks for both build modes. It does not run Docker, install dependencies, start a
server or contact Stripe; it proves the isolated source inputs, not an actual image
build or form readiness. Only the five required public diagnostic module/declaration
files are copied from `infrastructure`, never private local inputs or the whole folder.

Catalog and Donation now mount Stripe's provider container as a child of their own
widget in document Light DOM, projected through a named slot into the existing
shadow wrapper. Fake keeps its shadow container. A widget embedded in another
ShadowRoot rejects Stripe mounting before requesting a provider capability; global
portals are not supported. Internal generation ownership, rather than class names
or slot attributes, selects diagnostic geometry and frames. Removing/replacing the
owned host, slot, or wrapper permanently loses the diagnostic lease, including a
same-turn remove/reinsert. Pay is still read only inside that widget's ShadowRoot.

`examples/demo-site/tests/support/provider-mount-browser.test.ts` characterizes this
DOM contract in installed headless Chromium using locally bundled SDK bytes and
synthetic in-process page/API/provider responses. All other requests and WebSockets
are denied, service workers blocked, and no browser artifacts are collected. It
checks actual slot projection, outer geometry, native iframe load, independent
Catalog/Donation cleanup and the Fake path. Run it from the repository root with
the installed Node: `node --test examples/demo-site/tests/support/provider-mount-browser.test.ts`.
This is offline DOM compatibility evidence, not real Stripe ready or Sandbox
acceptance. A provider iframe and its native load do not establish rendered fields;
the coherent provider-ready/SDK/Pay checks and immutable confirmation deny remain.

Only after that future approval, the exact command template from the repository root
is below. Values are placeholders, not commands to execute now. Use a fresh UUID, the
final reviewed commit SHA, the approved local Unix Docker daemon, six distinct approved
free loopback ports and a seventh distinct approved gateway port (1024–65535). Keep
provider/admin credentials inside the existing private runtime boundary; none belong
in this command, terminal output or chat. No positional or optional CLI arguments are
accepted. The direct CLI copies only allowlisted raw environment fields into its
strict manual admission record.

```bash
env -i PATH="<APPROVED_NODE_AND_SYSTEM_PATH>" LANG=C LC_ALL=C NO_COLOR=1 \
  PAYMENT_STRIPE_SANDBOX_ENABLED=true \
  PAYMENT_STRIPE_SANDBOX_MANUAL_FORM_DIAGNOSTIC=true \
  PAYMENT_STRIPE_SANDBOX_ALLOW_PREPARE_ONLY=true \
  PAYMENT_STRIPE_SANDBOX_RUN_ID="<FRESH_UUID>" \
  PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE="<REVIEWED_COMMIT_SHA>" \
  PAYMENT_STRIPE_SANDBOX_DAEMON="<APPROVED_UNIX_DAEMON>" \
  PAYMENT_STRIPE_SANDBOX_PORT_BACKEND="<BACKEND_PORT>" \
  PAYMENT_STRIPE_SANDBOX_PORT_DEMO="<DEMO_PORT>" \
  PAYMENT_STRIPE_SANDBOX_PORT_RECEIVER="<RECEIVER_PORT>" \
  PAYMENT_STRIPE_SANDBOX_PORT_POSTGRES="<POSTGRES_PORT>" \
  PAYMENT_STRIPE_SANDBOX_PORT_MAILPITHTTP="<MAILPIT_HTTP_PORT>" \
  PAYMENT_STRIPE_SANDBOX_PORT_MAILPITSMTP="<MAILPIT_SMTP_PORT>" \
  PAYMENT_STRIPE_SANDBOX_PORT_MANUAL="<GATEWAY_PORT>" \
  node infrastructure/stripe-manual-form.mjs
```

Automatic FORM_DIAGNOSTIC, full-run, owned-run and allow-test-operations modes are
incompatible. DEBUG, NODE_DEBUG, PWDEBUG and PW_TRACE must remain disabled. One original
15-minute active deadline starts at entry; cleanup is bounded by that deadline plus
two minutes. New manual starts stop two minutes before the active deadline. Observation
ends at the earlier of the original session grant plus 60 seconds and the active
deadline minus 60 seconds; waiting, errors and cleanup never renew these limits.

Final output uses only the remainder of that original cleanup ceiling. Rejected
admission gets at most one second for its failure report, without runtime authority.
An interrupt or stalled output gives exit 1 and removes the invocation's signal
subscriptions. The direct command then exits its own Node process; a blocked stdout
may leave the sole report incomplete or missing. Output is never retried, and a printed
report alone is not successful completion: check the exit status too. Imported CLI
calls return without exiting the host process or destroying its shared stdout; a
pending write remains observed for late errors and is not claimed to have flushed.

After approval and successful preparation, stdout prints only the fixed
`http://127.0.0.1:<GATEWAY_PORT>/form-load-manual.html` URL and one finite JSON report.
The URL contains no capability, query or fragment. Open it once in an ordinary browser,
without an extension or DevTools. Click **«Загрузить форму» exactly once**. Do not enter
card details, press Pay, reload, open another tab or retry. The page says
«Ручная проверка — без оплаты» and warns «Не вводите карту и не нажимайте Pay. Не
обновляйте страницу». If ready is reported, inspect only whether the real fields are
visible; nothing should be typed. Early ready keeps the widget mounted until the
original observation cutoff. Afterwards, close only this tab and separately tell the
operator whether the fields were visible. No console/Network/HAR/trace/iframe payload
or screenshot collection is requested.

The ready status explicitly distinguishes the diagnostic snapshot from the current
display: «Диагностический снимок отражает этот момент и больше не обновляется.
Проверьте, видны ли поля сейчас; ничего не вводите». The snapshot is taken at the
first coherent SDK-ready report, after the provider event and the SDK view/widget
handoff, not necessarily at the exact instant of the raw Stripe `ready` event.
Evidence collection then closes, while the widget remains mounted for human
inspection until the original cutoff. A later style change does not rewrite that
snapshot or send another observation. Thus `frame_visibility=HIDDEN` can coexist
with fields seen later, and `VISIBLE` is not a promise that they remain visible.
The message describes local capture, not acknowledgement of the report POST.

The report is version 2, mode `MANUAL_FORM_DIAGNOSTIC`, at most 4096 UTF-8 bytes. Manual
observations are exactly `{version:2,sample,evidence}`; the nested SDK sample remains
version 1. Legacy/manual mixed formats, duplicate decoded keys at any depth, unknown
fields, and partial evidence are rejected. Automated diagnostic formats are unchanged.
The final report exposes `observation`, `end`, a finite `sample` and `evidence` (both
null when no sample was accepted), and `no_confirmation`, `gateway_closed`,
`runtime_closed`. Its provenance is always `BROWSER_REPORTED`; `browser_closure` is
always `NOT_OWNED`. `READY_REPORTED` requires a coherent SDK sample and visible/enabled
Pay; an iframe alone, no report or an AT_LIMIT sample cannot establish readiness. A
historical ready sample can remain alongside `end=FAILED`. Exit 0 requires
`end=READY_REPORTED` and all three positive owned proofs, and means only that this
bounded experiment completed. It is no payment-success, independent browser or
environment proof. Unknown financial facts or unconfirmed owned cleanup give exit 1.

Manual evidence contains only finite categories. `loaderstart_seen` records the SDK's
owned Stripe loader event; it does not establish `ready`. `host_box` and `frame_box`
are `ABSENT`, `ZERO_AREA`, `POSITIVE_AREA`, or `UNAVAILABLE`; only the frame may also
be `MULTIPLE`. Dimensions are discarded immediately. Host/frame visibility is
`ABSENT`, `VISIBLE`, `HIDDEN`, or `UNAVAILABLE`: `VISIBLE` means only the checked
layout/style conditions, including the assigned slot, shadow wrapper and widget
ancestors. `HIDDEN` records a checked hiding condition at capture, not a pixel-level
verdict about the cross-origin contents or the later screen. The precise CSS
predicate/property and its ancestor are not retained; a category alone cannot
establish a particular Stripe animation or rendering cause. Multiple frames
have unavailable visibility and load evidence. `frame_load` is `NO_FRAME`, `NOT_SEEN`,
`SEEN`, or `UNAVAILABLE`, applies only to the current single iframe, and resets when
that frame changes. A missed/unknown load does not establish failure.

`csp_observation` is `OBSERVED` while the owned violation listener is available, or
`UNAVAILABLE` with zero masks. `csp_enforced_mask` and `csp_report_only_mask` separately
combine script=1, frame/child=2, connect=4, and other directives=8, using only trusted
events' directive and disposition. No blocked URL, source, sample, frame URL/document,
resource timing, or cross-origin contents are inspected. Evidence collection starts
at the single Start before session exchange and stops on SDK ready, page close,
abort, or the original deadline. Missing observations remain unknown. Active DOM
changes and trusted CSP/load events refresh the finite cache;
the deadline uses its last active snapshot. An already sent 15-second checkpoint
stays unchanged; only the next permitted terminal/limit observation uses newer evidence.
No extra observation or additional time is granted. Unavailable evidence remains
unknown and cannot upgrade readiness or authorize another attempt. This change
does not fix Stripe rendering or prove that Stripe card fields rendered. Browser
evidence always remains `BROWSER_REPORTED`.

The controller closes gateway admission, attempts exactly one financial read under
the original remaining authority, then closes only its verified owned runtime. Failed
prepare/open/wait/output/close paths still attempt that read while authority remains.
Late acquisitions and unfinished closes remain owned and cannot produce positive
closure until they settle. The external browser is not process-owned; no automatic
browser-closure claim is made. Containers are stopped only through their verified
ownership boundary; networks, volumes, database data and provider objects are retained.
Any failure ends the attempt and requires review plus new permission before a new run.

## Admin, SDK and payment journeys

### Stripe inspection and return recovery

Admin **Provider operations** lists bounded unresolved operations separately from dead provider-reference receipts (which may have no local Payment). A catalog acknowledgement still needs the existing audited catalog publication action. Manual recovery requires the original provider reference and creation-event proof; acceptance is a binding acknowledgement, never settlement. After an uncertain response, inspect and retry the same proof with the retained key. No new PaymentIntent or Refund is created by recovery.

Site integration now provisions exact checkout return URLs with optimistic versions and audit. Register the demo's exact origin and `/payment-return` URL; initial creation uses version `-1`, then read the current version before editing. Configure Sandbox Site/catalog/Donation fixtures on the backend and pass only public API/Site/Price/Campaign keys to the demo. No `VITE_*` Stripe key is used.

The return entry clears all query/fragment parameters before modules, sends no referrer and has no access logging. A redirect cannot identify a saved checkout, so this page makes no automatic backend request and shows no automatic success. **Check previously saved catalog payment** explicitly reads the original saved checkout and labels it as saved catalog state, which may be unrelated to this return. Anonymous Donation authority is memory-only and cannot be recovered from Stripe URL parameters; use login/history where available. Public recovery configuration storage is optional and never contains Stripe secrets.

Customer/guest demo Stripe creation hands the same checkout to the existing widget's explicit Resume/ready Pay flow. If recovery storage failed, the demo shows an existing-session-unavailable message and does not create another checkout. Fake direct confirmation is unchanged.

The demo CSP allows card-only Stripe.js and 3DS origins from the [official Stripe security guide](https://docs.stripe.com/security/guide#content-security-policy), exact SDK style hashes and one exact cleanup-script hash. Connections allow only self, `api.stripe.com` and the existing localhost/127.0.0.1 API ports; cloud deployment must explicitly add its trusted API origin. Arbitrary HTTPS API origins are no longer allowed. No Link, Maps, Connect, Checkout, crypto or unsafe-inline/eval permissions are added. From `examples/demo-site`, `pnpm build` followed by `pnpm exec playwright test --config playwright.offline.config.ts` runs an owned ephemeral nginx/browser policy test with external requests intercepted. This proves offline browser/policy behavior, not real Stripe interoperability; real Sandbox smoke still needs explicit opt-in.

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

The owned Stage 5A parent can import `createSandboxAcceptanceExtras` from
`infrastructure/stripe-sandbox-extras.mjs` after reviewed bootstrap and provisioning
are live. Pass `{env,bootstrap,provisioner,cli}`, then `open({signal,deadline})` with
an explicit future Unix-millisecond deadline and both Sandbox flags exactly `true`.
Donation create/Pay and OTP challenge/verify dispatch handoffs must precede their
single genuine browser actions; ACK only the observed safe response projections.
`missedFixture(limits)` supplies only the original fourth `success` checkout to the
existing card driver, retaining that finite deadline; its `refunds()` is unsupported.
Close that fixture and the extras capability after confirmation. Errors retain
private `extras.json`/`extras.lock` evidence and require manual review; never retry
with another key, payment, recipient or listener. This library has no standalone
runtime entrypoint. Its offline check is
`node --test infrastructure/stripe-sandbox-extras.test.mjs`; real invocation still
requires the independently reviewed parent and root's final runtime release.

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

## One manual Stripe Sandbox payment

The separate `infrastructure/stripe-manual-payment.mjs` entry prepares one fresh,
anonymous CATALOG payment for EUR20, tax NONE, ONE_TIME, CARD. It requires all offline
gates, independent task and whole-change review, a clean committed source and explicit
authorization for one fresh run. It is a bounded manual experiment; its report does
not establish full Stage 5A acceptance or live readiness. Imports do not start a run.

Before that single invocation, verify the reviewed SHA, canonical owned Unix Docker
socket, disk/network capacity, previous owned closures and seven distinct free loopback
ports in 1024–65535. Use the existing isolated recovery worktree and a fresh lowercase
UUID. Source, ownership, identity, secret conflict or port failures stop the attempt;
preserve the resources and evidence for inspection. Do not delete resources, change
ports, reuse fixtures, renew an expired run or start an automatic replacement.

This is an invocation template, with placeholders only. Provider and Admin credentials
stay in the existing ignored private configuration boundary and never belong in this
command, argv, URLs, output, chat or Git. There are zero positional or optional CLI
arguments. The entry snapshots only these allowlisted raw environment fields.

```bash
env -i PATH="<APPROVED_NODE_AND_SYSTEM_PATH>" LANG=C LC_ALL=C NO_COLOR=1 \
  PAYMENT_STRIPE_SANDBOX_ENABLED=true \
  PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS=true \
  PAYMENT_STRIPE_SANDBOX_MANUAL_PAYMENT=true \
  PAYMENT_STRIPE_SANDBOX_RUN_ID="<FRESH_UUID>" \
  PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE="<REVIEWED_COMMIT_SHA>" \
  PAYMENT_STRIPE_SANDBOX_DAEMON="<APPROVED_UNIX_DAEMON>" \
  PAYMENT_STRIPE_SANDBOX_PORT_BACKEND="<BACKEND_PORT>" \
  PAYMENT_STRIPE_SANDBOX_PORT_DEMO="<DEMO_PORT>" \
  PAYMENT_STRIPE_SANDBOX_PORT_RECEIVER="<RECEIVER_PORT>" \
  PAYMENT_STRIPE_SANDBOX_PORT_POSTGRES="<POSTGRES_PORT>" \
  PAYMENT_STRIPE_SANDBOX_PORT_MAILPITHTTP="<MAILPIT_HTTP_PORT>" \
  PAYMENT_STRIPE_SANDBOX_PORT_MAILPITSMTP="<MAILPIT_SMTP_PORT>" \
  PAYMENT_STRIPE_SANDBOX_PORT_MANUAL="<GATEWAY_PORT>" \
  node infrastructure/stripe-manual-payment.mjs
```

MANUAL_REFUND, FULL_RUN, OWNED_RUN, FORM_DIAGNOSTIC, MANUAL_FORM_DIAGNOSTIC and ALLOW_PREPARE_ONLY
are incompatible. DEBUG, NODE_DEBUG, PWDEBUG and PW_TRACE must remain disabled. The
binding is fixed to account `acct_1UPiMpELswAd8tpR`, API `2026-09-30.endive`, CLI
`1.52.0` and test mode. A genuine isolated CLI authorization URL and device code may
be printed first: use only that exact handoff, then keep the original terminal open.
An expired code is a stopped attempt, never permission to run another login. No
global credentials, Keychain, live flag or arbitrary `next_step` command is used.

Completion permits at most one exact stdout line from pinned CLI `1.52.0`:
`Warning: the system keyring is unavailable. Your credentials have been stored unencrypted in <ROOT>/test-results/stripe-sandbox/<RUN_ID>/cli/config/stripe/credentials.json`.
`<ROOT>` is this checkout's fixed root and `<RUN_ID>` is the original invocation's
UUID. Raw type, size and control checks run first; both channels together remain
bounded to 65536 bytes. Other warning paths, extra text on that line, duplicates,
stderr warnings and login-init JSON receive no exception. Remaining output still
passes the credential-marker scan. This warning never proves authorization or
account identity: callback success, actual child close exit 0 and independent
account binding remain required. Offline synthetic coverage does not establish
that this warning was the sole cause of a prior failure or that a live run succeeds.

If the initial manual-payment preflight refuses admission, the direct entry attempts
one stderr JSON line after existing cleanup attempts and before the unchanged final
stdout report. It contains only `tag="stripe_manual_payment_preflight_diagnostic"`,
`phase` and `code`, at most 256 UTF-8 bytes. Exact known PENDING pairs retain
OPT_IN/OPT_IN_REQUIRED, INPUTS/CONFIGURATION, SOURCE/SOURCE_MISMATCH,
DAEMON/DAEMON_UNCONFIRMED or PORTS/PORT_UNAVAILABLE. A malformed/unrecognized reply,
exception or controller timeout maps to UNKNOWN/UNKNOWN without inspecting error
properties or printing the reply. There are no raw logs, paths, IDs, keys or other
private values. A READY preflight and any later failure emit no initial-preflight
diagnostic. Admission still requires exactly the original single-field READY reply;
refusal cannot create the bootstrap, CLI, listener, fixtures or payment permission.
In this early-refusal report, closure=true may mean the capability was never acquired,
not that an existing runtime was inspected or stopped. The diagnostic gets at most
one second clipped to the original cleanup ceiling; failed/stalled output cannot
renew authority, retry or suppress the one final report attempt. An absent diagnostic
is not proof that preflight passed. Historical PREPARE reports without it remain
unclassified, even if an earlier standalone check was READY.

If authorization fails, the direct entry attempts one stderr JSON line after its
independent cleanup attempts and before the unchanged final report. Its only fields
are `tag="stripe_manual_payment_auth_diagnostic"`, `stage` and `reason`; it is at
most 256 UTF-8 bytes. Stages are LOGIN, HANDOFF, COMPLETE or UNKNOWN. Reasons are
DESCRIPTOR_INVALID, PROCESS_FAILED, CHILD_CLOSE_UNCONFIRMED, TIMEOUT, ABORTED,
OUTPUT_INVALID, OUTPUT_UNSAFE, HANDOFF_INVALID, HANDOFF_FAILED or INTERNAL.
Unknown errors map to UNKNOWN/INTERNAL. The diagnostic uses only module-owned finite
classifications, never raw output, error messages/stacks, paths, account identifiers,
credentials or device codes. Successful authorization and later binding failures
produce no authorization diagnostic. Emission has at most one second, clipped to
the original cleanup ceiling; a failed or stalled write remains PENDING/exit 1 and
cannot renew authority, retry authorization or enable financial work.

The original active deadline is 45 minutes from entry, including build and human CLI
authorization; cleanup ends at that same deadline plus two minutes. Login has at most
three minutes, completion ten minutes, and actual child-close proof ten seconds of
grace, all under the original authority. The page has at most five minutes, clipped
to Checkout expiry and the active deadline minus 90 seconds; a fresh page allowance
requires the full five minutes plus 90 seconds to remain. Financial verification uses
at most 30 probes in 45 seconds, HTTP requests at most ten seconds, clipped to that
original active deadline. Waiting, failure and cleanup never renew these windows.

For a future, separately authorized human-operated 3DS experiment, add
`PAYMENT_STRIPE_SANDBOX_MANUAL_3DS=true` to the same invocation template. It requires
all three existing opt-ins above; absent, empty or `false` keeps ordinary manual
payment, and any other value is rejected. This is offline preparation only: no new
runtime, authorization URL/code, Checkout or payment has been created or authorized
by these instructions. A new run requires fresh explicit runtime/financial authority
after the offline gates and review.

The controller selects 3DS through the protected session response's exact
`X-Payment-Scenario: 3DS` header. There is no browser URL, storage or form selector.
Before loading, the page gives neutral instructions; after session selection it
shows the appropriate card. An unexpected present scenario header stops before
widget/lease mounting with the existing fixed `SESSION_INVALID` code.

After preparation the terminal prints the actual fixed
`http://127.0.0.1:<GATEWAY_PORT>/manual-payment.html` URL once. Copy and paste that URL
into an ordinary human-owned Chrome tab. Open it once and click «Загрузить форму»
once. The page warns: «Только Stripe Sandbox. Один тестовый платёж €20. Реальную карту
не вводите». Enter the official Stripe test card `4242 4242 4242 4242`, a future expiry
date and any three-digit CVC, then press Pay exactly once. Do not use a real card,
reload, reopen, retry, correct the card after a failed Pay, or resume after an error.
Stop and report the observation to the operator. No browser automation, iframe/card
inspection, screenshots, console/Network/HAR/trace collection or secret screenshots
are part of this workflow. Close this tab afterwards and report that human observation
separately; the controller always reports `browser_closure=NOT_OWNED`.

For selected 3DS, follow the page's official test card `4000 0025 0000 3155`, future
expiry and synthetic three-digit CVC instead. Press Pay once, then promptly complete
the genuine Stripe challenge yourself. The provider widget stays visible and
connected while the challenge is pending, checking, processing or requiring action;
the page retires it on a terminal result, post-attempt ready, expiry or close. No
challenge click or iframe/card inspection is automated. The existing server
verification window starts after the one permit and
still ends within 45 seconds/30 probes. Complete the human challenge within that
window; the five-minute page allowance does not extend it. A late, failed or unknown
outcome stays `PENDING` with no retry. Do not press Pay again, reopen, or rerun to
resolve it; any future attempt needs new explicit authority.

A repeated action-required snapshot can make the SDK show `ready` again. After the
one Pay the selected page removes the form once and shows the existing neutral
message that the operator checks the final result in the server report. This does
not claim browser success, permit another confirmation or restart polling. Initial
ready still permits the sole Pay. The server report remains authoritative. Selected
server verification accepts the bound action-required journal/callback history without dropping its unique success and
delivered success-callback requirements.

If the form cannot load, the existing terminal error appends one fixed code:
`SESSION_HTTP_FORBIDDEN` (session HTTP 403), `SESSION_HTTP_GONE` (410),
`SESSION_HTTP_ERROR` (another non-success HTTP response), `SESSION_HTTP_UNAVAILABLE`
(session fetch/body rejection or HTTP timeout), `SESSION_JSON_INVALID` (JSON parsing),
`SESSION_INVALID` (session size, record or validation), `WIDGET_SETUP_FAILED`
(widget registration, construction or configuration), `AUTHORIZATION_BINDING_FAILED`
(one-shot lease arming), or `WIDGET_MOUNT_FAILED` (observer, append or initial
inspection). Report only that UI observation to the operator and stop. These codes
are local stage hints, not proof of a provider/network cause, financial permission,
payment outcome or server report evidence. They expose no additional Pay result.
The previous Chrome load failure's cause remains unproven. Do not collect cookies,
headers, bodies, raw errors, DevTools output or other browser data for this diagnostic.
These offline changes do not authorize another live attempt; the existing one-shot
and deadline instructions still apply.

Permission is committed durably before the one Stripe confirmation. A browser success
message is only a browser observation. The final JSON report (at most 4096 bytes)
independently records confirmation permission, original server payment, original
provider Intent/Event, webhook, journal, signed callback and owned cleanup evidence.
Only `result=SUCCEEDED`, every proof/cleanup boolean true and process exit 0 establish
this single successful experiment. `NOT_STARTED` requires both original unused server
and provider proofs and exits 1. False means unverified, not proof that payment did
not happen. Any failed/unknown operation latches `PENDING` and exits 1; later unused
reads or browser messages cannot turn it green. Keep the report and original evidence
for manual review. Do not issue refunds, cancel, reconcile, create another Checkout,
or rerun the entry to resolve a pending result.

For a future separately authorized invocation, a failure of granted verification
may add one stderr JSON line after existing independent cleanup/drain and before
the sole final stdout report. Its exact fields are `tag`, `stage` and `reason`,
with tag `stripe_manual_payment_verify_diagnostic`; it is at most 256 UTF-8 bytes.
Actual provisioner failures carry immutable module-owned stages `ADMISSION`,
`STATUS_POLL` or `SERVER_PROOF`. A rejected exact server reply is `SERVER_REPLY`;
a rejected or thrown provider inspection is `PROVIDER`. Unbranded errors and
unclassified outer timeouts/rejections fall back to `UNKNOWN` without reading
error properties. The reason is `UNCONFIRMED`, except that exhaustion of the
existing thirty-probe loop gives `PROBE_LIMIT` with its last actual stage.
Neither reason establishes a precise timeout or a financial/provider outcome.
There are no IDs, paths, amounts, counters, credentials, raw replies or errors.

The first classified failure can arrive while the original pending work drains;
finalization prevents later settlement from changing the diagnostic or report.
Success, unused/no-grant verification, earlier failures and cleanup-only failure
after accepted full verification produce no verification diagnostic. Emission
gets at most one second clipped to the original cleanup ceiling and rechecks
that ceiling before dispatch. Rejected, stalled or expired output cannot suppress
the sole final report attempt or authorize a retry. An absent line does not prove
verification succeeded. Every existing authority, one-shot permission, proof gate,
deadline and report field remains unchanged; this diagnostic authorizes no run.

Ordinary reports retain `mode=MANUAL_PAYMENT`. Only selected 3DS reports use
`mode=MANUAL_3DS` and add `three_ds_verified`. Their `SUCCEEDED` result requires all
the same server payment, incoming signed provider event, journal, outbox/callback,
capture and cleanup proofs, plus `three_ds_verified=true`. That additional proof
comes from the same captured charge's 3DS result `authenticated`, flow `challenge`
and a valid `2.x.x` version, read through the existing exact Intent/Event inspection.
Missing, malformed, frictionless or failed 3DS evidence is unverified and cannot be
promoted to success. Browser success alone never supplies any of these proofs.
This mode alone does not complete the wider 5A lifecycle. The separately successful
manual 3DS run and the current aggregated technical acceptance, including completed
independent reviews, the separate publication gate and `browser_closure=NOT_OWNED`, are recorded in the
[dated acceptance matrix](stage-5a-acceptance.md).

The controller closes/drains its gateway and provisioner, stops its listener/children,
then stops only freshly verified owned runtime containers. Containers, networks,
volumes, provider objects and audit evidence are retained. The sole final report write
uses only the original remaining cleanup ceiling; rejected admission gets at most one
second. Signal, stalled stdout or write failure gives exit 1 without a second output
attempt. The direct entry exits only its own process, so a timed-out write can leave
an incomplete or missing report; check the actual exit code as well as printed text.

## Separate one-shot remainder of the retained Sandbox refund

Actual remainder Sandbox acceptance is **PENDING**. This is a future invocation
template, not a development prerequisite or runtime permission. The approved design
and offline implementation do not authorize a real attempt. A separate authorization
must name restoration/listener startup, one callback rotation expecting version3,
and one EUR13 refund of the fixed retained payment.

The previous EUR7 acknowledgement, server delivery and later read-only confirmation
are historical evidence. Its old PENDING report, original `manual-refund.lock`, state,
and all retained artifacts remain unchanged. The new mode freshly proves the exact
prior EUR7 Refund and delivery before any mutation; it cannot recreate its old HMAC
receipt or repeat its callback. Only the new EUR13 callback receives a new signature
proof. There are no Chrome, browser, card, Checkout or new-payment steps.

After separate runtime approval, substitute only the approved restricted Node/system
PATH, fresh lowercase UUID, reviewed clean controller HEAD and canonical owned Unix
Docker socket. Do not derive a new source SHA or UUID during execution. Supply exactly
the three opt-ins below and zero arguments. Do not supply keys, target IDs, amounts,
ports, URLs or credential paths:

```bash
env -i PATH="<APPROVED_NODE_AND_SYSTEM_PATH>" LANG=C LC_ALL=C NO_COLOR=1 \
  PAYMENT_STRIPE_SANDBOX_ENABLED=true \
  PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS=true \
  PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND_REMAINDER=true \
  PAYMENT_STRIPE_SANDBOX_RUN_ID="<FRESH_REMAINDER_UUID>" \
  PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE="<REVIEWED_CLEAN_CONTROLLER_HEAD>" \
  PAYMENT_STRIPE_SANDBOX_DAEMON="<CANONICAL_OWNED_UNIX_DAEMON>" \
  node infrastructure/stripe-manual-refund-remainder.mjs
```

Every old mode and debug flag is incompatible; all old entries reject the remainder
flag before normalization. The dedicated entry reuses only the prior unused financial
remainder key. Its new exclusive `manual-refund-remainder.lock` remains sticky after
success or uncertain admission, and another UUID does not grant a second attempt.
There is no automatic repeat, retry, reset, reconcile, cancellation, resource deletion
or cleanup deletion of either lock. A false report boolean means unverified, not that
money did not move.

Only the original isolated CLI credentials are used. Missing/expired credentials,
Account/API/version mismatch or a changed retained verifier stops before rotation and
refund. Any new OAuth authorization needs separate approval. The listener verifier is
compared in memory, never printed or replaced. After fresh server/provider baseline
proof, the sole rotation's response must prove version3 and the retained callback
endpoint/enabled state. These checks happen after the write: a mismatch may mean the
rotation already occurred, and permanently leaves PENDING with zero refund POSTs.
There is no rotation retry or repair. The returned secret configures only the exact
owned receiver in memory. Exactly one EUR13 POST can follow durable rotation/configure
acknowledgement, repeated baseline checks and the synchronous live-listener fence.

One active authority lasts at most 30 minutes from this entry; independent cleanup
ends at that same deadline plus 2 minutes. No late factory, response, body disposal or
child callback renews it. Session, listener, restored runtime and state cleanup are
checked independently; actual child/process settlement is required. Session work
unresponsive at the fixed active deadline plus 1 minute permits only last-resort
runtime shutdown with PENDING and `runtime_closed=false`. In-flight restore/start
must settle before shutdown. Docker stop grace10s has at most20s transport within the
same cleanup ceiling. Issuer helpers are never restarted. All resources and audit
evidence are retained.

The one final JSON line is bounded to4096 bytes. It separately records freshly
verified prior partial/delivery and new provider/webhook/journal/signature/aggregate
proofs. Only `SUCCEEDED`, all twelve booleans true, and actual exit0 establish this
experiment's success; they do not declare all Stage5A complete. The first copied
finite diagnostic and PENDING are sticky. Failed or stalled stdout yields actual
exit1 without a second output attempt, even if a success line was already printed.

## Separate manual refund of the retained Sandbox payment

Runtime acceptance is **PENDING**. The following is only a future invocation template;
offline tests and review do not authorize execution. After all offline gates and fresh
independent review, separately authorize one bounded attempt to restore the original
paid run, rotate its Site callback key once, refund EUR7, and refund EUR13 only after
independent confirmation of the first refund. Use the existing recovery worktree and
a clean, reviewed controller commit. Imports of `infrastructure/stripe-manual-refund.mjs`
are inert.

The target is fixed internally to the retained successful manual-payment run and its
original source, Site, Account, Payment, Intent and Price. Its recorded six ports and
CLI credential directory are derived from retained evidence. They are not operator
inputs. The new UUID and controller SHA establish one new authority; they do not renew
the original payment attempt. No browser, Chrome, card entry, new payment, Checkout or
PaymentIntent is involved. No backend or Stripe key belongs in this command.

After separate runtime consent, replace only these placeholders with the approved Node
path, fresh lowercase UUID, reviewed clean controller SHA and canonical owned Unix
Docker socket. There are zero positional or optional arguments and exactly three opt-ins:

```bash
env -i PATH="<APPROVED_NODE_AND_SYSTEM_PATH>" LANG=C LC_ALL=C NO_COLOR=1 \
  PAYMENT_STRIPE_SANDBOX_ENABLED=true \
  PAYMENT_STRIPE_SANDBOX_ALLOW_TEST_OPERATIONS=true \
  PAYMENT_STRIPE_SANDBOX_MANUAL_REFUND=true \
  PAYMENT_STRIPE_SANDBOX_RUN_ID="<FRESH_REFUND_UUID>" \
  PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE="<REVIEWED_CLEAN_CONTROLLER_SHA>" \
  PAYMENT_STRIPE_SANDBOX_DAEMON="<CANONICAL_OWNED_UNIX_DAEMON>" \
  node infrastructure/stripe-manual-refund.mjs
```

MANUAL_PAYMENT, FULL_RUN, OWNED_RUN, FORM_DIAGNOSTIC, MANUAL_FORM_DIAGNOSTIC and
ALLOW_PREPARE_ONLY are incompatible. Leave DEBUG, NODE_DEBUG, PWDEBUG and PW_TRACE
disabled. The old modes also reject MANUAL_REFUND. No target IDs, amounts, ports,
endpoint URLs, credential paths or secrets can be supplied to this entry.

Only the retained isolated CLI authorization is used. Missing or expired credentials,
wrong account/API/CLI version, mismatched retained webhook verifier, changed topology,
occupied original ports or nonzero prior refunds stop the attempt before rotation or
refunds. There is no automatic login or OAuth handoff. Any later CLI authorization
needs a separate decision. The listener secret is compared in memory against the
retained backend verifier; it is never printed, replaced or persisted by this entry.

After the fresh baseline, exactly one rotation affects only the original Site. Its
response must preserve the original callback URL/enabled state and produce version2.
The returned plaintext goes only to the verified owned receiver in memory. Unknown
rotation/configure outcomes, changed configuration or invalid replies permanently
produce PENDING and permit zero refund POSTs. There is no rotation retry or rollback.

The first EUR7 refund needs independent provider, webhook, journal and signed callback
proof before the EUR13 POST is allowed. Lost responses, timeouts, 5xx, aborted work or
uncertain durable commits permanently stop progression. Do not retry an unknown
dispatch with the same/new key, rerun with a fresh UUID, reconcile, cancel or delete
anything to resolve it. Existing state or the original-target lock rejects replay.
A read-only investigation of PENDING requires a separate operator decision.

One active deadline is at most 30 minutes from entry; cleanup authority ends at that
original deadline plus 2 minutes. There is no renewed authority. The controller aborts
active work and waits for actual HTTP/session work, including late session acquisition
and response-body disposal, before stopping runtime. A settled session failure or a
failed listener close still allows the independent runtime stop attempt. The HTTP
settlement cutoff is fixed at the original active deadline plus 1 minute, reserving
the final minute of the same cleanup ceiling for shutdown; an early error does not
move this cutoff. Only work still unresponsive at that cutoff permits last-resort
shutdown with HTTP work unsettled. That fallback permanently reports PENDING, exit 1
and `runtime_closed=false`, even if containers later stop. An in-flight runtime
restore/start must still settle before shutdown can begin. Shutdown targets only exact
freshly verified ordinary containers restored by this controller, and no cleanup or
financial authority is renewed after the original ceiling.
Issuer helpers are not restarted. Containers, images,
volumes, networks, credentials, provider objects, state and audit history are retained;
an uncertain original-target lock remains for inspection. No resource deletion or
recreation is part of this workflow.

The entry attempts exactly one final JSON line, at most 4096 bytes, with no target IDs,
PII, secrets or raw errors. Only `result=SUCCEEDED`, every report boolean `true`, and
the actual process exit 0 confirm the experiment. A `false` boolean means unverified;
it never proves that no money moved. PENDING remains latched after any unknown work,
even if later reads or cleanup succeed. Stalled/failed stdout gives actual exit 1
without a second report attempt; a printed success line without exit 0 is insufficient.

The offline proof-observer correction permits only an exact forward transition between
separate detail/list reads for the acknowledged leg. Pending reservations already reduce
availability: the EUR7 leg moves from `(SUCCEEDED, refunded=0, available=1300)` to
`(PARTIALLY_REFUNDED, refunded=700, available=1300)`; the EUR13 leg moves from
`(PARTIALLY_REFUNDED, refunded=700, available=0)` to `(REFUNDED, refunded=2000, available=0)`.
All immutable projection facts must agree. Such a straddled read proves nothing by itself;
a fresh coherent probe and every existing provider/webhook/journal/callback check are
still required within the original 30-probe/45-second budget. Terminal refunds, changed
identities/reasons, impossible money, regressions and reconciliation-only success fail
closed. The observer never repeats a refund POST or rotation.

An unconfirmed report may include the optional copied `diagnostic` object with exactly
`stage`, `reason`, and integer `probes` (0–30). Stage is one of BASELINE, ROTATION,
DISPATCH, ACKNOWLEDGEMENT, DETAIL, CALLBACK, PROVIDER, CONFIRMATION, FINAL, CLEANUP;
reason is INTEGRITY, UNCONFIRMED, EXHAUSTED, ABORTED, or DEADLINE. The first session
failure survives cleanup. Diagnostics contain only these static values, certify no
financial proof, and identify a future observed boundary rather than the cause of any
historical attempt. Legacy version-1 reports without diagnostics remain valid.

## Stop and persisted data

```bash
node infrastructure/local-compose.mjs stop
```

This stops the default local project while retaining its containers, network and payment-stage-1_payment-postgres volume. For separately owned smoke/Sandbox harness runs, follow their explicit ownership and stop instructions above. The project name intentionally remains payment-stage-1 for in-place V4 migration and prior data. PostgreSQL 18 mounts /var/lib/postgresql and uses version-aware PGDATA beneath it. Never use down --volumes or volume removal to solve port conflicts. A destructive database reset erases all payment/catalog/admin/journal data and requires explicit authorization and a recoverable backup; it is not part of ordinary stop or this verification.

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

## Offline initial manual-payment checks — 2026-10-26

Artificial regressions using the actual manual bootstrap and provisioner reproduced redundant full credential verification and missing propagation of the existing credential child's cancellation. Manual payments now retain the initial full ownership proof and provider binding, check the captured topology immediately before and inside credential acquisition, and let the credential capability perform its own complete ownership verification once. Both original and child cancellation apply. Synthetic timing proves this bounded composition change; the historical PROVISION failure's exact boundary and host latency remain unproven, and sufficiently slow initial ownership can still time out.

For a future separately authorized manual-payment invocation, construction or preparation failure may add one stderr JSON line after independent cleanup and before the unchanged final stdout report: `{"tag":"stripe_manual_payment_provision_diagnostic","stage":"OWNERSHIP"}`. The only stages are ADMISSION, OWNERSHIP, PROVIDER_BINDING, ADMIN_CREDENTIALS, PRIVATE_STORAGE, ADMIN_SESSION, FIXTURE_SETUP, RETURN_POLICY_READ, RETURN_POLICY_PROOF, PRODUCT_DISPATCH, CHECKOUT, FINAL_BASELINE and UNKNOWN. Named stages identify module-owned preparation boundaries: PRIVATE_STORAGE covers directory proof, lock and initial save; ADMIN_SESSION covers dispatch, login, cookies and acknowledgement; FIXTURE_SETUP covers the initial empty baseline through catalog/callback setup except the three narrow substeps. RETURN_POLICY_READ covers only the post-PUT policy reread, including its ownership/request/decoding; RETURN_POLICY_PROOF covers only the existing policy validation; PRODUCT_DISPATCH covers only the product's durable intent dispatch/save. After successful product dispatch the marker resets to FIXTURE_SETUP before product HTTP, proof and acknowledgement. CHECKOUT covers the single checkout mutation and proof; FINAL_BASELINE covers remaining baseline checks, save and handoff construction. These markers do not inspect dependency errors, and nonmanual failures remain unbranded. Unbranded failures, malformed replies at the controller boundary and controller timeouts retain UNKNOWN. The line contains only tag and stage, at most 256 UTF-8 bytes, with no reason, timing, paths, IDs, error text or credentials. Successful preparation, authorization failure and later binding/financial failures add no provision diagnostic. Existing authorization diagnostics remain separate.

Historical PROVISION/UNKNOWN does not establish successful credentials or any precise failure boundary: the earlier provisioner reset its marker to UNKNOWN after credentials, and UNKNOWN is also the unbranded/controller fallback. The offline marker correction neither reclassifies historical evidence nor proves that it explains a historical runtime failure.

The later separately authorized attempt `75456a71-342e-4549-a5b2-b7dfd605ade3` on source `398ee98fbb70c86727d3301da8e38cc4690518ea` exited 1/PENDING at PROVISION with only FIXTURE_SETUP classified. No gateway was emitted; confirmation and every financial/3DS proof were false, while the three controller closure flags were true. A bounded ledger projection found admin-session, account, site-and-email-provider and return-url ACKNOWLEDGED, with no persisted product DISPATCHING intent. This narrows the unclassified range to the post-PUT policy GET, existing policy validation or next product durable dispatch; it does not identify the cause. Independent inspection of actual backend record serialization with cached Jackson 3.1.5 and SNAKE_CASE matched the expected origin/return_url/version ordering, so ordering is not supported as that attempt's cause. The three new markers close this reporting gap only; no equality predicate, request, timeout or retry changed. The historical report stays unchanged. At that checkpoint Stage 5A was NOT_ACCEPTED; the [dated acceptance matrix](stage-5a-acceptance.md) records the later evidence and current gates.

The controller also retains the existing once-only bootstrap stop's directly observed literal-true settlement time. If its 30-second close slice expires but that same stop fulfills true strictly before the original cleanup ceiling while the existing final work drain runs, the final immutable report can record `runtime_closed=true`. False, rejection, unsettled work and true observed at or after the ceiling cannot supply that proof; fulfillment after the report never changes it. The failure remains latched as PENDING/exit 1, with no new stop, wait, retry or authority extension. Gateway and listener closure rules are unchanged. Persisted `STOPPED` or exited-container metadata alone is insufficient: bootstrap lock closure and its final deadline check follow the manifest write. This source correction does not rewrite historical reports or prove their runtime cause.

Diagnostic output gets at most one second clipped to the original cleanup ceiling; failure or stall leaves exit 1/PENDING and cannot postpone independent cleanup or authorize another attempt. Report version/shape, source/account/API/ownership/signed-journal/callback/capture gates, one Pay/permit, ten-second child/HTTP, 45-minute authority, 45-second/30-probe proof, five-minute page and original plus two-minute cleanup limits remain unchanged. This offline correction ran no runtime/provider operation and authorizes none; historical reports remain intact. Stage 5A was NOT_ACCEPTED at that checkpoint; see the [dated acceptance matrix](stage-5a-acceptance.md) for the current evidence decision and gates.

## Stage 5B Billing implementation and acceptance checkpoint

**Stage 5B is NOT_ACCEPTED.** The [current acceptance matrix](stage-5b-acceptance.md) distinguishes implemented/offline-tested capabilities, observed hosted results and actual provider NOT_RUN gates. The SDK manager now supports memory-only PAYMENT/SETUP client sessions and visible ready Stripe Elements. It reads the exact original operation/action even when CREATE returns PROCESSING with `action_available=false`; explicit Pay consumes the grant, and only its winning response-bound permission allows one browser confirmation. Polling, replay, lost ACK and UNKNOWN never reopen it. Preparation failure may be retried only by explicit Refresh of the same unspent operation. Fresh v2 synchronous-decline proof permits one explicit successor and separate Pay; native decline/successor acceptance remains NOT_RUN.

Billing actions are default-disabled and require a dedicated versioned backend key outside repository tooling. The [client-session guide](stripe-billing-client-sessions.md#disabled-safe-dedicated-key-configuration) describes the exact key configuration and UTC grant boundary. Use the [current SDK guide](../frontend/sdk/README.md#stripe-billing-forms-and-portal) for PAYMENT/SETUP, authority/expiry teardown, constrained real Portal parsing and return behavior. Portal creation/navigation and return do not prove payment, cancellation or entitlement; current backend review/hosted gates are recorded in the matrix.

Task 12’s eight Billing Cucumber scenarios and combined v2 source passed exact-head `152ea79121fb14742be25e8a2f7e1fade06f2dcd`, CI `40855668800`: backend 3563/0 failures/0 errors/1 existing skip, Cucumber 87/676 steps, SDK 892/32 files, Admin 239/36, demo 9/3, support 401, return 7, default Fake 61/61 and infrastructure 2120. The initial-only owner/browser/proof slice subsequently passed exact head `4b604b89d8a4f05690015dd6e69458fe84617423`, [CI 40877692992](https://github.com/viktar-mar/payment/actions/runs/40877692992), completed SUCCESS: backend 3564/0 failures/0 errors/1 existing skip, Cucumber 87, shared SQL 1/1, SDK 892/32, Admin 239/36, demo 10/3, support 417, return 7, default Fake 61/61 and infrastructure 2220. The matrix retains cancelled `e518`/40872292196 (2220 infrastructure, 1 failed allowlist assertion) and `660`/40873611776 (backend/infrastructure passed, Fake 60/61 failed CDP body assertion, fixed by `4b604b8`). Native Stripe TestClock provenance, assignment/readiness and provider/event timestamp interpretation remain unimplemented; the Stage 4 launcher cannot supply them by clock injection. The newer finite billing/UTC prerequisite is PREPARED and needs a separate exact-head hosted gate. Native Billing initial/decline/manual 3DS, trials, renewal/grace/recovery, changes and Portal acceptance remain NOT_RUN. The prepared initial-only entry below covers no clock-driven case. The matrix records the required source, review, hosted and separately authorized runtime gates without extending earlier 5A authority or touching its retained evidence.

### Prepared initial Billing entry — separate runtime authorization required

Native status is **NOT_RUN**; scope is **INITIAL_NON_CLOCK**, and full 5B is **NOT_ACCEPTED**. After separate authorization and exact-head hosted verification, the dedicated entry from `examples/demo-site` is `pnpm exec playwright test --config=playwright.stripe-billing.config.ts`. This documentation is not permission to launch it.

Supply `PAYMENT_STRIPE_SANDBOX_ENABLED=true` and `PAYMENT_STRIPE_BILLING_ACCEPTANCE=true`, a fresh lowercase UUID in `PAYMENT_STRIPE_SANDBOX_RUN_ID`, the clean checked-out 40-character SHA in `PAYMENT_STRIPE_SANDBOX_EXPECTED_SOURCE`, the canonical owned Unix socket in `PAYMENT_STRIPE_SANDBOX_DAEMON`, and six distinct loopback ports in `PAYMENT_STRIPE_SANDBOX_PORT_BACKEND`, `_DEMO`, `_RECEIVER`, `_POSTGRES`, `_MAILPITHTTP`, `_MAILPITSMTP`. Existing full/remaining/manual/refund/diagnostic and ALLOW_TEST_OPERATIONS modes must be absent or false; debug/trace flags must be off. Approved binding is fixed to `acct_1UPiMpELswAd8tpR` / `2026-09-30.endive`. Do not supply old provider IDs, keys, fixture files or recovery state.

Concrete adapters wire the existing bootstrap/CLI, fresh admin endpoints, real OTP/Elements, scoped read-only SQL, exact provider reads and signed canonical callback digests. Construction is inert; run is single-use. One worker, zero retries and no screenshot/video/trace are enforced. The SDK’s original fresh keys are fsynced before CREATE and confirm continue unchanged. One explicit Pay is the financial budget; UNKNOWN/lost ACK cannot reopen it. Finite independent cleanup stops exact owned resources and retains volumes. Reports contain fixed phases, scope/acceptance booleans and public run/source identities, never credentials, raw responses or browser error details.

The owner retains its 45-minute lifetime/financial authority. The dedicated test allows 48 minutes, including 120 seconds for independent cleanup and 60 seconds for setup/scheduling; the global runner ceiling is 50 minutes. This headroom grants no additional financial attempt or owner lifetime.

Preparation's intended local boundary was injected Node contracts, pure frontend tests and typecheck/build. An accidental local Vite runtime batch attempted standalone, embedded and preview child tests: all **3 failed**, alongside **7 passing pure tests**. The original failed log is retained; child cleanup is **not independently confirmed**. There was no local retry or runtime investigation. The later `4b604b8` hosted Vite/runtime gate completed **SUCCESS**, with shared SQL **1/1**; that result does not independently confirm cleanup of the earlier local child processes.

See the [acceptance matrix](stage-5b-acceptance.md#prepared-initial-only-native-entry) for proof requirements and the hosted-only SQL schema test. Actual initial success, decline/manual 3DS and remaining native cases need separate evidence. No Docker, SQL, full Java application, Stripe or native Billing browser execution occurred during preparation.

### Prepared scoped billing-time prerequisite

The [finite billing-time spec](superpowers/specs/2026-10-29-billing-time-domains.md) adds an exact account/Site/Customer sample with explicit ordinary/isolated mode. Production returns the existing Clock instant unchanged. No native activation, registration, configuration, HTTP route or Stage 4 bean-list extension exists. Only the dedicated hosted test configuration supplies a +35-day billing instant while operational/security clocks stay UTC. The isolated slice covers unchanged-term INITIAL/RENEWAL paid/unpaid projection, exact reads and exact grace expiry; trial/change/cancel/paused/resume and other command proof shapes are refused. Exact views suppress financial actions and list/offers/global deadline guards execute before SQL.

Local verification is restricted to audited exact pure-JUnit classes and cached offline Maven compilation. The new real SQL/application integration fixture is authored and compiled only; execution belongs to the future exact-head hosted gate. It checks lifecycle occurrence/access versus UTC audit/enqueue, both callback families, lease/retry/signature independence, foreign owners and representative session/capability expiry. It does not prove complete JWT/OTP/quote boundaries, provider timestamps or any native Billing outcome. Status is **PREPARED**, native **NOT_RUN**, full 5B **NOT_ACCEPTED**; the completed `4b604b8` receipt cannot substitute for this newer gate.
