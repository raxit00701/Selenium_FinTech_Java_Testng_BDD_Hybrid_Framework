package stepdefinitions;

import java.time.Duration;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.Assert;

import base.DriverFactory;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;

public class LoginSteps {

    private WebDriver driver;
    private String loginUrl;
    private String actualMessage;

    private static final By USERNAME =
            By.id("user_kb_username");

    private static final By PASSWORD =
            By.id("user_password");

    private static final By LOGIN_BUTTON =
            By.cssSelector("button[type='submit']");

    private static final By INVALID_MESSAGE =
            By.xpath("//span[normalize-space()='Invalid username or password.']");

    private static final By SUCCESS_MESSAGE =
            By.xpath("//span[normalize-space()='Signed in successfully.']");


    @Given("user navigates to the Kill Bill login page")
    public void user_navigates_to_login_page() {

        driver = DriverFactory.getDriver();

        loginUrl = driver.getCurrentUrl();

        Assert.assertNotNull(
                loginUrl,
                "Login URL should not be null"
        );

        System.out.println("Login page URL: " + loginUrl);
    }


    @When("user logs in with username {string} and password {string}")
    public void user_logs_in_with_credentials(
            String username,
            String password) {

        driver.get(loginUrl);

        WebDriverWait wait =
                new WebDriverWait(driver, Duration.ofSeconds(10));

        wait.until(
                ExpectedConditions.visibilityOfElementLocated(USERNAME)
        ).sendKeys(username);

        wait.until(
                ExpectedConditions.visibilityOfElementLocated(PASSWORD)
        ).sendKeys(password);

        wait.until(
                ExpectedConditions.elementToBeClickable(LOGIN_BUTTON)
        ).click();

        WebElement response = wait.until(webDriver -> {

            for (By locator : new By[]{
                    INVALID_MESSAGE,
                    SUCCESS_MESSAGE
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

        actualMessage = response.getText().trim();

        System.out.println("--------------------------------");
        System.out.println("Username: " + username);
        System.out.println("Response: " + actualMessage);
        System.out.println("--------------------------------");
    }


    @Then("login response should be either successful or invalid")
    public void validate_login_response() {

        boolean validResponse =
                actualMessage.equals("Invalid username or password.")
                || actualMessage.equals("Signed in successfully.");

        Assert.assertTrue(
                validResponse,
                "Unexpected login response: " + actualMessage
        );
    }
}