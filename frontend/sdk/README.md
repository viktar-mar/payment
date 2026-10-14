# Generic Payments browser SDK

Private local ESM package, version 0.1.0. Build with `pnpm --filter @generic-payments/sdk build` from `frontend/`. Serve the complete `dist/` directory, retaining relative `.js` imports; the runtime has no framework or provider SDK dependency. There is no automatic Web Component registration.

```html
<generic-payment-widget site-key="test-site" price-key="lifetime"
  api-base-url="http://localhost:8080" locale="en"></generic-payment-widget>
<script type="module">
  import { defineGenericPaymentWidget } from './sdk/dist/index.js';
  defineGenericPaymentWidget();
  document.querySelector('generic-payment-widget').addEventListener('succeeded', event => {
    // Update UI using event.detail.paymentId and event.detail.status.
    // Fulfillment still requires a trusted backend confirmation.
    document.querySelector('#payment-message').textContent = 'Payment received';
  });
</script>
<p id="payment-message"></p>
```

Bundler users can import from `@generic-payments/sdk`. Concrete imperative construction:

```js
import { createGenericPaymentClient, recoverCheckout, PaymentSdkError } from '@generic-payments/sdk';

const identity = { apiBaseUrl: 'https://payments.example', siteKey: 'my-shop', priceKey: 'lifetime' };
const controller = new AbortController();
const client = createGenericPaymentClient({ apiBaseUrl: identity.apiBaseUrl, signal: controller.signal });
let session = recoverCheckout(identity);
if (session) {
  const current = await client.get(session); // Read before deciding whether retry is allowed.
  renderPayment(current); // Safe state, never the secret-bearing session.
} else {
  session = await client.createCatalogCheckout({ siteKey: identity.siteKey, priceKey: identity.priceKey });
}
// Explicit user Pay action, only when state is CREATED or REQUIRES_PAYMENT_METHOD:
const confirmed = await client.confirm(session);
renderPayment(confirmed);
for await (const state of client.watch(session, { signal: controller.signal })) {
  renderPayment(state);
}
// Cancel active work on host teardown:
controller.abort();
// When the user explicitly discards this checkout, remove its exact recovery record:
client.clear(session);
session = undefined;
```

`renderPayment` is the host's text-only UI function. `CheckoutSession` is a secret-bearing capability; `CheckoutState` is the separate safe display projection. Do not serialize or log the session. `clear` removes only the record owned by that checkout ID/token, so an old session cannot delete its replacement. Discard the host's in-memory reference after clearing. Storage can be disabled or exhausted; current in-memory checkout still works, but recovery is unavailable.

Recovery is scoped to the normalized API base, Site key and Price key. Only one versioned bounded record for each identity is kept in `sessionStorage`; it contains the checkout ID/token, expiry, normalized base, Site/Price keys and last safe state. Invalid, mismatched, oversized, expired and unsupported records are removed when recovered. It does not contain provider payloads. The widget reads a recovered checkout before enabling a new attempt. `REQUIRES_PAYMENT_METHOD` permits an explicit retry on the same Payment, with a fresh idempotency key. A transport or unusable successful confirmation result marks the session UNKNOWN, blocks another confirm and requires a safe read before retry. Financial POSTs are never automatically replayed. Abort cancels browser work, but cannot prove that a server-side financial effect was rolled back.

Dynamic purchases are created only by the site's trusted backend using the Partner API. Browser code never receives Partner API keys or supplies purchase amounts/currency. Donation amounts are the separate server-policy-validated exception below. The backend passes a one-time handoff token to browser memory for exchange:

```js
// handoffToken comes from the site's trusted backend, never from a URL.
const dynamic = await client.exchangeHandoff({
  siteKey: 'my-shop', priceKey: 'dynamic-order', handoffToken,
});
// For dynamic payments without a Price snapshot, priceKey is a host-chosen,
// bounded recovery identity (not an amount or Partner authority).
const current = await client.get(dynamic);
renderPayment(current);
```

The exchange has no invented Partner header or idempotency requirement. Every request uses browser fetch with CORS, omitted credentials, no-store cache and redirect rejection. The browser supplies Origin naturally. The API base permits HTTPS, or HTTP only at `localhost`, `127.0.0.1`, or `[::1]` for an explicit local development integration. Credentials, query, fragments, encoded paths, traversal and other schemes are rejected. Do not use loopback HTTP outside local development. Configure the Site's exact browser Origin on the backend.

Tokens live only in memory/sessionStorage until expiry. Never log them or put them in URLs, history, DOM, events or persistent browser storage. The SDK sends a checkout token only in one Authorization bearer header. Host code must discard retained references when no longer needed. Errors expose only fixed `PaymentSdkError.code` and safe messages; raw response detail and exception text are discarded, and AbortError is preserved.

`watch` reads immediately, then delays 500ms, 1s, 2s, 4s and 5s, capped at 5 seconds with bounded downward jitter. Reads do not overlap. It stops on a terminal result, retryable payment-method-required result, checkout expiry, cancellation or a monotonic ten-minute deadline. Deadline/expiry cancels an in-flight read and returns the last safe state as UNKNOWN/EXPIRED. A transport failure stops watch with a safe error; the host may explicitly start a new read/watch rather than automatically retrying financial work.

Widget configuration attributes and camelCase properties are `site-key`/`siteKey`, `price-key`/`priceKey`, `api-base-url`/`apiBaseUrl`, and `locale`. Locale formats the displayed currency; current labels are English. A locale-only change rerenders presentation without aborting work, changing the session/lifecycle state, making a request or emitting another lifecycle event. Changing Site/Price/API-base configuration or disconnecting aborts active work and suppresses stale updates/events. Disconnect leaves only the approved recovery record. Reconnecting reads a valid matching session before enabling action and retains exact-once transitions for that session. Without a recoverable capability (including missing, invalid, expired or blocked storage), reconnect renders neutral idle with `Start checkout`, never stale ready/success UI. The nine states are idle, loading, ready, processing, requires_action, succeeded, failed, unknown and expired. Native labelled buttons support keyboard activation, guarded submissions, visible focus and polite screen-reader status announcements.

Lifecycle events `ready`, `processing`, `requires-action`, `succeeded`, `failed`, `unknown` and `expired` bubble and cross the Shadow DOM boundary. They fire only on actual transitions; detail is a fresh frozen object containing only `paymentId` and `status`, or an empty object before any Payment is known. Repeated values and a reconnect that retains the same state do not repeat succeeded. No token, provider payload, full session or idempotency key is emitted. Browser `succeeded` is UX-only: entitlement/fulfillment must use a signed backend callback or trusted Partner API read.

```css
generic-payment-widget {
  --gp-accent: #1554ad;
  --gp-button-text: white;
  --gp-background: white;
  --gp-text: #17202a;
  --gp-border: #ccd1d1;
  --gp-focus: #b35c00;
  --gp-radius: 0.75rem;
  --gp-font: system-ui, sans-serif;
}
```

Styles are isolated in Shadow DOM and honor `prefers-reduced-motion`. All display content uses text nodes. The internal current driver accepts only FAKE + SERVER with an empty payload and submits CARD to the public confirmation endpoint. It is replaceable; Stripe and card-entry UI are not implemented. Admin fake-event controls are absent from this SDK.

## Customer access

Customer access is optional. All SDK inputs/results use camelCase; HTTP uses the exact snake_case contracts below. `CustomerSession` is a deliberately secret-bearing capability, separate from the safe `CustomerSessionState`, `SafeCustomer`, and `CurrentCustomer` types. Never render, serialize into an event, or log a session. Source tokens, OIDC nonces, email, OTP codes, and link proofs exist only in ephemeral caller/request context. A proof is a separate `CustomerLinkProof`, never a session or persisted record.

```js
import { createGenericPaymentClient, recoverCustomerSession } from '@generic-payments/sdk';
const client = createGenericPaymentClient({ apiBaseUrl: 'https://payments.example' });
const session = await client.exchangeSiteToken({
  siteKey: 'my-shop', providerKey: 'site-token', intent: 'AUTHENTICATE', token: sourceToken,
});
const customer = await client.getCurrentCustomer(session); // Safe result.
const payments = await client.listCustomerPayments(session, { limit: 20 });
const nextPage = payments.nextCursor === null ? null :
  await client.listCustomerPayments(session, { limit: 20, cursor: payments.nextCursor });
// Matching valid Customer session attaches automatically; Checkout still returns its own token.
const checkout = await client.createCatalogCheckout({ siteKey: 'my-shop', priceKey: 'lifetime' });
// Explicit guest choice neither sends nor deletes the Customer session.
const guest = await client.createCatalogCheckout({ siteKey: 'my-shop', priceKey: 'lifetime', customerSession: 'omit' });
await client.logoutCustomerSession(session);
```

`recoverCustomerSession({ apiBaseUrl, siteKey })` returns a validated capability or null. Recovery uses only `sessionStorage`, with key `generic-payments:customer:v1:<encoded normalized API base>:<Site key>:<encoded exact browser Origin>`; there is no Price dimension or caller-supplied Origin. Version 1 records contain only `version`, `sessionToken`, `apiBaseUrl`, `siteKey`, `origin`, `customer`, `expiresAt`, and `scopes`, with an 8192-character parsing limit and exact field allowlists. Customer fields are `id`, `status`, `maskedEmail`; current backend status is ACTIVE and the uniform mask is `***@***` or null. Unknown fields, invalid UUID/token/time/scopes/context, expiry and raw email in a masked field are rejected. Session/proof tokens are exactly 43 base64url characters. Scopes are a nonempty unique subset of CUSTOMER_READ, CHECKOUT_CREATE, IDENTITY_LINK, SUBSCRIPTION_READ and SUBSCRIPTION_MANAGE. Old records are not upgraded.

Corrupt/expired matching records are removed, while safe in-memory failure markers retain INVALID_SESSION/SESSION_EXPIRED for subsequent catalog resolution in the same module lifetime. Reauthentication replaces that failure state; `customerSession: 'omit'` makes an explicit guest choice. Absence otherwise preserves guest checkout. A rejected session clears only its owned record and the request fails once, with no automatic guest or financial retry. This differs from an unrelated failed source authentication, which preserves the current session. If storage reads/writes are unavailable or quota is exhausted, the same client retains its valid memory session, without using another store. A replacement stored session supersedes old memory. Logout clears locally before awaiting revocation, including aborted/failed revocation; old completions cannot clear a replacement. Host code must discard its own retained capability after logout. A browser Origin must be a nonopaque HTTP(S) Origin; module import itself remains safe in SSR.

Authentication methods accept an optional per-call `signal`, merged with `ClientOptions.signal`. Session methods take the session as their first argument; `getCurrentCustomer`/`logoutCustomerSession` accept signal as the second argument, history takes `{limit?, cursor?, signal?}`, and `linkIdentity` takes `{proofToken, signal?}`. Methods with an `intent` return `CustomerSession` for AUTHENTICATE and `CustomerLinkProof` for LINK, with conditional TypeScript result types. OTP verification requires the expected intent from the caller's ephemeral challenge context and rejects a response of the other kind.

If a failed storage write leaves an older record behind, the newly authenticated same-client memory session owns that exact stale snapshot, even if the old record is corrupt or expired. A genuinely different record is still validated normally. Clearing the memory session retains a local revocation marker for the superseded snapshot (or an owned record whose removal was blocked), so automatic catalog creation fails closed instead of resuming an earlier Customer. New successful authentication or a genuinely different valid stored replacement supersedes that marker; explicit `customerSession: 'omit'` remains available for deliberate guest checkout. No unrelated or replacement token is deleted.

| Method | HTTP contract | Success |
| --- | --- | --- |
| `exchangeSiteToken({siteKey, providerKey, token, intent})` | POST `/api/v1/public/customer-auth/site-token/exchanges` for AUTHENTICATE; POST `/api/v1/public/customer-auth/site-token/link-proofs` for LINK; `{site_key,provider_key,token}` | 200 session or dedicated proof |
| `beginOidcAuthentication({siteKey, providerKey, intent})` | POST `/api/v1/public/customer-auth/oidc/attempts`; `{site_key,provider_key,intent}` | 201 `{attemptId,nonce,expiresAt}` |
| `exchangeOidcToken({siteKey, providerKey, intent, attemptId, nonce, expiresAt, token})` | POST `/api/v1/public/customer-auth/oidc/exchanges`; `{attempt_id,site_key,provider_key,intent,nonce,token}` | 200 session/proof; local `expiresAt` checks attempt expiry and is not sent |
| `requestEmailOtp({siteKey, email, intent})` | POST `/api/v1/public/customer-auth/email-otp/challenges`; `{site_key,email,intent}` | uniform 202 `{challengeId,expiresAt}` |
| `verifyEmailOtp({siteKey, challengeId, code, intent})` | POST `/api/v1/public/customer-auth/email-otp/challenges/{challengeId}/verify`; `{site_key,code}` | 200 session/proof; challenge owns intent |
| `getCurrentCustomer(session, signal?)` | GET `/api/v1/customer/me` | 200 `{id,status,maskedEmail,authenticationModes,sessionExpiresAt}`; ID and unchanged session expiry must match |
| `listCustomerPayments(session, options?)` | GET `/api/v1/customer/payments?limit=...&cursor=...` | 200 `{items,nextCursor}` |
| `listCustomerDonations(session, options?)` | GET `/api/v1/customer/donations?limit=...&cursor=...` | 200 strict `{items,nextCursor}` Donation history (see below) |
| `linkIdentity(session, {proofToken, signal?})` | POST `/api/v1/customer/identities`; `{proof_token}` | 201 LINKED or 200 ALREADY_LINKED, `{identityId,outcome}`; no idempotency header |
| `logoutCustomerSession(session, signal?)` | DELETE `/api/v1/customer/session` | 204, no JSON body |

Legacy three-scope session HTTP responses contain exactly `{session_token,expires_at,scopes,customer}`, where Customer is `{id,status,masked_email}`. Subscription-scoped sessions additionally contain the authenticated `site_id`; Origin and Site key remain bound to the verified request context, not response fields. Proof HTTP responses contain exactly `{proof_token,expires_at}`. OIDC redirects, provider buttons, and provider SDKs remain host-owned. OTP codes have six digits, challenges expire after ten minutes, and resend cooldown is sixty seconds. A challenge response makes no claim about mail delivery or address existence.

History limits default to 20 and range from 1 through 100; cursors are nonblank strings of at most 4096 characters, encoded only in the bounded history query. Payment items expose exactly `paymentId`, `source` (CATALOG/DYNAMIC/SUBSCRIPTION), `amountMinor`, `currency`, backend `status`, `createdAt`, `updatedAt`, and `catalog` (null or `{priceId,priceKey,version}`; always null for SUBSCRIPTION). Their HTTP counterparts are `{payment_id,source,amount_minor,currency,status,created_at,updated_at,catalog}` with catalog `{price_id,price_key,version}`. Customer/Site/provider/email/metadata are rejected, as are SDK-derived UNKNOWN/EXPIRED history statuses. Authentication/history response bodies have a 128KiB byte cap; Checkout responses retain 32KiB, including Customer-authorized creation. Reads stop at the cap and stalled reads cancel on abort. Error bodies are bounded too. All requests retain CORS, credentials omit, cache no-store and redirect error. Browser-supplied Origin is never spoofed. Request authorization is discriminated as none, Checkout token, or Customer token; handoff/read/confirm never receive a Customer capability. Errors expose fixed codes/messages, including INVALID_SESSION, SESSION_EXPIRED, AUTHENTICATION_FAILED and RATE_LIMIT_EXCEEDED, and preserve AbortError.

## Headless subscriptions

`client.subscriptions` is framework-independent and requires an explicit `session` on every call. `SUBSCRIPTION_READ` authorizes reads; `SUBSCRIPTION_MANAGE` authorizes commands, previews and action retrieval. The scope validator accepts exactly the original three scopes plus these two, with no duplicates. Older persisted three-scope sessions remain valid syntax but never gain subscription authority: authenticate again at a subscription-enabled Site. Customer payment history now also accepts `source: 'SUBSCRIPTION'`, always with `catalog: null`; subscription billing records provide the separate immutable recurring-price context.

Subscription-scoped authentication also returns the authenticated `site_id`, mapped to `session.siteId`. Subscription sessions and their V1 persisted records require this independent UUID; missing/invalid identity requires fresh authentication, never inference from a Subscription response. `get` and every list row must match this Site and Customer. Legacy three-scope authentication retains its four-field response and existing V1 records retain their exact authority; LINK proofs are unchanged. Failed, aborted or malformed source authentication preserves future use of the still-current session, while genuine renewal/logout and stale in-flight completions remain fenced.

```js
import { watchSubscriptionOperation, confirmSubscriptionAction } from '@generic-payments/sdk';
const subscriptions = client.subscriptions;
const page = await subscriptions.list({ session, limit: 20 });
const offers = await subscriptions.offers({ session, productId });
// Explicit user intent; retain the key if manually reconciling an uncertain result.
const operation = await subscriptions.create({ session, priceId,
  recurringConsent: true, idempotencyKey: crypto.randomUUID(), signal: controller.signal });
for await (const current of watchSubscriptionOperation(subscriptions, {
  session, subscriptionId: operation.subscriptionId, operationId: operation.operationId,
  signal: controller.signal,
})) renderOperation(current); // Safe status fields only.
// Only after an explicit user confirmation, if the server requires action:
const action = await subscriptions.action({ session, subscriptionId: operation.subscriptionId,
  operationId: operation.operationId, signal: controller.signal });
await confirmSubscriptionAction(subscriptions, { session,
  subscriptionId: operation.subscriptionId, operationId: operation.operationId,
  idempotencyKey: crypto.randomUUID(), signal: controller.signal }, action);
// Release all host-held action/session references on logout or teardown.
```

The complete method set is `list`, `offers`, `get`, `history`, `billingRecords`, `create`, `preview`, `change`, `clearChange`, `cancel`, `withdrawCancellation`, `setupMethod`, `recover`, `resume`, `operation`, `action`, `confirmAction`, and `portal`. History and billing/list pages accept `limit` (default20, max100) and `cursor`. Inputs/results use camelCase, mapped to exact Task13 snake_case DTOs and routes. `cancel` requires `confirmation` equal to the subscription UUID for IMMEDIATE mode. Every mutating command requires a caller-generated `idempotencyKey`; the side-effect-free preview takes a target price and IMMEDIATE/NEXT_PERIOD timing instead. No financial POST is automatically replayed after timeout, abort or an unreadable accepted response: reconcile with `operation`, `get` or `list` before deciding what to do.

The narrow authorized wrapper checks the original complete Customer authority before sending and after awaiting the response, including bearer renewal, expiry, logout, stored replacement/removal and Site/Customer changes. The same Customer with a new bearer is a new authority. Explicit sessions never fall back to guest or checkout tokens. Requests omit credentials, disable cache and reject redirects. Detail/action/portal responses are capped at32KiB; pages and offers at128KiB. Strict parsers reject extra fields, invalid enums/UUIDs/money/versions/cursors and inconsistent ownership or access boundaries. Browser `access` and `permittedActions` are UX hints only; trusted backend authorization remains mandatory.

`watchSubscriptionOperation` performs serial GETs only, immediately then with downward-jittered500ms/1s/2s/4s/5s intervals, a monotonic ten-minute deadline and session-expiry bound. It stops on SUCCEEDED, FAILED, UNKNOWN or REQUIRES_ACTION. Deadline/expiry cancels an in-flight request and returns the last safe operation as UNKNOWN when available. Poll results never contain `nextAction`. Host abort cancels browser work, not server execution; abort/discard active work on disconnect or authority replacement.

Mutation `nextAction` and explicit action responses are memory-only capabilities. `confirmSubscriptionAction` consumes the original returned action once, bound to its client, exact authority, subscription, operation and expiry; copied/tampered actions are rejected. It handles Fake confirmation only; PROVIDER_CLIENT integration belongs to Stage5. Neither action capabilities nor portal navigation results are stored or emitted as DOM events. Never log them, place action secrets in URLs, or retain them after logout. Stage4 portal results are explicitly simulated HTTPS navigation; HTTP is permitted only for literal loopback (`localhost`, `127.0.0.1`, `[::1]`) with the backend's explicit local profile. The server requires exact persisted allowlist membership for the canonical return URL, including its query but excluding `subscriptionPortal` and `operationId`. The manager removes those two markers before requesting navigation; the SDK validates the returned location, fresh expiry and single server marker/operation UUID. Returning reloads authoritative state and never confirms a financial operation. Real provider portals remain Stage5 work.

## Subscription manager Web Component

Upgrade host applications to this strict SDK contract together with the Stage 4 backend. Enable the Site and publish its subscription Prices before a fresh Customer login. Persisted older sessions do not acquire `SUBSCRIPTION_READ`/`SUBSCRIPTION_MANAGE`; old Partner keys do not acquire `subscriptions:read`/`subscriptions:manage`. Access checks require `subscriptions:read`. Issue a new least-privilege Partner key server-side, never in a browser bundle, manager, URL or storage.

For protected content, your trusted server calls `GET /api/v1/partner/customers/{customerId}/products/{productId}/access` with `Authorization: Bearer <server-held key>`. Derive Customer/Site/Product from your authenticated request context, not a browser decision. Bound request time and response size, reject redirects, and fail closed on errors. Validate exact returned identity and monotone revision per Subscription (revisions from replacement Subscriptions are not globally comparable); allow only `allowed === true` before `valid_until`, never for more than five minutes or an earlier entitlement boundary. A verified newer lifecycle callback invalidates the cache; it cannot grant access or extend TTL. Trial/grace access may be valid without new cash; a refund does not independently remove subscription access.

```js
import { createGenericPaymentClient, registerGenericSubscriptionManager } from '@generic-payments/sdk';
registerGenericSubscriptionManager(); // Explicit and safe to call in SSR; no implicit registration.
const manager = document.createElement('generic-subscription-manager');
manager.client = createGenericPaymentClient({ apiBaseUrl: 'https://payments.example' });
manager.productId = publishedProductUuid; // Exact Product; no cross-Product plan changes.
manager.priceId = publishedPriceUuid; // Optional initial selection, never an amount.
manager.locale = 'pt'; // 'en' or 'pt'; render only, without requests or financial intent.
manager.customerSession = authenticatedCustomerSession; // Explicit, subscription-scoped authority.
document.querySelector('#subscriptions').append(manager);
// On logout, immediately release authority, before awaiting remote revocation:
manager.customerSession = undefined;
```

`GenericSubscriptionManager` is framework-free and uses Shadow DOM, native labelled controls, fieldset/legend, polite status, visible focus and reduced motion. `registerGenericSubscriptionManager(tagName = 'generic-subscription-manager')` is idempotent. Properties are `client`, `customerSession`, `productId`, optional `priceId`, and `locale`; locale also supports the `locale` attribute. Authority/Site/API/Product/price changes and disconnect abort active requests, increment `authorityRevision`, discard capabilities/confirmation, and suppress stale completions. Reconnect reads current server state. Session expiry clears the live action. A locale-only change preserves selection, consent and confirmation without a request.

The manager reads published offers for exactly one Product, separate billing records and lifecycle history. It shows billing/entitled tiers, billing status, access hint, period/paid-through/trial/grace boundaries, cancellation, pending plan change, next amount and tax estimate. A reopened `pendingOperation.operationId` is followed with safe operation/action reads, never another financial command. An original local operation remains the reconciliation pointer even before list projections catch up. UNKNOWN or an uncertain mutation offers read-only refresh, not a fresh financial retry. A failed initial read never establishes an empty Product slot. Controls are limited to server `permittedActions` and Customer scopes; the server remains the authority.

Create and payment-method setup/resume need recurring consent. Preview is separate from explicitly confirmed Apply; cancellation requires its own confirmation and states the effective date and that earlier payments are not automatically refunded. “Update payment method” is not “Pay the existing invoice”. Fake action completion uses exported `new SubscriptionFakeActionDriver(client.subscriptions).complete(action, input)` and the original one-shot bound capability; there are no Admin simulation controls. Capabilities and portal URLs stay in memory, never DOM/events/storage. The simulated portal navigates only to the server-validated return location; remount and authenticated reads reload server state. Real providers/portals belong to Stage5.

`generic-subscription-state` bubbles/composes with a fresh frozen `{subscriptionId, operationId, state, revision}` detail, and `generic-subscription-error` with frozen `{code}` only. Neither emits URLs, tokens, price/provider payloads, sessions or idempotency keys. The optional read-only `state` projection contains safe subscription/operation data only. Exported `ManagerState` and pure `acceptManagerResult(state, authorityRevision, next)` preserve the current authority revision and reject stale results by returning the unchanged state.

```css
generic-subscription-manager {
  --gp-subscription-font: system-ui, sans-serif;
  --gp-subscription-text: #172033;
  --gp-subscription-background: #fff;
  --gp-subscription-accent: #234d98;
  --gp-subscription-focus: #b35c00;
  --gp-subscription-border: #c5cfdf;
  --gp-subscription-radius: 0.8rem;
}
```

The local demo uses the existing OTP host's verified explicit session for two independent Product managers. Configure public query parameters `subscription_product_1_id`, `subscription_product_2_id` and optional `subscription_price_1_id`, `subscription_price_2_id`, or HTML dataset equivalents `subscriptionProduct1Id`, `subscriptionProduct2Id`, `subscriptionPrice1Id`, `subscriptionPrice2Id`. Alternatively enter Product/price UUIDs in the labelled form and choose “Load products”; only public UUID configuration is retained in the URL for portal return. No backend defaults or provider secrets are invented. The exact accessible buttons `Português` and `Subscription theme` change presentation only; `English` restores English. The demo and manager explicitly explain that protected content needs backend/serverless access verification: browser access hints are not a public content gate.

## Optional email OTP component

```html
<generic-email-otp api-base-url="https://payments.example" site-key="my-shop" locale="pt"></generic-email-otp>
<script type="module">
  import { defineGenericEmailOtp } from './sdk/dist/index.js';
  defineGenericEmailOtp(); // No automatic registration.
  document.querySelector('generic-email-otp').addEventListener('customer-authenticated', event => {
    document.querySelector('#customer-message').textContent = 'Signed in';
    // event.detail contains only safe Customer/session-state metadata.
  });
</script>
<p id="customer-message"></p>
```

`GenericEmailOtp` supports `api-base-url`/`apiBaseUrl`, `site-key`/`siteKey`, and `locale`. Labels and generic error messages support English (`en`, default/fallback) and Portuguese (`pt`, including regional variants). It is an AUTHENTICATE UI; use headless methods for LINK. Email is cleared as soon as requested and code as soon as submitted; resend becomes available after sixty seconds and asks for the address again. Native labelled form controls support Enter, focus moves to code after the uniform challenge and to status after success, errors use role=alert, status uses polite aria-live, and pending guards block duplicate requests. API/Site reconfiguration or disconnect aborts work, clears values/challenges/timers and suppresses stale events/storage effects. Locale-only changes preserve challenge, input and active work.

The sole custom event is `customer-authenticated`, which bubbles and is composed. Its `CustomerAuthenticatedDetail` is a fresh deeply frozen `{state:'authenticated',customer:{id,status,maskedEmail},expiresAt,scopes}`. No session/proof token, nonce, code, raw email, provider detail or request body is emitted. Success replaces the input form with a status. The component uses Shadow DOM, text content only, visible focus and the same `--gp-accent`, `--gp-button-text`, `--gp-background`, `--gp-text`, `--gp-border`, `--gp-focus`, `--gp-radius`, and `--gp-font` design tokens as the payment widget. It has no OAuth/provider buttons or Customer cabinet.

## One-time Donations

```js
const campaign = await client.getDonationCampaign({siteKey:'my-shop',campaignKey:'community'});
// Keep one input object (or one explicit idempotencyKey) for the same live intent/retry.
const intent = {siteKey:'my-shop',campaignKey:'community',amountMinor:1000,
  message:optionalMessage,customerSession:'auto',idempotencyKey:crypto.randomUUID()};
const donation = await client.createDonationCheckout(intent);
delete intent.message; // Caller owns its input and must release its own plaintext references.
// Local creation is NOT financial success. Use the common checkout continuation.
renderPayment(await client.confirm(donation));
for await (const state of client.watch(donation)) renderPayment(state);
client.clear(donation); // Also discard the in-memory capability when finished.
```

`DonationCheckoutInput` accepts ONLY `siteKey,campaignKey,amountMinor,message?,customerSession?,idempotencyKey?,signal?`. Amounts are positive safe integer minor units; optional message permits at most500 Unicode code points (including present-empty), with server NFC and policy checks authoritative. No currency, Customer ID, tax or provider fields are accepted. Explicit keys are nonblank, at most255 characters, without ISO control characters; use ASCII for browser HTTP-header compatibility. A generated key is associated weakly with the same input-object identity; copied inputs need an explicit shared key for retry. Editing amount/message/auth mode starts a new intent. No automatic financial POST retries occur.

Default `auto` attaches a valid same-API/Site/Origin Customer session; absence is anonymous. Invalid/expired/anonymized/cross-context stored authority fails closed without anonymous retry. `omit` is a deliberate anonymous choice made before requesting, not a recovery strategy. Campaign `anonymousAllowed` remains server-enforced. Customer authentication may restore from approved sessionStorage; Donation checkout capabilities are memory-only and do NOT restore after reload. Catalog checkout recovery is unchanged. Hosts must abort/discard live Donation capabilities on logout, expiry or context replacement; the demo remounts on Customer-authority changes.

Public policy GET `/api/v1/public/sites/{siteKey}/donation-campaigns/{campaignKey}` has no authorization and exact wire fields `key,display_name,purpose_description,currency,suggested_amounts,minimum_amount,maximum_amount,custom_amount_allowed,anonymous_allowed`. SDK policy fields are `campaignKey,displayName,purposeDescription,currency,suggestedAmounts,minimumAmount,maximumAmount,customAmountAllowed,anonymousAllowed`, retaining suggested order. There are no invented option-count/string limits; the existing bounded32KiB public-response reader applies. Create POST appends `/checkouts`, sends only `{amount_minor,message?}` and `Idempotency-Key`; response uses the accepted snake_case Checkout fields with `price:null` and exact `donation:{donation_id,campaign_key,campaign_version}`. SDK session adds `donation:{donationId,campaignKey,campaignVersion}`; its inherited `priceKey` is only an internal common-checkout identity bridge, not a Price or storage namespace. Subsequent common confirm/get/watch responses do not contain Donation context. Generated legacy Checkout documentation naming debt does not change these strict runtime contracts.

`listCustomerDonations` uses actual `listCustomerDonations` operation, default20/max100 and opaque cursor. HTTP envelope is exactly `{items,next_cursor}`. Each item is exactly `donation_id,payment_id,campaign_id,campaign_key,campaign_version,amount_minor,currency,status,message_state,created_at,updated_at`; SDK converts to camelCase. Message state is PRESENT/ABSENT/REDACTED; no plaintext, Customer ID, provider details or message API is exposed. History is authenticated owner-scoped on the server, with the existing128KiB response cap.

```html
<generic-donation api-base-url="https://payments.example" site-key="my-shop"
  campaign-key="community" locale="pt-PT"></generic-donation>
<script type="module">
  import {defineGenericDonation} from './sdk/dist/index.js';
  defineGenericDonation();
</script>
```

Exports include `GenericDonation`, `defineGenericDonation`, `DonationEventDetail`, `DonationCampaignInput`, `DonationCampaign`, `DonationCheckoutInput`, `DonationCheckoutSession`, `DonationContext`, `CustomerDonation`, `CustomerDonationHistory`. Attributes/properties are `api-base-url`/`apiBaseUrl`, `site-key`/`siteKey`, `campaign-key`/`campaignKey`, `locale`; optional attribute `customer-session` is `auto` (default) or `omit`. Locale-only updates preserve work. Other configuration changes/disconnect abort work, clear draft/request references, discard the live checkout and suppress stale generation updates. English and Portuguese copy, native keyboard controls, ordered suggestions/pressed state, optional custom minor-unit input, min/max hints, Unicode counter, labelled fieldset, focused validation errors, role=alert and polite status are included. Display text is never HTML.

Composed bubbling `donation-created`, `donation-state`, `donation-succeeded` events contain ONLY a fresh frozen `{donationId,paymentId,checkoutId,status}`. Creation/replay never emits `donation-succeeded`; a replay may return the current financial status, and a processing replay immediately resumes the common watcher. Only a subsequent common financial read/confirmation/watch transition emits that completion event. Trusted server-side completion is still required for fulfillment. The component clears its message input and captured intent/body references immediately after successful LOCAL creation, before waiting for payment. No message/Donation session is written to storage. Host code must likewise release its own inputs. Safe error copy never echoes the server body.

Isolated CSS tokens: `--generic-donation-accent`, `--generic-donation-text`, `--generic-donation-background`, `--generic-donation-button-text`, `--generic-donation-border`, `--generic-donation-radius`, `--generic-donation-padding`, `--generic-donation-focus`, `--generic-donation-error`. No motion is introduced. Static demo accepts public `campaign_key` query configuration; it shows the existing catalog flow, optional email login, intentional anonymous toggle and real owned Donation history. Never put messages, tokens, email or administrative credentials in public configuration/URLs.
