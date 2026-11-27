# First subscription payment with a 3DS challenge

This isolated case creates one ordinary EUR 20 Sandbox subscription. The first Pay uses Stripe's web test card ending in 3220, which requires successful 3DS2 authentication. After the server confirms the original PaymentIntent, only the browser that received that confirmation can continue the challenge. Stripe does not document a fixed issuer resource set for this test card. [Stripe test cards](https://docs.stripe.com/payments/3d-secure/authentication-flow#test-the-3ds-flow)

## Running the case

Use a fresh billing run UUID, the exact committed source, dedicated ports and Docker endpoint, and the approved Sandbox account. The case uses the [reviewed checkout-local static asset policy](billing-failed-renewal-recovery.md#static-assets-in-the-owned-payment-frame). Select `PAYMENT_STRIPE_SANDBOX_ENABLED=true`, `PAYMENT_STRIPE_BILLING_ACCEPTANCE=true` and `PAYMENT_STRIPE_BILLING_CASE=initial_3ds`. From `examples/demo-site`, run the dedicated project:

```sh
pnpm exec playwright test --config=playwright.stripe-billing-initial-3ds.config.ts
```

For local runs, `PAYMENT_STRIPE_BILLING_3DS_CLI_PROFILE_RUN_ID` selects an earlier owned profile. The runner copies it into the fresh private directory and verifies that it is bound to the single approved test account. `PAYMENT_STRIPE_BILLING_3DS_SUBNET` optionally selects an unused, aligned `10.0.0.0/8` subnet with a `/28` prefix. Check for overlap with Docker networks and host routes before launch. The runner verifies the created bridge against the selected subnet before starting services.

There is one subscription creation, one confirmation and one SDK challenge. When the issuer's test page is ready, open this run's private `operator.html` in the operator's Chrome profile and use the challenge button once. By default the file is under `examples/demo-site/test-results/<run-uuid>/`. A custom Playwright output directory changes the parent directory. An unexpected issuer address, mismatched transaction, lost acknowledgement or expired browser authority ends the attempt. An unfinished operation cannot be submitted again.

If a refused Stripe.com origin is the first failure after the challenge dispatch receipt, the runner attempts one write to `test-results/stripe-sandbox/<run-uuid>/refused-provider-origin.private.json` from the repository root. The owner-only file contains the canonical origin, run ID, source and fixed checkpoint. Keep the file in the private run directory. The terminal marker reports only the checkpoint, and the blocked request remains blocked if the file cannot be written.

The private `issuerRelation` field records whether the refused form navigation matches the URL and 3DS transaction fields of the original Stripe authentication response. The runner aborts the route before reading that response and stops the read after 750 ms. It reads only an uncompressed response with a measured body size up to 64 KiB. A match is diagnostic evidence only. The request path, query, form data and response body are discarded.

The dedicated entry can resolve the reviewed issuer origin from the owner-only report and checkout-local policy key. The complete report must match its reviewed HMAC, original run and source, and verified transaction-and-frame category. The rule applies to the original POST into the direct Stripe.js challenge iframe and the one trusted form submission on that same issuer origin. Both requests still require their original frame and paired nonredirect responses. If the report or policy key is unavailable, the checkout uses its existing issuer policy.

The browser recognizes two issuer documents. Before handoff it uses the exact committed response to the original CReq. After the operator click it uses the exact committed response to the one trusted form POST in the same iframe. Each document has its own bounded asset allowance. At most six credential-free, bodyless GETs for raster images, WOFF/WOFF2 fonts or CSS stylesheets can be blocked locally. Each URL must stay on the same origin, use a distinct path with no query and have the suffix that matches its resource type. Each block requires its own blocked-by-client event within 750 ms of the first request. A matching Chromium error pause is failed again at the response stage. An HTTP response for a blocked asset, a repeated path, an exceeded budget or an unverified frame ends the run.

At most six same-origin, parser-initiated JavaScript GETs with distinct canonical `.js` paths can load per issuer document. The request's Chromium loader must match both the live document and the verified navigation that produced it. Responses require HTTP 200 and an exact JavaScript media type. A redirect, script-initiated request, wrong media type or incomplete load fails the check. Script loads on the original challenge document must finish before operator handoff.

If the issuer submits a CRes, the browser accepts one form POST from the committed issuer decision document to the exact `https://hooks.stripe.com` action in that document's paired HTML response. For responses with a verified identity body length up to 64 KiB, the guard reads those bytes during the Chromium response pause so a frame process swap cannot discard them. It uses the MIME type, encoding and size checked at that pause for the captured body. The POST must preserve the form fields byte for byte and carry the original 3DS transaction IDs and version. Once observed, this request must receive its own paired HTTP 200 HTML response before SDK completion or the final PaymentIntent read proceeds.

The form may target its own verified issuer frame by its exact name or use the browser's self target. A present hidden `threeDSSessionData` control with no `value` contributes an empty value in its original position. The hidden `cres` value remains required and nonempty. Ambiguous forms, other frame targets and unsupported controls fail the check.

If the paired HTML contains no static form, the guard can also derive a form from one classic inline script that inserts a complete literal form with `document.write` or `document.body.insertAdjacentHTML('beforeend', ...)`, then submits it. The guard parses the literal as data and applies the same form and wire checks. The script must be the only producer on an otherwise plain page. For other response shapes the guard keeps the request blocked and can report the presence of inline or external scripts, templates or event handlers. That diagnostic describes the response structure and does not identify what ran.

For a decision encoded with gzip, deflate, Brotli or Zstandard, Chromium continues the original response and counts decoded bytes on the exact request, frame and loader. CRes waits for the completed response and the original body. The guard caps the encoded transfer including headers and the decoded body at 64 KiB each, and checks the decoded count before requesting the body. The Chromium buffer is also capped at 64 KiB per session. Missing or evicted bytes leave CRes blocked.

The terminal report distinguishes CRes reservation and ordering, the destination, original decision response and body checks, action equality and exact POST bytes. Form failures get a fixed code for the rejected condition or the deadline after successful parsing. Response headers, HTML, form data and transaction IDs stay out of the terminal report.

## What establishes a pass

The original confirmation attempt must retain its winning `REQUIRES_ACTION` receipt. The terminal server records must show one paid invoice and one EUR 20 payment for the bound subscription, with matching journal entries and signed site callbacks. A separate read of the original PaymentIntent and latest captured Charge must show an authenticated 3DS 2.x challenge. Frictionless authentication, a different Charge or a second charge fails the check.

Record the source, run ID, terminal marker and cleanup result. Ordinary CI uses synthetic fixtures. This case still needs a native Sandbox receipt.
