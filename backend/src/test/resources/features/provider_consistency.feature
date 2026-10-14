Feature: Durable provider truth
  Background:
    Given a published one-time price for 4999 EUR cents

  Scenario: duplicate provider event
    When the guest creates and confirms a checkout
    And the provider reports "PAYMENT_SUCCEEDED"
    And the provider repeats the same event
    Then the payment is "SUCCEEDED" with 1 attempt
    And exactly 1 "PAYMENT_SUCCEEDED" journal fact exists

  Scenario: out-of-order provider event
    When the guest creates and confirms a checkout
    And the provider reports "PAYMENT_SUCCEEDED"
    And the provider reports a delayed processing event
    Then the payment is "SUCCEEDED" with 1 attempt
    And no regressive journal or callback fact is added

  Scenario: unknown result and reconciliation
    When the guest creates a checkout
    And confirmation has an unknown provider result
    Then another confirmation cannot create a second attempt
    When the provider truth is reconciled as succeeded
    Then the payment is "SUCCEEDED" with 1 attempt
    And exactly 1 "PAYMENT_SUCCEEDED" journal fact exists
