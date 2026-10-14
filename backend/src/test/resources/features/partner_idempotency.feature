Feature: Partner and idempotent payment intents
  Background:
    Given a published one-time price for 4999 EUR cents

  Scenario: dynamic Partner payment
    When the trusted partner creates a dynamic payment for 735 EUR cents
    Then the partner reads the original order and 735 EUR cents

  Scenario: same idempotency key and same request
    When the guest creates and confirms a checkout
    And the guest replays both requests with their original keys
    Then only one payment exists for the site
    And the payment is "PROCESSING" with 1 attempt

  Scenario: same idempotency key and different request
    When the guest creates a checkout
    And the guest reuses the create key for another published price
    Then the response is an idempotency conflict
    And only one payment exists for the site
