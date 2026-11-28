@stripe-billing @billing-plan-boundary
Feature: A scheduled Stripe plan starts on its original paid boundary

  Scenario: The first target renewal settles exactly the scheduled period
    Given an owned Stripe Billing subscription with 0 trial days
    When its initial Stripe invoice is verified through a signed receipt
    And the customer schedules an allowed Stripe downgrade for the next period
    Then the exact Stripe schedule is projected without changing paid terms
    When the source period ends and the owned schedule bills its first target month
    Then only the settled target month changes the existing subscription entitlement
