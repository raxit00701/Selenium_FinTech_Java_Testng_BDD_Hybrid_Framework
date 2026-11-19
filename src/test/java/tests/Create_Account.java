package tests;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.stream.IntStream;

import org.openqa.selenium.WebElement;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import API.Login;
import API.account;
import API.create_accountApi;
import DB.DatabaseConnection;
import base.Base;
import io.restassured.response.Response;
import pages.Dashboard;
import utils.Videocapture.Video;
import utils.screenshot.AutoScreenshots;
import utils.screenshot.Screenshot;

public class Create_Account extends Base {

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

    @DataProvider(name = "accountTestData")
    public Object[][] accountTestData() {
        return IntStream.range(0, create_accountApi.getTotalIterations())
                .mapToObj(i -> new Object[]{i})
                .toArray(Object[][]::new);
    }

    @Test(priority = 3,dataProvider = "accountTestData")
    @Video
    @Screenshot
    @AutoScreenshots
    public void createAccountApiTest(int iteration) {
        System.out.printf("%n========== ACCOUNT ITERATION %d ==========%n", iteration + 1);

        // Step 1: Login via session bypass
        Login.bypassLogin(driver);
        Assert.assertNotNull(driver, "WebDriver is not initialized.");

        // Step 2: Validate GET /accounts/new page API
        Response getPageResponse = account.getNewAccountPage(driver);
        account.printResponseDetails(getPageResponse);
        account.validateNewAccountPage(getPageResponse);

        // Step 3: Create account via POST API
        Response createResponse = create_accountApi.createAccount(driver, iteration);
        create_accountApi.printResponseDetails(createResponse);
        create_accountApi.validateCreateAccountResponse(createResponse);

        // Step 4: Extract created Account URL & ID
        String createdAccountUrl = create_accountApi.getCreatedAccountUrl(createResponse);
        String accountId = extractAccountId(createdAccountUrl);
        String expectedExternalKey = create_accountApi.getExpectedExternalKeyFromJson(iteration);

        // Step 5 & 6: Verify account on UI
        verifyAccountOnUi(createdAccountUrl, expectedExternalKey, iteration);

        // Step 7: Verify account in MariaDB
        verifyAccountInDatabase(accountId, expectedExternalKey);

        // Step 8: Remove account data from MariaDB entirely
        deleteAccountFromDatabase(accountId);

        System.out.printf("%n========== ITERATION %d PASSED ==========%n", iteration + 1);
    }

    // =========================================================
    // HELPER METHODS
    // =========================================================

    private String extractAccountId(String url) {
        String cleanedUrl = url.replaceAll("/+$", "");
        String accountId = cleanedUrl.substring(cleanedUrl.lastIndexOf("/") + 1).trim();

        Assert.assertFalse(accountId.isEmpty(), "Account ID could not be extracted from URL: " + url);
        System.out.println("Extracted Account ID: " + accountId);
        return accountId;
    }

    private void verifyAccountOnUi(String accountUrl, String expectedExternalKey, int iteration) {
        driver.get(accountUrl);

        Dashboard dashboard = new Dashboard(driver);
        WebElement container = dashboard.getAccountDetailsContainer();

        Assert.assertTrue(container.isDisplayed(), "Account details container is not displayed.");

        String uiText = container.getText();
        Assert.assertTrue(
                uiText.contains(expectedExternalKey),
                String.format("EXTERNAL KEY MISMATCH in iteration %d. Expected: %s | Actual UI: %s",
                        iteration + 1, expectedExternalKey, uiText)
        );

        printContainerContents(uiText);
    }

    private void verifyAccountInDatabase(String accountId, String expectedExternalKey) {
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(ACCOUNT_SQL_QUERY)) {

            stmt.setString(1, accountId);

            try (ResultSet rs = stmt.executeQuery()) {
                Assert.assertTrue(rs.next(), "Account ID " + accountId + " not found in database.");

                printDatabaseRow(rs);

                Assert.assertEquals(rs.getString("id"), accountId, "Database account ID mismatch.");
                Assert.assertEquals(rs.getString("external_key"), expectedExternalKey, "Database external key mismatch.");
            }
        } catch (Exception e) {
            Assert.fail("DATABASE VALIDATION FAILED: " + e.getMessage(), e);
        }
    }

    private void deleteAccountFromDatabase(String accountId) {
        System.out.println("\n========== STEP 8: DELETE ACCOUNT FROM DB ==========");

        String[] cleanupQueries = {
                "DELETE FROM audit_log WHERE account_record_id IN (SELECT record_id FROM accounts WHERE id = ?)",
                "DELETE FROM account_email_history WHERE account_id = ?",
                "DELETE FROM account_emails WHERE account_id = ?",
                "DELETE FROM account_history WHERE id = ?",
                "DELETE FROM accounts WHERE id = ?"
        };

        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);

            try {
                for (String sql : cleanupQueries) {
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, accountId);
                        stmt.executeUpdate();
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }

            // Confirm the account row is completely gone
            try (PreparedStatement checkStmt = conn.prepareStatement("SELECT COUNT(*) FROM accounts WHERE id = ?")) {
                checkStmt.setString(1, accountId);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    rs.next();
                    Assert.assertEquals(
                            rs.getInt(1),
                            0,
                            "Account ID " + accountId + " still exists in the database after deletion."
                    );
                }
            }

            System.out.println("PASS: Account " + accountId + " and all related records deleted from MariaDB.");

        } catch (Exception e) {
            Assert.fail("DATABASE CLEANUP FAILED: " + e.getMessage(), e);
        }
    }

    private void printContainerContents(String containerText) {
        System.out.println("\n========== CREATED ACCOUNT DETAILS (UI) ==========");
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
        System.out.println("\n========== DATABASE ACCOUNT DETAILS ==========");
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
}