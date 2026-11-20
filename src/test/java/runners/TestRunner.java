package runners;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.testng.ITestContext;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Listeners;

import io.cucumber.testng.AbstractTestNGCucumberTests;
import io.cucumber.testng.CucumberOptions;
import io.cucumber.testng.PickleWrapper;
import utils.artifact.ReportingListener;

@Listeners(ReportingListener.class)
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
        glue = {"stepdefinitions", "hooks"},
        plugin = {
                "pretty",
                "html:target/cucumber-reports/cucumber.html",
                "json:target/cucumber-reports/cucumber.json"
        },
        monochrome = true
)
public class TestRunner extends AbstractTestNGCucumberTests {

    private static final List<String> FEATURE_ORDER = Arrays.asList(
            "Login.feature",
            "Login_Test.feature",
            "CreateAccount.feature",
            "CreateCatalog.feature",
            "CreateSubs.feature",
            "CreateInvoice.feature",
            "CreatePaymethod.feature",
            "CreatePayment.feature"
    );

    @Override
    @DataProvider(parallel = false)
    public Object[][] scenarios(ITestContext context) {
        Object[][] scenarios = super.scenarios(context);

        Arrays.sort(scenarios, Comparator.comparingInt(row -> {
            PickleWrapper pickleWrapper = (PickleWrapper) row[0];
            String uri = pickleWrapper.getPickle().getUri().toString();
            int lastSlash = Math.max(uri.lastIndexOf('/'), uri.lastIndexOf('\\'));
            String fileName = lastSlash >= 0 ? uri.substring(lastSlash + 1) : uri;
            int index = FEATURE_ORDER.indexOf(fileName);
            return index >= 0 ? index : Integer.MAX_VALUE;
        }));

        return scenarios;
    }
}
