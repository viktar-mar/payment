Feature: Catalog one-time payments
  Background:
    Given a published one-time price for 4999 EUR cents

  Scenario: successful catalog payment
    When the guest creates and confirms a checkout
    And the provider reports "PAYMENT_SUCCEEDED"
    Then the payment is "SUCCEEDED" with 1 attempt
    And exactly 1 "PAYMENT_SUCCEEDED" journal fact exists

  Scenario: declined catalog payment
    When the guest creates and confirms a checkout
    And the provider reports "PAYMENT_FAILED"
    Then the payment is "FAILED" with 1 attempt

  Scenario: retry after recoverable decline on the same Payment
    When the guest creates a checkout
    And the provider declines the next confirmation recoverably
    And the guest confirms the checkout again
    And the provider reports "PAYMENT_SUCCEEDED"
    Then the payment is "SUCCEEDED" with 2 attempts
    And only one payment exists for the site

  Scenario: PaymentAccount routing
    When the guest creates and confirms a checkout
    Then the payment and provider operation use the configured account
