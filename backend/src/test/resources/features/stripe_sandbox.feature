@stripe-sandbox
Feature: Stripe one-time settlement preserves platform authority
  # These are offline application/PostgreSQL/official-SDK HTTP fixture scenarios.
  # Genuine Stripe browser acceptance is a separate explicit opt-in gate.

  Scenario Outline: Browser acknowledgement never settles the original business payment
    Given an authorized Stripe "<source>" checkout for 2000 EUR minor units
    When its browser confirmation reports success
    And Stripe has not yet confirmed the intent on the server
    Then the platform payment is not succeeded
    When a signed Stripe success receipt is reconciled with the matching captured charge
    Then exactly one payment succeeded fact and one Site callback exist
    And the Stripe request and persisted payment retain "<source>" ownership
    Examples:
      | source              |
      | CATALOG             |
      | DYNAMIC             |
      | ANONYMOUS_DONATION  |
      | IDENTIFIED_DONATION |

  Scenario: Recoverable decline retries the same intent and SCA remains pending
    Given an authorized Stripe "CATALOG" checkout for 2000 EUR minor units
    When a verified decline is followed by another client attempt
    Then two client attempts share the original Stripe intent
    When Stripe requires action and then reports processing
    Then the platform payment is not succeeded
    When a signed Stripe success receipt is reconciled with the matching captured charge
    Then exactly one payment succeeded fact and one Site callback exist

  Scenario: Duplicate and out-of-order receipts preserve exactly one settlement
    Given an authorized Stripe "DYNAMIC" checkout for 2000 EUR minor units
    When a signed Stripe success receipt is reconciled with the matching captured charge
    And Stripe repeats success and sends an old decline
    Then exactly one payment succeeded fact and one Site callback exist

  Scenario: Missed webhook is recovered by the existing reconciliation path
    Given an authorized Stripe "CATALOG" checkout for 2000 EUR minor units
    When a missed Stripe success is retrieved by the administrator
    Then exactly one payment succeeded fact and one Site callback exist

  Scenario: Partial and remaining refunds require individual verified evidence
    Given an authorized Stripe "CATALOG" checkout for 2000 EUR minor units
    When a signed Stripe success receipt is reconciled with the matching captured charge
    And the administrator requests a Stripe refund of 700 minor units
    Then the synchronous Stripe refund acknowledgement adds no refunded money
    When the individual Stripe refund is verified
    And the administrator requests a Stripe refund of 1300 minor units
    And the individual Stripe refund is verified
    Then exactly 2000 minor units and two refund facts are confirmed

  Scenario: Unknown preparation past its retention window cannot create another intent
    Given a Stripe dynamic payment whose preparation response is unknown
    When its original provider idempotency window expires
    Then retrying preparation does not send another Stripe write

  Scenario: Fake and Stripe coexist without sharing settlement authority
    Given an authorized Stripe "CATALOG" checkout for 2000 EUR minor units
    When an independent Fake checkout is confirmed in the same application
    Then the Fake checkout uses server confirmation and Stripe remains unpaid
