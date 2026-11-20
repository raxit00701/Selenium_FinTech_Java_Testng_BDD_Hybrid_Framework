package utils.screenshot;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.Logger;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.events.WebDriverListener;

import base.DriverFactory;
import utils.log.LogUtil;

public class ElementTracker implements WebDriverListener {

    private static final Logger LOGGER = LogUtil.getLogger(ElementTracker.class);

    private static final ThreadLocal<WebElement> LAST_FOUND_ELEMENT = new ThreadLocal<>();
    private static final ThreadLocal<WebElement> LAST_INTERACTED_ELEMENT = new ThreadLocal<>();
    // Whichever element was found OR interacted with most recently (what getLastElement() returns)
    private static final ThreadLocal<WebElement> LAST_ELEMENT = new ThreadLocal<>();
    private static final ThreadLocal<By> LAST_LOCATOR = new ThreadLocal<>();
    private static final ThreadLocal<By> FAILED_LOCATOR = new ThreadLocal<>();
    private static final ThreadLocal<String> LAST_URL = new ThreadLocal<>();

    @Override
    public void beforeGet(WebDriver driver, String url) {
        LOGGER.info("Navigating to URL: {}", url);
    }

    @Override
    public void afterGet(WebDriver driver, String url) {
        captureIfPageChanged(driver, "Initial_Page");
    }

    @Override
    public void afterTo(WebDriver.Navigation navigation, String url) {
        try {
            WebDriver driver = DriverFactory.getDriver();
            captureIfPageChanged(driver, "Navigated_Page");
        } catch (Exception ignored) {
        }
    }

    @Override
    public void beforeFindElement(WebDriver driver, By locator) {
        if (locator != null) {
            LAST_LOCATOR.set(locator);
            FAILED_LOCATOR.set(locator);
            LOGGER.info("Locating element: {}", locator);
        }
    }

    @Override
    public void afterFindElement(WebDriver driver, By locator, WebElement result) {
        if (result != null) {
            LAST_FOUND_ELEMENT.set(result);
            LAST_ELEMENT.set(result);
            LAST_LOCATOR.set(locator);
            FAILED_LOCATOR.remove();
            LOGGER.info("Found element successfully: {}", locator);
        }
    }

    @Override
    public void beforeFindElements(WebDriver driver, By locator) {
        if (locator != null) {
            LAST_LOCATOR.set(locator);
            FAILED_LOCATOR.set(locator);
        }
    }

    @Override
    public void afterFindElements(WebDriver driver, By locator, List<WebElement> result) {
        if (result != null && !result.isEmpty()) {
            LAST_FOUND_ELEMENT.set(result.get(0));
            LAST_ELEMENT.set(result.get(0));
            LAST_LOCATOR.set(locator);
            FAILED_LOCATOR.remove();
            LOGGER.info("Found {} element(s) for locator: {}", result.size(), locator);
        } else if (locator != null) {
            FAILED_LOCATOR.set(locator);
        }
    }

    @Override
    public void beforeClick(WebElement element) {
        track(element);
        LOGGER.info("Clicking element: {}", LAST_LOCATOR.get() != null ? LAST_LOCATOR.get() : describeElement(element));
    }

    @Override
    public void afterClick(WebElement element) {
        track(element);

        // If clicking the element navigated to a new page/URL, capture the new page
        try {
            WebDriver driver = DriverFactory.getDriver();
            captureIfPageChanged(driver, null);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void beforeSendKeys(WebElement element, CharSequence... keysToSend) {
        track(element);
        LOGGER.info("Typing {} into element: {}",
                Arrays.toString(keysToSend),
                LAST_LOCATOR.get() != null ? LAST_LOCATOR.get() : describeElement(element));
    }

    @Override
    public void afterSendKeys(WebElement element, CharSequence... keysToSend) {
        track(element);
    }

    @Override
    public void onError(Object target, Method method, Object[] args, InvocationTargetException exception) {
        if (method == null) {
            return;
        }

        String methodName = method.getName();
        Throwable cause = exception.getTargetException();

        if (("findElement".equals(methodName) || "findElements".equals(methodName))
                && args != null && args.length > 0 && args[0] instanceof By failedBy) {
            FAILED_LOCATOR.set(failedBy);
            LOGGER.error("Failed to locate element: {} | Reason: {}",
                    failedBy, cause != null ? cause.getMessage().split("\n")[0] : "Unknown");
        } else if (target instanceof WebElement failedElement) {
            LAST_INTERACTED_ELEMENT.set(failedElement);
            LAST_ELEMENT.set(failedElement);
            LOGGER.error("Element action '{}' failed | Reason: {}",
                    methodName, cause != null ? cause.getMessage().split("\n")[0] : "Unknown");
        }
    }

    // =========================================================
    // AUTOMATIC PAGE SCREENSHOT HELPER
    // =========================================================
    private static void captureIfPageChanged(WebDriver driver, String fallbackLabel) {
        if (!utils.artifact.ReportingListener.isAutoScreenshotEnabled()) return;
        if (driver == null) {
            return;
        }
        try {
            String currentUrl = driver.getCurrentUrl();
            String previousUrl = LAST_URL.get();

            if (currentUrl != null && !currentUrl.equals(previousUrl)) {
                LAST_URL.set(currentUrl);
                String pageLabel = (fallbackLabel != null)
                        ? fallbackLabel
                        : extractPageNameFromUrl(currentUrl);

                Apiscreenshot.capturePage(driver, pageLabel);
            }
        } catch (Exception ignored) {
        }
    }

    private static String extractPageNameFromUrl(String url) {
        try {
            String clean = url.replaceAll("https?://[^/]+/?", "");
            return clean.isBlank() ? "Home_Page" : clean;
        } catch (Exception e) {
            return "Page";
        }
    }

    private static void track(WebElement element) {
        if (element != null) {
            LAST_INTERACTED_ELEMENT.set(element);
            LAST_FOUND_ELEMENT.set(element);
            LAST_ELEMENT.set(element);
        }
    }

    private static String describeElement(WebElement element) {
        try {
            return element.getTagName();
        } catch (Exception e) {
            return "WebElement";
        }
    }

    /**
     * The element touched most recently, whether it was found or interacted with.
     * (Previously a stale "interacted" element always beat a newer "found" one, so a failed
     * assertion on e.g. a welcome message highlighted the earlier Sign In button instead.)
     */
    public static WebElement getLastElement() {
        WebElement latest = LAST_ELEMENT.get();
        if (latest != null) {
            return latest;
        }
        WebElement interacted = LAST_INTERACTED_ELEMENT.get();
        return interacted != null ? interacted : LAST_FOUND_ELEMENT.get();
    }

    public static WebElement getLastFoundElement() {
        return LAST_FOUND_ELEMENT.get();
    }

    public static WebElement getLastInteractedElement() {
        return LAST_INTERACTED_ELEMENT.get();
    }

    public static By getLastLocator() {
        return LAST_LOCATOR.get();
    }

    public static By getFailedLocator() {
        return FAILED_LOCATOR.get();
    }

    public static void clear() {
        LAST_FOUND_ELEMENT.remove();
        LAST_INTERACTED_ELEMENT.remove();
        LAST_ELEMENT.remove();
        LAST_LOCATOR.remove();
        FAILED_LOCATOR.remove();
        LAST_URL.remove();
    }
}