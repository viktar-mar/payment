# Manual PROVISION phase and runtime-stop evidence

## Intent and evidence

Diagnose the latest failed manual 3DS attempt without another runtime operation. The recorded outcome is PROVISION/UNKNOWN, exit 1/PENDING, no gateway URL or confirmation grant, runtime_closed=false; subsequent state-only inspection found STOPPED and seven exited owned containers. Preserve all historical evidence. UNKNOWN does not prove that credentials succeeded: it is both the post-credential stage and the fallback for unbranded failures, malformed replies or controller timeouts.

Source inspection establishes two independently testable gaps, not the precise historical runtime cause:

1. The actual provisioner resets its stage to UNKNOWN after credentials, masking every subsequent known preparation boundary.
2. The controller assigns runtime_closed during a 30-second cleanup slice only. Its already-owned stop promise can fulfill true during the existing final work drain, before the cleanup ceiling, without that evidence entering the report. Conversely, persisted STOPPED is insufficient: the bootstrap still has lock closure and a final deadline check after persisting that status.

## Minimal correction

For manual payments only, add five module-owned preparation stage labels: PRIVATE_STORAGE (directory/lock/initial save), ADMIN_SESSION (dispatch/login/cookies/ack), FIXTURE_SETUP (initial empty baseline through catalog/callback setup), CHECKOUT (the single checkout mutation and proof), FINAL_BASELINE (remaining baseline checks/save/handoff construction). Keep the existing four initial labels and UNKNOWN for unbranded errors. Change no provisioning sequence, predicates, effects or retry behavior. The diagnostic remains exactly {tag,stage}, at most 256 UTF-8 bytes; do not inspect dependency errors or emit raw content.

Capture the existing, once-only bootstrap-stop promise's literal-true fulfillment observed strictly before the original cleanup ceiling. Reconcile runtime_closed from that direct capability evidence before final report construction, including after its first 30-second slice timed out. No second stop, new wait, deadline extension, filesystem/status shortcut or post-report mutation. A timeout/failure remains latched: a corrected cleanup boolean never promotes PENDING to SUCCEEDED. Do not reconcile gateway/listener semantics or alter their ownership contracts.

## Constraints and non-goals

Offline only; no Docker commands, Sandbox/Stripe/network, installation, Python, keys/config, old payment/refund/archive or raw runtime-log reads. Use injected artificial fixtures. Preserve source/account/API/ownership/cookie/financial/journal/callback predicates, report version/shape and all existing limits (45-minute active authority, original plus two-minute cleanup ceiling, 30-second per-close slice, 10-second child, 45-second/30-probe financial proof, five-minute page). No new diagnostic subsystem or dependencies. Do not rerun or rewrite historical evidence or claim live root cause/5A acceptance. Reuse the current isolated feature worktree and preserve unrelated changes.

## Verification

Each task gets a failing behavioral regression first, a minimal change, focused green tests and independent spec/quality review. Cover ordinary and selected 3DS, finite diagnostic redaction/hostile errors, no follow-on effects or retries, direct stop fulfillment versus false/rejection/timeout, strict deadline boundary, and immutable reports after late completion. Finish with the existing Docker-free infrastructure regression selection and a fresh final cross-task review. No publishing or runtime launch is part of this request.
