Feature: Kill Bill Dashboard Validation

  @Dashboard @Smoke
  Scenario: Validate dashboard after API login
    Given user is logged in using the login API
    When the dashboard is loaded
    Then the Get Started heading should be displayed