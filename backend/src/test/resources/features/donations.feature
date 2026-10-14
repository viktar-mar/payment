Feature: Donations reuse the common payment pipeline without exposing donor messages
  Background:
    Given two Customer-enabled Sites with published one-time prices

  Scenario: Campaign lifecycle controls new admission
    Given a Donation Campaign in "DRAFT" with custom amounts true and anonymous access true
    Then the Donation Campaign public read returns 404
    When the Donation Campaign becomes "ACTIVE"
    Then the Donation Campaign public read returns 200
    When the Donation Campaign becomes "PAUSED"
    Then the Donation Campaign public read returns 404
    When the Donation Campaign becomes "ARCHIVED"
    Then the Donation Campaign public read returns 404

  Scenario Outline: Suggested and custom amount bounds
    Given a Donation Campaign in "ACTIVE" with custom amounts <custom> and anonymous access true
    When a Donation for <amount> cents with anonymous authority returns <status>
    Then exactly <pairs> atomic Donation and Payment pair exists
    Examples:
      | custom | amount | status | pairs |
      | false  | 500    | 201    | 1     |
      | false  | 501    | 422    | 0     |
      | true   | 501    | 201    | 1     |
      | true   | 99     | 422    | 0     |
      | true   | 2001   | 422    | 0     |

  Scenario: Anonymous policy and invalid session never fall back
    Given a Donation Campaign in "ACTIVE" with custom amounts true and anonymous access false
    When a Donation for 500 cents with anonymous authority returns 422
    And a Donation for 500 cents with invalid authority returns 401
    Then exactly 0 atomic Donation and Payment pair exists
    When a Customer verifies an OTP delivered by Mailpit
    And a Donation for 500 cents with Customer authority returns 201
    Then the Donation belongs only to the authenticated Customer
    And the Donation message is encrypted and no plaintext enters financial sinks

  Scenario: Identity-aware replay and isolation
    Given a Donation Campaign in "ACTIVE" with custom amounts true and anonymous access true
    When the same email authenticates on both Sites
    And a Donation for 500 cents with Customer authority returns 201
    And the Customer Donation intent is replayed and changed identity conflicts
    Then Customer Partner and Admin Donation reads enforce ownership without messages

  Scenario: Atomic rollback releases the Donation intent
    Given a Donation Campaign in "ACTIVE" with custom amounts true and anonymous access true
    When a Donation insert fails and the same intent is retried
    Then the Donation message is encrypted and no plaintext enters financial sinks

  Scenario: Success, signed callback, duplicate event, refund and explicit redaction
    Given a Donation Campaign in "ACTIVE" with custom amounts true and anonymous access true
    And a site callback that verifies signatures
    When a Donation for 500 cents with anonymous authority returns 201
    And the Donation checkout is confirmed
    And the provider reports "PAYMENT_SUCCEEDED"
    And the provider repeats the same event
    And pending callbacks are delivered
    Then the succeeded callback is verified exactly once
    And the signed Donation callback has strict message-free version two fields
    When the administrator explicitly redacts the Donation message
    And the administrator refunds 500 EUR cents
    And the provider confirms the refund
    Then the payment is "REFUNDED" with 1 attempt

  Scenario: Recoverable provider failure permits a fresh attempt
    Given a Donation Campaign in "ACTIVE" with custom amounts true and anonymous access true
    When a Donation for 500 cents with anonymous authority returns 201
    And the provider declines the next confirmation recoverably
    And the guest confirms the checkout again
    And the provider reports "PAYMENT_SUCCEEDED"
    Then the payment is "SUCCEEDED" with 2 attempts

  Scenario: Final provider failure remains financial truth
    Given a Donation Campaign in "ACTIVE" with custom amounts true and anonymous access true
    When a Donation for 500 cents with anonymous authority returns 201
    And the Donation checkout is confirmed
    And the provider reports "PAYMENT_FAILED"
    Then the payment is "FAILED" with 1 attempt

  Scenario: Unknown provider result resolves only through reconciliation
    Given a Donation Campaign in "ACTIVE" with custom amounts true and anonymous access true
    When a Donation for 500 cents with anonymous authority returns 201
    And Donation confirmation has an unknown provider result
    Then another Donation confirmation cannot create a second attempt
    When the provider truth is reconciled as succeeded
    Then the payment is "SUCCEEDED" with 1 attempt

  Scenario: Customer anonymization removes only mutable message context
    Given a Donation Campaign in "ACTIVE" with custom amounts true and anonymous access true
    When a signed Site token is exchanged
    And a Donation for 500 cents with Customer authority returns 201
    And the Donation checkout is confirmed
    And the provider reports "PAYMENT_SUCCEEDED"
    And the Donation Customer is anonymized
    Then the old session is rejected
    And the payment is "SUCCEEDED" with 1 attempt
