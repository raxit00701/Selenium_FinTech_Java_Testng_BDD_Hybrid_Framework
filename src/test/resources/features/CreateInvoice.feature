
Feature: Kill Bill Invoice Creation and Validation

  @CreateInvoice @UI @API @Database
  Scenario: Create and validate invoices using JSON test data
    Given a browser is ready for invoice creation
    When invoices are created using all configured JSON test data
    Then all invoices should be successfully validated in the UI and database
