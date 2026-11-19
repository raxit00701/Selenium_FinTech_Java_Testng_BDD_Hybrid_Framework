
package stepdefinitions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Map;

import io.github.cdimascio.dotenv.Dotenv;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.testng.Assert;

import API.Login;
import DB.DatabaseConnection;
import base.DriverFactory;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;
import utils.data_driven.JsonReader;

public class Createpaymentmethod {

    private WebDriver driver;
    private WebDriverWait wait;

    private int totalIterations = 0;
    private int completedIterations = 0;

    // Read BASE_URL from .env
    private static final Dotenv dotenv = Dotenv.configure()
            .directory("src/main/java/env")
            .filename(".env")
            .ignoreIfMissing()
            .load();

    private static final String BASE_URL = dotenv.get("BASE_URL");

    // Locators
    private static final By ADD_METHOD =
            By.xpath("//button[normalize-space()='Add Method']");

    private static final By EXTERNAL_KEY =
            By.xpath("(//*[@id='payment_method_external_key'])[1]");

    private static final By CARD_HOLDER_NAME =
            By.xpath("(//*[@id='card_holder_name'])[1]");

    private static final By EXPIRATION_MONTH =
            By.cssSelector("#expiration_month");

    private static final By EXPIRATION_YEAR =
            By.cssSelector("#expiration_year");

    private static final By CREDIT_CARD_NUMBER =
            By.cssSelector("#credit_card_number");

    private static final By ADDRESS1 =
            By.cssSelector("#address1");

    private static final By ADDRESS2 =
            By.cssSelector("#address2");

    private static final By CITY =
            By.cssSelector("#city");

    private static final By POSTAL_CODE =
            By.cssSelector("#postal_code");

    private static final By STATE =
            By.cssSelector("#state");

    private static final By COUNTRY =
            By.cssSelector("#country");

    private static final By PLUGIN_PROPERTY_KEY =
            By.cssSelector("#plugin_properties_0_key");

    private static final By PLUGIN_PROPERTY_VALUE =
            By.cssSelector("#plugin_properties_0_value");

    private static final By DEFAULT_PAYMENT =
            By.cssSelector("label[for='payment_method_is_default']");

    private static final By SAVE_BUTTON =
            By.xpath("//form[@id='new_payment_method']//div[contains(@class,'justify-content-end')]//button[@id='saveButton' and @type='submit' and normalize-space()='Save Method']");

    private static final By FLASH_NOTICE =
            By.cssSelector(
                    "div[id='flash-notice'] div[class='custom-alert custom-alert-info']"
            );

    // Step 1: Initialize browser
    @Given("a browser is ready to create payment methods")
    public void browser_is_ready_to_create_payment_methods() {

        driver = DriverFactory.getDriver();

        wait = new WebDriverWait(
                driver,
                Duration.ofSeconds(20)
        );

        Assert.assertNotNull(
                BASE_URL,
                "BASE_URL is missing from src/main/java/env/.env"
        );

        System.out.println("Browser initialized for payment method creation.");
    }

    // Step 2: Create payment methods using all JSON records
    @When("payment methods are created using all configured JSON test data")
    public void create_payment_methods_using_json_data() throws Exception {

        Object[][] allTestData =
                JsonReader.getIterativeData("pay_method.json");

        Assert.assertNotNull(
                allTestData,
                "Payment method JSON test data is null."
        );

        totalIterations = allTestData.length;

        Assert.assertTrue(
                totalIterations > 0,
                "No payment method test data found in pay_method.json."
        );

        System.out.println(
                "Total payment method test data records: " + totalIterations
        );

        for (int i = 0; i < totalIterations; i++) {

            System.out.println(
                    "\n========== PAYMENT METHOD ITERATION "
                            + (i + 1) + " =========="
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> data =
                    (Map<String, Object>) allTestData[i][0];

            createPaymentMethod(data);

            completedIterations++;

            System.out.println(
                    "Payment method iteration "
                            + (i + 1) + " completed successfully."
            );
        }
    }

    // Main payment method creation logic
    private void createPaymentMethod(
            Map<String, Object> data
    ) throws Exception {

        // Read test data from JSON
        String accountId = getData(data, "accountId");
        String externalKey = getData(data, "externalKey");
        String cardHolderName = getData(data, "cardHolderName");
        String expirationMonth = getData(data, "expirationMonth");
        String expirationYear = getData(data, "expirationYear");
        String creditCardNumber = getData(data, "creditCardNumber");
        String address1 = getData(data, "address1");
        String address2 = getData(data, "address2");
        String city = getData(data, "city");
        String postalCode = getData(data, "postalCode");
        String state = getData(data, "state");
        String country = getData(data, "country");
        String pluginPropertyKey = getData(data, "pluginPropertyKey");
        String pluginPropertyValue = getData(data, "pluginPropertyValue");

        // 1. Login and navigate to account
        Login.bypassLogin(driver);

        String accountUrl = BASE_URL + "/accounts/" + accountId;

        driver.get(accountUrl);

        System.out.println("Account URL: " + accountUrl);

        // 2. Locate Add Method button
        WebElement addMethodButton = wait.until(
                ExpectedConditions.presenceOfElementLocated(ADD_METHOD)
        );

        // Scroll button into the center of the screen
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block:'center', inline:'nearest'});",
                addMethodButton
        );

        // Wait until button is clickable
        wait.until(
                ExpectedConditions.elementToBeClickable(ADD_METHOD)
        );

        // Normal click with JavaScript fallback
        try {

            addMethodButton.click();

        } catch (org.openqa.selenium.ElementClickInterceptedException e) {

            System.out.println(
                    "Normal click intercepted. Trying JavaScript click."
            );

            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].click();",
                    addMethodButton
            );
        }

        // 3. Enter JSON data into payment method form
        enterValue(EXTERNAL_KEY, externalKey);
        enterValue(CARD_HOLDER_NAME, cardHolderName);
        enterValue(EXPIRATION_MONTH, expirationMonth);
        enterValue(EXPIRATION_YEAR, expirationYear);
        enterValue(CREDIT_CARD_NUMBER, creditCardNumber);
        enterValue(ADDRESS1, address1);
        enterValue(ADDRESS2, address2);
        enterValue(CITY, city);
        enterValue(POSTAL_CODE, postalCode);
        enterValue(STATE, state);
        enterValue(COUNTRY, country);
        enterValue(PLUGIN_PROPERTY_KEY, pluginPropertyKey);
        enterValue(PLUGIN_PROPERTY_VALUE, pluginPropertyValue);

        // 4. Assert UI values against JSON data
        Assert.assertEquals(
                getValue(EXTERNAL_KEY),
                externalKey,
                "External key mismatch"
        );

        Assert.assertEquals(
                getValue(CARD_HOLDER_NAME),
                cardHolderName,
                "Card holder name mismatch"
        );

        Assert.assertEquals(
                getValue(EXPIRATION_MONTH),
                expirationMonth,
                "Expiration month mismatch"
        );

        Assert.assertEquals(
                getValue(EXPIRATION_YEAR),
                expirationYear,
                "Expiration year mismatch"
        );

        Assert.assertEquals(
                getValue(CREDIT_CARD_NUMBER),
                creditCardNumber,
                "Credit card number mismatch"
        );

        Assert.assertEquals(
                getValue(ADDRESS1),
                address1,
                "Address 1 mismatch"
        );

        Assert.assertEquals(
                getValue(ADDRESS2),
                address2,
                "Address 2 mismatch"
        );

        Assert.assertEquals(
                getValue(CITY),
                city,
                "City mismatch"
        );

        Assert.assertEquals(
                getValue(POSTAL_CODE),
                postalCode,
                "Postal code mismatch"
        );

        Assert.assertEquals(
                getValue(STATE),
                state,
                "State mismatch"
        );

        Assert.assertEquals(
                getValue(COUNTRY),
                country,
                "Country mismatch"
        );

        Assert.assertEquals(
                getValue(PLUGIN_PROPERTY_KEY),
                pluginPropertyKey,
                "Plugin property key mismatch"
        );

        Assert.assertEquals(
                getValue(PLUGIN_PROPERTY_VALUE),
                pluginPropertyValue,
                "Plugin property value mismatch"
        );

        System.out.println(
                "All UI fields match the JSON test data."
        );

        // 5. Scroll down to Default Payment
        ((JavascriptExecutor) driver).executeScript(
                "window.scrollTo({top: document.body.scrollHeight, behavior: 'smooth'});"
        );

        WebElement defaultPayment = wait.until(
                ExpectedConditions.presenceOfElementLocated(DEFAULT_PAYMENT)
        );

        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});",
                defaultPayment
        );

        wait.until(
                ExpectedConditions.visibilityOf(defaultPayment)
        );

        // 6. Click Save Method
        WebElement saveButton = wait.until(
                ExpectedConditions.presenceOfElementLocated(SAVE_BUTTON)
        );

        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].click();",
                saveButton
        );

        // 7. Verify flash notice
        WebElement flashNotice = wait.until(
                ExpectedConditions.visibilityOfElementLocated(FLASH_NOTICE)
        );

        Assert.assertTrue(
                flashNotice.isDisplayed(),
                "Payment method success message was not displayed."
        );

        System.out.println(
                "Payment method saved successfully."
        );

        // 8. Verify database record
        verifyPaymentMethodInDatabase(
                accountId,
                externalKey,
                data
        );

        System.out.println(
                "========== PAYMENT METHOD TEST PASSED =========="
        );
    }

    // Enter value into a field
    private void enterValue(By locator, String value) {

        WebElement element = wait.until(
                ExpectedConditions.visibilityOfElementLocated(locator)
        );

        element.clear();
        element.sendKeys(value);
    }

    // Get current input value
    private String getValue(By locator) {

        return driver.findElement(locator).getAttribute("value");
    }

    // Read a required value from JSON
    private String getData(
            Map<String, Object> data,
            String key
    ) {

        Object value = data.get(key);

        Assert.assertNotNull(
                value,
                "Missing JSON value: " + key
        );

        return String.valueOf(value);
    }

    // Verify saved payment method in MariaDB
    private void verifyPaymentMethodInDatabase(
            String accountId,
            String externalKey,
            Map<String, Object> data
    ) throws Exception {

        String sql =
                "SELECT id, account_id, external_key, plugin_name, is_active " +
                "FROM payment_methods " +
                "WHERE account_id = ? AND external_key = ? " +
                "LIMIT 1";

        try (
                Connection connection = DatabaseConnection.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {

            statement.setString(1, accountId);
            statement.setString(2, externalKey);

            try (ResultSet result = statement.executeQuery()) {

                Assert.assertTrue(
                        result.next(),
                        "Payment method record was not found in the database."
                );

                String id = result.getString("id");
                String dbAccountId = result.getString("account_id");
                String dbExternalKey = result.getString("external_key");
                String dbPluginName = result.getString("plugin_name");
                boolean dbIsActive = result.getBoolean("is_active");

                // Database assertions
                Assert.assertNotNull(
                        id,
                        "Payment method ID is null."
                );

                Assert.assertEquals(
                        dbAccountId,
                        accountId,
                        "Database account ID mismatch."
                );

                Assert.assertEquals(
                        dbExternalKey,
                        externalKey,
                        "Database external key mismatch."
                );

                Assert.assertNotNull(
                        dbPluginName,
                        "Plugin name was not saved."
                );

                Assert.assertFalse(
                        dbPluginName.trim().isEmpty(),
                        "Plugin name is empty."
                );

                // Optional expected database values in JSON
                if (data.containsKey("pluginName")) {

                    Assert.assertEquals(
                            dbPluginName,
                            getData(data, "pluginName"),
                            "Plugin name mismatch."
                    );
                }

                if (data.containsKey("isActive")) {

                    boolean expectedActive =
                            Boolean.parseBoolean(
                                    getData(data, "isActive")
                            );

                    Assert.assertEquals(
                            dbIsActive,
                            expectedActive,
                            "Payment method active status mismatch."
                    );
                }

                System.out.println(
                        "========== DATABASE VERIFICATION =========="
                );

                System.out.println("Payment Method ID: " + id);
                System.out.println("Account ID: " + dbAccountId);
                System.out.println("External Key: " + dbExternalKey);
                System.out.println("Plugin Name: " + dbPluginName);
                System.out.println("Is Active: " + dbIsActive);

                System.out.println(
                        "Database verification passed."
                );
            }
        }
    }

    // Step 3: Verify all iterations
    @Then("all payment methods should be successfully saved and verified in the database")
    public void verify_all_payment_methods() {

        Assert.assertEquals(
                completedIterations,
                totalIterations,
                "Not all payment method records were processed successfully."
        );

        System.out.println(
                "\n========== ALL PAYMENT METHODS VERIFIED =========="
        );

        System.out.println(
                "Total records: " + totalIterations
        );

        System.out.println(
                "Successfully completed: " + completedIterations
        );
    }
}
