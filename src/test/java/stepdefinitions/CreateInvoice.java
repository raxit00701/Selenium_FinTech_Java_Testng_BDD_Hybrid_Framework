
package stepdefinitions;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.testng.Assert;

import API.Login;
import API.catalog;
import API.deletecatalog;
import DB.DatabaseConnection;
import base.DriverFactory;
import io.github.cdimascio.dotenv.Dotenv;
import io.restassured.response.Response;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;
import utils.data_driven.JsonReader;

public class CreateInvoice {

    private WebDriver driver;
    private WebDriverWait wait;

    private int totalIterations = 0;
    private int completedIterations = 0;

    // =========================================================
    // ENVIRONMENT CONFIGURATION
    // =========================================================

    private static final Dotenv dotenv = Dotenv.configure()
            .directory("src/main/java/env")
            .filename(".env")
            .ignoreIfMissing()
            .load();

    private static final String BASE_URL = getInvoiceBaseUrl();
    private static final String JSON_FILE = "invoice.json";

    // =========================================================
    // UI LOCATORS
    // =========================================================

    private static final By ADD_CREDIT_BUTTON =
            By.xpath("//button[@type='button' and normalize-space()='Add Credit']");

    private static final By CHARGE_AMOUNT_INPUT =
            By.id("charge_amount");

    private static final By REASON_DROPDOWN =
            By.id("reason");

    private static final By COMMENT_INPUT =
            By.id("comment");

    private static final By SAVE_CREDIT_BUTTON =
            By.id("saveButton");

    private static final By FLASH_NOTICE_ALERT =
            By.cssSelector(
                    "div[id='flash-notice'] div[class='custom-alert custom-alert-info']"
            );

    private static final By CREATE_CHARGE_BUTTON =
            By.cssSelector("a[href*='/charges/new'] button");

    private static final By INVOICE_ITEM_DESCRIPTION_INPUT =
            By.id("invoice_item_description");

    private static final By SAVE_CHARGE_BUTTON =
            By.cssSelector("#saveButton");

    private static final By DETAILS_FLEX_CONTAINER =
            By.cssSelector("div.d-flex.flex-column");

    private static final Pattern INVOICE_HEADER_PATTERN =
            Pattern.compile("Invoice\\s+(\\d+)");

    // Dynamic locator for credit reason dropdown
    private By getDynamicReasonOption(String reasonValue) {

        return By.xpath(
                "//select[@id='reason']/option[@value='" + reasonValue
                        + "' or normalize-space()='" + reasonValue + "']"
        );
    }

    // =========================================================
    // SQL QUERIES
    // =========================================================

    private static final String INVOICE_SQL_QUERY = """
            SELECT record_id, id, account_id, invoice_date, target_date,
                   currency, status, migrated, parent_invoice, grp_id,
                   created_by, created_date, account_record_id, tenant_record_id
            FROM invoices
            WHERE account_id = ?
            ORDER BY record_id DESC
            LIMIT 1
            """;

    private static final String INVOICE_ITEMS_SQL_QUERY = """
            SELECT record_id, id, type, invoice_id, account_id,
                   description, start_date, end_date, amount, currency,
                   created_by, created_date
            FROM invoice_items
            WHERE invoice_id = ?
            ORDER BY record_id ASC
            """;

    private static final String AUDIT_COMMENT_SQL_QUERY = """
            SELECT comments
            FROM audit_log
            WHERE account_record_id = ?
              AND comments = ?
            ORDER BY record_id DESC
            LIMIT 1
            """;

    // =========================================================
    // STEP 1: INITIALIZE BROWSER
    // =========================================================

    @Given("a browser is ready for invoice creation")
    public void browser_is_ready_for_invoice_creation() {

        driver = DriverFactory.getDriver();

        Assert.assertNotNull(
                driver,
                "WebDriver is not initialized."
        );

        wait = new WebDriverWait(
                driver,
                Duration.ofSeconds(20)
        );

        Assert.assertNotNull(
                BASE_URL,
                "BASE_URL is missing from src/main/java/env/.env"
        );

        System.out.println(
                "Browser initialized for invoice creation."
        );
    }

    // =========================================================
    // STEP 2: PROCESS ALL JSON TEST DATA
    // =========================================================

    @When("invoices are created using all configured JSON test data")
    public void create_invoices_using_json_data() {

        Object[][] allTestData =
                JsonReader.getIterativeData(JSON_FILE);

        Assert.assertNotNull(
                allTestData,
                "Invoice JSON test data is null."
        );

        totalIterations = allTestData.length;

        Assert.assertTrue(
                totalIterations > 0,
                "No invoice test data found in invoice.json."
        );

        System.out.println(
                "Total invoice test data records: " + totalIterations
        );

        for (int i = 0; i < totalIterations; i++) {

            System.out.println(
                    "\n========== INVOICE ITERATION "
                            + (i + 1) + " =========="
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> testData =
                    (Map<String, Object>) allTestData[i][0];

            createInvoice(testData);

            completedIterations++;

            System.out.println(
                    "Invoice iteration " + (i + 1)
                            + " completed successfully."
            );
        }
    }

    // =========================================================
    // MAIN INVOICE CREATION WORKFLOW
    // =========================================================

    private void createInvoice(Map<String, Object> testData) {

        WebDriverWait invoiceWait =
                new WebDriverWait(driver, Duration.ofSeconds(20));

        // Extract dynamic values from invoice.json
        String accountId =
                String.valueOf(testData.get("account_id")).trim();

        String creditAmount =
                String.valueOf(testData.get("credit_amount")).trim();

        String creditReason =
                String.valueOf(testData.get("credit_reason")).trim();

        String creditComment =
                String.valueOf(testData.get("credit_comment")).trim();

        String chargeAmount =
                String.valueOf(testData.get("charge_amount")).trim();

        String chargeDescription =
                String.valueOf(testData.get("charge_description")).trim();

        String chargeComment =
                String.valueOf(testData.get("charge_comment")).trim();

        String accountUrl =
                BASE_URL + "/accounts/" + accountId;

        // =====================================================
        // PRE-STEP: LOGIN & CREATE CATALOG VIA API
        // =====================================================

        System.out.println(
                "\n========== PRE-STEP: LOGIN & CREATE CATALOG API =========="
        );

        Login.bypassLogin(driver);

        Assert.assertNotNull(
                driver,
                "WebDriver is not initialized."
        );

        Response createCatalogResponse =
                catalog.createSimplePlan(driver, 0);

        catalog.printResponseDetails(createCatalogResponse);

        catalog.validateCreateSimplePlanResponse(
                createCatalogResponse
        );

        try {

            // =================================================
            // STEP 1: NAVIGATE TO ACCOUNT PAGE
            // =================================================

            System.out.println(
                    "\n========== STEP 1: NAVIGATE TO ACCOUNT PAGE =========="
            );

            System.out.println("Navigating to: " + accountUrl);

            driver.get(accountUrl);

            // =================================================
            // STEP 2: CLICK ADD CREDIT
            // =================================================

            System.out.println(
                    "\n========== STEP 2: CLICK ADD CREDIT =========="
            );

            WebElement addCreditBtn = invoiceWait.until(
                    ExpectedConditions.presenceOfElementLocated(
                            ADD_CREDIT_BUTTON
                    )
            );

            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].scrollIntoView({block: 'center', inline: 'center'});",
                    addCreditBtn
            );

            invoiceWait.until(
                    ExpectedConditions.visibilityOf(addCreditBtn)
            );

            invoiceWait.until(
                    ExpectedConditions.elementToBeClickable(addCreditBtn)
            );

            addCreditBtn.click();

            // =================================================
            // STEP 3: ENTER CREDIT AMOUNT
            // =================================================

            System.out.println(
                    "\n========== STEP 3: ENTER CREDIT AMOUNT =========="
            );

            WebElement creditAmountField = invoiceWait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            CHARGE_AMOUNT_INPUT
                    )
            );

            creditAmountField.clear();
            creditAmountField.sendKeys(creditAmount);

            // =================================================
            // STEP 4: SELECT CREDIT REASON
            // =================================================

            System.out.println(
                    "\n========== STEP 4: SELECT CREDIT REASON =========="
            );

            WebElement reasonDropdown = invoiceWait.until(
                    ExpectedConditions.elementToBeClickable(
                            REASON_DROPDOWN
                    )
            );

            reasonDropdown.click();

            WebElement reasonOption = invoiceWait.until(
                    ExpectedConditions.elementToBeClickable(
                            getDynamicReasonOption(creditReason)
                    )
            );

            reasonOption.click();

            // =================================================
            // STEP 5: ENTER CREDIT COMMENT
            // =================================================

            System.out.println(
                    "\n========== STEP 5: ENTER CREDIT COMMENT =========="
            );

            WebElement creditCommentField = invoiceWait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            COMMENT_INPUT
                    )
            );

            creditCommentField.clear();
            creditCommentField.sendKeys(creditComment);

            // =================================================
            // STEP 6: SAVE CREDIT
            // =================================================

            System.out.println(
                    "\n========== STEP 6: CLICK SAVE CREDIT =========="
            );

            WebElement saveCreditBtn = invoiceWait.until(
                    ExpectedConditions.elementToBeClickable(
                            SAVE_CREDIT_BUTTON
                    )
            );

            saveCreditBtn.click();

            // =================================================
            // STEP 7: VERIFY FLASH NOTICE
            // =================================================

            System.out.println(
                    "\n========== STEP 7: VERIFY FLASH NOTICE =========="
            );

            WebElement flashNotice = invoiceWait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            FLASH_NOTICE_ALERT
                    )
            );

            Assert.assertTrue(
                    flashNotice.isDisplayed(),
                    "Flash notice alert is not displayed after saving credit."
            );

            System.out.println(
                    "Flash Notice Displayed: "
                            + flashNotice.getText().trim()
            );

            // =================================================
            // STEP 8: CLICK CREATE CHARGE
            // =================================================

            System.out.println(
                    "\n========== STEP 8: CLICK CREATE CHARGE =========="
            );

            if (driver.findElements(CREATE_CHARGE_BUTTON).isEmpty()) {
                driver.get(accountUrl);
            }

            WebDriverWait shortWait =
                    new WebDriverWait(driver, Duration.ofSeconds(3));

            WebElement createChargeBtn = shortWait.until(
                    ExpectedConditions.elementToBeClickable(
                            CREATE_CHARGE_BUTTON
                    )
            );

            createChargeBtn.click();

            // =================================================
            // STEP 9: ENTER CHARGE AMOUNT
            // =================================================

            System.out.println(
                    "\n========== STEP 9: ENTER CHARGE AMOUNT =========="
            );

            WebElement chargeAmountField = invoiceWait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            CHARGE_AMOUNT_INPUT
                    )
            );

            chargeAmountField.clear();
            chargeAmountField.sendKeys(chargeAmount);

            // =================================================
            // STEP 10: ENTER CHARGE DESCRIPTION
            // =================================================

            System.out.println(
                    "\n========== STEP 10: ENTER INVOICE ITEM DESCRIPTION =========="
            );

            WebElement descriptionField = invoiceWait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            INVOICE_ITEM_DESCRIPTION_INPUT
                    )
            );

            descriptionField.clear();
            descriptionField.sendKeys(chargeDescription);

            // =================================================
            // STEP 11: ENTER CHARGE COMMENT
            // =================================================

            System.out.println(
                    "\n========== STEP 11: ENTER CHARGE COMMENT =========="
            );

            WebElement chargeCommentField = invoiceWait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            COMMENT_INPUT
                    )
            );

            chargeCommentField.clear();
            chargeCommentField.sendKeys(chargeComment);

            // =================================================
            // STEP 12: SAVE CHARGE
            // =================================================

            System.out.println(
                    "\n========== STEP 12: CLICK SAVE CHARGE =========="
            );

            WebElement saveChargeBtn = invoiceWait.until(
                    ExpectedConditions.elementToBeClickable(
                            SAVE_CHARGE_BUTTON
                    )
            );

            saveChargeBtn.click();

            // =================================================
            // STEP 13: VERIFY INVOICE UI DETAILS
            // =================================================

            System.out.println(
                    "\n========== STEP 13: VERIFY INVOICE UI DETAILS =========="
            );

            invoiceWait.until(
                    ExpectedConditions.visibilityOfElementLocated(
                            DETAILS_FLEX_CONTAINER
                    )
            );

            String fullUiContent = getCombinedUiContainerText();

            printUiContainerDetails(fullUiContent);

            UiInvoiceData uiData = verifyAllUiInvoiceDetails(
                    fullUiContent,
                    accountId,
                    chargeDescription,
                    chargeAmount,
                    creditAmount,
                    chargeComment
            );

            // =================================================
            // STEP 14: VERIFY INVOICE AND LINE ITEMS IN DATABASE
            // =================================================

            verifyInvoiceInDatabase(
                    accountId,
                    uiData,
                    chargeDescription,
                    chargeAmount,
                    creditAmount,
                    chargeComment
            );

        } finally {

            // =================================================
            // CLEANUP 1: DELETE CATALOG USING API
            // =================================================

            System.out.println(
                    "\n========== CLEANUP 1: DELETE CATALOG VIA API =========="
            );

            Response deleteCatalogResponse =
                    deletecatalog.deleteCatalog(driver);

            deletecatalog.printResponseDetails(
                    deleteCatalogResponse
            );

            deletecatalog.validateDeleteCatalogResponse(
                    deleteCatalogResponse
            );

            driver.get(
                    deletecatalog.getRedirectUrl(deleteCatalogResponse)
            );

            // =================================================
            // CLEANUP 2: DELETE CATALOG FROM DATABASE
            // =================================================

            deleteCatalogFromDatabase();
        }
    }

    // =========================================================
    // UI DATA MODEL
    // =========================================================

    private static class UiInvoiceData {

        long invoiceNumber;

        String invoiceDate;
        String targetDate;
        String accountId;
        String invoiceId;

        String chargeLine;
        String adjustmentLine;

        String invoiceTotalText;
        String resultingCreditsText;
        String refundedText;
        String balanceText;

        BigDecimal invoiceTotalAmount;
        BigDecimal resultingCreditsAmount;
        BigDecimal refundedAmount;
        BigDecimal balanceAmount;
    }

    // =========================================================
    // VERIFY ALL UI INVOICE DETAILS
    // =========================================================

    private UiInvoiceData verifyAllUiInvoiceDetails(
            String fullUiContent,
            String expectedAccountId,
            String expectedChargeDescription,
            String expectedChargeAmount,
            String expectedCreditAmount,
            String expectedChargeComment) {

        List<String> lines = fullUiContent.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();

        UiInvoiceData uiData = new UiInvoiceData();

        // 1. Verify Invoice Number
        Matcher headerMatcher =
                INVOICE_HEADER_PATTERN.matcher(fullUiContent);

        Assert.assertTrue(
                headerMatcher.find(),
                "UI does not display 'Invoice <number>' header."
        );

        uiData.invoiceNumber =
                Long.parseLong(headerMatcher.group(1));

        // 2. Verify action buttons and links
        Assert.assertTrue(
                fullUiContent.contains("View customer invoice html"),
                "Missing 'View customer invoice html' on UI."
        );

        Assert.assertTrue(
                fullUiContent.contains("Tag As"),
                "Missing 'Tag As' on UI."
        );

        Assert.assertTrue(
                fullUiContent.contains("Void"),
                "Missing 'Void' on UI."
        );

        Assert.assertTrue(
                fullUiContent.contains("Make payment"),
                "Missing 'Make payment' on UI."
        );

        // 3. Extract invoice details
        uiData.invoiceDate =
                getLineAfterLabel(lines, "Invoice date");

        uiData.targetDate =
                getLineAfterLabel(lines, "Target date");

        uiData.accountId =
                getLineAfterLabel(lines, "Account Id");

        uiData.invoiceId =
                getLineAfterLabel(lines, "Invoice Id");

        Assert.assertNotNull(
                uiData.invoiceDate,
                "UI 'Invoice date' value is missing."
        );

        Assert.assertNotNull(
                uiData.targetDate,
                "UI 'Target date' value is missing."
        );

        Assert.assertEquals(
                uiData.accountId,
                expectedAccountId,
                "UI 'Account Id' mismatch."
        );

        Assert.assertNotNull(
                uiData.invoiceId,
                "UI 'Invoice Id' value is missing."
        );

        // 4. Verify invoice table headers
        Assert.assertTrue(
                fullUiContent.contains("Description"),
                "Missing 'Description' column header on UI."
        );

        Assert.assertTrue(
                fullUiContent.contains("Start Date"),
                "Missing 'Start Date' column header on UI."
        );

        Assert.assertTrue(
                fullUiContent.contains("End Date"),
                "Missing 'End Date' column header on UI."
        );

        Assert.assertTrue(
                fullUiContent.contains("Subscription ID"),
                "Missing 'Subscription ID' column header on UI."
        );

        Assert.assertTrue(
                fullUiContent.contains("Amount"),
                "Missing 'Amount' column header on UI."
        );

        Assert.assertTrue(
                fullUiContent.contains("Comments"),
                "Missing 'Comments' column header on UI."
        );

        // 5. Verify charge line item
        uiData.chargeLine = String.format(
                "%s %s $%s (USD) %s",
                expectedChargeDescription,
                uiData.invoiceDate,
                expectedChargeAmount,
                expectedChargeComment
        );

        Assert.assertTrue(
                fullUiContent.contains(uiData.chargeLine),
                "Expected Charge row not found on UI. Expected: "
                        + uiData.chargeLine
        );

        // 6. Verify credit adjustment line item
        uiData.adjustmentLine = String.format(
                "Adjustment (use of account credit) %s %s $-%s (USD) %s",
                uiData.invoiceDate,
                uiData.targetDate,
                expectedCreditAmount,
                expectedChargeComment
        );

        Assert.assertTrue(
                fullUiContent.contains(uiData.adjustmentLine),
                "Expected Credit Adjustment row not found on UI. Expected: "
                        + uiData.adjustmentLine
        );

        Assert.assertTrue(
                fullUiContent.contains("Adjust"),
                "Missing 'Adjust' button text on UI."
        );

        // 7. Verify summary totals
        Assert.assertTrue(
                fullUiContent.contains("Invoice Total:"),
                "Missing 'Invoice Total:' label on UI."
        );

        Assert.assertTrue(
                fullUiContent.contains("Resulting available credits:"),
                "Missing 'Resulting available credits:' label on UI."
        );

        Assert.assertTrue(
                fullUiContent.contains("Refunded:"),
                "Missing 'Refunded:' label on UI."
        );

        Assert.assertTrue(
                fullUiContent.contains("Balance:"),
                "Missing 'Balance:' label on UI."
        );

        BigDecimal chargeBd =
                new BigDecimal(expectedChargeAmount);

        BigDecimal creditBd =
                new BigDecimal(expectedCreditAmount);

        BigDecimal balanceBd =
                chargeBd.subtract(creditBd);

        uiData.invoiceTotalAmount =
                chargeBd.setScale(2, RoundingMode.HALF_UP);

        uiData.resultingCreditsAmount =
                creditBd.negate().setScale(2, RoundingMode.HALF_UP);

        uiData.refundedAmount =
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        uiData.balanceAmount =
                balanceBd.setScale(2, RoundingMode.HALF_UP);

        uiData.invoiceTotalText =
                "$" + chargeBd.stripTrailingZeros().toPlainString() + " (USD)";

        uiData.resultingCreditsText =
                "$-" + creditBd.stripTrailingZeros().toPlainString() + " (USD)";

        uiData.refundedText = "$0 (USD)";

        uiData.balanceText =
                "$" + balanceBd.stripTrailingZeros().toPlainString() + " (USD)";

        Assert.assertTrue(
                fullUiContent.contains(uiData.invoiceTotalText),
                "Expected Invoice Total '" + uiData.invoiceTotalText
                        + "' not found on UI."
        );

        Assert.assertTrue(
                fullUiContent.contains(uiData.resultingCreditsText),
                "Expected Resulting available credits '"
                        + uiData.resultingCreditsText + "' not found on UI."
        );

        Assert.assertTrue(
                fullUiContent.contains(uiData.refundedText),
                "Expected Refunded '" + uiData.refundedText
                        + "' not found on UI."
        );

        Assert.assertTrue(
                fullUiContent.contains(uiData.balanceText),
                "Expected Balance '" + uiData.balanceText
                        + "' not found on UI."
        );

        // Print verified UI details
        System.out.println(
                "========== VERIFIED UI INVOICE DETAILS =========="
        );

        System.out.println("Invoice Number      : Invoice " + uiData.invoiceNumber);
        System.out.println("Invoice Date        : " + uiData.invoiceDate);
        System.out.println("Target Date         : " + uiData.targetDate);
        System.out.println("Account Id          : " + uiData.accountId);
        System.out.println("Invoice Id          : " + uiData.invoiceId);
        System.out.println("Charge Line         : " + uiData.chargeLine);
        System.out.println("Credit Adjustment   : " + uiData.adjustmentLine);
        System.out.println("Invoice Total       : " + uiData.invoiceTotalText);
        System.out.println("Resulting Credits   : " + uiData.resultingCreditsText);
        System.out.println("Refunded            : " + uiData.refundedText);
        System.out.println("Balance             : " + uiData.balanceText);

        System.out.println(
                "=================================================\n"
        );

        return uiData;
    }

    // =========================================================
    // GET LINE AFTER LABEL
    // =========================================================

    private String getLineAfterLabel(
            List<String> lines,
            String label) {

        for (int i = 0; i < lines.size() - 1; i++) {

            if (lines.get(i).equalsIgnoreCase(label)) {
                return lines.get(i + 1);
            }
        }

        return null;
    }

    // =========================================================
    // VERIFY INVOICE AND LINE ITEMS IN DATABASE
    // =========================================================

    private void verifyInvoiceInDatabase(
            String expectedAccountId,
            UiInvoiceData uiData,
            String expectedChargeDescription,
            String expectedChargeAmount,
            String expectedCreditAmount,
            String expectedChargeComment) {

        System.out.println(
                "\n========== VERIFY INVOICE & LINE ITEMS IN DATABASE =========="
        );

        BigDecimal expectedChargeBd =
                new BigDecimal(expectedChargeAmount)
                        .setScale(2, RoundingMode.HALF_UP);

        BigDecimal expectedCreditAdjBd =
                new BigDecimal(expectedCreditAmount)
                        .negate()
                        .setScale(2, RoundingMode.HALF_UP);

        BigDecimal expectedBalanceBd =
                expectedChargeBd.add(expectedCreditAdjBd)
                        .setScale(2, RoundingMode.HALF_UP);

        try (Connection conn = DatabaseConnection.getConnection()) {

            long dbRecordId;
            String dbInvoiceId;
            String dbAccountId;
            String dbInvoiceDate;
            String dbTargetDate;
            String dbCurrency;
            String dbStatus;
            long dbAccountRecordId;

            // 1. Verify invoices table
            try (
                    PreparedStatement stmt =
                            conn.prepareStatement(INVOICE_SQL_QUERY)
            ) {

                stmt.setString(1, expectedAccountId);

                try (ResultSet rs = stmt.executeQuery()) {

                    Assert.assertTrue(
                            rs.next(),
                            "No invoice found in database for account_id: "
                                    + expectedAccountId
                    );

                    printInvoiceDatabaseRow(rs);

                    dbRecordId = rs.getLong("record_id");
                    dbInvoiceId = rs.getString("id");
                    dbAccountId = rs.getString("account_id");

                    dbInvoiceDate =
                            String.valueOf(rs.getDate("invoice_date"));

                    dbTargetDate =
                            String.valueOf(rs.getDate("target_date"));

                    dbCurrency = rs.getString("currency");
                    dbStatus = rs.getString("status");

                    dbAccountRecordId =
                            rs.getLong("account_record_id");

                    Assert.assertEquals(
                            dbRecordId,
                            uiData.invoiceNumber,
                            "DB record_id vs UI Invoice Number mismatch."
                    );

                    Assert.assertEquals(
                            dbInvoiceId,
                            uiData.invoiceId,
                            "DB id vs UI Invoice Id mismatch."
                    );

                    Assert.assertEquals(
                            dbAccountId,
                            uiData.accountId,
                            "DB account_id vs UI Account Id mismatch."
                    );

                    Assert.assertEquals(
                            dbInvoiceDate,
                            uiData.invoiceDate,
                            "DB invoice_date vs UI Invoice date mismatch."
                    );

                    Assert.assertEquals(
                            dbTargetDate,
                            uiData.targetDate,
                            "DB target_date vs UI Target date mismatch."
                    );

                    Assert.assertEquals(
                            dbCurrency,
                            "USD",
                            "DB currency mismatch."
                    );

                    Assert.assertEquals(
                            dbStatus,
                            "COMMITTED",
                            "DB status mismatch."
                    );
                }
            }

            // 2. Verify invoice_items
            boolean foundExternalCharge = false;
            boolean foundCreditAdjustment = false;

            String dbChargeDescription = null;
            String dbChargeStartDate = null;
            String dbAdjStartDate = null;
            String dbAdjEndDate = null;

            BigDecimal dbInvoiceTotal =
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

            BigDecimal dbResultingCredits =
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

            BigDecimal dbRefunded =
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

            BigDecimal dbBalance =
                    BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

            try (
                    PreparedStatement itemStmt =
                            conn.prepareStatement(INVOICE_ITEMS_SQL_QUERY)
            ) {

                itemStmt.setString(1, dbInvoiceId);

                try (ResultSet itemRs = itemStmt.executeQuery()) {

                    System.out.println(
                            "========== DATABASE INVOICE_ITEMS =========="
                    );

                    while (itemRs.next()) {

                        long itemRecordId =
                                itemRs.getLong("record_id");

                        String itemId =
                                itemRs.getString("id");

                        String itemType =
                                itemRs.getString("type");

                        String description =
                                itemRs.getString("description");

                        String startDate =
                                String.valueOf(itemRs.getDate("start_date"));

                        String endDate =
                                itemRs.getDate("end_date") != null
                                        ? String.valueOf(itemRs.getDate("end_date"))
                                        : "NULL";

                        BigDecimal amount =
                                itemRs.getBigDecimal("amount")
                                        .setScale(2, RoundingMode.HALF_UP);

                        String currency =
                                itemRs.getString("currency");

                        System.out.printf(
                                "record_id=%d | id=%s | type=%s | description=%s | start_date=%s | end_date=%s | amount=%s (%s)%n",
                                itemRecordId,
                                itemId,
                                itemType,
                                description,
                                startDate,
                                endDate,
                                amount,
                                currency
                        );

                        Assert.assertEquals(
                                itemRs.getString("invoice_id"),
                                uiData.invoiceId,
                                "invoice_items.invoice_id mismatch."
                        );

                        Assert.assertEquals(
                                itemRs.getString("account_id"),
                                uiData.accountId,
                                "invoice_items.account_id mismatch."
                        );

                        Assert.assertEquals(
                                currency,
                                "USD",
                                "invoice_items.currency mismatch."
                        );

                        dbBalance = dbBalance.add(amount);

                        if ("EXTERNAL_CHARGE".equalsIgnoreCase(itemType)) {

                            foundExternalCharge = true;

                            dbChargeDescription = description;
                            dbChargeStartDate = startDate;

                            dbInvoiceTotal =
                                    dbInvoiceTotal.add(amount);

                            Assert.assertEquals(
                                    description,
                                    expectedChargeDescription,
                                    "DB EXTERNAL_CHARGE description mismatch."
                            );

                            Assert.assertEquals(
                                    startDate,
                                    uiData.invoiceDate,
                                    "DB EXTERNAL_CHARGE start_date mismatch."
                            );

                            Assert.assertEquals(
                                    amount,
                                    expectedChargeBd,
                                    "DB EXTERNAL_CHARGE amount mismatch."
                            );

                        } else if ("CBA_ADJ".equalsIgnoreCase(itemType)) {

                            foundCreditAdjustment = true;

                            dbAdjStartDate = startDate;
                            dbAdjEndDate = endDate;

                            dbResultingCredits =
                                    dbResultingCredits.add(amount);

                            Assert.assertEquals(
                                    startDate,
                                    uiData.invoiceDate,
                                    "DB CBA_ADJ start_date mismatch."
                            );

                            Assert.assertEquals(
                                    endDate,
                                    uiData.targetDate,
                                    "DB CBA_ADJ end_date mismatch."
                            );

                            Assert.assertEquals(
                                    amount,
                                    expectedCreditAdjBd,
                                    "DB CBA_ADJ credit adjustment amount mismatch."
                            );
                        }
                    }

                    System.out.println(
                            "============================================\n"
                    );
                }
            }

            Assert.assertTrue(
                    foundExternalCharge,
                    "EXTERNAL_CHARGE line item not found in DB invoice_items."
            );

            Assert.assertTrue(
                    foundCreditAdjustment,
                    "CBA_ADJ line item not found in DB invoice_items."
            );

            // 3. Verify summary totals
            Assert.assertEquals(
                    dbInvoiceTotal,
                    uiData.invoiceTotalAmount,
                    "DB Invoice Total vs UI Invoice Total mismatch."
            );

            Assert.assertEquals(
                    dbResultingCredits,
                    uiData.resultingCreditsAmount,
                    "DB Resulting available credits vs UI mismatch."
            );

            Assert.assertEquals(
                    dbRefunded,
                    uiData.refundedAmount,
                    "DB Refunded vs UI Refunded mismatch."
            );

            Assert.assertEquals(
                    dbBalance,
                    uiData.balanceAmount,
                    "DB Balance vs UI Balance mismatch."
            );

            Assert.assertEquals(
                    dbBalance,
                    expectedBalanceBd,
                    "DB Balance vs expected JSON Balance mismatch."
            );

            // 4. Verify audit comment
            String dbAuditComment = null;

            try (
                    PreparedStatement auditStmt =
                            conn.prepareStatement(AUDIT_COMMENT_SQL_QUERY)
            ) {

                auditStmt.setLong(1, dbAccountRecordId);
                auditStmt.setString(2, expectedChargeComment);

                try (ResultSet auditRs = auditStmt.executeQuery()) {

                    if (auditRs.next()) {
                        dbAuditComment = auditRs.getString("comments");
                    }
                }
            }

            Assert.assertEquals(
                    dbAuditComment,
                    expectedChargeComment,
                    "DB audit_log comment vs UI Comments mismatch."
            );

            // 5. Print UI vs DB verification report
            System.out.println(
                    "========== SIDE-BY-SIDE UI vs MARIADB VERIFICATION =========="
            );

            System.out.printf(
                    "Invoice Number              : UI = Invoice %-28d | DB = %d%n",
                    uiData.invoiceNumber,
                    dbRecordId
            );

            System.out.printf(
                    "Invoice Date                : UI = %-36s | DB = %s%n",
                    uiData.invoiceDate,
                    dbInvoiceDate
            );

            System.out.printf(
                    "Target Date                 : UI = %-36s | DB = %s%n",
                    uiData.targetDate,
                    dbTargetDate
            );

            System.out.printf(
                    "Account Id                  : UI = %-36s | DB = %s%n",
                    uiData.accountId,
                    dbAccountId
            );

            System.out.printf(
                    "Invoice Id                  : UI = %-36s | DB = %s%n",
                    uiData.invoiceId,
                    dbInvoiceId
            );

            System.out.printf(
                    "Charge Description          : UI = %-36s | DB = %s%n",
                    expectedChargeDescription,
                    dbChargeDescription
            );

            System.out.printf(
                    "Charge Start Date           : UI = %-36s | DB = %s%n",
                    uiData.invoiceDate,
                    dbChargeStartDate
            );

            System.out.printf(
                    "Credit Adjustment Dates     : UI = %s to %s | DB = %s to %s%n",
                    uiData.invoiceDate,
                    uiData.targetDate,
                    dbAdjStartDate,
                    dbAdjEndDate
            );

            System.out.printf(
                    "Comments                    : UI = %-36s | DB = %s%n",
                    expectedChargeComment,
                    dbAuditComment
            );

            System.out.printf(
                    "Invoice Total               : UI = %-36s | DB = $%s (USD)%n",
                    uiData.invoiceTotalText,
                    dbInvoiceTotal
            );

            System.out.printf(
                    "Resulting Available Credits : UI = %-36s | DB = $%s (USD)%n",
                    uiData.resultingCreditsText,
                    dbResultingCredits
            );

            System.out.printf(
                    "Refunded                    : UI = %-36s | DB = $%s (USD)%n",
                    uiData.refundedText,
                    dbRefunded
            );

            System.out.printf(
                    "Balance                     : UI = %-36s | DB = $%s (USD)%n",
                    uiData.balanceText,
                    dbBalance
            );

            System.out.println(
                    "=============================================================\n"
            );

        } catch (Exception e) {

            Assert.fail(
                    "INVOICE DATABASE VALIDATION FAILED: " + e.getMessage(),
                    e
            );
        }
    }

    // =========================================================
    // GET COMBINED UI CONTAINER TEXT
    // =========================================================

    private String getCombinedUiContainerText() {

        StringBuilder combinedUiText = new StringBuilder();

        for (WebElement container :
                driver.findElements(DETAILS_FLEX_CONTAINER)) {

            String text = container.getText();

            if (text != null && !text.isBlank()) {

                combinedUiText.append(text).append("\n");
            }
        }

        return combinedUiText.toString();
    }

    // =========================================================
    // PRINT UI CONTAINER DETAILS
    // =========================================================

    private void printUiContainerDetails(String containerText) {

        System.out.println(
                "\n========== INVOICE DETAILS ON UI =========="
        );

        if (containerText == null || containerText.isBlank()) {

            System.out.println(
                    "No visible text found inside div.d-flex.flex-column."
            );

            return;
        }

        long lineCount = containerText.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .peek(line -> System.out.println("  • " + line))
                .count();

        System.out.println(
                "Total visible lines: " + lineCount
        );

        System.out.println(
                "============================================\n"
        );
    }

    // =========================================================
    // PRINT DATABASE INVOICE ROW
    // =========================================================

    private void printInvoiceDatabaseRow(ResultSet rs)
            throws Exception {

        System.out.println(
                "\n========== DATABASE INVOICE DETAILS =========="
        );

        System.out.println("record_id         : " + rs.getLong("record_id"));
        System.out.println("id                : " + rs.getString("id"));
        System.out.println("account_id        : " + rs.getString("account_id"));
        System.out.println("invoice_date      : " + rs.getDate("invoice_date"));
        System.out.println("target_date       : " + rs.getDate("target_date"));
        System.out.println("currency          : " + rs.getString("currency"));
        System.out.println("status            : " + rs.getString("status"));
        System.out.println("migrated          : " + rs.getBoolean("migrated"));
        System.out.println("parent_invoice    : " + rs.getBoolean("parent_invoice"));
        System.out.println("grp_id            : " + rs.getString("grp_id"));
        System.out.println("created_by        : " + rs.getString("created_by"));
        System.out.println("created_date      : " + rs.getTimestamp("created_date"));
        System.out.println("account_record_id : " + rs.getLong("account_record_id"));
        System.out.println("tenant_record_id  : " + rs.getLong("tenant_record_id"));

        System.out.println(
                "==============================================\n"
        );
    }

    // =========================================================
    // CLEANUP: DELETE CATALOG FROM DATABASE
    // =========================================================

    private void deleteCatalogFromDatabase() {

        System.out.println(
                "\n========== CLEANUP 2: DELETE CATALOG FROM DB =========="
        );

        String deleteCatalogSql =
                "DELETE FROM tenant_kvs WHERE tenant_key LIKE 'CATALOG%'";

        String verifyCatalogSql =
                "SELECT COUNT(*) FROM tenant_kvs WHERE tenant_key LIKE 'CATALOG%'";

        try (Connection conn = DatabaseConnection.getConnection()) {

            try (
                    PreparedStatement deleteStmt =
                            conn.prepareStatement(deleteCatalogSql)
            ) {

                int deletedRows = deleteStmt.executeUpdate();

                System.out.println(
                        "Deleted catalog rows: " + deletedRows
                );
            }

            try (
                    PreparedStatement checkStmt =
                            conn.prepareStatement(verifyCatalogSql);
                    ResultSet rs = checkStmt.executeQuery()
            ) {

                rs.next();

                Assert.assertEquals(
                        rs.getInt(1),
                        0,
                        "Catalog records still exist in tenant_kvs."
                );
            }

            System.out.println(
                    "PASS: Catalog completely removed from MariaDB."
            );

        } catch (Exception e) {

            Assert.fail(
                    "CATALOG DATABASE CLEANUP FAILED: " + e.getMessage(),
                    e
            );
        }
    }

    // =========================================================
    // STEP 3: FINAL VERIFICATION
    // =========================================================

    @Then("all invoices should be successfully validated in the UI and database")
    public void verify_all_invoices() {

        Assert.assertEquals(
                completedIterations,
                totalIterations,
                "Not all invoice records were processed successfully."
        );

        System.out.println(
                "\n========== ALL INVOICE TESTS PASSED =========="
        );

        System.out.println(
                "Total invoice records: " + totalIterations
        );

        System.out.println(
                "Successfully completed: " + completedIterations
        );

        System.out.println(
                "UI and database verification completed."
        );
    }

    // =========================================================
    // BASE URL CONFIGURATION
    // =========================================================

    private static String getInvoiceBaseUrl() {

        String url = System.getProperty("BASE_URL");

        if (url == null || url.isBlank()) {
            url = System.getenv("BASE_URL");
        }

        if (url == null || url.isBlank()) {
            url = dotenv.get("BASE_URL");
        }

        if (url == null || url.isBlank()) {

            throw new RuntimeException(
                    "BASE_URL is not defined in src/main/java/env/.env"
            );
        }

        return url.trim().replaceAll("/+$", "");
    }
}
