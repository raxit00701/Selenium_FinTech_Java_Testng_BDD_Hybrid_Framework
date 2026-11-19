
package stepdefinitions;

import java.time.Duration;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.testng.Assert;

import API.Login;
import base.DriverFactory;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;

public class LoginTest {

    private WebDriver driver;

    private static final By GET_STARTED_HEADING =
            By.xpath("//h5[normalize-space()='Get Started']");

    @Given("user is logged in using the login API")
    public void user_logs_in_using_login_api() {

        driver = DriverFactory.getDriver();

        Assert.assertNotNull(
                driver,
                "WebDriver is not initialized."
        );

        Login.bypassLogin(driver);

        System.out.println("Login bypassed successfully using API.");
    }

    @When("the dashboard is loaded")
    public void dashboard_is_loaded() {

        WebDriverWait wait = new WebDriverWait(
                driver,
                Duration.ofSeconds(10)
        );

        wait.until(
                ExpectedConditions.visibilityOfElementLocated(
                        GET_STARTED_HEADING
                )
        );

        System.out.println("Dashboard loaded successfully.");
    }

    @Then("the Get Started heading should be displayed")
    public void verify_get_started_heading() {

        WebElement heading = driver.findElement(GET_STARTED_HEADING);

        Assert.assertTrue(
                heading.isDisplayed(),
                "'Get Started' heading is not displayed on the dashboard!"
        );

        System.out.println(
                "Dashboard Validation PASSED: 'Get Started' heading is displayed."
        );
    }
}
