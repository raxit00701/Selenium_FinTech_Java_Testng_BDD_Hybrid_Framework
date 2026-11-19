
Feature: Kill Bill Payment Method Creation

  @CreatePaymentMethod @UI @Database
  Scenario: Create and validate payment methods using JSON test data
    Given a browser is ready to create payment methods
    When payment methods are created using all configured JSON test data
    Then all payment methods should be successfully saved and verified in the database
