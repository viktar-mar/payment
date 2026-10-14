Feature: Site-scoped Customer access through real HTTP, PostgreSQL and SMTP
  Background:
    Given two Customer-enabled Sites with published one-time prices

  Scenario: Signed Site token authenticates a Customer
    When a signed Site token is exchanged
    Then the current Customer is active and only safe fields are returned

  Scenario: OIDC binds the signed token to an attempt and nonce
    When an OIDC attempt and signed token are exchanged
    Then the current Customer is active and only safe fields are returned

  Scenario: Email authentication delivers through Mailpit
    When a Customer verifies an OTP delivered by Mailpit
    Then the current Customer is active and only safe fields are returned

  Scenario: Identical email addresses never merge across Sites
    When the same email authenticates on both Sites
    Then the two Customers and sessions are Site isolated

  Scenario: Explicit sessions fail closed across Site and exact Origin
    When a signed Site token is exchanged
    Then cross-Site checkout and cross-Origin Customer reads are rejected

  Scenario: Explicit linking attaches an unowned identity
    When a signed Site token is exchanged
    And another verified identity is explicitly linked
    Then one Customer owns two identities

  Scenario: Linking an owned identity conflicts without merging Customers
    When two Customers authenticate and one tries to link the other's identity
    Then linking conflicts and both Customer graphs remain separate

  Scenario: Catalog distinguishes authenticated, absent and invalid credentials
    When authenticated and guest catalog checkouts are created
    Then owned and guest Payments coexist and an invalid session creates no Payment

  Scenario: Payment history is isolated and cursor paginated
    When two Customers create their own catalog Payments
    Then history pages contain only the requesting Customer's Payments

  Scenario: Anonymization revokes access and retains financial facts
    When a Customer with an owned Payment is anonymized by the administrator
    Then the old session is rejected and Payment facts remain unchanged

  Scenario: Logout revokes the opaque session
    When a signed Site token is exchanged
    And the Customer logs out
    Then the old session is rejected

  Scenario: Site token replay is rejected after successful exchange
    When a signed Site token is exchanged twice
    Then the replay has the uniform authentication failure
