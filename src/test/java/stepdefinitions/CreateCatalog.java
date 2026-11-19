
package stepdefinitions;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.Assert;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import API.Login;
import DB.DatabaseConnection;
import base.DriverFactory;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;
import pages.Dashboard;
import utils.data_driven.JsonReader;

public class CreateCatalog {

    private WebDriver driver;

    private boolean allCatalogsProcessed = false;

    private static final By ADMIN_TENANT =
            By.xpath("//a[@href='/admin_tenants/1' and contains(@class,'non-current') and normalize-space()='admin']");

    private static final By ADD_CATALOG_BTN =
            By.xpath("//button[normalize-space()='Add Catalog']");

    private static final By PRODUCT_CATEGORY =
            By.xpath("(//*[@id='simple_plan_product_category'])[1]");

    private static final By PRODUCT_NAME =
            By.xpath("(//*[@id='simple_plan_product_name'])[1]");

    private static final By PLAN_ID =
            By.xpath("(//*[@id='simple_plan_plan_id'])[1]");

    private static final By AMOUNT =
            By.cssSelector("input#simple_plan_amount");

    private static final By CURRENCY =
            By.xpath("(//*[@id='simple_plan_currency'])[1]");

    private static final By BILLING_PERIOD =
            By.xpath("//select[@name='simple_plan[billing_period]']");

    private static final By TRIAL_LENGTH =
            By.xpath("(//*[@id='simple_plan_trial_length'])[1]");

    private static final By TRIAL_TIME_UNIT =
            By.xpath("(//*[@id='simple_plan_trial_time_unit'])[1]");

    private static final By SAVE_CATALOG_BTN =
            By.xpath("//button[@type='submit' and normalize-space()='Save Catalog']");

    private static final By FLASH_NOTICE =
            By.cssSelector("#flash-notice .custom-alert-info .d-flex > span");

    private static final By DELETE_CATALOG_BTN =
            By.xpath("//button[normalize-space()='Delete Catalog']");


    // =========================================================
    // WHEN: CREATE CATALOG USING ALL JSON TEST DATA
    // =========================================================

    @When("catalogs are created and their XML configuration is validated using all JSON test data")
    public void create_catalogs_and_validate_xml() throws Exception {

        driver = DriverFactory.getDriver();

        Assert.assertNotNull(
                driver,
                "WebDriver is not initialized."
        );

        Object[][] allTestData =
                JsonReader.getIterativeData("catalog.json");

        Assert.assertNotNull(
                allTestData,
                "Catalog test data is null."
        );

        Assert.assertTrue(
                allTestData.length > 0,
                "No catalog test data found in catalog.json."
        );

        for (int iteration = 0; iteration < allTestData.length; iteration++) {

            @SuppressWarnings("unchecked")
            Map<String, Object> testData =
                    (Map<String, Object>) allTestData[iteration][0];

            System.out.printf(
                    "%n========== CATALOG ITERATION %d ==========%n",
                    iteration + 1
            );

            createCatalogTest(testData);
        }

        allCatalogsProcessed = true;
    }


    // =========================================================
    // ORIGINAL TEST METHOD CONVERTED INTO A HELPER METHOD
    // =========================================================

    private void createCatalogTest(
            Map<String, Object> testData) throws Exception {

        WebDriverWait wait =
                new WebDriverWait(driver, Duration.ofSeconds(20));

        System.out.println("\n========== CREATING CATALOG ==========");

        Assert.assertNotNull(
                driver,
                "WebDriver is not initialized."
        );


        // 1. LOGIN AND OPEN CATALOG FORM

        Login.bypassLogin(driver);

        Dashboard dashboard = new Dashboard(driver);

        dashboard.clickAdminAllowedUsers();

        wait.until(
                ExpectedConditions.elementToBeClickable(ADMIN_TENANT)
        ).click();

        WebElement addCatalogBtn =
                wait.until(
                        ExpectedConditions.presenceOfElementLocated(
                                ADD_CATALOG_BTN
                        )
                );

        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block: 'center'});",
                addCatalogBtn
        );

        wait.until(
                ExpectedConditions.elementToBeClickable(ADD_CATALOG_BTN)
        ).click();


        // 2. FILL CATALOG FORM

        String expectedProductCategory =
                testData.get("productCategory").toString().trim();

        String expectedProductName =
                testData.get("productName").toString().trim();

        String expectedPlanId =
                testData.get("planId").toString().trim();

        String expectedAmountStr =
                testData.get("amount").toString().trim();

        String expectedCurrency =
                testData.get("currency").toString().trim();

        String expectedBillingPeriod =
                testData.get("billingPeriod").toString().trim();

        int expectedTrialLength =
                new BigDecimal(
                        testData.get("trialLength").toString().trim()
                ).intValue();

        String expectedTrialLengthStr =
                String.valueOf(expectedTrialLength);

        String expectedTrialTimeUnit =
                testData.get("trialTimeUnit").toString().trim();


        new Select(
                wait.until(
                        ExpectedConditions.elementToBeClickable(
                                PRODUCT_CATEGORY
                        )
                )
        ).selectByValue(expectedProductCategory);


        WebElement productName =
                wait.until(
                        ExpectedConditions.visibilityOfElementLocated(
                                PRODUCT_NAME
                        )
                );

        productName.clear();
        productName.sendKeys(expectedProductName);


        WebElement planId =
                wait.until(
                        ExpectedConditions.visibilityOfElementLocated(
                                PLAN_ID
                        )
                );

        planId.clear();
        planId.sendKeys(expectedPlanId);


        WebElement amount =
                wait.until(
                        ExpectedConditions.visibilityOfElementLocated(
                                AMOUNT
                        )
                );

        amount.clear();
        amount.sendKeys(expectedAmountStr);


        new Select(
                wait.until(
                        ExpectedConditions.elementToBeClickable(
                                CURRENCY
                        )
                )
        ).selectByValue(expectedCurrency);


        new Select(
                wait.until(
                        ExpectedConditions.elementToBeClickable(
                                BILLING_PERIOD
                        )
                )
        ).selectByValue(expectedBillingPeriod);


        // Select Trial Time Unit FIRST so it does not reset Trial Length

        new Select(
                wait.until(
                        ExpectedConditions.elementToBeClickable(
                                TRIAL_TIME_UNIT
                        )
                )
        ).selectByValue(expectedTrialTimeUnit);


        WebElement trialLength =
                wait.until(
                        ExpectedConditions.visibilityOfElementLocated(
                                TRIAL_LENGTH
                        )
                );

        if (trialLength.isEnabled()) {

            trialLength.clear();
            trialLength.sendKeys(expectedTrialLengthStr);

            System.out.println(
                    "Trial length in form: " +
                    trialLength.getAttribute("value")
            );

            Assert.assertEquals(
                    trialLength.getAttribute("value"),
                    expectedTrialLengthStr,
                    "Trial length input mismatch."
            );
        }

        System.out.println(
                "Trial time unit in form: " + expectedTrialTimeUnit
        );


        // 3. SAVE CATALOG

        wait.until(
                ExpectedConditions.elementToBeClickable(
                        SAVE_CATALOG_BTN
                )
        ).click();


        WebElement flashMessage =
                wait.until(
                        ExpectedConditions.visibilityOfElementLocated(
                                FLASH_NOTICE
                        )
                );

        Assert.assertTrue(
                flashMessage.isDisplayed(),
                "Catalog save failed!"
        );

        System.out.println(
                "Catalog saved: " + flashMessage.getText()
        );


        // 4. FETCH ACTIVE CATALOG FROM DATABASE

        String selectSQL =
                "SELECT id, tenant_value FROM tenant_kvs " +
                "WHERE tenant_key = ? AND is_active = 1 " +
                "ORDER BY record_id DESC LIMIT 1";

        Object catalogId;
        String catalogXml;

        try (
                Connection connection = DatabaseConnection.getConnection();
                PreparedStatement selectStatement =
                        connection.prepareStatement(selectSQL)
        ) {

            selectStatement.setString(1, "CATALOG");

            try (ResultSet resultSet = selectStatement.executeQuery()) {

                Assert.assertTrue(
                        resultSet.next(),
                        "Active catalog not found in database."
                );

                catalogId = resultSet.getObject("id");

                catalogXml = resultSet.getString("tenant_value");
            }
        }

        Assert.assertNotNull(
                catalogXml,
                "Catalog XML is null."
        );

        System.out.println(
                "Latest Catalog ID: " + catalogId
        );


        // 5. PARSE XML AND VALIDATE FIELDS

        Document document =
                DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder()
                        .parse(
                                new ByteArrayInputStream(
                                        catalogXml.getBytes(
                                                StandardCharsets.UTF_8
                                        )
                                )
                        );

        Element root = document.getDocumentElement();


        // VERIFY PRODUCT

        Element product = findElement(
                root,
                "product",
                "name",
                expectedProductName
        );

        Assert.assertNotNull(
                product,
                "Product not found in XML."
        );

        Assert.assertEquals(
                getValue(product, "category"),
                expectedProductCategory,
                "Product category mismatch."
        );


        // VERIFY PLAN

        Element plan = findElement(
                root,
                "plan",
                "name",
                expectedPlanId
        );

        Assert.assertNotNull(
                plan,
                "Plan not found in XML."
        );

        Assert.assertEquals(
                getValue(plan, "product"),
                expectedProductName,
                "Plan product mismatch."
        );


        // VERIFY TRIAL

        Element trial = findElement(
                plan,
                "phase",
                "type",
                "TRIAL"
        );

        if (expectedTrialLength > 0
                && !"UNLIMITED".equalsIgnoreCase(expectedTrialTimeUnit)) {

            if (trial == null) {

                System.out.println(
                        "DEBUG - Catalog XML fetched from DB:\n" + catalogXml
                );
            }

            Assert.assertNotNull(
                    trial,
                    "Trial phase not found."
            );

            Element trialDuration =
                    getFirstElement(trial, "duration");

            Assert.assertNotNull(
                    trialDuration,
                    "Trial duration not found."
            );

            Assert.assertEquals(
                    getValue(trialDuration, "number"),
                    expectedTrialLengthStr,
                    "Trial length mismatch."
            );

            Assert.assertEquals(
                    getValue(trialDuration, "unit"),
                    expectedTrialTimeUnit,
                    "Trial unit mismatch."
            );

        } else {

            Assert.assertNull(
                    trial,
                    "Unexpected TRIAL phase found when trialLength is 0 or UNLIMITED."
            );

            System.out.println(
                    "No trial configured (length=" +
                    expectedTrialLength +
                    ", unit=" +
                    expectedTrialTimeUnit +
                    ") - skipped TRIAL phase check."
            );
        }


        // VERIFY RECURRING BILLING

        Element finalPhase =
                getFirstElement(plan, "finalPhase");

        Assert.assertNotNull(
                finalPhase,
                "Final phase not found."
        );

        Element recurring =
                getFirstElement(finalPhase, "recurring");

        Assert.assertNotNull(
                recurring,
                "Recurring section not found."
        );

        Assert.assertEquals(
                getValue(recurring, "billingPeriod"),
                expectedBillingPeriod,
                "Billing period mismatch."
        );

        Element price =
                getFirstElement(recurring, "price");

        Assert.assertNotNull(
                price,
                "Recurring price not found."
        );

        Assert.assertEquals(
                getValue(price, "currency"),
                expectedCurrency,
                "Currency mismatch."
        );

        BigDecimal actualAmount =
                new BigDecimal(getValue(price, "value"));

        BigDecimal expectedAmount =
                new BigDecimal(expectedAmountStr);

        Assert.assertEquals(
                actualAmount.compareTo(expectedAmount),
                0,
                "Amount mismatch."
        );

        System.out.println(
                "All catalog XML validations passed."
        );


        // 6. DELETE CATALOG THROUGH UI

        System.out.println("Deleting catalog through UI...");

        clickButtonInsideNoFollowLink();

        System.out.println(
                "Delete action completed. Checking database..."
        );


        // 7. VERIFY SOFT DELETION OF THE SAME CATALOG UUID

        verifyCatalogDeletion(catalogId);

        System.out.println(
                "UI deletion verified in database."
        );

        System.out.println(
                "Deleted Catalog ID: " + catalogId
        );

        System.out.println(
                "========== CATALOG TEST PASSED =========="
        );
    }


    // =========================================================
    // THEN: CONFIRM ALL CATALOG TESTS COMPLETED
    // =========================================================

    @Then("every created catalog should be soft deleted in the database")
    public void verify_all_catalogs_are_soft_deleted() {

        Assert.assertTrue(
                allCatalogsProcessed,
                "Catalog creation, XML validation, or soft deletion did not complete."
        );

        System.out.println(
                "\n========== ALL CATALOG TESTS PASSED =========="
        );
    }


    // =========================================================
    // DELETE CATALOG THROUGH UI
    // =========================================================

    public void clickButtonInsideNoFollowLink() {

        WebDriverWait wait =
                new WebDriverWait(driver, Duration.ofSeconds(15));

        driver.navigate().refresh();

        wait.until(webDriver ->
                ((JavascriptExecutor) webDriver)
                        .executeScript("return document.readyState")
                        .equals("complete")
        );

        WebElement button =
                wait.until(
                        ExpectedConditions.presenceOfElementLocated(
                                DELETE_CATALOG_BTN
                        )
                );

        JavascriptExecutor js =
                (JavascriptExecutor) driver;

        js.executeScript(
                "arguments[0].scrollIntoView({block: 'center'});",
                button
        );

        js.executeScript(
                "arguments[0].click();",
                button
        );

        System.out.println(
                "Delete Catalog button clicked."
        );
    }


    // =========================================================
    // VERIFY DATABASE DELETION
    // =========================================================

    private void verifyCatalogDeletion(Object catalogId)
            throws Exception {

        String verifySQL =
                "SELECT COUNT(*) FROM tenant_kvs WHERE id = ? AND is_active = 1";

        long timeoutMillis = 15000;

        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < timeoutMillis) {

            try (
                    Connection connection = DatabaseConnection.getConnection();
                    PreparedStatement verifyStatement =
                            connection.prepareStatement(verifySQL)
            ) {

                verifyStatement.setObject(1, catalogId);

                try (ResultSet verifyResult =
                             verifyStatement.executeQuery()) {

                    Assert.assertTrue(
                            verifyResult.next(),
                            "Deletion verification query returned no result."
                    );

                    int activeCount = verifyResult.getInt(1);

                    if (activeCount == 0) {

                        System.out.println(
                                "Database deletion verified (is_active = 0). Active records remaining: 0"
                        );

                        return;
                    }
                }
            }

            Thread.sleep(500);
        }

        Assert.fail(
                "Catalog record is still active (is_active = 1) in database after UI deletion. ID: "
                        + catalogId
        );
    }


    // =========================================================
    // XML HELPERS
    // =========================================================

    private Element findElement(
            Element parent,
            String tag,
            String attribute,
            String value) {

        NodeList elements =
                parent.getElementsByTagName(tag);

        for (int i = 0; i < elements.getLength(); i++) {

            Element element =
                    (Element) elements.item(i);

            if (value.equals(element.getAttribute(attribute))) {
                return element;
            }
        }

        return null;
    }


    private Element getFirstElement(
            Element parent,
            String tag) {

        NodeList elements =
                parent.getElementsByTagName(tag);

        if (elements.getLength() == 0) {
            return null;
        }

        return (Element) elements.item(0);
    }


    private String getValue(
            Element parent,
            String tag) {

        Element element =
                getFirstElement(parent, tag);

        return element == null
                ? null
                : element.getTextContent().trim();
    }
}
