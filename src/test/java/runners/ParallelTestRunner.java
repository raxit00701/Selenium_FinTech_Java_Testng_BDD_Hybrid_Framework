package runners;

import org.testng.annotations.DataProvider;
import org.testng.ITestContext;

import io.cucumber.testng.AbstractTestNGCucumberTests;
import io.cucumber.testng.CucumberOptions;

@CucumberOptions(
    features = "classpath:features/KillBillExecution.feature",
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
public class ParallelTestRunner extends AbstractTestNGCucumberTests {

   @Override
    @DataProvider(parallel = false)
    public Object[][] scenarios(ITestContext context) {
        return super.scenarios(context);
    }
}
