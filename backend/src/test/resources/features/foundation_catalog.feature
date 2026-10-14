Feature: Manage an isolated site catalog

  Scenario: An administrator publishes the first monthly price
    Given an active fake payment account "local-fake"
    And an authenticated administrator
    When the administrator creates site "demo-site"
    And creates product "pro" for site "demo-site"
    And creates monthly price "pro-monthly" for 1999 EUR cents with a 14 day trial
    And publishes price "pro-monthly"
    Then price "pro-monthly" version 1 is published
    And a fake provider product mapping exists
    And a fake provider price mapping exists
    And the audit log contains the catalog changes

  Scenario: A changed amount creates a new draft version
    Given site "demo-site" has published price "pro-monthly" version 1 for 1999 EUR cents
    When the administrator creates price "pro-monthly" for 2499 EUR cents
    Then price "pro-monthly" version 1 remains published for 1999 EUR cents
    And price "pro-monthly" version 2 is draft for 2499 EUR cents
