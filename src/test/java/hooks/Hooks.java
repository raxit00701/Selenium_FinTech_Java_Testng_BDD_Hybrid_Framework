
package hooks;

import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

import base.Base;
import base.DriverFactory;
import io.cucumber.java.After;
import io.cucumber.java.AfterAll;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;

public class Hooks extends Base {

    // =========================================================
    // BEFORE EACH SCENARIO
    // =========================================================

    @Before
    public void setUpScenario() {

        // Initialize or reuse the browser using existing Base configuration.
        // Supports Chrome, Edge, Firefox, headless, incognito and browser reuse.
        super.setUp();

        // Retrieve the current thread's WebDriver.
        driver = DriverFactory.getDriver();

        System.out.println("\n========== CUCUMBER BROWSER INITIALIZATION ==========");
        System.out.println("Browser initialized successfully.");
        System.out.println("Current scenario: " + "Ready");
        System.out.println("Driver available: " + DriverFactory.hasDriver());
        System.out.println("=====================================================\n");
    }

    // =========================================================
    // AFTER EACH SCENARIO
    // =========================================================

    @After
    public void tearDownScenario(Scenario scenario) {

        try {

            // Capture screenshot when the scenario fails.
            if (scenario.isFailed() && DriverFactory.hasDriver()) {

                WebDriver currentDriver = DriverFactory.getDriver();

                byte[] screenshot =
                        ((TakesScreenshot) currentDriver)
                                .getScreenshotAs(OutputType.BYTES);

                scenario.attach(
                        screenshot,
                        "image/png",
                        "Failure Screenshot"
                );

                System.out.println(
                        "Failure screenshot attached: " + scenario.getName()
                );
            }

            System.out.println("\n========== CUCUMBER SCENARIO RESULT ==========");
            System.out.println("Scenario: " + scenario.getName());
            System.out.println("Status: " + scenario.getStatus());
            System.out.println("===============================================\n");

        } catch (Exception e) {

            System.out.println(
                    "Unable to capture scenario evidence: " + e.getMessage()
            );

        } finally {

            // Existing Base cleanup handles browser reuse or browser quit.
            super.tearDown();
        }
    }

    // =========================================================
    // AFTER ALL SCENARIOS
    // =========================================================

    @AfterAll
    public static void tearDownAllBrowsers() {

        System.out.println(
                "\n========== CUCUMBER SUITE CLEANUP =========="
        );

        // Close every remaining browser, including browsers
        // retained through the browser-reuse configuration.
        DriverFactory.quitAllDrivers();

        System.out.println("All remaining WebDriver sessions closed.");
        System.out.println("============================================\n");
    }
}
