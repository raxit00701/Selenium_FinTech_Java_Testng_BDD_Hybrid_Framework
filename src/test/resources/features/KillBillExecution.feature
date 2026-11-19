
Feature: Kill Bill End-to-End Execution

  @Login
  Scenario Outline: Validate login with different credentials
    Given user navigates to the Kill Bill login page
    When user logs in with username "<username>" and password "<password>"
    Then login response should be either successful or invalid

    Examples:
      | username | password |
      | USER1    | PASS1    |
      | USER2    | PASS2    |


  @Dashboard @Smoke
  Scenario: Validate dashboard after API login
    Given user is logged in using the login API
    When the dashboard is loaded
    Then the Get Started heading should be displayed


  @CreateAccount @API @UI @Database
  Scenario: Create and validate accounts using API, UI and database
    Given user establishes a session for account creation using the login API
    When user creates accounts using all configured test data
    Then each account should be verified on the UI and database
    And all created account records should be cleaned up


  @CreateCatalog @UI @Database
  Scenario: Create and validate catalog using JSON test data
    When catalogs are created and their XML configuration is validated using all JSON test data
    Then every created catalog should be soft deleted in the database


  @CreateSubscription @API @Database
  Scenario: Create and validate subscriptions
    Given user establishes a session for subscription creation using the login API
    When user creates subscriptions using all configured test data
    Then all subscriptions should be verified and cleaned up


  @CreatePaymentMethod @UI @Database
  Scenario: Create and validate payment methods using JSON test data
    Given a browser is ready to create payment methods
    When payment methods are created using all configured JSON test data
    Then all payment methods should be successfully saved and verified in the database


  @CreateInvoice @UI @API @Database
  Scenario: Create and validate invoices using JSON test data
    Given a browser is ready for invoice creation
    When invoices are created using all configured JSON test data
    Then all invoices should be successfully validated in the UI and database


  @CreatePayment @UI @Database @API
  Scenario: Create payment and validate transaction details
    Given the browser is ready for payment creation
    When the payment is created and validated using payment JSON data
    Then the payment transaction should be successfully validated
