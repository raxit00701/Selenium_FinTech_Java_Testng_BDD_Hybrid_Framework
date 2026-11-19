package API;

import io.github.cdimascio.dotenv.Dotenv;
import io.restassured.RestAssured;
import io.restassured.response.Response;

import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;
import org.testng.Assert;
import org.testng.annotations.DataProvider;

import utils.data_driven.JsonReader;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class subscription {

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
    // ENDPOINT & PAYLOAD CONSTANTS
    // =========================================================
    private static final String SUBSCRIPTIONS_ENDPOINT = "/subscriptions";
    private static final String NEW_SUBSCRIPTION_ENDPOINT = "/subscriptions/new";
    private static final String JSON_FILE = "subscription.json";

    private static final Pattern CSRF_META_PATTERN = Pattern.compile(
            "name=[\"']csrf-token[\"'][^>]*content=[\"']([^\"']+)[\"']|" +
            "content=[\"']([^\"']+)[\"'][^>]*name=[\"']csrf-token[\"']"
    );

    private static final Pattern SUBSCRIPTION_FORM_CSRF_PATTERN = Pattern.compile(
            "action=[\"']/subscriptions[\"'][\\s\\S]*?name=[\"']authenticity_token[\"'][^>]*value=[\"']([^\"']+)[\"']"
    );

    private static final Pattern CSRF_INPUT_PATTERN = Pattern.compile(
            "name=[\"']authenticity_token[\"'][^>]*value=[\"']([^\"']+)[\"']|" +
            "value=[\"']([^\"']+)[\"'][^>]*name=[\"']authenticity_token[\"']"
    );

    private static final Pattern FLASH_ERROR_PATTERN = Pattern.compile(
            "id=[\"']flash-error[\"'][\\s\\S]*?<span[^>]*>([\\s\\S]*?)</span>|" +
            "alert-danger[^>]*>([\\s\\S]*?)</div>"
    );

    private subscription() {
        // Utility class
    }

    // =========================================================
    // DATA HELPERS & DATA PROVIDER (JsonReader Integration)
    // =========================================================
    public static int getTotalIterations() {
        return JsonReader.getIterativeData(JSON_FILE).length;
    }

    @DataProvider(name = "subscriptionData")
    public static Object[][] getSubscriptionDataProvider() {
        return JsonReader.getIndexData(getTotalIterations());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getTestData(int iteration) {
        Object[][] data = JsonReader.getIterativeData(JSON_FILE);
        if (iteration < 0 || iteration >= data.length) {
            throw new IllegalArgumentException("Invalid iteration index: " + iteration);
        }
        return (Map<String, Object>) data[iteration][0];
    }

    // =========================================================
    // GET /subscriptions/new PAGE (FETCHES CSRF TOKEN)
    // =========================================================
    public static Response getNewSubscriptionPage(WebDriver driver, String accountId, String productCategory) {
        Map<String, String> cookies = extractSeleniumCookies(driver);

        Response response = RestAssured.given()
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .cookies(cookies)
                .queryParam("account_id", accountId)
                .queryParam("product_category", productCategory)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Upgrade-Insecure-Requests", "1")
                .when()
                .get(NEW_SUBSCRIPTION_ENDPOINT)
                .then()
                .extract()
                .response();

        syncCookiesToDriver(driver, response);
        return response;
    }

    // =========================================================
    // POST CREATE SUBSCRIPTION (BY ITERATION INDEX)
    // =========================================================
    public static Response createSubscription(WebDriver driver, int iteration) {
        Map<String, Object> testData = getTestData(iteration);
        return createSubscription(driver, testData, null);
    }

    public static Response createSubscription(WebDriver driver, int iteration, String dynamicAccountId) {
        Map<String, Object> testData = getTestData(iteration);
        return createSubscription(driver, testData, dynamicAccountId);
    }

    // =========================================================
    // POST CREATE SUBSCRIPTION (BY DATA MAP)
    // =========================================================
    public static Response createSubscription(WebDriver driver, Map<String, Object> testData) {
        return createSubscription(driver, testData, null);
    }

    public static Response createSubscription(WebDriver driver, Map<String, Object> testData, String dynamicAccountId) {
        Assert.assertNotNull(driver, "WebDriver instance cannot be null.");
        Assert.assertNotNull(testData, "Subscription testData map cannot be null.");

        String accountId = (dynamicAccountId != null && !dynamicAccountId.isBlank())
                ? dynamicAccountId.trim()
                : getValue(testData, "account_id", "");

        Assert.assertFalse(accountId.isEmpty(), "subscription[account_id] is required in subscription.json or via parameter.");

        String bundleId = getValue(testData, "bundle_id", "");
        String productCategory = getValue(testData, "product_category", "BASE");
        String bundleExternalKey = getValue(testData, "bundle_external_key", "QA_Basic_Plan");
        String externalKey = getValue(testData, "external_key", "QA_Basic_Plan");
        String planName = getValue(testData, "plan_name", "qa-basic-monthly-101");
        String priceOverride = getValue(testData, "price_override", "14");
        String typeChange = getValue(testData, "type_change", "IMM");
        String requestedDate = getValue(testData, "requested_date", "");

        // 1. Load GET /subscriptions/new to fetch CSRF token
        Response newSubPageResponse = getNewSubscriptionPage(driver, accountId, productCategory);
        Assert.assertEquals(
                newSubPageResponse.getStatusCode(),
                200,
                "GET /subscriptions/new did not return 200 OK. Location: " + newSubPageResponse.getHeader("Location")
        );

        String authenticityToken = extractAuthenticityToken(newSubPageResponse, driver);
        Assert.assertNotNull(
                authenticityToken,
                "Could not extract CSRF authenticity_token for POST " + SUBSCRIPTIONS_ENDPOINT
        );

        Map<String, String> cookies = extractSeleniumCookies(driver);
        cookies.putAll(newSubPageResponse.getCookies());

        String refererUrl = BASE_URL + NEW_SUBSCRIPTION_ENDPOINT
                + "?account_id=" + accountId
                + "&product_category=" + productCategory;

        // 2. Execute single POST /subscriptions request
        Response response = RestAssured.given()
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .cookies(cookies)
                .contentType("application/x-www-form-urlencoded")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Origin", BASE_URL)
                .header("Referer", refererUrl)
                .header("Upgrade-Insecure-Requests", "1")
                .formParam("authenticity_token", authenticityToken)
                .formParam("subscription[bundle_id]", bundleId)
                .formParam("subscription[account_id]", accountId)
                .formParam("subscription[product_category]", productCategory)
                .formParam("subscription[bundle_external_key]", bundleExternalKey)
                .formParam("subscription[external_key]", externalKey)
                .formParam("plan_name", planName)
                .formParam("price_override", priceOverride)
                .formParam("type_change", typeChange)
                .formParam("requested_date", requestedDate)
                .when()
                .post(SUBSCRIPTIONS_ENDPOINT)
                .then()
                .extract()
                .response();

        syncCookiesToDriver(driver, response);
        return response;
    }

    // =========================================================
    // VALIDATION & RESPONSE HELPERS
    // =========================================================
    public static void validateCreateSubscriptionResponse(Response response) {
        Assert.assertNotNull(response, "Create Subscription API response is null.");

        int statusCode = response.getStatusCode();
        if (statusCode != 302) {
            String flashError = extractFlashError(response);
            Assert.fail("Expected HTTP 302 Found from POST " + SUBSCRIPTIONS_ENDPOINT
                    + " but got: " + statusCode
                    + (flashError != null ? " | KAUI Error: " + flashError : ""));
        }

        String location = response.getHeader("Location");
        Assert.assertNotNull(location, "Redirect Location header is missing in 302 response.");
        Assert.assertTrue(
                location.contains("/accounts/") && location.contains("/bundles"),
                "Unexpected redirect Location header: " + location
        );
    }

    public static String getBundlesRedirectUrl(Response response) {
        Assert.assertNotNull(response, "Response cannot be null.");
        String location = response.getHeader("Location");

        Assert.assertNotNull(location, "Redirect Location header is missing in response.");
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location;
        }
        return BASE_URL + (location.startsWith("/") ? location : "/" + location);
    }

    public static void printResponseDetails(Response response) {
        if (response == null) {
            System.out.println("Subscription API Response is null.");
            return;
        }
        System.out.println("\n========== SUBSCRIPTION API RESPONSE ==========");
        System.out.println("Status Code : " + response.getStatusCode());
        System.out.println("Location    : " + response.getHeader("Location"));
        System.out.println("Request ID  : " + response.getHeader("x-request-id"));
        System.out.println("Runtime (s) : " + response.getHeader("x-runtime"));
        if (response.getStatusCode() != 302) {
            String flashError = extractFlashError(response);
            if (flashError != null) {
                System.out.println("KAUI Error  : " + flashError);
            }
        }
        System.out.println("===============================================\n");
    }

    // =========================================================
    // INTERNAL COOKIE, CSRF, & HTML PARSING HELPERS
    // =========================================================
    private static String extractFlashError(Response response) {
        if (response == null || response.getBody() == null) {
            return null;
        }
        String html = response.getBody().asString();
        Matcher matcher = FLASH_ERROR_PATTERN.matcher(html);
        if (matcher.find()) {
            String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            return raw.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
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

    private static String extractAuthenticityToken(Response pageResponse, WebDriver driver) {
        if (pageResponse != null && pageResponse.getBody() != null) {
            String html = pageResponse.getBody().asString();

            Matcher formMatcher = SUBSCRIPTION_FORM_CSRF_PATTERN.matcher(html);
            if (formMatcher.find()) {
                return formMatcher.group(1);
            }

            Matcher metaMatcher = CSRF_META_PATTERN.matcher(html);
            if (metaMatcher.find()) {
                return metaMatcher.group(1) != null ? metaMatcher.group(1) : metaMatcher.group(2);
            }

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

    private static String getValue(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        if (val == null) {
            return defaultValue;
        }
        return String.valueOf(val).trim();
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