# Stripe browser load check

This separate, one-shot probe initializes the existing SDK Stripe loader and a
deferred Payment Element before a PaymentIntent or SetupIntent exists. It uses
only `mode: 'payment'`, amount `2000`, currency `eur`, and card payment methods.
Apple Pay and Google Pay are disabled. The connected provider host is inert,
unfocusable and ignores pointer events. There is no form, card entry, Pay button,
submit, confirmation, customer, backend, billing owner or CLI operation.

Stripe documents this deferred initialization pattern in its
[official deferred-payment guide](https://docs.stripe.com/payments/accept-a-payment-deferred?platform=web&type=payment).

Verification of this separated-report version is injected or build-only. Its
actual Chrome and Stripe network execution is **NOT_RUN**. A prior invocation of
the earlier version recorded only `CLEANUP` and exit 1. That receipt cannot
recover whether readiness occurred or which cleanup component rejected. This
change does not establish the cause or resolution of that historical result and
is not native acceptance evidence.

After separately authorizing an external Chrome/Stripe run, from the repository
root use an explicitly supplied public test publishable key:

```sh
PAYMENT_STRIPE_BROWSER_LOAD_CHECK=true \
PAYMENT_STRIPE_BROWSER_LOAD_CHECK_PUBLISHABLE_KEY=pk_test_REPLACE_WITH_EXPLICIT_PUBLIC_TEST_KEY \
node examples/demo-site/scripts/stripe-browser-load-check.mjs
```

Use the repository's supported Node version (native TypeScript source import is
required), existing dependencies and already installed Google Chrome. The probe
does not install anything. No account tools, old operations, key files, `.env`
files, secret keys, live keys, client secrets or historical artifacts supply
authority. Imports acquire no browser or build. Missing/invalid opt-in, a live or
secret key, and debug/capture environment flags refuse before acquisition. The
Chrome child receives a small OS environment allowlist, excluding provider keys.

The launcher builds only the dedicated page into a fresh temporary directory,
serves its exact HTML/assets from memory through intercepted virtual loopback
requests, and opens one headed Chrome context with service workers and downloads
disabled. There is no listening server. It blocks popups, WebSockets, redirects,
Authorization headers, userinfo, additional main-frame navigation and unknown
egress. Only GET/POST `/v1/elements/sessions` may reach `api.stripe.com`.
GET/POST subresources at the unchanged private Stripe origin allowlist may pass.
Other Stripe API paths, including intents, payment methods and tokens, fail
before the origin fallback. Unsupported origins (including `m.stripe.com`) stay
blocked; the probe never expands its policy automatically.

Admitted provider requests use a single bounded intercepted fetch with
`maxRedirects: 0` and `maxRetries: 0`. Every 3xx response is refused before
fulfillment, so the browser cannot follow a redirect outside the gate. Only a
validated successful response is fulfilled; fetched responses are disposed on
every path. A response arriving after retirement is aborted rather than
fulfilled, and owned transports/disposal settle during cleanup.

The standard 60-second budget begins before build acquisition and covers the
load/readiness work; Chrome launch and navigation use the remaining budget.
There are no retries. A timeout prevents subsequent acquisition stages and late
browser/context acquisition is immediately closed. Vite build and resource
cleanup lack a reliable cancellation deadline, so wall-clock completion can
exceed 60 seconds while awaiting those operations. The launcher waits for owned
acquisitions and cleanup rather than reporting completion with a background
browser. A cleanup rejection is recorded independently of the first terminal
load observation; a hung acquisition or cleanup API can delay the result
indefinitely. This is not a hard process termination guarantee.

The page shows fixed `IDLE`, `LOADING`, `READY` or terminal error status. It
destroys the Element on readiness, loaderror, deadline, stop or page loss; the
launcher then closes its context/browser and removes only its own temporary
directory. The immutable result contains `loadResult` and a deduplicated
`cleanupErrors` array in fixed component order. Stdout contains one safe line,
for example:

```text
STRIPE_BROWSER_LOAD_CHECK load=READY cleanup=OK components=NONE
STRIPE_BROWSER_LOAD_CHECK load=READY cleanup=FAILED components=CONTEXT_CLOSE
STRIPE_BROWSER_LOAD_CHECK load=API_PATH cleanup=FAILED components=BROWSER_CLOSE,BUILD_DISPOSE
STRIPE_BROWSER_LOAD_CHECK load=NOT_CONFIRMED cleanup=FAILED components=RESPONSE_DISPOSE
```

Exit 0 requires `READY` and no cleanup errors; every other report exits 1.
`READY` requires the readiness wait to succeed. A cleanup-triggered retirement
before a terminal load observation yields `NOT_CONFIRMED`. Later events and
cleanup failures cannot replace an observed readiness, refusal or timeout.
Cleanup categories identify context/browser closure, build disposal, response
disposal, late build/browser/context/page retirement, extra-page closure and
download cancellation. Invalid diagnostic categories render as fixed `UNKNOWN`
instead of raw values. `cleanup=OK` means no owned cleanup rejection was
observed; it is not a process inventory or proof of a whole payment flow.
No raw URLs, error text, keys, provider logs, screenshots, traces, videos or HAR
files are recorded. Failures do not authorize a retry or policy widening. The
launcher is outside CI and default Playwright discovery.

Deferred readiness would establish that the current Stripe script can initialize
the Element under this egress gate. It does not establish client-secret payment
or setup compatibility, account binding, cash movement, renewal, recovery, SCA,
settlement or full failed-renewal acceptance. Allowed provider requests can
include telemetry; this is a future networked check, not a zero-egress test.
