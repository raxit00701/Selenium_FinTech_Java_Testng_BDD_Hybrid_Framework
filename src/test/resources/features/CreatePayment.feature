Feature: Kill Bill Payment Creation and Validation

  @CreatePayment @UI @Database @API
  Scenario: Create payment and validate transaction details
    Given the browser is ready for payment creation
    When the payment is created and validated using payment JSON data
    Then the payment transaction should be successfully validated