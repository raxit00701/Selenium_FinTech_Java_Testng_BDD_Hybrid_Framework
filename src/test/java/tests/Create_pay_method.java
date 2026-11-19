
package tests;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Map;

import io.github.cdimascio.dotenv.Dotenv;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import API.Login;
import DB.DatabaseConnection;
import base.Base;
import utils.Videocapture.Video;
import utils.data_driven.JsonReader;
import utils.screenshot.Screenshot;

public class Create_pay_method extends Base {

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

    // DataProvider
    @DataProvider(name = "paymentMethodData")
    public Object[][] paymentMethodData() {
        return JsonReader.getIterativeData("pay_method.json");
    }

    @Test(priority = 6,dataProvider = "paymentMethodData")
    @Video
    @Screenshot
    public void createPaymentMethodTest(Map<String, Object> data) throws Exception {

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));

        Assert.assertNotNull(
                BASE_URL,
                "BASE_URL is missing from src/main/java/env/.env"
        );

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

      WebElement addMethodButton = wait.until(
        ExpectedConditions.presenceOfElementLocated(ADD_METHOD)
);

        // Scroll button into the center of the screen
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({block:'center', inline:'nearest'});",
                addMethodButton
        );

        // Wait for the button to become clickable
        wait.until(ExpectedConditions.elementToBeClickable(ADD_METHOD));

        // Normal click with JavaScript fallback
        try {
            addMethodButton.click();
        } catch (org.openqa.selenium.ElementClickInterceptedException e) {

            System.out.println("Normal click intercepted. Trying JavaScript click.");

            ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].click();",
                    addMethodButton
            );
        }

        // 4-16. Enter JSON data into payment method form
        enterValue(EXTERNAL_KEY, externalKey, wait);
        
        enterValue(CARD_HOLDER_NAME, cardHolderName, wait);
        enterValue(EXPIRATION_MONTH, expirationMonth, wait);
        enterValue(EXPIRATION_YEAR, expirationYear, wait);
        enterValue(CREDIT_CARD_NUMBER, creditCardNumber, wait);
        enterValue(ADDRESS1, address1, wait);
        enterValue(ADDRESS2, address2, wait);
        enterValue(CITY, city, wait);
        enterValue(POSTAL_CODE, postalCode, wait);
        enterValue(STATE, state, wait);
        enterValue(COUNTRY, country, wait);
        enterValue(PLUGIN_PROPERTY_KEY, pluginPropertyKey, wait);
        enterValue(PLUGIN_PROPERTY_VALUE, pluginPropertyValue, wait);

        // 17. Assert UI values against JSON data
        Assert.assertEquals(getValue(EXTERNAL_KEY), externalKey,
                "External key mismatch");
        
       

        Assert.assertEquals(getValue(CARD_HOLDER_NAME), cardHolderName,
                "Card holder name mismatch");

        Assert.assertEquals(getValue(EXPIRATION_MONTH), expirationMonth,
                "Expiration month mismatch");

        Assert.assertEquals(getValue(EXPIRATION_YEAR), expirationYear,
                "Expiration year mismatch");

        Assert.assertEquals(getValue(CREDIT_CARD_NUMBER), creditCardNumber,
                "Credit card number mismatch");

        Assert.assertEquals(getValue(ADDRESS1), address1,
                "Address 1 mismatch");

        Assert.assertEquals(getValue(ADDRESS2), address2,
                "Address 2 mismatch");

        Assert.assertEquals(getValue(CITY), city,
                "City mismatch");

        Assert.assertEquals(getValue(POSTAL_CODE), postalCode,
                "Postal code mismatch");

        Assert.assertEquals(getValue(STATE), state,
                "State mismatch");

        Assert.assertEquals(getValue(COUNTRY), country,
                "Country mismatch");

        Assert.assertEquals(getValue(PLUGIN_PROPERTY_KEY), pluginPropertyKey,
                "Plugin property key mismatch");

        Assert.assertEquals(getValue(PLUGIN_PROPERTY_VALUE), pluginPropertyValue,
                "Plugin property value mismatch");

        System.out.println("All UI fields match the JSON test data.");

       // 18. Wait for 3 seconds before any action
                // 1. Smoothly scroll down the page
        ((JavascriptExecutor) driver).executeScript(
                "window.scrollTo({top: document.body.scrollHeight, behavior: 'smooth'});"
        );

        // 2. Wait until DEFAULT_PAYMENT is present
        WebElement defaultPayment = wait.until(
                ExpectedConditions.presenceOfElementLocated(DEFAULT_PAYMENT)
        );

        // 3. Smoothly scroll to DEFAULT_PAYMENT
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].scrollIntoView({behavior: 'smooth', block: 'center'});",
                defaultPayment
        );

        // 4. Wait until DEFAULT_PAYMENT is visible
        wait.until(ExpectedConditions.visibilityOf(defaultPayment));

        // 5. Wait until SAVE_BUTTON is clickable and click
        WebElement saveButton = wait.until(
        ExpectedConditions.presenceOfElementLocated(SAVE_BUTTON)
);

        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].click();", saveButton
        );

        // 19. Verify flash notice
        WebElement flashNotice = wait.until(
                ExpectedConditions.visibilityOfElementLocated(FLASH_NOTICE)
        );

        Assert.assertTrue(
                flashNotice.isDisplayed(),
                "Payment method success message was not displayed."
        );

        System.out.println("Payment method saved successfully.");

        // 20. Verify database record
        verifyPaymentMethodInDatabase(accountId, externalKey, data);

        System.out.println("========== PAYMENT METHOD TEST PASSED ==========");
    }

    // Enter value into a field
    private void enterValue(By locator, String value, WebDriverWait wait) {

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
    private String getData(Map<String, Object> data, String key) {

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
                PreparedStatement statement = connection.prepareStatement(sql)
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
                Assert.assertNotNull(id, "Payment method ID is null.");

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
                    boolean expectedActive = Boolean.parseBoolean(
                            getData(data, "isActive")
                    );

                    Assert.assertEquals(
                            dbIsActive,
                            expectedActive,
                            "Payment method active status mismatch."
                    );
                }

                System.out.println("========== DATABASE VERIFICATION ==========");
                System.out.println("Payment Method ID: " + id);
                System.out.println("Account ID: " + dbAccountId);
                System.out.println("External Key: " + dbExternalKey);
                System.out.println("Plugin Name: " + dbPluginName);
                System.out.println("Is Active: " + dbIsActive);
                System.out.println("Database verification passed.");
            }
        }
    }
}
