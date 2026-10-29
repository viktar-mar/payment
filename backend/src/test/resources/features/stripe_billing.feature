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

  Scenario: An ordinary optional method trial proves ZERO without cash
    Given an owned Stripe Billing subscription with 7 trial days
    Then its Stripe create acknowledgement has no cash or paid access
    When its original zero Stripe trial invoice is reconciled
    Then trial access exists without a cash payment or payment succeeded fact

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

  Scenario: A narrow owned Portal session preserves scope and grants no money on return
    Given an owned Stripe Billing subscription with 0 trial days
    When its initial Stripe invoice is verified through a signed receipt
    And the customer opens the allowed narrow Stripe Billing Portal
    Then its Portal configuration and session have original persisted provider proofs
    And only its original customer and public operation can read the Portal return
    And the Portal return leaves subscription cash and lifecycle facts unchanged
