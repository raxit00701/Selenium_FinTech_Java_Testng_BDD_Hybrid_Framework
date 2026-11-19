
package stepdefinitions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

import org.openqa.selenium.WebDriver;
import org.testng.Assert;

import API.Login;
import API.catalog;
import API.deletecatalog;
import API.subscription;
import DB.DatabaseConnection;
import base.DriverFactory;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;
import io.restassured.response.Response;

public class CreateSubs {

    private WebDriver driver;
    private boolean allSubscriptionsProcessed = false;

    private static final String SUBSCRIPTION_SQL_QUERY = """
            SELECT record_id, id, bundle_id, external_key, category,
                   start_date, bundle_start_date, charged_through_date,
                   migrated, created_by, created_date, updated_by,
                   updated_date, account_record_id, tenant_record_id
            FROM subscriptions
            WHERE external_key = ?
            ORDER BY record_id DESC
            LIMIT 1
            """;

    // =========================================================
    // STEP 1: LOGIN
    // =========================================================

    @Given("user establishes a session for subscription creation using the login API")
    public void user_establishes_session_for_subscription_creation() {

        driver = DriverFactory.getDriver();

        Assert.assertNotNull(driver, "WebDriver is not initialized.");

        Login.bypassLogin(driver);

        System.out.println("Login completed using API.");
    }

    // =========================================================
    // STEP 2: CREATE SUBSCRIPTIONS USING ALL TEST DATA
    // =========================================================

    @When("user creates subscriptions using all configured test data")
    public void user_creates_subscriptions_using_all_configured_test_data() {

        driver = DriverFactory.getDriver();

        Assert.assertNotNull(driver, "WebDriver is not initialized.");

        int totalIterations = subscription.getTotalIterations();

        for (int iteration = 0; iteration < totalIterations; iteration++) {

            createSubscriptionApiTest(iteration);
        }

        allSubscriptionsProcessed = true;
    }

    private void createSubscriptionApiTest(int iteration) {

        System.out.printf(
                "%n========== SUBSCRIPTION ITERATION %d ==========%n",
                iteration + 1
        );

        Map<String, Object> testData = subscription.getTestData(iteration);

        String accountId = String.valueOf(testData.get("account_id")).trim();

        String expectedExternalKey = String.valueOf(
                testData.get("external_key")
        ).trim();

        String expectedCategory = String.valueOf(
                testData.getOrDefault("product_category", "BASE")
        ).trim();

        // =========================================================
        // PRE-CLEAN LEFTOVER SUBSCRIPTIONS
        // =========================================================

        deleteSubscriptionFromDatabase(
                accountId,
                expectedExternalKey,
                false
        );

        // =========================================================
        // STEP 1: LOGIN
        // =========================================================

        System.out.println("\n========== STEP 1: LOGIN ==========");

        Login.bypassLogin(driver);

        Assert.assertNotNull(
                driver,
                "WebDriver is not initialized."
        );

        // =========================================================
        // STEP 2: CREATE CATALOG
        // =========================================================

        System.out.println("\n========== STEP 2: CREATE CATALOG API ==========");

        int catalogIndex = Math.min(
                iteration,
                catalog.getTotalIterations() - 1
        );

        Response createCatalogResponse =
                catalog.createSimplePlan(driver, catalogIndex);

        catalog.printResponseDetails(createCatalogResponse);

        catalog.validateCreateSimplePlanResponse(createCatalogResponse);

        driver.get(catalog.getRedirectUrl(createCatalogResponse));

        try {

            // =========================================================
            // STEP 3: CREATE SUBSCRIPTION
            // =========================================================

            System.out.println("\n========== STEP 3: CREATE SUBSCRIPTION API ==========");

            Response createSubResponse =
                    subscription.createSubscription(driver, iteration);

            subscription.printResponseDetails(createSubResponse);

            subscription.validateCreateSubscriptionResponse(
                    createSubResponse
            );

            String bundlesUrl =
                    subscription.getBundlesRedirectUrl(createSubResponse);

            System.out.println(
                    "Created Subscription Bundles URL: " + bundlesUrl
            );

            driver.get(bundlesUrl);

            // =========================================================
            // STEP 4: VERIFY SUBSCRIPTION IN MARIADB
            // =========================================================

            verifySubscriptionInDatabase(
                    expectedExternalKey,
                    expectedCategory
            );

        } finally {

            // =========================================================
            // STEP 5: DELETE SUBSCRIPTION & BUNDLES FROM MARIADB
            // =========================================================

            deleteSubscriptionFromDatabase(
                    accountId,
                    expectedExternalKey,
                    true
            );

            // =========================================================
            // STEP 6: DELETE CATALOG
            // =========================================================

            System.out.println(
                    "\n========== STEP 6: DELETE CATALOG API =========="
            );

            Response deleteCatalogResponse =
                    deletecatalog.deleteCatalog(driver);

            deletecatalog.printResponseDetails(deleteCatalogResponse);

            deletecatalog.validateDeleteCatalogResponse(
                    deleteCatalogResponse
            );

            driver.get(
                    deletecatalog.getRedirectUrl(deleteCatalogResponse)
            );
        }

        System.out.printf(
                "%n========== SUBSCRIPTION ITERATION %d PASSED ==========%n",
                iteration + 1
        );
    }

    // =========================================================
    // DATABASE VERIFICATION
    // =========================================================

    private void verifySubscriptionInDatabase(
            String expectedExternalKey,
            String expectedCategory
    ) {

        System.out.println(
                "\n========== STEP 4: VERIFY SUBSCRIPTION IN DB =========="
        );

        try (
                Connection conn = DatabaseConnection.getConnection();
                PreparedStatement stmt =
                        conn.prepareStatement(SUBSCRIPTION_SQL_QUERY)
        ) {

            stmt.setString(1, expectedExternalKey);

            try (ResultSet rs = stmt.executeQuery()) {

                Assert.assertTrue(
                        rs.next(),
                        "Subscription with external_key '"
                                + expectedExternalKey
                                + "' not found in database."
                );

                printSubscriptionDatabaseRow(rs);

                Assert.assertNotNull(
                        rs.getString("id"),
                        "Database subscription ID is null."
                );

                Assert.assertNotNull(
                        rs.getString("bundle_id"),
                        "Database bundle ID is null."
                );

                Assert.assertEquals(
                        rs.getString("external_key"),
                        expectedExternalKey,
                        "Database external_key mismatch."
                );

                Assert.assertEquals(
                        rs.getString("category"),
                        expectedCategory,
                        "Database product category mismatch."
                );
            }

        } catch (Exception e) {

            Assert.fail(
                    "SUBSCRIPTION DATABASE VALIDATION FAILED: "
                            + e.getMessage(),
                    e
            );
        }
    }

    // =========================================================
    // DATABASE CLEANUP
    // =========================================================

    private void deleteSubscriptionFromDatabase(
            String accountId,
            String externalKey,
            boolean logStep
    ) {

        if (logStep) {

            System.out.println(
                    "\n========== STEP 5: DELETE SUBSCRIPTION & BUNDLE DATA FROM DB =========="
            );
        }

        String[] accountCleanupQueries = {

                "DELETE FROM blocking_state_history WHERE account_record_id IN (SELECT record_id FROM accounts WHERE id = ?)",

                "DELETE FROM blocking_states WHERE account_record_id IN (SELECT record_id FROM accounts WHERE id = ?)",

                "DELETE FROM subscription_event_history WHERE account_record_id IN (SELECT record_id FROM accounts WHERE id = ?)",

                "DELETE FROM subscription_events WHERE account_record_id IN (SELECT record_id FROM accounts WHERE id = ?)",

                "DELETE FROM subscription_history WHERE account_record_id IN (SELECT record_id FROM accounts WHERE id = ?)",

                "DELETE FROM subscriptions WHERE account_record_id IN (SELECT record_id FROM accounts WHERE id = ?)",

                "DELETE FROM bundle_history WHERE account_record_id IN (SELECT record_id FROM accounts WHERE id = ?)",

                "DELETE FROM bundles WHERE account_record_id IN (SELECT record_id FROM accounts WHERE id = ?)"
        };

        try (Connection conn = DatabaseConnection.getConnection()) {

            conn.setAutoCommit(false);

            try {

                for (String sql : accountCleanupQueries) {

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

            if (logStep) {

                try (
                        PreparedStatement checkSubStmt =
                                conn.prepareStatement(
                                        "SELECT COUNT(*) FROM subscriptions WHERE external_key = ?"
                                )
                ) {

                    checkSubStmt.setString(1, externalKey);

                    try (ResultSet rs = checkSubStmt.executeQuery()) {

                        rs.next();

                        Assert.assertEquals(
                                rs.getInt(1),
                                0,
                                "Subscription external_key '"
                                        + externalKey
                                        + "' still exists in subscriptions table."
                        );
                    }
                }

                try (
                        PreparedStatement checkBundleStmt =
                                conn.prepareStatement(
                                        "SELECT COUNT(*) FROM bundles WHERE account_id = ?"
                                )
                ) {

                    checkBundleStmt.setString(1, accountId);

                    try (ResultSet rs = checkBundleStmt.executeQuery()) {

                        rs.next();

                        Assert.assertEquals(
                                rs.getInt(1),
                                0,
                                "Bundles still exist for account_id '"
                                        + accountId
                                        + "' in bundles table."
                        );
                    }
                }

                System.out.println(
                        "PASS: All subscription, bundle, and blocking_state records for external_key '"
                                + externalKey
                                + "' deleted from MariaDB."
                );
            }

        } catch (Exception e) {

            if (logStep) {

                Assert.fail(
                        "SUBSCRIPTION DATABASE CLEANUP FAILED: "
                                + e.getMessage(),
                        e
                );
            }
        }
    }

    // =========================================================
    // PRINT DATABASE SUBSCRIPTION DETAILS
    // =========================================================

    private void printSubscriptionDatabaseRow(ResultSet rs)
            throws Exception {

        System.out.println(
                "\n========== DATABASE SUBSCRIPTION DETAILS =========="
        );

        System.out.println("Record ID            : " + rs.getString("record_id"));
        System.out.println("Subscription ID      : " + rs.getString("id"));
        System.out.println("Bundle ID            : " + rs.getString("bundle_id"));
        System.out.println("External Key         : " + rs.getString("external_key"));
        System.out.println("Category             : " + rs.getString("category"));
        System.out.println("Start Date           : " + rs.getString("start_date"));
        System.out.println("Bundle Start Date    : " + rs.getString("bundle_start_date"));
        System.out.println("Charged Through Date : " + rs.getString("charged_through_date"));
        System.out.println("Migrated             : " + rs.getString("migrated"));
        System.out.println("Created By           : " + rs.getString("created_by"));
        System.out.println("Created Date         : " + rs.getString("created_date"));
        System.out.println("Updated By           : " + rs.getString("updated_by"));
        System.out.println("Updated Date         : " + rs.getString("updated_date"));
        System.out.println("Account Record ID    : " + rs.getString("account_record_id"));
        System.out.println("Tenant Record ID     : " + rs.getString("tenant_record_id"));

        System.out.println("===================================================\n");
    }

    // =========================================================
    // STEP 3: FINAL VALIDATION
    // =========================================================

    @Then("all subscriptions should be verified and cleaned up")
    public void all_subscriptions_should_be_verified_and_cleaned_up() {

        Assert.assertTrue(
                allSubscriptionsProcessed,
                "Subscriptions were not all processed successfully."
        );

        System.out.println(
                "PASS: All subscriptions were verified and cleanup completed."
        );
    }
}
