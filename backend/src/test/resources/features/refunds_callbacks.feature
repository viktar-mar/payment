Feature: Refunds and signed site callbacks
  Background:
    Given a published one-time price for 4999 EUR cents

  Scenario: full refund
    Given a succeeded catalog payment
    When the administrator refunds 4999 EUR cents
    And the provider confirms the refund
    Then the payment is "REFUNDED" with 1 attempt
    And exactly 1 "REFUND_SUCCEEDED" journal fact exists

  Scenario: partial refunds and over-refund rejection
    Given a succeeded catalog payment
    When the administrator refunds 2000 EUR cents
    And the provider confirms the refund
    And the administrator attempts to refund 3000 EUR cents above the remaining balance
    Then the refund exceeds the available amount
    And the payment is "PARTIALLY_REFUNDED" with 1 attempt
    When the administrator refunds 2999 EUR cents
    And the provider confirms the refund
    Then the payment is "REFUNDED" with 1 attempt
    And exactly 2 "REFUND_SUCCEEDED" journal fact exists

  Scenario: callback success
    Given a site callback that verifies signatures
    When the guest creates and confirms a checkout
    And the provider reports "PAYMENT_SUCCEEDED"
    And pending callbacks are delivered
    Then the succeeded callback is verified exactly once

  Scenario: callback retries followed by DEAD
    Given a site callback that returns unavailable
    When the guest creates and confirms a checkout
    And the provider reports "PAYMENT_SUCCEEDED"
    And callback retries exhaust the delivery schedule
    Then the succeeded callback is dead after six attempts
    And the payment is "SUCCEEDED" with 1 attempt
