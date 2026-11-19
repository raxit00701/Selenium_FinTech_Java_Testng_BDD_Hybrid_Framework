package API;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Logger;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.support.decorators.Decorated;

import io.github.cdimascio.dotenv.Dotenv;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import utils.log.LogUtil;

public class Login {

    private static final Logger log = LogUtil.getLogger(Login.class);

    // Load environment variables from .env
    private static final Dotenv dotenv = Dotenv.configure()
            .directory("src/main/java/env")
            .filename(".env")
            .ignoreIfMissing()
            .load();

    // =========================================================
    // CONSTANTS
    // =========================================================

    private static final String SIGN_IN_PATH = "/users/sign_in";
    private static final String DEFAULT_LANDING_PATH = "/tenants";
    private static final String SESSION_COOKIE_NAME = "_kaui-standalone_session";

    // Tiny same-origin URL, loaded ONLY when CDP is unavailable (e.g. Firefox) just so
    // Selenium is "on the domain" when adding the cookie. Far cheaper than the full sign-in page.
    private static final String DOMAIN_WARMUP_PATH = "/robots.txt";

    // Pre-compiled once instead of on every login
    private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");
    private static final Pattern META_TOKEN_PATTERN =
            Pattern.compile("<meta name=\"csrf-token\" content=\"([^\"]+)\"");
    private static final Pattern INPUT_TOKEN_PATTERN =
            Pattern.compile("name=\"authenticity_token\" value=\"([^\"]+)\"");

    // =========================================================
    // SESSION CACHE (API login happens once per run, not once per test)
    // =========================================================

    private static final Map<String, Cookie> SESSION_CACHE = new ConcurrentHashMap<>();

    /**
     * Forget the cached session so the next bypassLogin() performs a fresh API login.
     * Call this after a test that logs out, changes the password, or otherwise kills the session.
     */
    public static void clearSessionCache() {
        SESSION_CACHE.clear();
    }

    // =========================================================
    // PUBLIC API
    // =========================================================

    /**
     * Authenticates via API (once per run) and injects the session into the browser,
     * landing on the default secured dashboard (/tenants).
     */
    public static void bypassLogin(WebDriver driver) {
        bypassLogin(driver, DEFAULT_LANDING_PATH);
    }

    /**
     * Same as {@link #bypassLogin(WebDriver)} but lands directly on the page the test needs
     * (e.g. "/accounts"), which saves loading the dashboard first.
     */
    public static void bypassLogin(WebDriver driver, String landingPath) {

        if (driver == null) {
            throw new IllegalArgumentException("WebDriver must not be null.");
        }

        String rawBaseUrl = env("BASE_URL");
        final String username = env("LOGIN_USERNAME");
        final String password = env("LOGIN_PASSWORD");

        if (rawBaseUrl == null || rawBaseUrl.trim().isEmpty()) {
            throw new IllegalStateException("BASE_URL is missing from system properties / .env");
        }

        if (username == null || username.trim().isEmpty()
                || password == null || password.trim().isEmpty()) {
            throw new IllegalStateException("LOGIN_USERNAME or LOGIN_PASSWORD is missing from system properties / .env");
        }

        final String baseUrl = TRAILING_SLASHES.matcher(rawBaseUrl.trim()).replaceAll("");
        final String path = normalizePath(landingPath);
        final String cacheKey = baseUrl + "|" + username;

        // 1. Session cookie: from cache, or one API login (concurrent threads share that single login)
        boolean hadCachedSession = SESSION_CACHE.containsKey(cacheKey);
        Cookie sessionCookie = getOrCreateSession(cacheKey, baseUrl, username, password);

        // 2. Inject cookie and open the target page
        boolean authenticated = openWithSession(driver, baseUrl, path, sessionCookie);

        // 3. Self-heal: a cached session that expired / was invalidated gets replaced once
        if (!authenticated && hadCachedSession) {
            log.warn("Cached session was rejected - re-authenticating via API.");
            SESSION_CACHE.remove(cacheKey, sessionCookie); // no-op if another thread already refreshed it
            sessionCookie = getOrCreateSession(cacheKey, baseUrl, username, password);
            authenticated = openWithSession(driver, baseUrl, path, sessionCookie);
        }

        if (!authenticated) {
            throw new IllegalStateException(
                    "Login bypass failed: still redirected to " + SIGN_IN_PATH + " after a fresh API login."
            );
        }

        log.info("Login bypass OK -> {}{}", baseUrl, path);
    }

    // =========================================================
    // BROWSER SESSION INJECTION
    // =========================================================

    /**
     * Injects the cookie, opens the page, and reports whether we landed on a secured page
     * (i.e. were NOT bounced back to the sign-in page).
     */
    private static boolean openWithSession(WebDriver driver, String baseUrl, String path, Cookie cookie) {
        injectCookie(driver, baseUrl, cookie);
        driver.get(baseUrl + path);
        return !driver.getCurrentUrl().contains(SIGN_IN_PATH);
    }

    private static void injectCookie(WebDriver driver, String baseUrl, Cookie cookie) {

        // Fast path (Chrome/Edge): set the cookie through CDP. No page load needed at all.
        HasCdp cdp = asCdp(driver);
        if (cdp != null) {
            try {
                Map<String, Object> params = new HashMap<>();
                params.put("name", cookie.getName());
                params.put("value", cookie.getValue());
                params.put("url", baseUrl + "/");
                params.put("path", "/");

                Map<String, Object> result = cdp.executeCdpCommand("Network.setCookie", params);
                if (result == null || !Boolean.FALSE.equals(result.get("success"))) {
                    return;
                }
            } catch (RuntimeException e) {
                log.debug("CDP cookie injection failed, using portable path: {}", e.getMessage());
            }
        }

        // Portable path: must be on the domain first, so load the lightest page available
        driver.get(baseUrl + DOMAIN_WARMUP_PATH);
        driver.manage().addCookie(cookie);
    }

    /**
     * Unwraps the EventFiringDecorator proxy (also skips its per-call listener overhead)
     * and returns the CDP-capable driver, or null for non-Chromium browsers.
     */
    private static HasCdp asCdp(WebDriver driver) {
        WebDriver target = driver;

        if (driver instanceof Decorated) {
            Object original = ((Decorated<?>) driver).getOriginal();
            if (original instanceof WebDriver) {
                target = (WebDriver) original;
            }
        }

        return (target instanceof HasCdp) ? (HasCdp) target : null;
    }

    // =========================================================
    // API LOGIN
    // =========================================================

    private static Cookie getOrCreateSession(String cacheKey, String baseUrl, String username, String password) {
        // computeIfAbsent blocks other threads asking for the same key, so only ONE login runs
        return SESSION_CACHE.computeIfAbsent(cacheKey, key -> getSessionCookie(baseUrl, username, password));
    }

    /**
     * Performs API login and extracts the authenticated session cookie.
     */
    private static Cookie getSessionCookie(String baseUrl, String username, String password) {

        // GET login page.
        Response getResponse = RestAssured.given()
                .baseUri(baseUrl)
                .get(SIGN_IN_PATH);

        if (getResponse.getStatusCode() != 200) {
            throw new RuntimeException(
                    "Unable to load login page. HTTP Status: " + getResponse.getStatusCode()
            );
        }

        Map<String, String> cookies = getResponse.getCookies();
        String authToken = extractAuthenticityToken(getResponse.getBody().asString());

        // POST login credentials.
        Response postResponse = RestAssured.given()
                .baseUri(baseUrl)
                .cookies(cookies)
                .contentType(ContentType.URLENC)
                .formParam("authenticity_token", authToken)
                .formParam("user[kb_username]", username)
                .formParam("user[password]", password)
                .redirects().follow(false)
                .post(SIGN_IN_PATH);

        if (postResponse.getStatusCode() != 302) {
            throw new RuntimeException(
                    "API Login failed. Expected HTTP 302, received: "
                            + postResponse.getStatusCode()
                            + "\nResponse: "
                            + postResponse.getBody().asString()
            );
        }

        String sessionCookieValue = postResponse.getCookie(SESSION_COOKIE_NAME);

        if (sessionCookieValue == null || sessionCookieValue.trim().isEmpty()) {
            throw new RuntimeException(
                    "Authenticated session cookie was not returned: " + SESSION_COOKIE_NAME
            );
        }

        return new Cookie(SESSION_COOKIE_NAME, sessionCookieValue);
    }

    /**
     * Extracts authenticity token from login page HTML.
     */
    private static String extractAuthenticityToken(String html) {

        Matcher metaMatcher = META_TOKEN_PATTERN.matcher(html);
        if (metaMatcher.find()) {
            return metaMatcher.group(1);
        }

        Matcher inputMatcher = INPUT_TOKEN_PATTERN.matcher(html);
        if (inputMatcher.find()) {
            return inputMatcher.group(1);
        }

        throw new RuntimeException("Could not find authenticity_token in login page HTML.");
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private static String normalizePath(String landingPath) {
        String path = (landingPath == null || landingPath.isBlank()) ? DEFAULT_LANDING_PATH : landingPath.trim();
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * Same lookup order as Base.getEnv(): system properties (-DBASE_URL=...) first, then .env.
     */
    private static String env(String key) {
        String systemValue = System.getProperty(key);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }
        return dotenv.get(key);
    }
}