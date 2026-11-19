Feature: Kill Bill Login Validation

  @Login
  Scenario Outline: Validate login with different credentials
    Given user navigates to the Kill Bill login page
    When user logs in with username "<username>" and password "<password>"
    Then login response should be either successful or invalid

    Examples:
      | username | password |
      | USER1     | PASS1    |
      | USER2     | PASS2    |