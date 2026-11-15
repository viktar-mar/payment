# Failed-renewal CREATE_ACK → CONFIRM diagnosis

The sole run `6a6fc2c2-50ca-49f5-b28d-5f06ceef9a28` reached CREATE_ACK, then exited about 15 seconds later without a CONFIRM dispatch receipt. The user reports automatic setup followed by page closure, without pressing Pay. The initiating historical cause is unknown; this is not evidence of an operator timeout or provider settlement.

Source inspection establishes an observability defect: the held-browser helper replaces failures with generic errors and aborts before preserving their checkpoint. The dedicated terminal formatter only accepts LISTENER persistence failures. Correct both losses without guessing at a payment behavior change.

## Approach

Reuse the existing process-local WeakMap browser diagnostic provenance and closed finite checkpoint vocabulary. Latch the first trusted controller checkpoint before abort or teardown; never inspect raw exception properties. Add bounded controller checkpoints around preparation, bridge delivery, readiness, synthetic-card filling, manual arming and manual waiting. A page STOPPED notification only identifies the controller's current checkpoint; it cannot supply diagnostic text, authorize work or establish payment success.

Extend only the dedicated failed-renewal formatter/parser to accept a terminal BROWSER checkpoint for an unaccepted initial payment, preserving LISTENER behavior. Emit only fixed stage/checkpoint/cleanup/witness fields after owner close, using the existing reporter provenance gate.

## Constraints and acceptance

- Offline source work and inert memory tests only. No Docker, native browser, Stripe, SQL execution, installs, Python, archives, keys or old operations.
- No retries, longer budgets, weaker route/session/identity checks, synthetic Pay, or changes to payment/CREATE/CONFIRM dispatch authority.
- Preserve original one-use manual-click flow, held-page revocation, seven-port ownership, immutable receipts and cleanup semantics.
- First failure wins even when abort, page loss or teardown follows. Raw error messages, URLs, tokens and payloads never enter diagnostics.
- Reject unknown/checkpoint-stage mismatch, forged error properties, nonterminal/accepted results, malformed/contradictory cleanup and untrusted reporter annotations.
- Regression tests reproduce swallowed action/session/ready/card/manual-arm failures with no CONFIRM dispatch and exactly one close. Existing successful two-phase journey remains green.
- Historical receipts remain unchanged. New diagnostics improve future evidence and do not retrospectively establish the original cause or close stage 5B.

Alternatives rejected: speculative payment fixes lack causal evidence; raw logs expose credentials; automatic reruns violate the explicit offline boundary.
