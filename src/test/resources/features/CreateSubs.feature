
Feature: Kill Bill Subscription Creation

  @CreateSubscription @API @Database
  Scenario: Create and validate subscriptions
    Given user establishes a session for subscription creation using the login API
    When user creates subscriptions using all configured test data
    Then all subscriptions should be verified and cleaned up
