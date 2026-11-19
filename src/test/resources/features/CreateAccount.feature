Feature: Kill Bill Account Creation

  @CreateAccount @API @UI @Database
  Scenario: Create and validate accounts using API, UI and database
    Given user establishes a session for account creation using the login API
    When user creates accounts using all configured test data
    Then each account should be verified on the UI and database
    And all created account records should be cleaned up