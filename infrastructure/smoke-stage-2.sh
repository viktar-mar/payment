#!/usr/bin/env bash
set -euo pipefail
umask 077
root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
material="$(mktemp -d "${TMPDIR:-/tmp}/payment-stage2-smoke.XXXXXX")"
cleanup() {
  rm -f -- "$material/session.json" "$material/checkout.json" "$material/callback.json"
  rmdir -- "$material"
}
trap cleanup EXIT
export PAYMENT_SMOKE_TMP="$material"
cd -- "$root"
node infrastructure/smoke-stage-2.mjs
