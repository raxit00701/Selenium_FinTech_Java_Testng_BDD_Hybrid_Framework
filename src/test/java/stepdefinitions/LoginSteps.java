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
import io.github.cdimascio.dotenv.Dotenv;

public class LoginSteps {

    private WebDriver driver;
    private String loginUrl;
    private String actualMessage;

    private static final Dotenv dotenv = Dotenv.configure()
            .directory("src/main/java/env")
            .ignoreIfMissing()
            .load();

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

        Assert.assertNotNull(driver, "WebDriver should not be null.");

        // Read from src/main/java/env/.env first, then system environment.
        loginUrl = dotenv.get("BASE_URL");
        if (loginUrl == null || loginUrl.isBlank()) {
            loginUrl = System.getenv("BASE_URL");
        }

        if (loginUrl == null || loginUrl.isBlank()) {
            throw new IllegalStateException(
                    "BASE_URL is missing in src/main/java/env/.env and system environment."
            );
        }

        loginUrl = loginUrl.trim().replaceAll("/+$", "");

        System.out.println("Navigating to login page: " + loginUrl);

        driver.get(loginUrl);

        WebDriverWait wait =
                new WebDriverWait(driver, Duration.ofSeconds(20));

        wait.until(ExpectedConditions.visibilityOfElementLocated(USERNAME));

        System.out.println("Login page loaded successfully.");
    }

    @When("user logs in with username {string} and password {string}")
    public void user_logs_in_with_credentials(
            String username,
            String password) {

        WebDriverWait wait =
                new WebDriverWait(driver, Duration.ofSeconds(20));

        System.out.println("Current URL: " + driver.getCurrentUrl());
        System.out.println("Page title: " + driver.getTitle());

        WebElement usernameField =
                wait.until(ExpectedConditions.visibilityOfElementLocated(USERNAME));

        usernameField.clear();
        usernameField.sendKeys(username);

        WebElement passwordField =
                wait.until(ExpectedConditions.visibilityOfElementLocated(PASSWORD));

        passwordField.clear();
        passwordField.sendKeys(password);

        wait.until(
                ExpectedConditions.elementToBeClickable(LOGIN_BUTTON)
        ).click();

        WebElement response = wait.until(webDriver -> {

            for (By locator : new By[]{
                    INVALID_MESSAGE,
                    SUCCESS_MESSAGE
            }) {

                for (WebElement element : webDriver.findElements(locator)) {

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
                "Invalid username or password.".equals(actualMessage)
                || "Signed in successfully.".equals(actualMessage);

        Assert.assertTrue(
                validResponse,
                "Unexpected login response: " + actualMessage
        );
    }
}
