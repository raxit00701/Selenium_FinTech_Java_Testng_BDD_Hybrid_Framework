package API;

import io.github.cdimascio.dotenv.Dotenv;
import io.restassured.RestAssured;
import io.restassured.response.Response;

import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;
import org.testng.Assert;

import utils.data_driven.JsonReader;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class catalog {

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
    private static final String DEFAULT_TENANT_ID = "1";
    private static final String ADMIN_TENANT_ENDPOINT = "/admin_tenants/1";
    private static final String NEW_CATALOG_ENDPOINT = "/admin_tenants/1/new_catalog";
    private static final String CREATE_SIMPLE_PLAN_ENDPOINT = "/admin_tenants/create_simple_plan";
    private static final String JSON_FILE = "catalog.json";

    private static final Pattern CSRF_META_PATTERN = Pattern.compile(
            "name=[\"']csrf-token[\"'][^>]*content=[\"']([^\"']+)[\"']|" +
            "content=[\"']([^\"']+)[\"'][^>]*name=[\"']csrf-token[\"']"
    );

    private static final Pattern CREATE_PLAN_FORM_CSRF_PATTERN = Pattern.compile(
            "action=[\"']/admin_tenants/create_simple_plan[\"'][\\s\\S]*?name=[\"']authenticity_token[\"'][^>]*value=[\"']([^\"']+)[\"']"
    );

    private static final Pattern CSRF_INPUT_PATTERN = Pattern.compile(
            "name=[\"']authenticity_token[\"'][^>]*value=[\"']([^\"']+)[\"']|" +
            "value=[\"']([^\"']+)[\"'][^>]*name=[\"']authenticity_token[\"']"
    );

    private static final Pattern FLASH_ERROR_PATTERN = Pattern.compile(
            "id=[\"']flash-error[\"'][\\s\\S]*?<span[^>]*>([\\s\\S]*?)</span>|" +
            "alert-danger[^>]*>([\\s\\S]*?)</div>"
    );

    private catalog() {
        // Utility class
    }

    // =========================================================
    // DATA HELPERS (JsonReader Integration)
    // =========================================================
    public static int getTotalIterations() {
        return JsonReader.getIterativeData(JSON_FILE).length;
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
    // GET ADMIN TENANT & NEW CATALOG PAGE (FETCHES CSRF TOKEN)
    // =========================================================
    public static Response getAdminTenantPage(WebDriver driver) {
        Map<String, String> cookies = extractSeleniumCookies(driver);

        Response tenantResponse = RestAssured.given()
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .cookies(cookies)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Upgrade-Insecure-Requests", "1")
                .when()
                .get(ADMIN_TENANT_ENDPOINT)
                .then()
                .extract()
                .response();

        cookies.putAll(tenantResponse.getCookies());
        syncCookiesToDriver(driver, tenantResponse);

        Response newCatalogResponse = RestAssured.given()
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .cookies(cookies)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Referer", BASE_URL + ADMIN_TENANT_ENDPOINT)
                .header("Upgrade-Insecure-Requests", "1")
                .when()
                .get(NEW_CATALOG_ENDPOINT)
                .then()
                .extract()
                .response();

        if (newCatalogResponse.getStatusCode() == 200) {
            syncCookiesToDriver(driver, newCatalogResponse);
            return newCatalogResponse;
        }

        return tenantResponse;
    }

    // =========================================================
    // POST CREATE SIMPLE PLAN (BY ITERATION INDEX)
    // =========================================================
    public static Response createSimplePlan(WebDriver driver, int iteration) {
        Map<String, Object> testData = getTestData(iteration);
        return createSimplePlan(driver, testData);
    }

    // =========================================================
    // POST CREATE SIMPLE PLAN (BY DATA MAP)
    // =========================================================
    public static Response createSimplePlan(WebDriver driver, Map<String, Object> testData) {
        Assert.assertNotNull(driver, "WebDriver instance cannot be null.");
        Assert.assertNotNull(testData, "Catalog testData map cannot be null.");

        Response response = submitSimplePlanPost(driver, testData);

        if (response.getStatusCode() == 200) {
            String initialError = extractFlashError(response);
            System.out.println("Initial POST /admin_tenants/create_simple_plan returned 200. "
                    + (initialError != null ? "KAUI Message: " + initialError : "Cleaning existing catalog and retrying..."));

            deletecatalog.deleteCatalog(driver);
            response = submitSimplePlanPost(driver, testData);
        }

        return response;
    }

    private static Response submitSimplePlanPost(WebDriver driver, Map<String, Object> testData) {
        Response pageResponse = getAdminTenantPage(driver);
        String authenticityToken = extractAuthenticityToken(pageResponse, driver);

        Assert.assertNotNull(
                authenticityToken,
                "Could not extract CSRF authenticity_token for POST " + CREATE_SIMPLE_PLAN_ENDPOINT
        );

        Map<String, String> cookies = extractSeleniumCookies(driver);
        cookies.putAll(pageResponse.getCookies());

        String tenantId = getValue(testData, "id", DEFAULT_TENANT_ID);
        String productCategory = getValue(testData, "productCategory", "BASE");
        String productName = getValue(testData, "productName", "QA_Basic_Plan");
        String planId = getValue(testData, "planId", "qa-basic-monthly-101");
        String availableBaseProducts = getValue(testData, "availableBaseProducts", "");
        String amount = getValue(testData, "amount", "19");
        String currency = getValue(testData, "currency", "USD");
        String billingPeriod = getValue(testData, "billingPeriod", "MONTHLY");
        String trialLength = getValue(testData, "trialLength", "14");
        String trialTimeUnit = getValue(testData, "trialTimeUnit", "UNLIMITED");

        Response response = RestAssured.given()
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .cookies(cookies)
                .contentType("application/x-www-form-urlencoded")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Origin", BASE_URL)
                .header("Referer", BASE_URL + CREATE_SIMPLE_PLAN_ENDPOINT)
                .header("Upgrade-Insecure-Requests", "1")
                .formParam("authenticity_token", authenticityToken)
                .formParam("id", tenantId)
                .formParam("simple_plan[product_category]", productCategory)
                .formParam("simple_plan[product_name]", productName)
                .formParam("simple_plan[plan_id]", planId)
                .formParam("simple_plan[available_base_products][]", availableBaseProducts)
                .formParam("simple_plan[amount]", amount)
                .formParam("simple_plan[currency]", currency)
                .formParam("simple_plan[billing_period]", billingPeriod)
                .formParam("simple_plan[trial_length]", trialLength)
                .formParam("simple_plan[trial_time_unit]", trialTimeUnit)
                .when()
                .post(CREATE_SIMPLE_PLAN_ENDPOINT)
                .then()
                .extract()
                .response();

        syncCookiesToDriver(driver, response);
        return response;
    }

    // =========================================================
    // VALIDATION & RESPONSE HELPERS
    // =========================================================
    public static void validateCreateSimplePlanResponse(Response response) {
        Assert.assertNotNull(response, "Create Simple Plan API response is null.");

        int statusCode = response.getStatusCode();
        if (statusCode != 302) {
            String flashError = extractFlashError(response);
            Assert.fail("Expected HTTP 302 Found from POST " + CREATE_SIMPLE_PLAN_ENDPOINT
                    + " but got: " + statusCode
                    + (flashError != null ? " | KAUI Error: " + flashError : ""));
        }

        String location = response.getHeader("Location");
        Assert.assertNotNull(location, "Redirect Location header is missing in 302 response.");
        Assert.assertTrue(
                location.contains("/admin_tenants/"),
                "Unexpected redirect Location header: " + location
        );
    }

    public static String getRedirectUrl(Response response) {
        Assert.assertNotNull(response, "Response cannot be null.");
        String location = response.getHeader("Location");

        if (location == null || location.isBlank()) {
            return BASE_URL + ADMIN_TENANT_ENDPOINT;
        }
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location;
        }
        return BASE_URL + (location.startsWith("/") ? location : "/" + location);
    }

    public static void printResponseDetails(Response response) {
        if (response == null) {
            System.out.println("Catalog API Response is null.");
            return;
        }
        System.out.println("\n========== CATALOG API RESPONSE ==========");
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
        System.out.println("==========================================\n");
    }

    // =========================================================
    // INTERNAL COOKIE, CSRF, & BASE_URL HELPERS
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

            Matcher formMatcher = CREATE_PLAN_FORM_CSRF_PATTERN.matcher(html);
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
        if (val == null || String.valueOf(val).isBlank()) {
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