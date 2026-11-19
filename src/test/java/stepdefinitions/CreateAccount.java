
package stepdefinitions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.openqa.selenium.WebElement;
import org.openqa.selenium.WebDriver;
import org.testng.Assert;

import API.Login;
import API.account;
import API.create_accountApi;
import DB.DatabaseConnection;
import base.DriverFactory;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;
import io.restassured.response.Response;
import pages.Dashboard;

public class CreateAccount {

    private WebDriver driver;

    private final List<CreatedAccount> createdAccounts = new ArrayList<>();

    private static final String ACCOUNT_SQL_QUERY = """
            SELECT accounts.id, accounts.name, accounts.first_name_length,
                   accounts.external_key, account_emails.email,
                   accounts.billing_cycle_day_local, accounts.currency,
                   accounts.time_zone, accounts.locale, accounts.address1,
                   accounts.address2, accounts.postal_code, accounts.company_name,
                   accounts.city, accounts.state_or_province, accounts.country,
                   accounts.phone, accounts.notes
            FROM accounts
            LEFT JOIN account_emails ON accounts.id = account_emails.account_id
            WHERE accounts.id = ?
            """;

    // =========================================================
    // GIVEN
    // =========================================================

   @Given("user establishes a session for account creation using the login API")
public void user_is_logged_in_using_login_api() {

    driver = DriverFactory.getDriver();

    Login.bypassLogin(driver);

    System.out.println("Login completed using API.");
}

    // =========================================================
    // WHEN
    // =========================================================

    @When("user creates accounts using all configured test data")
    public void user_creates_accounts_using_all_configured_test_data() {

        createdAccounts.clear();

        int totalIterations = create_accountApi.getTotalIterations();

        Assert.assertTrue(
                totalIterations > 0,
                "No account test data iterations found."
        );

        for (int iteration = 0; iteration < totalIterations; iteration++) {

            System.out.printf(
                    "%n========== ACCOUNT ITERATION %d ==========%n",
                    iteration + 1
            );

            // Step 1: Validate GET /accounts/new page API
            Response getPageResponse = account.getNewAccountPage(driver);

            account.printResponseDetails(getPageResponse);
            account.validateNewAccountPage(getPageResponse);

            // Step 2: Create account through POST API
            Response createResponse =
                    create_accountApi.createAccount(driver, iteration);

            create_accountApi.printResponseDetails(createResponse);
            create_accountApi.validateCreateAccountResponse(createResponse);

            // Step 3: Extract account URL and ID
            String createdAccountUrl =
                    create_accountApi.getCreatedAccountUrl(createResponse);

            String accountId = extractAccountId(createdAccountUrl);

            String expectedExternalKey =
                    create_accountApi.getExpectedExternalKeyFromJson(iteration);

            createdAccounts.add(
                    new CreatedAccount(
                            createdAccountUrl,
                            accountId,
                            expectedExternalKey,
                            iteration
                    )
            );

            System.out.println("Account created successfully: " + accountId);
        }
    }

    // =========================================================
    // THEN
    // =========================================================

    @Then("each account should be verified on the UI and database")
    public void verify_each_account_on_ui_and_database() {

        Assert.assertFalse(
                createdAccounts.isEmpty(),
                "No accounts were created."
        );

        for (CreatedAccount accountData : createdAccounts) {

            System.out.printf(
                    "%n========== VERIFYING ACCOUNT ITERATION %d ==========%n",
                    accountData.iteration + 1
            );

            // Step 1: UI verification
            verifyAccountOnUi(
                    accountData.accountUrl,
                    accountData.expectedExternalKey,
                    accountData.iteration
            );

            // Step 2: Database verification
            verifyAccountInDatabase(
                    accountData.accountId,
                    accountData.expectedExternalKey
            );
        }
    }

    @Then("all created account records should be cleaned up")
    public void cleanup_all_created_accounts() {

        for (CreatedAccount accountData : createdAccounts) {

            deleteAccountFromDatabase(accountData.accountId);
        }

        System.out.println(
                "All created account records have been cleaned up."
        );
    }

    // =========================================================
    // HELPER METHODS
    // =========================================================

    private String extractAccountId(String url) {

        String cleanedUrl = url.replaceAll("/+$", "");

        String accountId = cleanedUrl.substring(
                cleanedUrl.lastIndexOf("/") + 1
        ).trim();

        Assert.assertFalse(
                accountId.isEmpty(),
                "Account ID could not be extracted from URL: " + url
        );

        System.out.println("Extracted Account ID: " + accountId);

        return accountId;
    }

    private void verifyAccountOnUi(
            String accountUrl,
            String expectedExternalKey,
            int iteration) {

        driver.get(accountUrl);

        Dashboard dashboard = new Dashboard(driver);

        WebElement container = dashboard.getAccountDetailsContainer();

        Assert.assertTrue(
                container.isDisplayed(),
                "Account details container is not displayed."
        );

        String uiText = container.getText();

        Assert.assertTrue(
                uiText.contains(expectedExternalKey),
                String.format(
                        "EXTERNAL KEY MISMATCH in iteration %d. Expected: %s | Actual UI: %s",
                        iteration + 1,
                        expectedExternalKey,
                        uiText
                )
        );

        printContainerContents(uiText);
    }

    private void verifyAccountInDatabase(
            String accountId,
            String expectedExternalKey) {

        try (
                Connection conn = DatabaseConnection.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement(ACCOUNT_SQL_QUERY)
        ) {

            stmt.setString(1, accountId);

            try (ResultSet rs = stmt.executeQuery()) {

                Assert.assertTrue(
                        rs.next(),
                        "Account ID " + accountId + " not found in database."
                );

                printDatabaseRow(rs);

                Assert.assertEquals(
                        rs.getString("id"),
                        accountId,
                        "Database account ID mismatch."
                );

                Assert.assertEquals(
                        rs.getString("external_key"),
                        expectedExternalKey,
                        "Database external key mismatch."
                );
            }

        } catch (Exception e) {

            Assert.fail(
                    "DATABASE VALIDATION FAILED: " + e.getMessage(),
                    e
            );
        }
    }

    private void deleteAccountFromDatabase(String accountId) {

        System.out.println(
                "\n========== DELETE ACCOUNT FROM DB =========="
        );

        String[] cleanupQueries = {

                "DELETE FROM audit_log WHERE account_record_id IN " +
                "(SELECT record_id FROM accounts WHERE id = ?)",

                "DELETE FROM account_email_history WHERE account_id = ?",

                "DELETE FROM account_emails WHERE account_id = ?",

                "DELETE FROM account_history WHERE id = ?",

                "DELETE FROM accounts WHERE id = ?"
        };

        try (Connection conn = DatabaseConnection.getConnection()) {

            conn.setAutoCommit(false);

            try {

                for (String sql : cleanupQueries) {

                    try (PreparedStatement stmt =
                                 conn.prepareStatement(sql)) {

                        stmt.setString(1, accountId);
                        stmt.executeUpdate();
                    }
                }

                conn.commit();

            } catch (Exception e) {

                conn.rollback();
                throw e;
            }

            // Confirm that the account no longer exists
            try (
                    PreparedStatement checkStmt =
                            conn.prepareStatement(
                                    "SELECT COUNT(*) FROM accounts WHERE id = ?"
                            )
            ) {

                checkStmt.setString(1, accountId);

                try (ResultSet rs = checkStmt.executeQuery()) {

                    rs.next();

                    Assert.assertEquals(
                            rs.getInt(1),
                            0,
                            "Account ID " + accountId +
                            " still exists after deletion."
                    );
                }
            }

            System.out.println(
                    "PASS: Account " + accountId +
                    " and related records deleted from MariaDB."
            );

        } catch (Exception e) {

            Assert.fail(
                    "DATABASE CLEANUP FAILED: " + e.getMessage(),
                    e
            );
        }
    }

    private void printContainerContents(String containerText) {

        System.out.println(
                "\n========== CREATED ACCOUNT DETAILS (UI) =========="
        );

        if (containerText == null || containerText.isBlank()) {

            System.out.println("No visible text found inside the locator.");
            return;
        }

        long lineCount = containerText.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .peek(line -> System.out.println("  • " + line))
                .count();

        System.out.println("Total visible lines: " + lineCount);
        System.out.println("==================================================\n");
    }

    private void printDatabaseRow(ResultSet rs) throws Exception {

        System.out.println(
                "\n========== DATABASE ACCOUNT DETAILS =========="
        );

        System.out.println("Account ID        : " + rs.getString("id"));
        System.out.println("Name              : " + rs.getString("name"));
        System.out.println("First Name Length : " + rs.getString("first_name_length"));
        System.out.println("External Key      : " + rs.getString("external_key"));
        System.out.println("Email             : " + rs.getString("email"));
        System.out.println("Billing Cycle Day : " + rs.getString("billing_cycle_day_local"));
        System.out.println("Currency          : " + rs.getString("currency"));
        System.out.println("Time Zone         : " + rs.getString("time_zone"));
        System.out.println("Locale            : " + rs.getString("locale"));
        System.out.println("Address 1         : " + rs.getString("address1"));
        System.out.println("Address 2         : " + rs.getString("address2"));
        System.out.println("Postal Code       : " + rs.getString("postal_code"));
        System.out.println("Company           : " + rs.getString("company_name"));
        System.out.println("City              : " + rs.getString("city"));
        System.out.println("State             : " + rs.getString("state_or_province"));
        System.out.println("Country           : " + rs.getString("country"));
        System.out.println("Phone             : " + rs.getString("phone"));
        System.out.println("Notes             : " + rs.getString("notes"));

        System.out.println("==============================================");
    }

    // =========================================================
    // ACCOUNT TEST DATA HOLDER
    // =========================================================

    private static class CreatedAccount {

        private final String accountUrl;
        private final String accountId;
        private final String expectedExternalKey;
        private final int iteration;

        private CreatedAccount(
                String accountUrl,
                String accountId,
                String expectedExternalKey,
                int iteration) {

            this.accountUrl = accountUrl;
            this.accountId = accountId;
            this.expectedExternalKey = expectedExternalKey;
            this.iteration = iteration;
        }
    }
}
