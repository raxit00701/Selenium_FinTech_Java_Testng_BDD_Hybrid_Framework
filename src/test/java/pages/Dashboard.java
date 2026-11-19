package pages;

import java.time.Duration;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.FindBy;
import org.openqa.selenium.support.PageFactory;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

public class Dashboard {

    WebDriverWait wait;

    // All Dashboard locators stored in one place
    @FindBy(xpath = "//h5[normalize-space()='Get Started']")
    WebElement getStartedHeading;

    @FindBy(css = ".d-flex.flex-column.w-100")
    WebElement accountDetailsContainer;

    @FindBy(xpath = "//a[@href='/admin_allowed_users']")
    WebElement adminAllowedUsersLink;

    @FindBy(xpath = "//a[@href='/invoices' and normalize-space()='View All']")
    WebElement viewAllInvoicesLink;

    // Constructor: connects your driver to the locators above (20s max wait)
    public Dashboard(WebDriver driver) {
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(20));
        PageFactory.initElements(driver, this);
    }

    // Reusable actions for your test scripts
    public boolean isGetStartedDisplayed() {
        return wait.until(ExpectedConditions.visibilityOf(getStartedHeading)).isDisplayed();
    }

    public WebElement getAccountDetailsContainer() {
        return wait.until(ExpectedConditions.visibilityOf(accountDetailsContainer));
    }

    public void clickAdminAllowedUsers() {
        wait.until(ExpectedConditions.elementToBeClickable(adminAllowedUsersLink)).click();
    }

    public void clickViewAllInvoices() {
        wait.until(ExpectedConditions.elementToBeClickable(viewAllInvoicesLink)).click();
    }
}