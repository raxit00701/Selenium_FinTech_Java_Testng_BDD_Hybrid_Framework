
package stepdefinitions;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.testng.Assert;

import pages.Dashboard;
import API.Login;
import API.delete_pay_method;
import DB.DatabaseConnection;
import base.DriverFactory;
import io.restassured.response.Response;
import utils.data_driven.JsonReader;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;

public class CreatePayment {

    private static final String JSON_FILE = "payment.json";

    private static final String JSON_PATH =
            "src/main/resources/data/payment.json";

    private WebDriver driver;
    private Dashboard dashboard;

    private boolean paymentValidated = false;

    private static final By SECOND_INVOICE_CELL =
            By.xpath("(//tbody[contains(@class,'table-body')]/tr[td/a[contains(@href,'/invoices/')]])[2]/td[1]");

    private static final By MAKE_PAYMENT =
            By.xpath("//button[normalize-space()='Make payment']");

    private static final By PAYMENT_METHOD =
            By.id("invoice_payment_payment_method_id");

    private static final By PAYMENT_REASON =
            By.id("reason");

    private static final By COMMENT =
            By.id("comment");

    private static final By SAVE_BUTTON =
            By.id("saveButton");

    private static final By PAYMENT_TRIGGERED =
            By.xpath("//span[normalize-space()='Payment triggered']");

    private static final By PAYMENT_DETAILS_HEADING =
            By.xpath("//h2[normalize-space()='Payment Details']");

    private static final By PURCHASE_ROW =
            By.xpath("//tr[.//span[@class='phase-type' and normalize-space()='PURCHASE']]");

    private static final By PAYMENT_RESULT_CONTAINER =
            By.cssSelector("div.mx-auto");


    // =============================================================
    // STEP 1: BROWSER SETUP
    // =============================================================

    @Given("the browser is ready for payment creation")
    public void browserIsReadyForPaymentCreation() {

        driver = DriverFactory.getDriver();

        Assert.assertNotNull(
                driver,
                "WebDriver is not initialized."
        );

        dashboard = new Dashboard(driver);
    }


    // =============================================================
    // STEPS 2-15: CREATE AND VALIDATE PAYMENT
    // =============================================================

    @When("the payment is created and validated using payment JSON data")
    public void createPaymentAndValidate() throws Exception {

        WebDriverWait wait =
                new WebDriverWait(driver, Duration.ofSeconds(25));

        Map<String, Object> testData =
                JsonReader.getSingleData(JSON_FILE, null);

        // All test input values come from JSON.
        String paymentMethodExternalKey =
                String.valueOf(testData.getOrDefault(
                        "payment_method_external_key",
                        "__EXTERNAL_PAYMENT__"
                )).trim();

        String paymentReason =
                String.valueOf(testData.get("payment_reason"));

        String comment =
                String.valueOf(testData.get("comment"));

        Assert.assertNotNull(
                paymentReason,
                "payment_reason is missing from JSON."
        );

        Assert.assertNotNull(
                comment,
                "comment is missing from JSON."
        );

        String invoiceId = null;

        try {

            // =====================================================
            // STEP 1: LOGIN
            // =====================================================

            System.out.println("\n========== STEP 1: LOGIN ==========");

            Login.bypassLogin(driver);

            Assert.assertNotNull(
                    driver,
                    "WebDriver is not initialized."
            );


            // =====================================================
            // STEP 2: VIEW ALL INVOICES
            // =====================================================

            System.out.println("\n========== STEP 2: VIEW ALL INVOICES ==========");

            dashboard.clickViewAllInvoices();


            // =====================================================
            // STEP 3: EXTRACT INVOICE ID AND SAVE TO JSON
            // =====================================================

            System.out.println("\n========== STEP 3: EXTRACT INVOICE ID ==========");

            WebElement invoiceCell = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            SECOND_INVOICE_CELL
                    )
            );

            WebElement invoiceLink =
                    invoiceCell.findElement(By.xpath(".//a"));

            String invoiceHref =
                    invoiceLink.getAttribute("href");

            Assert.assertNotNull(
                    invoiceHref,
                    "Invoice link href was not found."
            );

            invoiceId = invoiceHref.substring(
                    invoiceHref.lastIndexOf("/") + 1
            );

            Assert.assertFalse(
                    invoiceId.isBlank(),
                    "Invoice ID could not be extracted."
            );

            System.out.println("Extracted Invoice ID: " + invoiceId);

            // Save invoice ID dynamically into payment.json.
            testData.put("invoice_id", invoiceId);

            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);

            mapper.writeValue(new File(JSON_PATH), testData);

            System.out.println("Invoice ID saved to: " + JSON_PATH);


            // =====================================================
            // STEP 4: OPEN EXTRACTED INVOICE
            // =====================================================

            System.out.println("\n========== STEP 4: OPEN INVOICE ==========");

            By invoiceIdLink = By.xpath(
                    "//a[normalize-space()='" + invoiceId + "']"
            );

            wait.until(
                    ExpectedConditions.elementToBeClickable(invoiceIdLink)
            ).click();


            // =====================================================
            // STEP 5: CLICK MAKE PAYMENT
            // =====================================================

            System.out.println("\n========== STEP 5: MAKE PAYMENT ==========");

            wait.until(
                    ExpectedConditions.elementToBeClickable(MAKE_PAYMENT)
            ).click();


            // =====================================================
            // STEP 6: SELECT PAYMENT METHOD USING JSON EXTERNAL KEY
            // =====================================================

            System.out.println("\n========== STEP 6: SELECT PAYMENT METHOD ==========");

            WebElement paymentMethodDropdown = wait.until(
                    ExpectedConditions.elementToBeClickable(
                            PAYMENT_METHOD
                    )
            );

            Select paymentMethodSelect =
                    new Select(paymentMethodDropdown);

            String selectedPaymentOptionText =
                    selectPaymentMethodOption(
                            paymentMethodSelect,
                            paymentMethodExternalKey
                    );

            System.out.println(
                    "Selected payment method: " +
                            selectedPaymentOptionText
            );


            // =====================================================
            // STEP 7: SELECT PAYMENT REASON FROM JSON
            // =====================================================

            System.out.println("\n========== STEP 7: SELECT PAYMENT REASON ==========");

            WebElement reasonDropdown = wait.until(
                    ExpectedConditions.elementToBeClickable(
                            PAYMENT_REASON
                    )
            );

            new Select(reasonDropdown)
                    .selectByVisibleText(paymentReason);

            System.out.println("Selected reason: " + paymentReason);


            // =====================================================
            // STEP 8: ENTER COMMENT FROM JSON
            // =====================================================

            System.out.println("\n========== STEP 8: ENTER COMMENT ==========");

            WebElement commentField = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            COMMENT
                    )
            );

            commentField.clear();
            commentField.sendKeys(comment);

            System.out.println("Comment entered: " + comment);


            // =====================================================
            // STEP 9: SAVE PAYMENT
            // =====================================================

            System.out.println("\n========== STEP 9: SAVE PAYMENT ==========");

            wait.until(
                    ExpectedConditions.elementToBeClickable(SAVE_BUTTON)
            ).click();


            // =====================================================
            // STEP 10: PRINT PAYMENT RESULT
            // =====================================================

            System.out.println("\n========== STEP 10: PAYMENT RESULT ==========");

            WebElement paymentResult = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            PAYMENT_RESULT_CONTAINER
                    )
            );

            System.out.println(
                    "Payment result:\n" + paymentResult.getText()
            );


            // =====================================================
            // STEP 11: VERIFY PAYMENT TRIGGERED
            // =====================================================

            System.out.println("\n========== STEP 11: VERIFY PAYMENT TRIGGERED ==========");

            WebElement paymentTriggered = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            PAYMENT_TRIGGERED
                    )
            );

            Assert.assertTrue(
                    paymentTriggered.isDisplayed(),
                    "Payment triggered message is not visible."
            );

            System.out.println(
                    "PASS: Payment triggered message is visible."
            );


            // =====================================================
            // STEP 12: SCROLL TO PAYMENT DETAILS
            // =====================================================

            System.out.println("\n========== STEP 12: PAYMENT DETAILS ==========");

            WebElement paymentDetailsHeading = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            PAYMENT_DETAILS_HEADING
                    )
            );

            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block:'center'});",
                    paymentDetailsHeading
            );


            // =====================================================
            // STEP 13: VERIFY AND PRINT PURCHASE TRANSACTION ROW
            // =====================================================

            System.out.println("\n========== STEP 13: PURCHASE TRANSACTION UI ==========");

            WebElement purchaseRow = wait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            PURCHASE_ROW
                    )
            );

            Assert.assertTrue(
                    purchaseRow.isDisplayed(),
                    "PURCHASE transaction row is not visible."
            );

            System.out.println(
                    "PURCHASE transaction row:\n" +
                            purchaseRow.getText()
            );

            // Extract transaction type directly from the PURCHASE label.
            String uiTransactionType = purchaseRow
                    .findElement(By.cssSelector("span.phase-type"))
                    .getText()
                    .trim();

            // Extract remaining values using UI table headers.
            String uiExternalKey =
                    getTableValueByHeader(purchaseRow, "external key");

            String uiAmount =
                    getTableValueByHeader(purchaseRow, "amount");

            System.out.println(
                    "UI transaction_external_key: " + uiExternalKey
            );

            System.out.println(
                    "UI transaction_type: " + uiTransactionType
            );

            System.out.println("UI amount: " + uiAmount);


            // =====================================================
            // STEP 14: DATABASE VALIDATION
            // =====================================================

            System.out.println("\n========== STEP 14: DATABASE VALIDATION ==========");

            String query =
                    "SELECT * FROM payment_transaction_history";

            try (
                    Connection connection =
                            DatabaseConnection.getConnection();

                    PreparedStatement statement =
                            connection.prepareStatement(query);

                    ResultSet resultSet =
                            statement.executeQuery()
            ) {

                boolean matchingRecordFound = false;

                while (resultSet.next()) {

                    String dbExternalKey =
                            getColumnValue(
                                    resultSet,
                                    "transaction_external_key"
                            );

                    String dbTransactionType =
                            getColumnValue(
                                    resultSet,
                                    "transaction_type"
                            );

                    String dbAmount =
                            getColumnValue(
                                    resultSet,
                                    "amount"
                            );

                    if (dbExternalKey == null
                            || dbTransactionType == null
                            || dbAmount == null) {
                        continue;
                    }

                    // Match the database row with the UI transaction.
                    if (dbExternalKey.equals(uiExternalKey)) {

                        matchingRecordFound = true;

                        System.out.println(
                                "\n========== MATCHING DATABASE TRANSACTION =========="
                        );

                        System.out.println(
                                "DB transaction_external_key: " +
                                        dbExternalKey
                        );

                        System.out.println(
                                "DB transaction_type: " +
                                        dbTransactionType
                        );

                        System.out.println(
                                "DB amount: [" + dbAmount + "]"
                        );

                        System.out.println(
                                "UI amount: [" + uiAmount + "]"
                        );

                        // Compare transaction external key.
                        Assert.assertEquals(
                                dbExternalKey,
                                uiExternalKey,
                                "transaction_external_key mismatch."
                        );

                        // Compare transaction type.
                        Assert.assertEquals(
                                dbTransactionType,
                                uiTransactionType,
                                "transaction_type mismatch."
                        );

                        // Remove currency symbols, commas and whitespace.
                        String cleanDbAmount =
                                dbAmount.replaceAll("[^0-9.\\-]", "");

                        String cleanUiAmount =
                                uiAmount.replaceAll("[^0-9.\\-]", "");

                        System.out.println(
                                "Clean DB amount: [" + cleanDbAmount + "]"
                        );

                        System.out.println(
                                "Clean UI amount: [" + cleanUiAmount + "]"
                        );

                        // Compare amounts.
                        Assert.assertEquals(
                                new BigDecimal(cleanDbAmount).compareTo(
                                        new BigDecimal(cleanUiAmount)
                                ),
                                0,
                                "amount mismatch between UI and DB."
                        );

                        // Verify transaction type.
                        Assert.assertEquals(
                                dbTransactionType,
                                "PURCHASE",
                                "Database transaction is not PURCHASE."
                        );

                        System.out.println(
                                "PASS: PURCHASE transaction UI and DB values match."
                        );

                        break;
                    }
                }

                Assert.assertTrue(
                        matchingRecordFound,
                        "No database transaction matched UI transaction_external_key: "
                                + uiExternalKey
                );
            }

            paymentValidated = true;

        } finally {

            // =====================================================
            // STEP 15: DELETE PAYMENT METHOD USING EXISTING API
            // =====================================================

            System.out.println(
                    "\n========== STEP 15: PAYMENT METHOD CLEANUP =========="
            );

            String accountId =
                    resolveAccountIdForCleanup(testData, invoiceId);

            Response deletePmResponse =
                    (accountId != null && !accountId.isBlank())
                            ? delete_pay_method.deletePaymentMethod(
                                    driver,
                                    accountId
                            )
                            : delete_pay_method.deletePaymentMethod(driver);

            delete_pay_method.printResponseDetails(deletePmResponse);

            delete_pay_method.validateDeletePaymentMethodResponse(
                    deletePmResponse
            );
        }
    }


    // =============================================================
    // STEP 16: CUCUMBER VALIDATION
    // =============================================================

    @Then("the payment transaction should be successfully validated")
    public void paymentTransactionShouldBeSuccessfullyValidated() {

        Assert.assertTrue(
                paymentValidated,
                "Payment transaction validation was not completed."
        );

        System.out.println(
                "PASS: Payment creation and transaction validation completed."
        );
    }


    // =============================================================
    // HELPER: SELECT PAYMENT METHOD OPTION BY EXTERNAL KEY OR FALLBACK
    // =============================================================

    private String selectPaymentMethodOption(
            Select select,
            String expectedKey) {

        List<WebElement> options = select.getOptions();

        // 1. Match by JSON payment_method_external_key (exact or partial text/value)
        if (expectedKey != null
                && !expectedKey.isBlank()
                && !"null".equalsIgnoreCase(expectedKey)) {

            for (WebElement option : options) {

                String text = option.getText().trim();

                String value = option.getAttribute("value");

                if (text.equals(expectedKey)
                        || text.contains(expectedKey)
                        || (value != null && value.equals(expectedKey))) {

                    select.selectByVisibleText(text);

                    return text;
                }
            }
        }

        // 2. Fallback: Match any __EXTERNAL_PAYMENT__ option regardless of dynamic UUID
        for (WebElement option : options) {

            String text = option.getText().trim();

            if (text.contains("__EXTERNAL_PAYMENT__")) {

                select.selectByVisibleText(text);

                return text;
            }
        }

        // 3. Fallback: Select the first non-empty payment method option
        for (WebElement option : options) {

            String text = option.getText().trim();

            if (!text.isEmpty()) {

                select.selectByVisibleText(text);

                return text;
            }
        }

        throw new AssertionError(
                "No selectable payment method options found in dropdown."
        );
    }


    // =============================================================
    // HELPER: RESOLVE ACCOUNT ID FOR STEP 15 CLEANUP
    // =============================================================

    private String resolveAccountIdForCleanup(
            Map<String, Object> testData,
            String invoiceId) {

        if (testData != null && testData.get("account_id") != null) {

            String jsonAccountId =
                    String.valueOf(testData.get("account_id")).trim();

            if (!jsonAccountId.isEmpty()
                    && !"null".equalsIgnoreCase(jsonAccountId)) {

                return jsonAccountId;
            }
        }

        if (invoiceId != null && !invoiceId.isBlank()) {

            String sql =
                    "SELECT account_id FROM invoices WHERE id = ? LIMIT 1";

            try (
                    Connection conn =
                            DatabaseConnection.getConnection();

                    PreparedStatement stmt =
                            conn.prepareStatement(sql)
            ) {

                stmt.setString(1, invoiceId.trim());

                try (ResultSet rs = stmt.executeQuery()) {

                    if (rs.next()) {
                        return rs.getString("account_id");
                    }
                }

            } catch (Exception ignored) {
            }
        }

        return null;
    }


    // =============================================================
    // HELPER: GET UI TABLE VALUE USING COLUMN HEADER
    // =============================================================

    private String getTableValueByHeader(
            WebElement row,
            String expectedHeader) {

        WebElement table =
                row.findElement(By.xpath("./ancestor::table[1]"));

        List<WebElement> headers =
                table.findElements(By.xpath(".//thead//th"));

        List<WebElement> cells =
                row.findElements(By.xpath("./td"));

        for (int i = 0; i < headers.size(); i++) {

            String header = headers.get(i).getText()
                    .trim()
                    .toLowerCase()
                    .replaceAll("[^a-z0-9]", "");

            String expected = expectedHeader
                    .toLowerCase()
                    .replaceAll("[^a-z0-9]", "");

            if (header.contains(expected)) {

                Assert.assertTrue(
                        i < cells.size(),
                        "UI cell not found for: " + expectedHeader
                );

                return cells.get(i).getText().trim();
            }
        }

        throw new AssertionError(
                "UI table header not found: " + expectedHeader
        );
    }


    // =============================================================
    // HELPER: GET DATABASE VALUE USING COLUMN NAME
    // =============================================================

    private String getColumnValue(
            ResultSet resultSet,
            String columnName) throws Exception {

        ResultSetMetaData metadata =
                resultSet.getMetaData();

        for (int i = 1; i <= metadata.getColumnCount(); i++) {

            if (metadata.getColumnLabel(i)
                    .equalsIgnoreCase(columnName)) {

                Object value = resultSet.getObject(i);

                return value == null
                        ? null
                        : value.toString().trim();
            }
        }

        throw new AssertionError(
                "Column '" + columnName
                        + "' not found in payment_transaction_history."
        );
    }
}
