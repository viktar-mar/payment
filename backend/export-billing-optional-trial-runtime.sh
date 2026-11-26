#!/bin/sh
# Export only the compiled entry, its supporting classes, and its explicit Flyway schema.
set -eu
[ "$#" = 2 ] || exit 64
source_dir=$1
destination=$2
[ -d "$source_dir" ] && [ ! -L "$source_dir" ] && [ ! -e "$destination" ] && [ ! -L "$destination" ] || exit 65

runtime='OwnedBillingOptionalTrialRuntime.class
OwnedBillingOptionalTrialRuntime$Admission.class
OwnedBillingOptionalTrialRuntime$Gate.class
OwnedBillingOptionalTrialRuntime$Handler.class
OwnedBillingOptionalTrialRuntime$Operations.class
OwnedBillingOptionalTrialRuntime$RuntimeConfiguration.class
OwnedBillingOptionalTrialRuntime$ScopedClients.class
OwnedBillingOptionalTrialRuntime$ScopedClients$Ticket.class
OwnedBillingOptionalTrialRuntime$ScopedClients$TrackedTransport.class
OwnedBillingOptionalTrialScenario.class
OwnedBillingOptionalTrialScenario$CustomerScope.class'
billing='JdbcOwnedOptionalTrialCreateStore.class
OwnedOptionalTrialAcceptance.class
OwnedOptionalTrialAcceptance$Configuration.class
OwnedOptionalTrialAcceptance$PhaseFailure.class
OwnedOptionalTrialAcceptance$Session.class
OwnedOptionalTrialAcceptance$Snapshot.class
OwnedOptionalTrialAcceptance$Status.class
OwnedOptionalTrialAdmission.class
OwnedOptionalTrialAdmission$Admitted.class
OwnedOptionalTrialAdmission$Configuration.class
OwnedOptionalTrialAdmission$Request.class
OwnedOptionalTrialClockAdvance.class
OwnedOptionalTrialClockAdvance$Claim.class
OwnedOptionalTrialClockAdvance$Current.class
OwnedOptionalTrialClockAdvance$Original.class
OwnedOptionalTrialCreate.class
OwnedOptionalTrialCreate$Authority.class
OwnedOptionalTrialCreate$Created.class
OwnedOptionalTrialCreate$Scope.class
OwnedOptionalTrialCreate$Store.class
OwnedOptionalTrialPaymentConfirmation.class
OwnedOptionalTrialPaymentConfirmation$Candidate.class
OwnedOptionalTrialPaymentConfirmation$Configuration.class
OwnedOptionalTrialPaymentConfirmation$PaymentReceipt.class
OwnedOptionalTrialPublication.class
OwnedOptionalTrialPublication$Configuration.class
OwnedOptionalTrialPublication$Lifecycle.class
OwnedOptionalTrialPublication$LocalOperation.class
OwnedOptionalTrialPublication$PendingResume.class
OwnedOptionalTrialPublication$Receipt.class
OwnedOptionalTrialPublication$Verified.class
OwnedOptionalTrialPublication$VerifiedLifecycle.class
OwnedOptionalTrialResume.class
OwnedOptionalTrialResume$Admission.class
OwnedOptionalTrialResume$Attempt.class
OwnedOptionalTrialResume$Configuration.class
OwnedOptionalTrialResume$Failure.class
OwnedOptionalTrialResume$Receipt.class
OwnedOptionalTrialResume$Stage.class
OwnedOptionalTrialResumeGraph.class
OwnedOptionalTrialResumeGraph$Identity.class
OwnedOptionalTrialResumeGraph$Read.class
OwnedOptionalTrialResumeGraph$Settlement.class
OwnedOptionalTrialSetup.class
OwnedOptionalTrialSetup$Claim.class
OwnedOptionalTrialSetup$Paused.class
OwnedOptionalTrialSetup$Prepared.class
OwnedOptionalTrialSetup$SetupPermit.class
OwnedOptionalTrialSetup$State.class'
migrations='V1__owned_optional_trial_create.sql
V2__owned_optional_trial_acknowledgements.sql
V3__owned_optional_trial_pause.sql
V4__owned_optional_trial_resume.sql
V5__owned_optional_trial_setup.sql
V6__owned_optional_trial_clock_advance.sql
V7__owned_optional_trial_payment_confirmation.sql'
inventory=$(
  printf '%s\n' "$runtime" | sed 's|^|dev/genericpayments/acceptance/runtime/|'
  printf '%s\n' "$billing" | sed 's|^|dev/genericpayments/platform/provider/adapter/out/stripe/billing/|'
  printf '%s\n' 'dev/genericpayments/platform/support/OwnedSubscriptionBillingTime.class'
  printf '%s\n' "$migrations" | sed 's|^|db/owned-optional-trial/|'
)

# Validate every file and ancestor before creating any destination.
printf '%s\n' "$inventory" | while IFS= read -r path; do
  [ -f "$source_dir/$path" ] && [ ! -L "$source_dir/$path" ] || exit 66
  parent=$(dirname "$path")
  while [ "$parent" != . ]; do [ ! -L "$source_dir/$parent" ] || exit 66; parent=$(dirname "$parent"); done
done

# A newly generated nested class or migration must update the closed inventory.
families=$(printf '%s\n' "$inventory" | sed -n '/\.class$/s/\(\$.*\)\{0,1\}\.class$//p' | sort -u)
for family in $families; do
  for class in "$source_dir/$family.class" "$source_dir/$family"\$*.class; do
    [ -e "$class" ] || [ -L "$class" ] || continue
    printf '%s\n' "$inventory" | grep -Fxq "${class#"$source_dir/"}" || exit 67
  done
done
for migration in "$source_dir/db/owned-optional-trial/"*.sql; do
  printf '%s\n' "$inventory" | grep -Fxq "${migration#"$source_dir/"}" || exit 67
done

mkdir "$destination"
printf '%s\n' "$inventory" | while IFS= read -r path; do
  mkdir -p "$destination/$(dirname "$path")"
  cp "$source_dir/$path" "$destination/$path"
done
