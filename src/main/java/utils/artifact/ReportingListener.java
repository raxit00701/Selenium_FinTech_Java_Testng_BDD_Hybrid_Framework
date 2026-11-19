package utils.artifact;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.Status;

import org.apache.logging.log4j.Logger;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import base.DriverFactory;
import utils.Videocapture.Video;
import utils.Videocapture.VideoRecorder;
import utils.log.LogUtil;
import utils.report.CustomDashboardManager;
import utils.report.ExtentManager;
import utils.screenshot.Apiscreenshot;
import utils.screenshot.ElementTracker;
import utils.screenshot.Screenshot;
import utils.screenshot.ScreenshotUtil;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class ReportingListener implements ITestListener {

    private static final Logger LOGGER = LogUtil.getLogger(ReportingListener.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private ExtentReports extent;

    private static final AtomicBoolean EVIDENCE_CLEANED_FOR_RUN = new AtomicBoolean(false);
    private static final ConcurrentHashMap<String, AtomicInteger> TEST_INVOCATION_COUNTER = new ConcurrentHashMap<>();

    private static final ThreadLocal<ExtentTest> TEST = new ThreadLocal<>();
    private static final ThreadLocal<VideoRecorder> RECORDER = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_TEST_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_SCRIPT_NAME = new ThreadLocal<>();
    
    // Toggle for Automatic Full-Page Step Screenshots
    private static final ThreadLocal<Boolean> AUTO_SCREENSHOT_ENABLED = ThreadLocal.withInitial(() -> false);

    public static ExtentTest getCurrentTest() {
        return TEST.get();
    }

    public static String getCurrentTestId() {
        return CURRENT_TEST_ID.get();
    }

    public static String getCurrentScriptName() {
        return CURRENT_SCRIPT_NAME.get();
    }
    
    public static boolean isAutoScreenshotEnabled() {
        return AUTO_SCREENSHOT_ENABLED.get();
    }

    @Override
    public void onStart(ITestContext context) {
        if (EVIDENCE_CLEANED_FOR_RUN.compareAndSet(false, true)) {
            cleanPreviousEvidenceDirectories();
        }
        extent = ExtentManager.getInstance();
        LOGGER.info("Test context started: {}", context.getName());
    }

    @Override
    public void onFinish(ITestContext context) {
        ExtentManager.flushReport();
        CustomDashboardManager.generateDashboard();
        LOGGER.info("Test context finished: {}", context.getName());
    }

    @Override
    public void onTestStart(ITestResult result) {
        String scriptName = result.getTestClass().getRealClass().getSimpleName();
        String uniqueTestName = buildUniqueTestName(result);

        CURRENT_SCRIPT_NAME.set(scriptName);
        CURRENT_TEST_ID.set(uniqueTestName);
        Apiscreenshot.clearTestScreenshots();

        // Check if the @AutoScreenshots annotation is present
        boolean enableAutoShots = hasAnnotation(result, utils.screenshot.AutoScreenshots.class);
        AUTO_SCREENSHOT_ENABLED.set(enableAutoShots);

        LogUtil.startTestLogging(uniqueTestName);

        ExtentTest extentTest = extent.createTest(uniqueTestName);
        TEST.set(extentTest);

        extentTest.log(Status.INFO, "Test Execution Started: " + uniqueTestName);
        LOGGER.info("Test Started: {}", uniqueTestName);

        // Capture initial UI page ONLY if @AutoScreenshots is enabled
        if (enableAutoShots) {
            try {
                WebDriver driver = DriverFactory.getDriver();
                Apiscreenshot.capturePage(driver, uniqueTestName + "_Start");
            } catch (Exception ignored) {
            }
        }

        if (hasAnnotation(result, Video.class)) {
            try {
                WebDriver driver = DriverFactory.getDriver();
                VideoRecorder recorder = new VideoRecorder();
                recorder.startRecording(uniqueTestName, driver);
                RECORDER.set(recorder);
            } catch (Exception e) {
                LOGGER.warn("Could not start video recorder for {}: {}", uniqueTestName, e.getMessage());
            }
        }
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        String uniqueTestName = getActiveTestName(result);
        ExtentTest extentTest = TEST.get();

        String dashboardScreenshotPath = Apiscreenshot.getLastScreenshotPath();
        
        // Capture final UI page state ONLY if @AutoScreenshots is enabled
        if (isAutoScreenshotEnabled()) {
            try {
                WebDriver driver = DriverFactory.getDriver();
                String finalShot = Apiscreenshot.capturePage(driver, uniqueTestName + "_Passed");
                if (finalShot != null) {
                    dashboardScreenshotPath = finalShot;
                }
            } catch (Exception ignored) {
            }
        }

        if (extentTest != null) {
            extentTest.log(Status.PASS, "Test Passed Successfully");
        }

        LOGGER.info("Test Passed: {}", uniqueTestName);

        long durationMs = Math.max(0, result.getEndMillis() - result.getStartMillis());
        String startTime = formatStartTime(result.getStartMillis());
        
        // Use either the single final shot OR the full CSV list of step shots based on your CustomDashboard setup
        String shotsToRecord = isAutoScreenshotEnabled() ? Apiscreenshot.getAllScreenshotPathsAsCsv() : dashboardScreenshotPath;
        
        CustomDashboardManager.recordTest(
                uniqueTestName,
                result.getTestClass().getRealClass().getSimpleName(),
                result.getMethod().getMethodName(),
                "PASS",
                durationMs,
                startTime,
                null,
                null,
                shotsToRecord,
                null,
                null
        );

        stopVideoRecording(false);
        LogUtil.deleteLogFileIfPassed(uniqueTestName);
        cleanupThreadLocals();
        ElementTracker.clear();
    }

    @Override
    public void onTestFailure(ITestResult result) {
        processTestException(result, Status.FAIL, "FAILED");
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        processTestException(result, Status.SKIP, "SKIPPED");
    }

    private void processTestException(ITestResult result, Status status, String eventType) {
        String uniqueTestName = getActiveTestName(result);
        ExtentTest extentTest = TEST.get();

        LOGGER.error("Test {}: {}", eventType, uniqueTestName, result.getThrowable());

        By failedLocator = ElementTracker.getFailedLocator();
        if (failedLocator == null) {
            failedLocator = extractFailedLocator(result.getThrowable());
        }

        String screenshotRelPath = null;
        String apiErrorScreenshotPath = null;
        String videoRelPath = null;
        String logRelPath = "../logs/" + uniqueTestName + ".log";

        if (extentTest != null) {
            if (result.getThrowable() != null) {
                extentTest.log(status, result.getThrowable());
            } else {
                extentTest.log(status, "Test " + eventType);
            }

            screenshotRelPath = captureScreenshotOnEvent(result, extentTest, uniqueTestName, eventType, failedLocator);

            // Capture API failure state ONLY if @AutoScreenshots is enabled
            if (isAutoScreenshotEnabled()) {
                try {
                    WebDriver driver = DriverFactory.getDriver();
                    apiErrorScreenshotPath = Apiscreenshot.capturePage(driver, uniqueTestName + "_ERROR_" + eventType);
                } catch (Exception ignored) {
                }
            }

            stopVideoRecording(true);
            if (hasAnnotation(result, Video.class)) {
                videoRelPath = "../video/" + uniqueTestName + ".mp4";
                String videoHtml =
                        "<video width='680' controls preload='none'>" +
                        "<source src='" + videoRelPath + "' type='video/mp4'></video>";
                extentTest.info("Test Execution Video: <br>" + videoHtml);
            }

            LogUtil.stopTestLogging();
            extentTest.info(
                    "<a href='" + logRelPath +
                    "' target='_blank'><b>Open Detailed Execution Log (" + uniqueTestName + ".log)</b></a>"
            );
        } else {
            stopVideoRecording(true);
            LogUtil.stopTestLogging();
        }

        // Determine what gets sent to the dashboard
        String dashboardScreenshot;
        if (isAutoScreenshotEnabled()) {
            String apiShotsCsv = Apiscreenshot.getAllScreenshotPathsAsCsv();
            if (screenshotRelPath != null && apiShotsCsv != null) {
                dashboardScreenshot = screenshotRelPath + "," + apiShotsCsv;
            } else if (screenshotRelPath != null) {
                dashboardScreenshot = screenshotRelPath;
            } else {
                dashboardScreenshot = apiShotsCsv;
            }
        } else {
            dashboardScreenshot = (screenshotRelPath != null) 
                    ? screenshotRelPath 
                    : (apiErrorScreenshotPath != null ? apiErrorScreenshotPath : Apiscreenshot.getLastScreenshotPath());
        }

        long durationMs = Math.max(0, result.getEndMillis() - result.getStartMillis());
        String startTime = formatStartTime(result.getStartMillis());
        
        CustomDashboardManager.recordTest(
                uniqueTestName,
                result.getTestClass().getRealClass().getSimpleName(),
                result.getMethod().getMethodName(),
                "FAILED".equals(eventType) ? "FAIL" : "SKIP",
                durationMs,
                startTime,
                result.getThrowable(),
                failedLocator != null ? failedLocator.toString() : null,
                dashboardScreenshot,
                videoRelPath,
                logRelPath
        );

        cleanupThreadLocals();
    }

    private String captureScreenshotOnEvent(
            ITestResult result,
            ExtentTest extentTest,
            String uniqueTestName,
            String status,
            By failedLocator) {

        try {
            if (!hasAnnotation(result, Screenshot.class)) {
                return null;
            }

            WebDriver driver;
            try {
                driver = DriverFactory.getDriver();
            } catch (IllegalStateException e) {
                LOGGER.warn("Driver is not available. Cannot capture screenshot.");
                return null;
            }

            String errorMessage = "Test " + status;
            if (result.getThrowable() != null && result.getThrowable().getMessage() != null) {
                errorMessage = result.getThrowable().getMessage();
            }

            if (failedLocator != null) {
                LOGGER.info("Failed locator captured: {}", failedLocator);
            } else {
                LOGGER.warn("No failed locator was captured.");
            }

            WebElement lastElement = ElementTracker.getLastElement();
            if (lastElement != null) {
                LOGGER.info("Last tracked element is available.");
            } else {
                LOGGER.warn("No previously tracked element is available; using locator/DOM fallback.");
            }

            String screenshotFileName = uniqueTestName + "_" + status;
            String screenshotPath = ScreenshotUtil.captureFullPage(
                    driver,
                    screenshotFileName,
                    lastElement,
                    failedLocator,
                    errorMessage
            );

            if (screenshotPath != null) {
                extentTest.info("Test " + status + " Screenshot");
                String relativeScreenshotPath = "../screenshots/" + screenshotFileName + ".png";
                extentTest.addScreenCaptureFromPath(relativeScreenshotPath, status + " Screenshot");
                LOGGER.info("Screenshot captured: {}", screenshotPath);
                return relativeScreenshotPath;
            } else {
                LOGGER.error("Screenshot utility returned a null path.");
                return null;
            }

        } catch (Exception e) {
            LOGGER.error("Unable to capture screenshot", e);
            return null;
        } finally {
            ElementTracker.clear();
        }
    }

    private synchronized void cleanPreviousEvidenceDirectories() {
        String baseOutputDir = System.getProperty("user.dir") + File.separator + "test-output";
        CustomDashboardManager.clearRecords();
        String[] subDirs = {"screenshots", "video", "logs", "extent-report", "dashboard"};

        for (String sub : subDirs) {
            Path dirPath = Path.of(baseOutputDir, sub);
            try {
                if (Files.exists(dirPath)) {
                    try (Stream<Path> walk = Files.walk(dirPath)) {
                        walk.sorted(Comparator.reverseOrder())
                                .filter(p -> !p.equals(dirPath))
                                .forEach(p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                    }
                                });
                    }
                }
                Files.createDirectories(dirPath);
            } catch (IOException e) {
                System.err.println("Could not clean evidence directory " + dirPath + ": " + e.getMessage());
            }
        }

        try {
            Files.createDirectories(Path.of(baseOutputDir, "extent-report", "apiscreenshoot"));
        } catch (IOException e) {
            System.err.println("Could not create apiscreenshoot directory: " + e.getMessage());
        }

        LOGGER.info("Cleaned previous test-output evidence directories (screenshots, video, logs, extent-report, apiscreenshoot, dashboard).");
    }

    private String buildUniqueTestName(ITestResult result) {
        String className = result.getTestClass().getRealClass().getSimpleName();
        String methodName = result.getMethod().getMethodName();
        String baseKey = className + "_" + methodName;

        Object[] params = result.getParameters();
        String paramSuffix = "";

        if (params != null && params.length > 0 && params[0] != null) {
            if (params[0] instanceof Map<?, ?> data && data.containsKey("testCaseId")) {
                paramSuffix = "_" + sanitizeFileName(String.valueOf(data.get("testCaseId")));
            } else {
                int runIndex = TEST_INVOCATION_COUNTER
                        .computeIfAbsent(baseKey, k -> new AtomicInteger(0))
                        .incrementAndGet();
                paramSuffix = "_TC" + runIndex;
            }
        } else {
            int count = TEST_INVOCATION_COUNTER
                    .computeIfAbsent(baseKey, k -> new AtomicInteger(0))
                    .incrementAndGet();
            if (count > 1) {
                paramSuffix = "_Run" + count;
            }
        }

        return sanitizeFileName(baseKey + paramSuffix);
    }

    private String getActiveTestName(ITestResult result) {
        String current = CURRENT_TEST_ID.get();
        return (current != null) ? current : buildUniqueTestName(result);
    }

    private String formatStartTime(long startMillis) {
        if (startMillis <= 0) {
            return LocalTime.now().format(TIME_FORMATTER);
        }
        return Instant.ofEpochMilli(startMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .format(TIME_FORMATTER);
    }

    private String sanitizeFileName(String input) {
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private By extractFailedLocator(Throwable throwable) {
        if (throwable == null) {
            return null;
        }

        Throwable current = throwable;
        Pattern locatorPattern = Pattern.compile(
                "By\\.(xpath|id|cssSelector|name|className|tagName|linkText|partialLinkText):\\s*(.+?)(?=\\s+\\(tried for|\\r?\\n|$)"
        );

        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                Matcher matcher = locatorPattern.matcher(message);
                if (matcher.find()) {
                    String locatorType = matcher.group(1);
                    String locatorValue = matcher.group(2).trim();

                    int newlineIndex = locatorValue.indexOf('\n');
                    if (newlineIndex >= 0) {
                        locatorValue = locatorValue.substring(0, newlineIndex).trim();
                    }

                    try {
                        return switch (locatorType) {
                            case "xpath" -> By.xpath(locatorValue);
                            case "id" -> By.id(locatorValue);
                            case "cssSelector" -> By.cssSelector(locatorValue);
                            case "name" -> By.name(locatorValue);
                            case "className" -> By.className(locatorValue);
                            case "tagName" -> By.tagName(locatorValue);
                            case "linkText" -> By.linkText(locatorValue);
                            case "partialLinkText" -> By.partialLinkText(locatorValue);
                            default -> null;
                        };
                    } catch (IllegalArgumentException e) {
                        LOGGER.warn("Invalid extracted locator: {}", locatorValue);
                        return null;
                    }
                }
            }
            current = current.getCause();
        }
        return null;
    }

    private void stopVideoRecording(boolean keepVideo) {
        VideoRecorder recorder = RECORDER.get();
        if (recorder != null) {
            recorder.stopRecording(keepVideo);
        }
    }

    private boolean hasAnnotation(
            ITestResult result,
            Class<? extends java.lang.annotation.Annotation> annotationClass) {

        Method method = result.getMethod().getConstructorOrMethod().getMethod();
        return method.isAnnotationPresent(annotationClass)
                || result.getTestClass().getRealClass().isAnnotationPresent(annotationClass);
    }

    private void cleanupThreadLocals() {
        TEST.remove();
        RECORDER.remove();
        CURRENT_TEST_ID.remove();
        CURRENT_SCRIPT_NAME.remove();
        AUTO_SCREENSHOT_ENABLED.remove(); // Clear flag when test ends
        Apiscreenshot.clearTestScreenshots();
    }
}