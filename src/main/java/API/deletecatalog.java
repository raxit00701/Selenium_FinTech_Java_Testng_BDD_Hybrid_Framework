package API;

import io.github.cdimascio.dotenv.Dotenv;
import io.restassured.RestAssured;
import io.restassured.response.Response;

import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;
import org.testng.Assert;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class deletecatalog {

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
    private static final String ADMIN_TENANT_ENDPOINT = "/admin_tenants/" + DEFAULT_TENANT_ID;
    private static final String DELETE_CATALOG_ENDPOINT = "/admin_tenants/" + DEFAULT_TENANT_ID + "/delete_catalog";
    private static final String CATALOG_SHOW_REFERER = "/admin_tenants/" + DEFAULT_TENANT_ID + "?active_tab=CatalogShow";

    // Matches the global Rails CSRF token in <head>
    private static final Pattern CSRF_META_PATTERN = Pattern.compile(
            "name=[\"']csrf-token[\"'][^>]*content=[\"']([^\"']+)[\"']|" +
            "content=[\"']([^\"']+)[\"'][^>]*name=[\"']csrf-token[\"']"
    );

    // Matches the authenticity_token specifically inside the delete_catalog form
    private static final Pattern DELETE_CATALOG_FORM_CSRF_PATTERN = Pattern.compile(
            "action=[\"']/admin_tenants/1/delete_catalog[\"'][\\s\\S]*?name=[\"']authenticity_token[\"'][^>]*value=[\"']([^\"']+)[\"']"
    );

    private static final Pattern CSRF_INPUT_PATTERN = Pattern.compile(
            "name=[\"']authenticity_token[\"'][^>]*value=[\"']([^\"']+)[\"']|" +
            "value=[\"']([^\"']+)[\"'][^>]*name=[\"']authenticity_token[\"']"
    );

    private deletecatalog() {
        // Utility class
    }

    // =========================================================
    // GET ADMIN TENANT CATALOG TAB (FETCHES CSRF TOKEN)
    // =========================================================
    public static Response getCatalogShowPage(WebDriver driver) {
        Map<String, String> cookies = extractSeleniumCookies(driver);

        Response response = RestAssured.given()
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .cookies(cookies)
                .queryParam("active_tab", "CatalogShow")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Upgrade-Insecure-Requests", "1")
                .when()
                .get(ADMIN_TENANT_ENDPOINT)
                .then()
                .extract()
                .response();

        syncCookiesToDriver(driver, response);
        return response;
    }

    // =========================================================
    // POST DELETE CATALOG (DEFAULT TENANT = 1)
    // =========================================================
    public static Response deleteCatalog(WebDriver driver) {
        Assert.assertNotNull(driver, "WebDriver instance cannot be null.");

        // 1. Fetch fresh authenticity_token and sync session cookies
        Response catalogPageResponse = getCatalogShowPage(driver);
        String authenticityToken = extractAuthenticityToken(catalogPageResponse, driver);

        Assert.assertNotNull(
                authenticityToken,
                "Could not extract CSRF authenticity_token for POST " + DELETE_CATALOG_ENDPOINT
        );

        Map<String, String> cookies = extractSeleniumCookies(driver);
        cookies.putAll(catalogPageResponse.getCookies());

        // 2. Execute POST /admin_tenants/1/delete_catalog with _method=delete
        Response response = RestAssured.given()
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .cookies(cookies)
                .contentType("application/x-www-form-urlencoded")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Origin", BASE_URL)
                .header("Referer", BASE_URL + CATALOG_SHOW_REFERER)
                .header("Upgrade-Insecure-Requests", "1")
                .formParam("_method", "delete")
                .formParam("authenticity_token", authenticityToken)
                .when()
                .post(DELETE_CATALOG_ENDPOINT)
                .then()
                .extract()
                .response();

        // 3. Sync updated _kaui_session cookie back to WebDriver
        syncCookiesToDriver(driver, response);

        return response;
    }

    // =========================================================
    // VALIDATION & RESPONSE HELPERS
    // =========================================================
    public static void validateDeleteCatalogResponse(Response response) {
        Assert.assertNotNull(response, "Delete Catalog API response is null.");

        int statusCode = response.getStatusCode();
        Assert.assertEquals(
                statusCode,
                302,
                "Expected HTTP 302 Found from POST " + DELETE_CATALOG_ENDPOINT + " but got: " + statusCode
        );

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
            System.out.println("Delete Catalog API Response is null.");
            return;
        }
        System.out.println("\n========== DELETE CATALOG API RESPONSE ==========");
        System.out.println("Status Code : " + response.getStatusCode());
        System.out.println("Location    : " + response.getHeader("Location"));
        System.out.println("Request ID  : " + response.getHeader("x-request-id"));
        System.out.println("Runtime (s) : " + response.getHeader("x-runtime"));
        System.out.println("=================================================\n");
    }

    // =========================================================
    // INTERNAL COOKIE, CSRF, & BASE_URL HELPERS
    // =========================================================
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

            // 1. Prefer the exact form token for delete_catalog if present
            Matcher formMatcher = DELETE_CATALOG_FORM_CSRF_PATTERN.matcher(html);
            if (formMatcher.find()) {
                return formMatcher.group(1);
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