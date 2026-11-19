package API;

import DB.DatabaseConnection;
import io.github.cdimascio.dotenv.Dotenv;
import io.restassured.RestAssured;
import io.restassured.response.Response;

import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;
import org.testng.Assert;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class deletesubs {

    // =========================================================
    // ENVIRONMENT CONFIGURATION (ONLY BASE_URL FROM .env)
    // =========================================================
    private static final Dotenv dotenv = Dotenv.configure()
            .directory("src/main/java/env")
            .filename(".env")
            .ignoreIfMissing()
            .load();

    private static final String BASE_URL = getBaseUrl();

    // =========================================================
    // ENDPOINT & REGEX CONSTANTS
    // =========================================================
    private static final String SUBSCRIPTIONS_BASE_ENDPOINT = "/subscriptions/";

    // Extracts dynamic subscription UUID from KAUI's delete form: action="/subscriptions/<UUID>"
    private static final Pattern SUBSCRIPTION_UUID_HTML_PATTERN = Pattern.compile(
            "/subscriptions/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
    );

    // Matches the global Rails CSRF token in <head>
    private static final Pattern CSRF_META_PATTERN = Pattern.compile(
            "name=[\"']csrf-token[\"'][^>]*content=[\"']([^\"']+)[\"']|" +
            "content=[\"']([^\"']+)[\"'][^>]*name=[\"']csrf-token[\"']"
    );

    private static final Pattern CSRF_INPUT_PATTERN = Pattern.compile(
            "name=[\"']authenticity_token[\"'][^>]*value=[\"']([^\"']+)[\"']|" +
            "value=[\"']([^\"']+)[\"'][^>]*name=[\"']authenticity_token[\"']"
    );

    private deletesubs() {
        // Utility class
    }

    // =========================================================
    // GET ACCOUNT BUNDLES PAGE (FETCHES CSRF TOKEN & SUB UUID)
    // =========================================================
    public static Response getAccountBundlesPage(WebDriver driver, String accountId) {
        Map<String, String> cookies = extractSeleniumCookies(driver);
        String bundlesEndpoint = "/accounts/" + accountId + "/bundles";

        Response response = RestAssured.given()
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .cookies(cookies)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Upgrade-Insecure-Requests", "1")
                .when()
                .get(bundlesEndpoint)
                .then()
                .extract()
                .response();

        syncCookiesToDriver(driver, response);
        return response;
    }

    // =========================================================
    // DYNAMICALLY RESOLVE SUBSCRIPTION UUID
    // =========================================================
    public static String resolveDynamicSubscriptionId(
            WebDriver driver,
            String accountId,
            String externalKey,
            Response bundlesPageResponse) {

        // 1. Primary: Query MariaDB for the latest subscription ID matching external_key
        if (externalKey != null && !externalKey.isBlank()) {
            String dbSubId = fetchSubscriptionIdFromDbByExternalKey(externalKey);
            if (dbSubId != null && !dbSubId.isBlank()) {
                System.out.println("Resolved dynamic Subscription ID from DB (by external_key '"
                        + externalKey + "'): " + dbSubId);
                return dbSubId;
            }
        }

        // 2. Fallback A: Query MariaDB for the latest subscription ID on the account_id
        if (accountId != null && !accountId.isBlank()) {
            String dbAccountSubId = fetchSubscriptionIdFromDbByAccountId(accountId);
            if (dbAccountSubId != null && !dbAccountSubId.isBlank()) {
                System.out.println("Resolved dynamic Subscription ID from DB (by account_id '"
                        + accountId + "'): " + dbAccountSubId);
                return dbAccountSubId;
            }
        }

        // 3. Fallback B: Extract UUID from /accounts/<account_id>/bundles HTML response
        if (bundlesPageResponse != null && bundlesPageResponse.getBody() != null) {
            String html = bundlesPageResponse.getBody().asString();
            Matcher htmlMatcher = SUBSCRIPTION_UUID_HTML_PATTERN.matcher(html);
            if (htmlMatcher.find()) {
                String extractedId = htmlMatcher.group(1);
                System.out.println("Resolved dynamic Subscription ID from Bundles HTML: " + extractedId);
                return extractedId;
            }
        }

        // 4. Fallback C: Extract UUID from active Selenium DOM if currently on the bundles page
        if (driver != null) {
            try {
                Matcher domMatcher = SUBSCRIPTION_UUID_HTML_PATTERN.matcher(driver.getPageSource());
                if (domMatcher.find()) {
                    String domId = domMatcher.group(1);
                    System.out.println("Resolved dynamic Subscription ID from Selenium DOM: " + domId);
                    return domId;
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    // =========================================================
    // POST DELETE SUBSCRIPTION (BY ITERATION INDEX)
    // =========================================================
    public static Response deleteSubscription(WebDriver driver) {
        return deleteSubscription(driver, 0);
    }

    public static Response deleteSubscription(WebDriver driver, int iteration) {
        Map<String, Object> testData = subscription.getTestData(iteration);
        String accountId = String.valueOf(testData.getOrDefault("account_id", "")).trim();
        String externalKey = String.valueOf(testData.getOrDefault("external_key", "")).trim();

        return deleteSubscription(driver, accountId, externalKey);
    }

    // =========================================================
    // POST DELETE SUBSCRIPTION (BY ACCOUNT ID & EXTERNAL KEY)
    // =========================================================
    public static Response deleteSubscription(WebDriver driver, String accountId, String externalKey) {
        Assert.assertNotNull(driver, "WebDriver instance cannot be null.");
        Assert.assertFalse(accountId == null || accountId.isBlank(), "accountId cannot be null or blank.");

        // 1. Load the Account Bundles page to get fresh CSRF token (and fallback HTML)
        Response bundlesPageResponse = getAccountBundlesPage(driver, accountId);

        // 2. Dynamically resolve the Subscription UUID
        String subscriptionId = resolveDynamicSubscriptionId(driver, accountId, externalKey, bundlesPageResponse);
        Assert.assertNotNull(
                subscriptionId,
                "Could not dynamically resolve Subscription ID for account_id: "
                        + accountId + " | external_key: " + externalKey
        );

        return executeDeleteSubscriptionPost(driver, accountId, subscriptionId, bundlesPageResponse);
    }

    // =========================================================
    // POST DELETE SUBSCRIPTION (WITH EXPLICIT SUBSCRIPTION UUID)
    // =========================================================
    public static Response deleteSubscriptionById(WebDriver driver, String accountId, String subscriptionId) {
        Assert.assertNotNull(driver, "WebDriver instance cannot be null.");
        Assert.assertFalse(accountId == null || accountId.isBlank(), "accountId cannot be null or blank.");
        Assert.assertFalse(subscriptionId == null || subscriptionId.isBlank(), "subscriptionId cannot be null or blank.");

        Response bundlesPageResponse = getAccountBundlesPage(driver, accountId);
        return executeDeleteSubscriptionPost(driver, accountId, subscriptionId.trim(), bundlesPageResponse);
    }

    // =========================================================
    // CORE POST /subscriptions/<dynamic_id> EXECUTION
    // =========================================================
    private static Response executeDeleteSubscriptionPost(
            WebDriver driver,
            String accountId,
            String subscriptionId,
            Response bundlesPageResponse) {

        String authenticityToken = extractAuthenticityToken(bundlesPageResponse, driver, subscriptionId);
        Assert.assertNotNull(
                authenticityToken,
                "Could not extract CSRF authenticity_token for POST /subscriptions/" + subscriptionId
        );

        Map<String, String> cookies = extractSeleniumCookies(driver);
        if (bundlesPageResponse != null) {
            cookies.putAll(bundlesPageResponse.getCookies());
        }

        String dynamicEndpoint = SUBSCRIPTIONS_BASE_ENDPOINT + subscriptionId;
        String refererUrl = BASE_URL + "/accounts/" + accountId + "/bundles";

        System.out.println("Executing DELETE via POST on dynamic endpoint: " + BASE_URL + dynamicEndpoint);

        Response response = RestAssured.given()
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .cookies(cookies)
                .contentType("application/x-www-form-urlencoded")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Origin", BASE_URL)
                .header("Referer", refererUrl)
                .header("Upgrade-Insecure-Requests", "1")
                .formParam("_method", "delete")
                .formParam("authenticity_token", authenticityToken)
                .when()
                .post(dynamicEndpoint)
                .then()
                .extract()
                .response();

        syncCookiesToDriver(driver, response);
        return response;
    }

    // =========================================================
    // VALIDATION & RESPONSE HELPERS
    // =========================================================
    public static void validateDeleteSubscriptionResponse(Response response) {
        Assert.assertNotNull(response, "Delete Subscription API response is null.");

        int statusCode = response.getStatusCode();
        Assert.assertEquals(
                statusCode,
                302,
                "Expected HTTP 302 Found from POST /subscriptions/<id> (_method=delete) but got: " + statusCode
        );

        String location = response.getHeader("Location");
        Assert.assertNotNull(location, "Redirect Location header is missing in 302 response.");
        Assert.assertFalse(
                location.contains("/users/sign_in"),
                "Session expired or invalid CSRF token—redirected to /users/sign_in."
        );
    }

    public static String getRedirectUrl(Response response, String accountId) {
        Assert.assertNotNull(response, "Response cannot be null.");
        String location = response.getHeader("Location");

        if (location == null || location.isBlank()) {
            return BASE_URL + "/accounts/" + accountId + "/bundles";
        }
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location;
        }
        return BASE_URL + (location.startsWith("/") ? location : "/" + location);
    }

    public static void printResponseDetails(Response response) {
        if (response == null) {
            System.out.println("Delete Subscription API Response is null.");
            return;
        }
        System.out.println("\n========== DELETE SUBSCRIPTION API RESPONSE ==========");
        System.out.println("Status Code : " + response.getStatusCode());
        System.out.println("Location    : " + response.getHeader("Location"));
        System.out.println("Request ID  : " + response.getHeader("x-request-id"));
        System.out.println("Runtime (s) : " + response.getHeader("x-runtime"));
        System.out.println("======================================================\n");
    }

    // =========================================================
    // INTERNAL DB, COOKIE, CSRF, & BASE_URL HELPERS
    // =========================================================
    private static String fetchSubscriptionIdFromDbByExternalKey(String externalKey) {
        String sql = "SELECT id FROM subscriptions WHERE external_key = ? ORDER BY record_id DESC LIMIT 1";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, externalKey.trim());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("id");
                }
            }
        } catch (Exception e) {
            System.err.println("DB lookup by external_key failed: " + e.getMessage());
        }
        return null;
    }

    private static String fetchSubscriptionIdFromDbByAccountId(String accountId) {
        String sql = """
                SELECT id FROM subscriptions
                WHERE account_record_id IN (SELECT record_id FROM accounts WHERE id = ?)
                ORDER BY record_id DESC
                LIMIT 1
                """;
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, accountId.trim());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("id");
                }
            }
        } catch (Exception e) {
            System.err.println("DB lookup by account_id failed: " + e.getMessage());
        }
        return null;
    }

    private static Map<String, String> extractSeleniumCookies(WebDriver driver) {
        Map<String, String> cookieMap = new HashMap<>();
        Set<Cookie> seleniumCookies = driver.manage().getCookies();
        for (Cookie cookie : seleniumCookies) {
            cookieMap.put(cookie.getName(), cookie.getValue());
        }
        return cookieMap;
    }

    private static void syncCookiesToDriver(WebDriver driver, Response response) {
        if (driver == null || response == null || response.getCookies().isEmpty()) {
            return;
        }
        String location = response.getHeader("Location");
        if (location != null && location.contains("/users/sign_in")) {
            return;
        }
        try {
            for (Map.Entry<String, String> entry : response.getCookies().entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if (value != null && !value.isBlank()) {
                    driver.manage().deleteCookieNamed(name);
                    driver.manage().addCookie(new Cookie(name, value, "/"));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String extractAuthenticityToken(Response pageResponse, WebDriver driver, String subscriptionId) {
        if (pageResponse != null && pageResponse.getBody() != null) {
            String html = pageResponse.getBody().asString();

            // 1. Prefer the exact form token for action="/subscriptions/<subscriptionId>" if present
            Pattern specificFormPattern = Pattern.compile(
                    "action=[\"']/subscriptions/" + Pattern.quote(subscriptionId) +
                    "[^\"']*[\"'][\\s\\S]*?name=[\"']authenticity_token[\"'][^>]*value=[\"']([^\"']+)[\"']"
            );
            Matcher specificMatcher = specificFormPattern.matcher(html);
            if (specificMatcher.find()) {
                return specificMatcher.group(1);
            }

            // 2. Otherwise use the global <meta name="csrf-token"> from <head>
            Matcher metaMatcher = CSRF_META_PATTERN.matcher(html);
            if (metaMatcher.find()) {
                return metaMatcher.group(1) != null ? metaMatcher.group(1) : metaMatcher.group(2);
            }

            // 3. Fallback to generic input token
            Matcher inputMatcher = CSRF_INPUT_PATTERN.matcher(html);
            if (inputMatcher.find()) {
                return inputMatcher.group(1) != null ? inputMatcher.group(1) : inputMatcher.group(2);
            }
        }

        try {
            return driver.findElement(By.cssSelector("meta[name='csrf-token']")).getAttribute("content");
        } catch (Exception ignored) {
        }

        try {
            return driver.findElement(By.name("authenticity_token")).getAttribute("value");
        } catch (Exception ignored) {
        }

        return null;
    }

    private static String getBaseUrl() {
        String url = System.getProperty("BASE_URL");
        if (url == null || url.isBlank()) {
            url = System.getenv("BASE_URL");
        }
        if (url == null || url.isBlank()) {
            url = dotenv.get("BASE_URL");
        }
        if (url == null || url.isBlank()) {
            throw new RuntimeException("BASE_URL is not defined in src/main/java/env/.env");
        }
        return url.trim().replaceAll("/+$", "");
    }
}