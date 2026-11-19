package base;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.openqa.selenium.chromium.ChromiumOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.support.events.EventFiringDecorator;

import utils.screenshot.ElementTracker;

public final class DriverFactory {

    // =========================================================
    // BROWSER, HEADLESS & INCOGNITO CONFIGURATION (CENTRALIZED)
    // =========================================================

    private static final String DEFAULT_BROWSER = "chrome";
    private static final boolean DEFAULT_HEADLESS = false;
    private static final boolean DEFAULT_INCOGNITO = false;

    // =========================================================
    // CONSTANTS
    // =========================================================

    // Timeouts are sent as W3C capabilities at session creation, so the driver
    // starts with them already applied (saves 3+ extra HTTP round-trips per launch).
    // Implicit wait stays ZERO: always rely on WebDriverWait in tests.
    private static final Map<String, Object> TIMEOUTS = Map.of(
            "implicit", 0L,
            "pageLoad", Duration.ofSeconds(30).toMillis(),
            "script", Duration.ofSeconds(15).toMillis()
    );

    private static final int WINDOW_WIDTH = 1920;
    private static final int WINDOW_HEIGHT = 1080;

    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    // Opt-in: -DBLOCK_IMAGES=true skips image downloads (faster pages, but breaks visual checks/screenshots)
    private static final boolean BLOCK_IMAGES = Boolean.getBoolean("BLOCK_IMAGES");

    // =========================================================
    // THREAD-SAFE STORAGE (SUPPORTS BOTH SERIAL & PARALLEL)
    // =========================================================

    private static final ThreadLocal<WebDriver> DRIVER = new ThreadLocal<>();
    private static final ThreadLocal<WebDriver> RAW_DRIVER = new ThreadLocal<>();
    private static final ThreadLocal<String> CONFIG_KEY = new ThreadLocal<>();

    // Every live browser, across all threads, so they can be closed at suite end
    // (a ThreadLocal can only be cleaned up by the thread that owns it).
    private static final Set<WebDriver> ALL_DRIVERS = ConcurrentHashMap.newKeySet();

    static {
        // Safety net: no orphaned browser processes if the run is aborted.
        Runtime.getRuntime().addShutdownHook(new Thread(DriverFactory::quitAllDrivers, "driver-shutdown-hook"));
    }

    private DriverFactory() {
        // Prevent instantiation
    }

    // =========================================================
    // CONFIGURATION RESOLVERS (-D CLI OVERRIDE -> FACTORY DEFAULT)
    // =========================================================

    public static String resolveBrowser() {
        String browser = getOverrideProperty("BROWSER", "browser");
        return (browser != null) ? browser.toLowerCase(Locale.ROOT) : DEFAULT_BROWSER;
    }

    public static boolean resolveHeadless() {
        String headless = getOverrideProperty("HEADLESS", "headless");
        return (headless != null) ? Boolean.parseBoolean(headless) : DEFAULT_HEADLESS;
    }

    public static boolean resolveIncognito() {
        String incognito = getOverrideProperty("INCOGNITO", "incognito");
        return (incognito != null) ? Boolean.parseBoolean(incognito) : DEFAULT_INCOGNITO;
    }

    private static String getOverrideProperty(String upperKey, String lowerKey) {
        String value = System.getProperty(upperKey);
        if (value == null || value.isBlank()) {
            value = System.getProperty(lowerKey);
        }
        if (value == null || value.isBlank()) {
            value = System.getenv(upperKey);
        }
        if (value == null || value.isBlank()) {
            value = System.getenv(lowerKey);
        }
        return (value != null && !value.isBlank()) ? value.trim() : null;
    }

    // =========================================================
    // INITIALIZE DRIVER (LAUNCH ONCE PER THREAD, THEN REUSE)
    // =========================================================

    /**
     * Initializes or reuses a browser using {@link #DEFAULT_BROWSER}, {@link #DEFAULT_HEADLESS},
     * and {@link #DEFAULT_INCOGNITO} (overridable via {@code -DBROWSER}, {@code -DHEADLESS}, {@code -DINCOGNITO}).
     *
     * @return true if an existing browser was reused, false if a brand-new browser was launched.
     */
    public static boolean initDriver() {
        return initDriverInternal(resolveBrowser(), resolveHeadless(), resolveIncognito());
    }

    /**
     * Backward-compatible overload: delegates to {@code DriverFactory}'s centralized configuration
     * so {@code INCOGNITO} does not need to be read from {@code .env}.
     */
    public static boolean initDriver(boolean incognito) {
        return initDriver();
    }

    /**
     * Backward-compatible overload: delegates to {@code DriverFactory}'s centralized configuration
     * so {@code BROWSER}, {@code HEADLESS}, and {@code INCOGNITO} do not need to be read from {@code .env}.
     */
    public static boolean initDriver(String browser, boolean headless, boolean incognito) {
        return initDriver();
    }

    /**
     * Explicit programmatic override when a specific test requires custom browser flags.
     */
    public static boolean initCustomDriver(String browser, boolean headless, boolean incognito) {
        return initDriverInternal(browser, headless, incognito);
    }

    private static boolean initDriverInternal(String browser, boolean headless, boolean incognito) {
        validateBrowser(browser);

        String browserName = browser.trim().toLowerCase(Locale.ROOT);
        String configKey = browserName + "|" + headless + "|" + incognito;

        if (DRIVER.get() != null) {
            if (configKey.equals(CONFIG_KEY.get()) && recycleBrowser(RAW_DRIVER.get())) {
                return true;
            }
            quitDriver(); // different config requested, or the old session is dead
        }

        // 1. Create the raw browser (all tuning is passed as options: zero extra commands)
        WebDriver rawDriver = createDriver(browserName, headless, incognito);

        // Only the cases that --start-maximized can't cover need a WebDriver command
        if (!headless && (IS_MAC || "firefox".equals(browserName))) {
            rawDriver.manage().window().maximize();
        }

        RAW_DRIVER.set(rawDriver);
        CONFIG_KEY.set(configKey);
        ALL_DRIVERS.add(rawDriver);

        // 2. Wrap with ElementTracker for automatic failure locator/element tracking
        DRIVER.set(new EventFiringDecorator<>(new ElementTracker()).decorate(rawDriver));
        return false;
    }

    // =========================================================
    // REUSE HELPERS
    // =========================================================

    /**
     * Cheap health check + cleanup of a warm browser: dismiss a stray alert, close extra
     * tabs, and switch back to the first tab (which also exits any iframe).
     * Returns false if the session is dead so the caller can rebuild it.
     */
    private static boolean recycleBrowser(WebDriver raw) {
        try {
            try {
                raw.switchTo().alert().dismiss();
            } catch (NoAlertPresentException ignored) {
                // normal case
            }

            List<String> handles = new ArrayList<>(raw.getWindowHandles());
            for (int i = handles.size() - 1; i > 0; i--) {
                raw.switchTo().window(handles.get(i)).close();
            }
            raw.switchTo().window(handles.get(0));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Wipes cookies and site storage so a reused browser behaves like a fresh one.
     * Chrome/Edge: two CDP commands, no page load needed, HTTP cache is kept (faster loads).
     * Firefox/other: portable fallback (loads the base URL, then clears).
     * Call this only when {@link #initDriver} returned true.
     */
    public static void resetSession(String baseUrl) {
        WebDriver raw = getRawDriver();

        if (raw instanceof ChromiumDriver) {
            try {
                ChromiumDriver cdp = (ChromiumDriver) raw;
                cdp.executeCdpCommand("Network.clearBrowserCookies", Map.of());

                String origin = originOf(baseUrl);
                if (origin != null) {
                    cdp.executeCdpCommand("Storage.clearDataForOrigin",
                            Map.of("origin", origin, "storageTypes", "all"));
                }
                return;
            } catch (RuntimeException ignored) {
                // fall through to the portable path
            }
        }

        raw.get(baseUrl);
        raw.manage().deleteAllCookies();
        try {
            ((JavascriptExecutor) raw).executeScript("localStorage.clear(); sessionStorage.clear();");
        } catch (RuntimeException ignored) {
            // storage not accessible on this page; cookies are already cleared
        }
    }

    /**
     * End-of-test hook when the browser is being kept alive.
     */
    public static void releaseDriver() {
        ElementTracker.clear();
    }

    private static String originOf(String url) {
        try {
            URI uri = URI.create(url.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // =========================================================
    // CREATE DRIVER
    // =========================================================

    private static WebDriver createDriver(String browser, boolean headless, boolean incognito) {
        String browserName = browser.trim().toLowerCase(Locale.ROOT);

        return switch (browserName) {
            case "chrome" -> createChromeDriver(headless, incognito);
            case "edge" -> createEdgeDriver(headless, incognito);
            case "firefox" -> createFirefoxDriver(headless, incognito);
            default -> throw new IllegalArgumentException(
                    "Unsupported browser: " + browser + ". Supported browsers: chrome, edge, firefox."
            );
        };
    }

    // =========================================================
    // CHROME & EDGE (SHARED CHROMIUM TUNING)
    // =========================================================

    private static WebDriver createChromeDriver(boolean headless, boolean incognito) {
        ChromeOptions options = new ChromeOptions();

        if (headless) {
            options.addArguments("--headless=new");
        }
        if (incognito) {
            options.addArguments("--incognito");
        }

        applyChromiumTuning(options, headless);
        return new ChromeDriver(options);
    }

    private static WebDriver createEdgeDriver(boolean headless, boolean incognito) {
        EdgeOptions options = new EdgeOptions();

        if (headless) {
            options.addArguments("--headless=new");
        }
        if (incognito) {
            options.addArguments("--inprivate");
        }

        applyChromiumTuning(options, headless);
        return new EdgeDriver(options);
    }

    /**
     * Speed tuning shared by Chrome and Edge. Flags that ChromeDriver already passes by default
     * (hang-monitor, prompt-on-repost, sync, default-apps, first-run, ...) and no-ops
     * (--disable-translate, imagesEnabled=true) were removed.
     */
    private static void applyChromiumTuning(ChromiumOptions<?> options, boolean headless) {
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        options.setCapability("timeouts", TIMEOUTS);

        // Window size via launch args instead of an extra WebDriver command
        if (headless) {
            options.addArguments("--window-size=" + WINDOW_WIDTH + "," + WINDOW_HEIGHT, "--disable-gpu");
        } else if (!IS_MAC) {
            options.addArguments("--start-maximized"); // ignored on macOS; handled in initDriver
        }

        options.addArguments(
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-extensions",
                "--disable-notifications",
                "--disable-infobars",
                // Keep background/occluded tabs at full speed (matters for parallel headed runs)
                "--disable-background-timer-throttling",
                "--disable-backgrounding-occluded-windows",
                "--disable-renderer-backgrounding",
                "--disable-ipc-flooding-protection",
                // Cut browser-side background work and network chatter
                "--disable-component-update",
                "--disable-domain-reliability",
                "--disable-breakpad",
                "--disable-search-engine-choice-screen",
                "--no-default-browser-check",
                "--mute-audio",
                "--log-level=3",
                // NOTE: Chrome honours only ONE --disable-features switch, so keep them all here
                "--disable-features=Translate,OptimizationHints,MediaRouter,AutofillServerCommunication,CalculateNativeWinOcclusion",
                "--remote-allow-origins=*"
        );

        // Disable password manager, credential prompts, notifications, safe-browsing lookups
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("credentials_enable_service", false);
        prefs.put("profile.password_manager_enabled", false);
        prefs.put("profile.default_content_setting_values.notifications", 2);
        prefs.put("safebrowsing.enabled", false);
        if (BLOCK_IMAGES) {
            prefs.put("profile.managed_default_content_settings.images", 2);
        }
        options.setExperimentalOption("prefs", prefs);
        options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation", "enable-logging"});
    }

    // =========================================================
    // FIREFOX
    // =========================================================

    private static WebDriver createFirefoxDriver(boolean headless, boolean incognito) {
        FirefoxOptions options = new FirefoxOptions();
        options.setPageLoadStrategy(PageLoadStrategy.EAGER);
        options.setCapability("timeouts", TIMEOUTS);

        if (headless) {
            options.addArguments("-headless", "--width=" + WINDOW_WIDTH, "--height=" + WINDOW_HEIGHT);
        }
        if (incognito) {
            options.addArguments("-private");
        }

        // No extensions, updates, telemetry, prefetching or first-run pages
        options.addPreference("extensions.enabledScopes", 0);
        options.addPreference("extensions.update.enabled", false);
        options.addPreference("app.update.enabled", false);
        options.addPreference("app.update.auto", false);
        options.addPreference("network.dns.disablePrefetch", true);
        options.addPreference("network.prefetch-next", false);
        options.addPreference("network.http.speculative-parallel-limit", 0);
        options.addPreference("dom.webnotifications.enabled", false);
        options.addPreference("dom.push.enabled", false);
        options.addPreference("toolkit.cosmeticAnimations.enabled", false);
        options.addPreference("toolkit.telemetry.enabled", false);
        options.addPreference("datareporting.healthreport.uploadEnabled", false);
        options.addPreference("datareporting.policy.dataSubmissionEnabled", false);
        options.addPreference("browser.shell.checkDefaultBrowser", false);
        options.addPreference("browser.startup.homepage_override.mstone", "ignore");
        options.addPreference("browser.aboutwelcome.enabled", false);
        options.addPreference("browser.safebrowsing.malware.enabled", false);
        options.addPreference("browser.safebrowsing.phishing.enabled", false);
        options.addPreference("browser.tabs.warnOnClose", false);
        if (BLOCK_IMAGES) {
            options.addPreference("permissions.default.image", 2);
        }

        return new FirefoxDriver(options);
    }

    // =========================================================
    // GETTERS (DECORATED & RAW)
    // =========================================================

    public static WebDriver getDriver() {
        WebDriver webDriver = DRIVER.get();
        if (webDriver == null) {
            throw new IllegalStateException(
                    "WebDriver is not initialized for thread [" + Thread.currentThread().getId() + "]. Call DriverFactory.initDriver() first."
            );
        }
        return webDriver;
    }

    /**
     * Returns the unwrapped WebDriver instance (useful for low-level CDP/Screenshot tools).
     */
    public static WebDriver getRawDriver() {
        WebDriver raw = RAW_DRIVER.get();
        return raw != null ? raw : getDriver();
    }

    public static boolean hasDriver() {
        return DRIVER.get() != null;
    }

    // =========================================================
    // QUIT DRIVER (THIS THREAD) / QUIT ALL (SUITE END)
    // =========================================================

    public static void quitDriver() {
        WebDriver rawDriver = RAW_DRIVER.get();
        WebDriver decoratedDriver = DRIVER.get();

        try {
            if (rawDriver != null) {
                rawDriver.quit();
            } else if (decoratedDriver != null) {
                decoratedDriver.quit();
            }
        } catch (Exception ignored) {
            // Ignore quit errors if browser process already terminated
        } finally {
            if (rawDriver != null) {
                ALL_DRIVERS.remove(rawDriver);
            }
            DRIVER.remove();
            RAW_DRIVER.remove();
            CONFIG_KEY.remove();
            ElementTracker.clear();
        }
    }

    /**
     * Quits every browser launched by any thread, in parallel. Safe to call more than once.
     */
    public static void quitAllDrivers() {
        List<WebDriver> snapshot = new ArrayList<>(ALL_DRIVERS);
        ALL_DRIVERS.clear();

        snapshot.parallelStream().forEach(driver -> {
            try {
                driver.quit();
            } catch (Exception ignored) {
                // browser already gone
            }
        });
    }

    // =========================================================
    // VALIDATE BROWSER
    // =========================================================

    private static void validateBrowser(String browser) {
        if (browser == null || browser.isBlank()) {
            throw new IllegalArgumentException("Browser cannot be null or empty.");
        }
    }
}