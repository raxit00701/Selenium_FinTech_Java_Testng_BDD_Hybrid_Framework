package utils.report;

import com.aventstack.extentreports.AnalysisStrategy;
import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;
import com.aventstack.extentreports.reporter.configuration.Theme;
import com.aventstack.extentreports.reporter.configuration.ViewName;

import io.github.cdimascio.dotenv.Dotenv;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ExtentManager {

    private static ExtentReports extent;

    // Report directory: C:\Projects\Selenium_java_Kill_bill\test-output\extent-report\
    private static final String REPORT_DIR = System.getProperty("user.dir")
            + File.separator + "test-output"
            + File.separator + "extent-report"
            + File.separator;

    private static final String REPORT_FILE_PATH = REPORT_DIR + "ExtentReport.html";

    // Load .env configuration
    private static final Dotenv dotenv = Dotenv.configure()
            .directory("src/main/java/env")
            .filename(".env")
            .ignoreIfMissing()
            .load();

    private ExtentManager() {
        // Prevent instantiation
    }

    public static synchronized ExtentReports getInstance() {

        if (extent == null) {

            try {
                // Ensure report directory exists
                Files.createDirectories(Path.of(REPORT_DIR));

                ExtentSparkReporter sparkReporter = new ExtentSparkReporter(REPORT_FILE_PATH);

                sparkReporter.config().setDocumentTitle("Kill Bill Automation Report");
                sparkReporter.config().setReportName("Kill Bill | Test Execution Dashboard");
                sparkReporter.config().setTheme(Theme.DARK);
                sparkReporter.config().setEncoding("UTF-8");
                sparkReporter.config().setTimeStampFormat("dd MMM yyyy, hh:mm:ss a");

                // =========================================================
                // HIGH-VOLUME / MASSIVE REPORT OPTIMIZATIONS
                // =========================================================
                // 1. Bundle JS/CSS locally so offline viewing is instantaneous
                sparkReporter.config().enableOfflineMode(true);

                // 2. Disable heavy timeline SVG canvas (lags severely above 100+ tests)
                sparkReporter.config().setTimelineEnabled(false);

                // 3. Trim unused views (AUTHOR/CATEGORY) so Extent doesn't duplicate DOM trees
                sparkReporter.viewConfigurer().viewOrder().as(new ViewName[] {
                        ViewName.DASHBOARD,
                        ViewName.TEST,
                        ViewName.EXCEPTION
                }).apply();

                // 4. DOM Virtualization & GPU-friendly CSS for 1,000+ test entries:
                //    - content-visibility: auto skips rendering off-screen tests until scrolled
                //    - Caps stack trace <textarea>/<pre> heights so 10,000-line traces don't bloat layout
                //    - Replaces GPU-heavy blur box-shadows with lightweight 1px borders
                sparkReporter.config().setCss(
                        ".test-item { content-visibility: auto; contain-intrinsic-size: 1px 60px; }" +
                        ".test-detail, .node { content-visibility: auto; contain-intrinsic-size: 1px 250px; }" +
                        ".card, .card-panel { border-radius: 8px !important; box-shadow: none !important; border: 1px solid rgba(255,255,255,0.08) !important; }" +
                        ".card-header, .card-title { font-weight: 600 !important; letter-spacing: .3px; }" +
                        ".badge { border-radius: 6px !important; font-weight: 600; }" +
                        "table.bordered > tbody > tr > td { padding: 6px 10px !important; word-break: break-word; }" +
                        ".test-content .name { font-weight: 600; }" +
                        "textarea.code-block, pre { max-height: 280px !important; overflow-y: auto !important; font-size: 12px !important; }" +
                        "img.r-img { max-height: 320px !important; width: auto !important; object-fit: contain; }"
                );

                // 5. Inject JS to lazy-load all images & prevent video auto-buffering on load
                sparkReporter.config().setJs(
                        "document.addEventListener('DOMContentLoaded', function() {" +
                        "  document.querySelectorAll('img').forEach(function(img) { img.setAttribute('loading', 'lazy'); });" +
                        "  document.querySelectorAll('video').forEach(function(vid) { vid.setAttribute('preload', 'none'); });" +
                        "});"
                );

                extent = new ExtentReports();
                // TEST strategy avoids deep multi-tier aggregation overhead on large suites
                extent.setAnalysisStrategy(AnalysisStrategy.TEST);
                extent.attachReporter(sparkReporter);

                // Safety net: flush the report if the JVM is terminated abruptly
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    synchronized (ExtentManager.class) {
                        if (extent != null) {
                            extent.flush();
                        }
                    }
                }));

                // Dynamic Environment Details (Fail-Fast aware + System Property priority)
                extent.setSystemInfo("Tester", getConfigValue("TESTER_NAME", "Raxit Sharma"));
                extent.setSystemInfo("Browser", getConfigValue("BROWSER", "Not Configured"));
                extent.setSystemInfo("Execution Mode", System.getProperty("execution.mode", "serial"));
                extent.setSystemInfo("Threads", System.getProperty("thread.count", "1"));
                extent.setSystemInfo("Headless Mode", getConfigValue("HEADLESS", "false"));
                extent.setSystemInfo("Incognito Mode", getConfigValue("INCOGNITO", "false"));
                extent.setSystemInfo("Application URL", getConfigValue("BASE_URL", "Not Configured"));
                extent.setSystemInfo("API Base URL", getConfigValue("API_BASE_URL", "Not Configured"));
                extent.setSystemInfo("Environment", getConfigValue("TEST_ENV", "Not Configured"));

                // Run details
                extent.setSystemInfo("Run Started",
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a")));
                extent.setSystemInfo("Machine",
                        System.getenv().getOrDefault("COMPUTERNAME",
                                System.getenv().getOrDefault("HOSTNAME", "Unknown")));
                extent.setSystemInfo("User", System.getProperty("user.name"));
                extent.setSystemInfo("Operating System", System.getProperty("os.name"));
                extent.setSystemInfo("OS Architecture", System.getProperty("os.arch"));
                extent.setSystemInfo("Java Version", System.getProperty("java.version"));

            } catch (Exception e) {
                System.err.println("Failed to initialize Extent Reports: " + e.getMessage());
            }
        }

        return extent;
    }

    /**
     * Thread-safe flush helper for large parallel runs.
     */
    public static synchronized void flushReport() {
        if (extent != null) {
            extent.flush();
        }
    }

    private static String getConfigValue(String key, String defaultValue) {
        String value = System.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value;
        }

        // Also check lowercase Maven property (e.g. -Dbrowser=chrome)
        value = System.getProperty(key.toLowerCase());
        if (value != null && !value.isBlank()) {
            return value;
        }

        value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            return value;
        }

        value = dotenv.get(key);
        if (value != null && !value.isBlank()) {
            return value;
        }

        return defaultValue;
    }
}