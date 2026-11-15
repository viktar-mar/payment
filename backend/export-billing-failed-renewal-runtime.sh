#!/bin/sh
# Closed test-runtime class inventory. No package-tree or fixture export.
set -eu
[ "$#" = 2 ] || exit 64
source_dir=$1
destination=$2
# Require lexical absolute paths so ancestor checks cannot skip a symlink through '..'.
check_ancestors() {
  case "$1" in /*) ;; *) return 1 ;; esac
  case "$1" in */../*|*/./*|*/..|*/.|*//*|*/) return 1 ;; esac
  path=$1
  while [ "$path" != / ]; do
    [ ! -L "$path" ] || return 1
    path=$(dirname "$path")
  done
}
check_ancestors "$source_dir" && check_ancestors "$destination" || exit 65
[ -d "$source_dir" ] && [ ! -e "$destination" ] && [ ! -L "$destination" ] && [ -d "$(dirname "$destination")" ] || exit 65
inventory='dev/genericpayments/acceptance/runtime/OwnedBillingFirstPaymentRuntime.class
dev/genericpayments/acceptance/runtime/OwnedBillingFirstPaymentRuntime$Admission.class
dev/genericpayments/acceptance/runtime/OwnedBillingFirstPaymentRuntime$Operations.class
dev/genericpayments/acceptance/runtime/OwnedBillingFirstPaymentRuntime$State.class
dev/genericpayments/acceptance/runtime/OwnedBillingFirstPaymentRuntime$Handler.class
dev/genericpayments/acceptance/runtime/OwnedBillingFirstPaymentRuntime$BillingTimeConfiguration.class
dev/genericpayments/acceptance/runtime/OwnedBillingFirstPaymentRuntime$StartupResources.class
dev/genericpayments/acceptance/runtime/OwnedBillingFirstPaymentScenario.class
dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalRuntime.class
dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalRuntime$Handler.class
dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalRuntime$BillingTimeConfiguration.class
dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalRuntime$ScopedClients.class
dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalRuntime$GuardedTransport.class
dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalOperations.class
dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalSandboxScenario.class
dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalSandboxScenario$Stage.class
dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalSandboxScenario$State.class
dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalComponents.class
dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalFailureArm.class
dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalFailureArm$Remote.class
dev/genericpayments/acceptance/runtime/FailureArmEvidence.class
dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalRecovery.class
dev/genericpayments/acceptance/runtime/OwnedBillingFailedRenewalRecovery$State.class
dev/genericpayments/platform/support/OwnedSubscriptionBillingTime.class
dev/genericpayments/platform/support/OwnedFirstRenewalBillingTime.class
dev/genericpayments/platform/support/OwnedFailedRenewalBillingTime.class
dev/genericpayments/platform/support/OwnedFailedRenewalBillingTime$Candidate.class
dev/genericpayments/platform/provider/adapter/out/persistence/OwnedFailedGraceBillingTime.class
dev/genericpayments/platform/provider/adapter/out/persistence/OwnedFailedGraceBillingTime$Candidate.class
dev/genericpayments/platform/subscriptions/adapter/out/persistence/OwnedGraceSubscriptionRows.class'
# Every class and package ancestor is validated before any destination is created.
printf '%s\n' "$inventory" | while IFS= read -r class; do
  [ -f "$source_dir/$class" ] && [ ! -L "$source_dir/$class" ] || exit 66
  check_ancestors "$source_dir/$class" || exit 66
done
# Closed nested-class closure for every exported owner, including the selector helpers.
printf '%s\n' "$inventory" | while IFS= read -r class; do
  case "$class" in *'$'*) continue ;; esac
  parent=$(dirname "$class")
  owner=$(basename "$class" .class)
  find "$source_dir/$parent" -maxdepth 1 -name "$owner\$*.class" -print | while IFS= read -r nested; do
    relative=${nested#"$source_dir"/}
    printf '%s\n' "$inventory" | grep -F -x -q "$relative" || exit 67
  done
done
mkdir "$destination"
printf '%s\n' "$inventory" | while IFS= read -r class; do
  mkdir -p "$destination/$(dirname "$class")"
  cp "$source_dir/$class" "$destination/$class"
done
