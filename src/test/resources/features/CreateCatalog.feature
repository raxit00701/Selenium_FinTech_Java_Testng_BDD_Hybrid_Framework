Feature: Kill Bill Catalog Creation

  @CreateCatalog @UI @Database
  Scenario: Create and validate catalog using JSON test data
    When catalogs are created and their XML configuration is validated using all JSON test data
    Then every created catalog should be soft deleted in the database