#!/bin/sh
# Explicit test-runtime inventory. Never export target/test-classes or a support package tree.
set -eu
[ "$#" = 2 ] || exit 64
source_dir=$1
destination=$2
[ -d "$source_dir" ] && [ ! -L "$source_dir" ] && [ ! -e "$destination" ] && [ ! -L "$destination" ] || exit 65
inventory='dev/genericpayments/acceptance/runtime/OwnedBillingFirstPaymentRuntime.class
dev/genericpayments/acceptance/runtime/OwnedBillingFirstPaymentRuntime$Admission.class
dev/genericpayments/acceptance/runtime/OwnedBillingFirstPaymentRuntime$Operations.class
dev/genericpayments/acceptance/runtime/OwnedBillingFirstPaymentRuntime$State.class
dev/genericpayments/acceptance/runtime/OwnedBillingFirstPaymentRuntime$Handler.class
dev/genericpayments/acceptance/runtime/OwnedBillingFirstPaymentRuntime$BillingTimeConfiguration.class
dev/genericpayments/acceptance/runtime/OwnedBillingFirstPaymentScenario.class
dev/genericpayments/platform/support/OwnedSubscriptionBillingTime.class'
# Validate the whole inventory before creating any export. Reject symlink ancestors too.
printf '%s\n' "$inventory" | while IFS= read -r class; do
  [ -f "$source_dir/$class" ] && [ ! -L "$source_dir/$class" ] || exit 66
  parent=$(dirname "$class")
  while [ "$parent" != . ]; do [ ! -L "$source_dir/$parent" ] || exit 66; parent=$(dirname "$parent"); done
done
actual=$(find "$source_dir/dev/genericpayments/acceptance/runtime" -maxdepth 1 \( -name 'OwnedBillingFirstPaymentRuntime.class' -o -name 'OwnedBillingFirstPaymentRuntime$*.class' -o -name 'OwnedBillingFirstPaymentScenario.class' -o -name 'OwnedBillingFirstPaymentScenario$*.class' \) | wc -l | tr -d ' ')
[ "$actual" = 7 ] || exit 67
mkdir "$destination"
printf '%s\n' "$inventory" | while IFS= read -r class; do
  mkdir -p "$destination/$(dirname "$class")"
  cp "$source_dir/$class" "$destination/$class"
done
