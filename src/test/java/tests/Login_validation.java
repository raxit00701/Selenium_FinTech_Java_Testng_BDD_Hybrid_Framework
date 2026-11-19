
package tests;

import java.time.Duration;
import java.util.Map;

import utils.Videocapture.Video;

import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import base.Base;
import utils.screenshot.Screenshot;
import utils.data_driven.JsonReader;

public class Login_validation extends Base {

    // Locators
    private static final By USERNAME = By.id("user_kb_username");
    private static final By PASSWORD = By.xpath("//input[@id='user_password']");
    private static final By LOGIN_BUTTON =
            By.cssSelector("button[type='submit']");

    private static final By INVALID_MESSAGE = By.xpath(
            "//span[normalize-space()='Invalid username or password.']"
    );

    private static final By SUCCESS_MESSAGE = By.xpath(
            "//span[normalize-space()='Signed in successfully.']"
    );

    // JSON Data Provider
    @DataProvider(name = "loginData")
    public Object[][] loginData() {
        return JsonReader.getIterativeData("Login.json", "login");
    }

    // Login Validation Test
    @Test(priority = 1,dataProvider = "loginData")
    @Video
    @Screenshot
    public void LoginValidationTest(Map<String, Object> credentials) {

        try {

            WebDriverWait wait = new WebDriverWait(
                    driver, Duration.ofSeconds(10)
            );

            // Enter username
            wait.until(
                    ExpectedConditions.visibilityOfElementLocated(USERNAME)
            ).sendKeys(credentials.get("username").toString());

            // Enter password
            driver.findElement(PASSWORD)
                    .sendKeys(credentials.get("password").toString());

            // Click login
            driver.findElement(LOGIN_BUTTON).click();

            // Wait for either login response
            WebElement response = wait.until(webDriver -> {

                for (By locator : new By[]{
                        INVALID_MESSAGE, SUCCESS_MESSAGE
                }) {

                    for (WebElement element :
                            webDriver.findElements(locator)) {

                        if (element.isDisplayed()) {
                            return element;
                        }
                    }
                }

                return null;
            });

            // Get actual response
            String actualMessage = response.getText().trim();

            // Validate login response
            if (actualMessage.equals("Invalid username or password.")
                    || actualMessage.equals("Signed in successfully.")) {

                System.out.println(
                        "PASS: Login response received for username: "
                                + credentials.get("username")
                );

                System.out.println("Response: " + actualMessage);

            } else {

                Assert.fail(
                        "Unexpected login response: " + actualMessage
                );
            }

        } catch (Exception e) {

            System.out.println(
                    "FAIL: Login validation failed for username: "
                            + credentials.get("username")
            );

            System.out.println("Error: " + e.getMessage());

            e.printStackTrace();

            Assert.fail(
                    "Login validation test failed: " + e.getMessage(), e
            );
        }
    }
}
