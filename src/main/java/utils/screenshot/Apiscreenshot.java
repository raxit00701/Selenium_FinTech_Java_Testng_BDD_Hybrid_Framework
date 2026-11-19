package utils.screenshot;

import com.assertthat.selenium_shutterbug.core.Capture;
import com.assertthat.selenium_shutterbug.core.Shutterbug;
import com.aventstack.extentreports.ExtentTest;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WrapsDriver;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.firefox.HasFullPageScreenshot;
import org.openqa.selenium.support.decorators.Decorated;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.ITestResult;
import org.testng.Reporter;

import utils.artifact.ReportingListener;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class Apiscreenshot {

    private static final Path OUTPUT_DIR = Path.of("test-output");
    private static final Path REPORT_DIR = OUTPUT_DIR.resolve("extent-report");
    private static final Path API_SCREENSHOT_BASE_DIR = REPORT_DIR.resolve("apiscreenshoot");

    // Chrome cannot render textures taller than ~16k px; larger captures come back blank/cropped.
    private static final double MAX_CAPTURE_HEIGHT_PX = 16_384;

    // Tracks sequential page numbers per test script (e.g., 01, 02, 03)
    private static final Map<String, AtomicInteger> STEP_COUNTER = new ConcurrentHashMap<>();

    // Tracks screenshots taken during the active test thread for ExecutionDashboard.html
    private static final ThreadLocal<String> LAST_SCREENSHOT_PATH = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> TEST_SCREENSHOT_PATHS =
            ThreadLocal.withInitial(ArrayList::new);

    private Apiscreenshot() {
        // Prevent object creation
    }

    public static String capturePage(WebDriver driver) {
        return capturePage(driver, "page");
    }

    public static String capturePage(WebDriver driver, String pageOrActionName) {
        if (driver == null) {
            return null;
        }

        try {
            // Work on the unwrapped driver: no listener overhead, no accidental element tracking
            WebDriver rawDriver = unwrapDriver(driver);

            waitForPageLoad(rawDriver);

            // 1. Dynamically get current Test Script Name (e.g., "Create_Account")
            String testScriptName = resolveTestScriptName();

            // 2. Create test-output/extent-report/apiscreenshoot/<testscriptname>
            Path scriptFolder = API_SCREENSHOT_BASE_DIR.resolve(testScriptName);
            Files.createDirectories(scriptFolder);

            // 3. Generate sequential file name WITHOUT ".png"
            int stepNumber = STEP_COUNTER
                    .computeIfAbsent(testScriptName, k -> new AtomicInteger(0))
                    .incrementAndGet();

            String safePageName = sanitizeFileName(pageOrActionName);
            String fileNameWithoutExt = String.format("%02d_%s", stepNumber, safePageName);
            Path destination = scriptFolder.resolve(fileNameWithoutExt + ".png");

            // 4. Full-page screenshot WITHOUT touching the page's scroll position.
            //    Preferred: browser-native capture. Fallback: Shutterbug, with scroll restored afterwards.
            if (!captureWithoutScrolling(rawDriver, destination)) {
                captureWithShutterbug(rawDriver, scriptFolder, fileNameWithoutExt);
            }

            // 5. Build universal relative path that works from BOTH:
            //    - test-output/extent-report/ExtentReport.html
            //    - test-output/dashboard/ExecutionDashboard.html
            String universalRelativePath = getUniversalRelativePath(destination);

            // 6. Store for ExecutionDashboard.html
            LAST_SCREENSHOT_PATH.set(universalRelativePath);
            TEST_SCREENSHOT_PATHS.get().add(universalRelativePath);

            // 7. Attach to active ExtentTest if present
            ExtentTest extentTest = ReportingListener.getCurrentTest();
            if (extentTest != null) {
                extentTest.info("UI Full Page Captured [" + safePageName + "]")
                        .addScreenCaptureFromPath(universalRelativePath, safePageName);
            }

            return universalRelativePath;

        } catch (Exception e) {
            System.err.println("Unable to capture full-page screenshot: " + e.getMessage());
            return null;
        }
    }

    // =========================================================
    // SCROLL-FREE CAPTURE (THE FIX)
    // =========================================================

    /**
     * Captures the whole page using the browser's own renderer, so the real viewport is never
     * scrolled, resized or re-laid-out. Nothing on the page can notice the screenshot:
     * no scroll events, no closed dropdowns, no re-rendered lazy lists.
     *
     * @return true if the PNG was written, false if this browser/page needs the fallback
     */
    private static boolean captureWithoutScrolling(WebDriver rawDriver, Path destination) {
        try {
            byte[] png = null;

            if (rawDriver instanceof HasFullPageScreenshot firefox) {
                // Firefox: native full-page screenshot
                png = firefox.getFullPageScreenshotAs(OutputType.BYTES);
            } else if (rawDriver instanceof HasCdp cdp) {
                // Chrome / Edge: CDP capture beyond the viewport
                png = captureViaCdp(cdp);
            }

            if (png == null || png.length == 0) {
                return false;
            }

            Files.write(destination, png);
            return true;

        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static byte[] captureViaCdp(HasCdp cdp) {
        Map<String, Object> metrics = cdp.executeCdpCommand("Page.getLayoutMetrics", Map.of());

        Object sizeObject = metrics.get("cssContentSize") != null
                ? metrics.get("cssContentSize")
                : metrics.get("contentSize");
        Map<String, Object> size = (Map<String, Object>) sizeObject;

        double width = Math.ceil(((Number) size.get("width")).doubleValue());
        double height = Math.ceil(Math.min(((Number) size.get("height")).doubleValue(), MAX_CAPTURE_HEIGHT_PX));

        Map<String, Object> clip = new HashMap<>();
        clip.put("x", 0);
        clip.put("y", 0);
        clip.put("width", width);
        clip.put("height", height);
        clip.put("scale", 1);

        Map<String, Object> params = new HashMap<>();
        params.put("format", "png");
        params.put("captureBeyondViewport", true);
        params.put("fromSurface", true);
        params.put("clip", clip);

        Map<String, Object> result = cdp.executeCdpCommand("Page.captureScreenshot", params);
        return Base64.getDecoder().decode((String) result.get("data"));
    }

    /**
     * Fallback for browsers without a native full-page capture. Shutterbug scrolls the real
     * window to stitch the image, so the original scroll position is always restored.
     */
    private static void captureWithShutterbug(WebDriver rawDriver, Path scriptFolder, String fileNameWithoutExt)
            throws Exception {

        JavascriptExecutor js = (JavascriptExecutor) rawDriver;

        Object originalScroll = null;
        try {
            originalScroll = js.executeScript("return [window.pageXOffset, window.pageYOffset];");
        } catch (Exception ignored) {
            // cannot read scroll position; nothing to restore
        }

        try {
            Shutterbug.shootPage(rawDriver, Capture.FULL, true)
                    .withName(fileNameWithoutExt)
                    .save(scriptFolder.toString());
        } finally {
            if (originalScroll instanceof List<?> position && position.size() == 2) {
                try {
                    // 'instant' so CSS scroll-behavior: smooth can't leave the page mid-animation
                    js.executeScript(
                            "window.scrollTo({left: arguments[0], top: arguments[1], behavior: 'instant'});",
                            position.get(0), position.get(1));
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
    }

    // =========================================================
    // DASHBOARD HELPERS
    // =========================================================

    // Returns all screenshots joined by a comma for the dashboard
    public static String getAllScreenshotPathsAsCsv() {
        List<String> paths = TEST_SCREENSHOT_PATHS.get();
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        return String.join(",", paths);
    }

    public static String getLastScreenshotPath() {
        return LAST_SCREENSHOT_PATH.get();
    }

    public static List<String> getTestScreenshotPaths() {
        return Collections.unmodifiableList(TEST_SCREENSHOT_PATHS.get());
    }

    public static void clearTestScreenshots() {
        LAST_SCREENSHOT_PATH.remove();
        TEST_SCREENSHOT_PATHS.remove();
    }

    // =========================================================
    // INTERNALS
    // =========================================================

    /**
     * Peels off EventFiringDecorator proxies (Decorated) and any WrapsDriver layers.
     */
    private static WebDriver unwrapDriver(WebDriver driver) {
        WebDriver current = driver;

        while (true) {
            WebDriver next = null;

            if (current instanceof Decorated<?> decorated && decorated.getOriginal() instanceof WebDriver original) {
                next = original;
            } else if (current instanceof WrapsDriver wrapsDriver) {
                next = wrapsDriver.getWrappedDriver();
            }

            if (next == null || next == current) {
                return current;
            }
            current = next;
        }
    }

    private static String resolveTestScriptName() {
        String scriptName = ReportingListener.getCurrentScriptName();
        if (scriptName != null && !scriptName.isBlank()) {
            return scriptName;
        }

        ITestResult result = Reporter.getCurrentTestResult();
        if (result != null) {
            return result.getTestClass().getRealClass().getSimpleName();
        }

        return "UnknownScript";
    }

    private static void waitForPageLoad(WebDriver driver) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(5))
                    .pollingEvery(Duration.ofMillis(100))   // default is 500ms
                    .until(webDriver -> "complete".equals(
                            ((JavascriptExecutor) webDriver).executeScript("return document.readyState")));
        } catch (Exception ignored) {
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "page";
        }
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static String getUniversalRelativePath(Path screenshotPath) {
        Path outputDirectory = OUTPUT_DIR.toAbsolutePath();
        Path absoluteScreenshot = screenshotPath.toAbsolutePath();

        // Returns "../extent-report/apiscreenshoot/<scriptName>/<file>.png"
        return "../" + outputDirectory
                .relativize(absoluteScreenshot)
                .toString()
                .replace("\\", "/");
    }
}