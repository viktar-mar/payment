# Stripe browser load check

This separate, one-shot probe initializes the existing SDK Stripe loader and a
deferred Payment Element without creating a PaymentIntent or SetupIntent.
The ordinary profile uses `mode: 'payment'`, amount `2000`, currency `eur` and
card payment methods. In this profile, the page calls the installed pure loader's
`setLoadParameters({advancedFraudSignals: false})` before its first load.
This requests Stripe.js with optional advanced fraud signals disabled in this
fresh, card-free document. The payment and recovery pages use the SDK defaults.
Apple Pay and Google Pay are disabled. The connected provider host is inert,
unfocusable and ignores pointer events. There is no form, card entry, Pay button,
submit, confirmation, customer, backend, billing owner or CLI operation.

Stripe documents this deferred initialization pattern in its
[official deferred-payment guide](https://docs.stripe.com/payments/accept-a-payment-deferred?platform=web&type=payment).

One invocation of the separated-report version at `b50978a` recorded
`load=ORIGIN cleanup=OK components=NONE` and exit 1. Readiness was not confirmed;
the report cannot identify which generic origin predicate refused the request.
A prior invocation of the earlier version recorded only `CLEANUP` and exit 1.
That receipt cannot recover whether readiness occurred or which cleanup component rejected. This
change does not establish the cause or resolution of that historical result and
is not native acceptance evidence.

A later native report at `4e2464c` recorded
`load=READY cleanup=FAILED components=RESPONSE_DISPOSE`, but its exact exception
was not retained. Source inspection of installed Playwright 1.63.0 shows that
context closure disposes the
request channel used by response disposal. Injected tests reproduce the race
when that channel closes before pending disposal completes. This supports the
cleanup ordering fix below; it does not prove the cause of the historical
exception. A subsequent native check at `ab85027` ran after the cleanup-order
fix and did not report `RESPONSE_DISPOSE`, but failed with `ORIGIN` and did not
confirm `READY`; it is not acceptance evidence.

A native report at `ab85027` recorded
`load=ORIGIN cleanup=OK components=NONE origin_reason=HOST_NOT_ALLOWED` and
exit 1. Its hostname was not retained, so no host family or justified allowlist
change can be inferred from that report. Later native checks recorded the host
family as described in the private refusal witness section below.

After separately authorizing an external Chrome/Stripe run, from the repository
root use an explicitly supplied public test publishable key:

```sh
PAYMENT_STRIPE_BROWSER_LOAD_CHECK=true \
PAYMENT_STRIPE_BROWSER_LOAD_CHECK_PUBLISHABLE_KEY=pk_test_REPLACE_WITH_EXPLICIT_PUBLIC_TEST_KEY \
node examples/demo-site/scripts/stripe-browser-load-check.mjs
```

Use the repository's supported Node version (native TypeScript source import is
required), existing dependencies and already installed Google Chrome. The probe
does not install anything. Ordinary mode reads no key files. No account tools,
old operations, `.env` files, secret keys, live keys, client secrets or historical
artifacts supply authority. Imports acquire no browser or build. Missing/invalid opt-in, a live or
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

After the terminal load observation and runner retirement, cleanup lets the
currently owned provider transports settle before context closure, bounded by
the time left in the original load
budget. This gives pending response disposal a chance to finish while its request
channel is alive. If the original deadline has passed or the drain reaches it,
cleanup attempts context closure to interrupt stuck fetch, fulfillment or
disposal, then awaits those same transports. There is no new timeout budget or
grace period. Independent disposal rejections, including any remaining failure
after context-close fallback, still report `RESPONSE_DISPOSE`; observed `READY`
and the first refusal or timeout remain unchanged.

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
destroys the Element on loaderror, deadline, stop or page loss. The ordinary
profile also destroys it immediately on readiness. The launcher closes its
context/browser and removes only its own temporary directory. The immutable
result contains `loadResult` and a deduplicated
`cleanupErrors` array in fixed component order. Stdout contains one safe line,
for example:

```text
STRIPE_BROWSER_LOAD_CHECK load=READY cleanup=OK components=NONE
STRIPE_BROWSER_LOAD_CHECK load=READY cleanup=FAILED components=CONTEXT_CLOSE
STRIPE_BROWSER_LOAD_CHECK load=API_PATH cleanup=FAILED components=BROWSER_CLOSE,BUILD_DISPOSE
STRIPE_BROWSER_LOAD_CHECK load=NOT_CONFIRMED cleanup=FAILED components=RESPONSE_DISPOSE
STRIPE_BROWSER_LOAD_CHECK load=ORIGIN cleanup=OK components=NONE origin_reason=HOST_NOT_ALLOWED host_group=STRIPE_NETWORK
```

Only generic `load=ORIGIN` appends `origin_reason` and includes `originReason` in
the immutable private report. These are fixed categories, not addresses:

| Cause | Meaning |
| --- | --- |
| `URL_INVALID` | The existing URL parser rejected the request URL. |
| `NON_HTTPS_SCHEME` | Outside the allowed virtual loopback origin, the scheme is not HTTPS. |
| `NON_DEFAULT_PORT` | The HTTPS URL retains a non-default explicit port. |
| `HOST_NOT_ALLOWED` | Its HTTPS host is outside the unchanged provider allowlist. |
| `UNKNOWN` | The cause is absent, invalid or unavailable (including older reports). |

Only the pair `load=ORIGIN origin_reason=HOST_NOT_ALLOWED` additionally includes
immutable `originHostGroup` and appends `host_group` to stdout. This updates the
private report format only for that pair. It groups the already parsed refused
hostname without retaining its value:

| Group | Meaning and examples |
| --- | --- |
| `STRIPE_COM` | `stripe.com` apex or a dot-delimited subdomain; dedicated Stripe refusal categories still take precedence, so generic cases are mostly the apex. |
| `STRIPE_NETWORK` | `stripe.network` apex or a dot-delimited subdomain, such as `sub.stripe.network`; URL normalization handles case and explicit default port `:443`. |
| `OTHER` | Any other parsed hostname, including `notstripe.network`, `stripe.network.evil.test`, `stripe.network.` and unrelated domains. |
| `UNKNOWN` | Missing, invalid or unavailable group, including a throwing diagnostic getter or a legacy report without the field. |

Non-HTTPS, non-default-port and all other results keep their previous report
shape and CLI output. The new field cannot recover the unknown hostname or host
family from the historical `ab85027` report. The example above is synthetic and
does not authorize a refused request.

The existing refusal order is preserved. After successful parsing, authentication,
redirect, header, method, local-document and main-navigation gates run first. The
existing `ORIGIN_STRIPE_COM_M` and `ORIGIN_STRIPE_COM_OTHER` categories stay unchanged.
For generic `ORIGIN`, non-HTTPS precedes port, which precedes host refusal. The
first terminal observation owns the cause and conditional group; subsequent requests, page events and
cleanup cannot replace it. No hostname, URL, path, query or raw error is added to the public report or
stdout; no extra request or origin permission is added. The old `b50978a` receipt cannot be retrospectively
assigned one of these causes. The last example above is synthetic.

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

Deferred readiness covers initialization of the Element under this egress gate.
Payment, setup, renewal and recovery have separate acceptance checks. Allowed
provider requests can include telemetry.

## Nested document observation

On a separately authorized native run, add
`PAYMENT_STRIPE_BROWSER_LOAD_CHECK_BLOCK_NESTED_DOCUMENT=true` to the command
above. Only this mode reads the existing local origin policy key used to
authenticate the reviewed origin. The key is independent of any Stripe
credentials or previous run artifacts. The probe still uses the deferred
Element and the same Stripe.js load parameters.

The new mode aborts one matching document GET locally and continues observing
the Element within the original deadline. The request must have an empty body,
no credentials and no redirect. The destination must be attached below at
least one Stripe.js frame, with all non-main ancestors on the same checkout
page hosting Stripe.js documents. Frame identity and ancestor URLs are checked
before and after reading headers. An unmatched or repeated refusal still
ends the load. There is no network request, substitute response or retry for
the matching document.

The report appends `nested_document=BLOCKED` once Chrome reports the expected
local block for the same intercepted request, or `nested_document=NOT_OBSERVED`
otherwise. Returning from the abort call alone cannot confirm the block. If
the probe attempts a block and receives no matching event, it cannot report
readiness and uses the remaining original deadline. With `load=READY` and
`nested_document=BLOCKED`, readiness was observed with that document blocked
in this deferred configuration. With `nested_document=NOT_OBSERVED`, the load
result supplies no evidence about the document. This includes a run that
never encountered the request or could not authenticate its origin using the
local policy. Exit status still describes the load and cleanup. The probe
records no frame URL, requested URL, header or body. Readiness with a client
secret requires separate evidence from the
[recovery workflow](billing-failed-renewal-recovery.md#confirming-a-local-document-block).

### Recovery presentation

One ordinary-profile Chrome check with document blocking enabled reported
`load=READY cleanup=OK nested_document=NOT_OBSERVED`. Once the launcher
classified an eligible request, it would have waited for the local block
event or timed out. The ordinary probe disabled advanced fraud signals,
used deferred payment mode and destroyed its Element at the first `ready`.
The subscription payment driver uses Stripe.js defaults, a client secret, an
English locale, and disables Link as well as Apple Pay and Google Pay. It keeps
the form mounted. The evidence cannot tell which difference affected emission
or whether the request arrived too late to classify before the probe retired.
The historical payment checkpoint records the target's immediate Stripe.js
parent only. The card-free check requires stable ownership of the whole frame
chain and continues to require that proof.

For a separately authorized one-shot Chrome check, use both opt-ins:

```sh
PAYMENT_STRIPE_BROWSER_LOAD_CHECK=true \
PAYMENT_STRIPE_BROWSER_LOAD_CHECK_BLOCK_NESTED_DOCUMENT=true \
PAYMENT_STRIPE_BROWSER_LOAD_CHECK_RECOVERY_PRESENTATION=true \
PAYMENT_STRIPE_BROWSER_LOAD_CHECK_PUBLISHABLE_KEY=pk_test_REPLACE_WITH_EXPLICIT_PUBLIC_TEST_KEY \
node examples/demo-site/scripts/stripe-browser-load-check.mjs
```

This profile restores Stripe.js default load parameters and uses deferred
subscription mode with the same €20 amount, card-only configuration, English
locale and wallet settings. It keeps the Element inert after `ready` and waits
up to five seconds for a matching nested request, within the original deadline.
An in-flight header read for a candidate can finish within that same original
deadline.
If a matching request is intercepted, the launcher waits for confirmation of
the same local block and checks readiness again. All other refusals still end
the load, including any telemetry a default loader requests outside the
allowlist. The form still receives no client secret and provides no usable card
input, Pay control or confirmation method. The only Stripe API path admitted is
`/v1/elements/sessions`. There is no backend or CLI action.

The report adds `profile=RECOVERY_PRESENTATION`. Exit zero requires `READY`,
`BLOCKED` and successful cleanup. `READY / NOT_OBSERVED` still describes a
successful form load, but exits one because it did not reproduce the request.
The offline test injects a delayed synthetic navigation and verifies its
blocking without network transport.

One ordinary Chrome launch with the recovery presentation on the working tree
exited zero with this result:

```text
STRIPE_BROWSER_LOAD_CHECK load=READY cleanup=OK components=NONE nested_document=BLOCKED profile=RECOVERY_PRESENTATION
```

Chrome confirmed the local block for the intercepted request, and the deferred
subscription Element reached readiness with that document blocked. The check
used no card input, Pay, client secret or payment. Readiness with a client
secret and acceptance of the paid recovery workflow remain unverified.

## Optional private refusal witness

The native receipt identified by `BLYqSg` reported
`HOST_NOT_ALLOWED` / `STRIPE_NETWORK`; its exact hostname remains unidentified.
The subsequent single run at `142a361` captured an exact origin for its own
first refusal. It reported `load=ORIGIN cleanup=OK`. An offline comparison with
the saved origin reproduced `HOST_NOT_ALLOWED` in the request gate. The record
contains no request path, method or initiator.

The installed Stripe.js loader documents how to omit optional advanced fraud
signals at script initialization. The bundled-page regression checks the script
URL produced by that loader before any external request. The local evidence
does not identify which component requested the refused origin.

On 2026-11-19, one headed Google Chrome run with the patched probe reported
`load=READY cleanup=OK components=NONE` (exit 0). Run
`708bce3c-c1ba-40b4-b604-d5b4494fd162` used the working tree based on
`142a361`, with probe page SHA-256
`9057d66e417c58f3f13560a9a1441ade31b80e2a274c7a9743f09e4632862bc0`.
The Element emitted its ready event and cleanup completed. The run covered
only the card-free probe with optional advanced fraud signals disabled.

The launcher exposes two private harness interfaces, outside the SDK:
`runLoadCheck(env, ports)` accepts optional `ports.onRefusedNetworkOrigin(origin)`,
and `runNativeLoadCheck(env, observer?)` accepts an explicit trusted callback.
Native admission validates the environment and optional callback type before
build or browser acquisition. The default CLI calls the native entry without an
observer. There is no environment switch, automatic capture, filesystem writer
or default persistence.

Only the original winning `ORIGIN` / `HOST_NOT_ALLOWED` / `STRIPE_NETWORK`
refusal can offer an origin. The runner commits its terminal result and abort
signal before reading the observer once. It derives the candidate from the
already captured URL, without reading the request again. Validation requires a
raw ASCII HTTPS DNS authority, no userinfo, no non-default port, labels of 1–63
characters with alphanumeric ends, and total hostname length at most 253. Only
`stripe.network` or a dotted descendant qualifies. Trailing dots, empty labels,
underscores, malformed hyphens, lookalikes and non-ASCII authority normalization
are rejected. Case and explicit `:443` canonicalize; only `url.origin` is offered,
so paths, queries and fragments are discarded.

Other gates, malformed candidates, a preceding failure, readiness, timeout and
retirement never read the observer. A throwing getter or callback, or a rejected
return, cannot replace the refusal, change cleanup or trigger another offer.
The runner does not await the return; returned promises are defensively rejection
handled. The callback contract is a bounded synchronous writer. This defensive
handling does not grant asynchronous work any authority or completion guarantee.

A future caller requires separate explicit authorization for both external
Chrome/Stripe execution and private evidence capture. That caller must supply
its own trusted origin-only writer, create a fresh private receipt directory
with mode `0700`, and create the evidence file exclusively with mode `0600`.
It must bind the receipt to the actual invocation and source identity, and report
capture success or failure separately from the unchanged load/cleanup result.
It must not persist a URL path, query, fragment, headers, body, key or raw error.
Those caller responsibilities are not implemented or exercised here. A missing
witness or writer failure is not proof of host absence and does not authorize a
retry, policy widening or a loading-fix claim.
