@stripe-billing
Feature: Stripe Billing through the configured application and official SDK
  Provider acknowledgements and browser claims are not financial evidence.
  Only a fresh owned invoice, allocation, intent and captured charge can grant paid access.

  Scenario: Initial acknowledgement is not cash and repeated signed receipts are idempotent
    Given an owned Stripe Billing subscription with 0 trial days
    Then its Stripe create acknowledgement has no cash or paid access
    When its initial Stripe invoice is verified through a signed receipt
    Then one owned subscription cash payment and canonical callbacks exist
    When Stripe repeats the receipt and sends another hint for the same invoice
    Then its subscription cash, lifecycle facts and callbacks are unchanged
    And its original Stripe create key and Account ownership remain intact
    And its canonical Site callbacks are signed and delivered exactly once

  @billing-missed-webhook
  Scenario: Reconciliation recovers a missed invoice and reversed late events preserve the original cash
    Given an owned Stripe Billing subscription with 0 trial days
    Then its Stripe create acknowledgement has no cash or paid access
    When its paid Stripe invoice is reconciled before any webhook arrives
    Then one owned subscription cash payment and canonical callbacks exist
    When its delayed paid and earlier finalized Stripe notifications arrive in reverse order
    Then its subscription cash, lifecycle facts and callbacks are unchanged
    And its canonical Site callbacks are signed and delivered exactly once

  Scenario: An ordinary optional method trial expires and pauses without cash or debt
    Given an owned Stripe Billing subscription with 7 trial days
    Then its Stripe create acknowledgement has no cash or paid access
    When its original zero Stripe trial invoice is reconciled
    Then trial access exists without a cash payment or payment succeeded fact
    When the trial reaches its exact end without a payment method
    And Stripe reports the original trial subscription paused
    Then the same subscription is paused without debt or a new invoice or payment

  @billing-required-trial-isolation
  Scenario: A required trial starts only after its own card setup and leaves a sibling trial unchanged
    Given an owned Stripe Billing subscription for a required trial proof run
    When its original zero Stripe trial invoice is reconciled
    Then trial access exists without a cash payment or payment succeeded fact
    When the same customer requests a required-method Stripe trial for a second product
    Then the second trial has only its original setup receipt without cash or access
    When that exact setup succeeds and its original Stripe zero invoice is reconciled
    Then only the second trial owns the verified card and the sibling trial is unchanged
    And the PostgreSQL proof verifies only the exact required trial and no cash

  @billing-trial-resume
  Scenario: A paused optional trial resumes only after verified cash for its explicit resumption
    Given an owned Stripe Billing subscription with 7 trial days
    When its original zero Stripe trial invoice is reconciled
    And the trial reaches its exact end without a payment method
    And Stripe reports the original trial subscription paused
    Then the same subscription is paused without debt or a new invoice or payment
    When the customer sets up a card for that Stripe subscription
    Then the card is saved without resuming the subscription or collecting cash
    When the customer explicitly resumes and Stripe leaves its new invoice open
    Then the resumption remains pending without paid access
    When the original resumption invoice has verified cash
    Then that payment restores access once and preserves the original zero trial invoice

  Scenario: Verified renewal cash advances the existing subscription
    Given an owned Stripe Billing subscription with 0 trial days
    When its initial Stripe invoice is verified through a signed receipt
    And its next Stripe renewal has fully captured cash
    Then two distinct subscription payments cover the renewed period

  Scenario: Open renewal keeps fixed grace and an unsupported recovery stays closed
    Given an owned Stripe Billing subscription with 0 trial days
    When its initial Stripe invoice is verified through a signed receipt
    And its next Stripe renewal remains open without a supported payment method
    Then the renewal has fixed grace without another cash payment
    When the future Site grace policy is extended and the open invoice is observed again
    Then the original Stripe renewal grace deadline is unchanged
    When the customer requests recovery of that same open Stripe invoice twice
    Then recovery remains unknown without an invoice pay or browser retry authority

  Scenario: Period end cancellation and withdrawal preserve paid cash
    Given an owned Stripe Billing subscription with 0 trial days
    When its initial Stripe invoice is verified through a signed receipt
    And the customer schedules and withdraws Stripe period end cancellation
    Then the Stripe cancellation proofs preserve the paid period and cash

  Scenario: Narrow Site policy rejects change and Portal while Fake still routes independently
    Given an owned Stripe Billing subscription with 0 trial days
    When its initial Stripe invoice is verified through a signed receipt
    And the customer requests a disallowed Stripe plan change and Portal return
    Then neither request dispatches a Stripe mutation or grants new cash
    When an independent Fake subscription is bought in the same application
    Then Fake grants its paid access without using the Stripe transport

  Scenario: An immediate upgrade acknowledgement waits for exact proration cash
    Given an owned Stripe Billing subscription with 0 trial days
    When its initial Stripe invoice is verified through a signed receipt
    And the customer previews and confirms an allowed immediate Stripe upgrade
    Then its Stripe change acknowledgement preserves the original entitlement and cash
    When its exact Stripe proration invoice and captured charge are reconciled
    Then the upgraded entitlement has one additional cash payment and immutable change proof
    And its canonical Site callbacks are signed and delivered exactly once

  @billing-plan-schedule
  Scenario: A verified scheduled downgrade can be cleared before the paid boundary
    Given an owned Stripe Billing subscription with 0 trial days
    When its initial Stripe invoice is verified through a signed receipt
    And the customer schedules an allowed Stripe downgrade for the next period
    Then the exact Stripe schedule is projected without changing paid terms
    When the customer clears the original Stripe schedule before the paid boundary
    Then the release preserves the original Stripe cash and paid terms
    And its canonical Site callbacks are signed and delivered exactly once

  Scenario: A narrow owned Portal session preserves scope and grants no money on return
    Given an owned Stripe Billing subscription for Portal acceptance
    When its initial Stripe invoice is verified through a signed receipt
    And the customer opens the allowed narrow Stripe Billing Portal
    Then its Portal configuration and session have original persisted provider proofs
    And only its original customer and public operation can read the Portal return
    And the Portal return leaves subscription cash and lifecycle facts unchanged
