@subscriptions
Feature: Site-owned subscriptions and confirmed product access
  Background:
    Given an authenticated customer of a subscription-enabled site

  Scenario: Optional trial ends without debt and resumes only explicitly
    Given a monthly subscription price with a 7 day optional-method trial
    When the customer creates the subscription with recurring consent
    And provider facts are processed
    Then access is allowed because of trial
    And no cash payment exists for the trial
    When time reaches trial end without a payment method
    And provider facts are processed
    Then the subscription is paused and access is denied
    And no renewal debt exists
    When a method setup is confirmed
    Then the subscription remains paused
    When the customer explicitly resumes and the first invoice is paid
    And provider facts are processed
    Then access is allowed for the new paid period without another trial

  Scenario: Required method trial consumes eligibility only after confirmation
    Given a monthly subscription price with a 7 day required-method trial
    When the customer creates the subscription with recurring consent
    And provider facts are processed
    Then access is denied pending initial confirmation
    When the original subscription action is confirmed
    And provider facts are processed
    Then access is allowed because of trial
    And no cash payment exists for the trial

  Scenario Outline: Confirmed recurring periods produce one cash payment per invoice
    When the customer buys a paid "<interval>" subscription
    Then access is allowed because of paid cash
    When the provider reaches the next billing period
    Then exactly 2 subscription cash payments exist
    And repeated provider delivery does not duplicate cash or lifecycle facts
    Examples:
      | interval |
      | MONTH    |
      | YEAR     |

  Scenario: Failed renewal uses fixed grace and recovery cannot double charge
    When the customer buys a paid "MONTH" subscription
    And the next renewal fails
    Then access is allowed during fixed grace
    When the site extends its future grace policy
    Then the current bill keeps its original grace deadline
    When time reaches the fixed grace deadline
    Then subscription access is denied
    When the customer recovers the same unpaid invoice twice
    Then access is allowed because of paid cash
    And recovery created one successful cash charge

  Scenario: Product slot remains reserved until confirmed cancellation
    When the customer buys a paid "MONTH" subscription
    Then another subscription for that product is rejected
    And a different product can be subscribed independently
    When the first subscription is immediately cancelled
    Then subscription access is denied
    And its product slot is released

  Scenario Outline: A plan upgrade applies only confirmed price and cash facts
    When the customer buys a paid "MONTH" subscription
    And a same-product upgrade is confirmed with "<settlement>"
    Then the upgrade grants "<entitlement>" with <payments> cash payments
    Examples:
      | settlement             | entitlement | payments |
      | SUCCESS                | target      | 2        |
      | ZERO_PRORATION         | target      | 1        |
      | UNSUPPORTED_SETTLEMENT | original    | 1        |

  Scenario: Scheduled unpaid target retains original entitled price during grace
    When the customer buys a paid "MONTH" subscription
    And a downgrade is scheduled for the next period
    And the next renewal fails
    Then the target is billed while the original price remains entitled
    And access is allowed during fixed grace

  Scenario: Expired preview cannot dispatch a plan change
    When the customer buys a paid "MONTH" subscription
    And an upgrade quote expires before confirmation
    Then the original price and single cash payment remain

  Scenario: Cancellation withdrawal preserves the paid period
    When the customer buys a paid "MONTH" subscription
    And cancellation is scheduled and then withdrawn
    Then access is allowed because of paid cash
    And the paid period and cash count are unchanged

  Scenario: Method setup and Portal navigation do not pay renewal debt
    When the customer buys a paid "MONTH" subscription
    And the next renewal fails
    When a method setup is confirmed
    And a management Portal is opened
    Then the renewal debt remains unpaid

  Scenario: Late cash cannot resurrect a confirmed terminal subscription
    When the customer buys a paid "MONTH" subscription
    And the first subscription is immediately cancelled
    And a previously captured paid provider fact arrives late
    Then subscription access is denied
    And exactly 1 subscription cash payments exist

  Scenario Outline: Initial outcomes are not confused with paid entitlement
    When initial creation returns "<outcome>"
    Then the initial operation is "<status>" with <payments> cash payments and access <allowed>
    Examples:
      | outcome         | status          | payments | allowed |
      | SUCCESS         | SUCCEEDED       | 1        | true    |
      | DECLINE         | FAILED          | 1        | false   |
      | REQUIRES_ACTION | REQUIRES_ACTION | 1        | false   |
      | UNKNOWN         | UNKNOWN         | 0        | false   |

  Scenario: UNKNOWN create resolves after a real process restart without a second charge
    When initial creation returns "UNKNOWN"
    Then another subscription for that product is rejected
    When a fresh worker process observes provider success for the original operation
    Then access is allowed because of paid cash
    And exactly 1 subscription cash payments exist
    And repeated provider delivery does not duplicate cash or lifecycle facts

  Scenario: Old paid facts cannot undo a newer renewal
    When the customer buys a paid "MONTH" subscription
    And the provider reaches the next billing period
    And a previously captured paid provider fact arrives late
    Then the renewed period remains authoritative
    And exactly 2 subscription cash payments exist

  Scenario: Refund is independent from subscription cancellation
    When the customer buys a paid "MONTH" subscription
    And the administrator fully refunds its confirmed subscription payment
    Then access is allowed because of paid cash
    And the paid period and cash count are unchanged

  Scenario: Persisted old scopes and foreign Site authority cannot operate a subscription
    When the customer buys a paid "MONTH" subscription
    Then a foreign Site cannot read or mutate that subscription
    And an old session without subscription scopes remains unauthorized

  Scenario: Anonymization competes with the live subscription lifecycle lock
    When the customer buys a paid "MONTH" subscription
    Then concurrent anonymization cannot erase the live subscription owner
    When the first subscription is immediately cancelled
    Then anonymization succeeds without removing historical cash

  Scenario: Populated current migration state preserves the subscription ledger
    When the customer buys a paid "MONTH" subscription
    Then migration validation preserves its cash and lifecycle identities

  Scenario: Signed cash and lifecycle callbacks replay without duplicate source facts
    Given subscription callbacks are enabled
    When the customer buys a paid "MONTH" subscription
    Then signed version three and four callbacks contain only canonical fields
    And repeated provider delivery does not duplicate cash or lifecycle facts

  Scenario: Legacy signed version one callbacks remain executable
    Given a published one-time price for 500 EUR cents
    And a site callback that verifies signatures
    When the guest creates and confirms a checkout
    And the provider reports "PAYMENT_SUCCEEDED"
    And pending callbacks are delivered
    Then the succeeded callback is verified exactly once

  Scenario: Legacy signed version two Donation callbacks remain executable
    Given two Customer-enabled Sites with published one-time prices
    And a Donation Campaign in "ACTIVE" with custom amounts true and anonymous access true
    And a site callback that verifies signatures
    When a Donation for 500 cents with anonymous authority returns 201
    And the Donation checkout is confirmed
    And the provider reports "PAYMENT_SUCCEEDED"
    And pending callbacks are delivered
    Then the succeeded callback is verified exactly once
    And the signed Donation callback has strict message-free version two fields
