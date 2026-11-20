package runners;

import io.cucumber.testng.AbstractTestNGCucumberTests;
import io.cucumber.testng.CucumberOptions;

@CucumberOptions(
    features = {
        "src/test/resources/features/Login.feature",
        "src/test/resources/features/Login_Test.feature",
        "src/test/resources/features/CreateAccount.feature",
        "src/test/resources/features/CreateCatalog.feature",
        "src/test/resources/features/CreateSubs.feature",
        "src/test/resources/features/CreateInvoice.feature",
        "src/test/resources/features/CreatePaymethod.feature",
        "src/test/resources/features/CreatePayment.feature"
        
    },
    glue = {
        "stepdefinitions",
        "hooks"
    },
    plugin = {
        "pretty",
        "html:target/cucumber-reports/cucumber.html",
        "json:target/cucumber-reports/cucumber.json"
    },
    monochrome = true
)
public class TestRunner extends AbstractTestNGCucumberTests {
}
