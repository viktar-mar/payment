# Manual Pay handoff for the owned first-renewal check

Date: 2026-11-04. Offline preparation only; this document grants no Docker or Stripe execution.

The existing owned €20 initial payment → €20 first-renewal scenario remains the sole owner of credentials, one-shot CREATE/CONFIRM/advance authority, server evidence, cleanup and final acceptance. A dedicated Playwright config opens installed Chrome visibly. The runner performs the same OTP, prepare, mount and test-card entry. Pay stays disabled in manual mode until card entry is complete; then the runner enables it and waits for the operator to click once. The page disables Pay synchronously on click. No arbitrary browser action, second click, recovery, old operation or separate payment route is added.

The manual wait has a finite three-minute bound and ends when the page reports CONFIRMING, OBSERVE or STOPPED. It then enters the existing OBSERVE/terminal reader and server-proof sequence. Waiting out the bound, cancellation, navigation, page stop or browser loss consumes the attempt and closes its context. There is no automatic fallback click. The ordinary unattended config retains its current behavior and limits. This manual config is excluded from default Playwright discovery, has one worker, zero retries, no trace/screenshots/video, and does not start on import.

The operator sees only the dedicated local browser window and test payment form. No card digits, OTP, customer/session tokens, Stripe secrets, raw logs or browser artifacts are emitted to stdout. The existing fixed diagnostic reporter and full server proof remain authoritative. A visible form or Stripe response alone never establishes accepted payment or renewal. A separately authorized future Sandbox run is required to use this mode.
