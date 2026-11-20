package base;

import org.apache.logging.log4j.Logger;
import org.openqa.selenium.WebDriver;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Listeners;

import io.github.cdimascio.dotenv.Dotenv;
import io.restassured.RestAssured;
import utils.artifact.ReportingListener;
import utils.log.LogUtil;

@Listeners(ReportingListener.class)
public class Base {

    protected WebDriver driver;

    // Instantiates a dynamic logger for whichever test class extends Base
    protected Logger log = LogUtil.getLogger(this.getClass());

    // Load .env variables once for the entire execution
    protected static final Dotenv dotenv = Dotenv.configure()
            .directory("src/main/java/env")
            .filename(".env")
            .ignoreIfMissing()
            .load();

    @BeforeMethod
    public void setUp() {

        // 1. Validate URL config first (fail fast, before paying for a browser launch)
        String baseUrl = getBaseUrl();

        // 2. Configure REST Assured (Optional)
        String apiBaseUrl = getApiBaseUrl();
        if (apiBaseUrl != null && !apiBaseUrl.isBlank()) {
            RestAssured.baseURI = apiBaseUrl;
        }

        // 3. Get a browser: DriverFactory resolves BROWSER, HEADLESS, and INCOGNITO
        // (with -DBROWSER, -DHEADLESS, -DINCOGNITO CLI overrides) and reuses warm sessions
        boolean reused = DriverFactory.initDriver();
        driver = DriverFactory.getDriver();

        // 4. A reused browser gets its cookies/storage wiped so every test starts clean
        if (reused) {
            DriverFactory.resetSession(baseUrl);
        }

        // 5. Open Application (Required)
        driver.get(baseUrl);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        if (isBrowserReuseEnabled()) {
            DriverFactory.releaseDriver();   // keep the browser alive for the next test
        } else {
            DriverFactory.quitDriver();      // old behaviour: fresh browser per test
        }
    }

    @AfterSuite(alwaysRun = true)
    public void tearDownSuite() {
        // Closes every thread's browser (in parallel). A JVM shutdown hook backs this up.
        DriverFactory.quitAllDrivers();
    }

    /**
     * Fetches configuration prioritizing System Properties (e.g. -DBASE_URL=http://localhost:9090/),
     * then OS environment variables, over the .env file.
     *
     * @param key The environment variable key
     * @return The value, or null if not found
     */
    protected String getEnv(String key) {
        String systemValue = System.getProperty(key);

        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }

        String envValue = System.getenv(key);

        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }

        return dotenv.get(key);
    }

    /**
     * Browser reuse is ON by default. Set REUSE_BROWSER=false (system property or .env)
     * to launch a brand-new browser for every test.
     */
    protected boolean isBrowserReuseEnabled() {
        String value = getEnv("REUSE_BROWSER");
        return value == null || value.isBlank() || Boolean.parseBoolean(value);
    }

    /**
     * Retrieves the UI Base URL. Throws an exception if missing.
     */
    protected String getBaseUrl() {
        String baseUrl = getEnv("BASE_URL");

        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("CRITICAL ERROR: 'BASE_URL' is not set in system properties or .env file!");
        }

        return baseUrl;
    }

    /**
     * Retrieves the API Base URL. Can return null if not used.
     */
    protected String getApiBaseUrl() {
        return getEnv("API_BASE_URL");
    }
}
