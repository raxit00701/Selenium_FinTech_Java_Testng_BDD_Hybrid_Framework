
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class delete_pay_method {

    // =========================================================
    // ENVIRONMENT CONFIGURATION
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

    private static final String PAYMENT_METHODS_ENDPOINT = "/payment_methods/";

    private static final Pattern PAYMENT_METHOD_UUID_PATTERN = Pattern.compile(
            "/payment_methods/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
    );

    private static final Pattern ACCOUNT_UUID_PATTERN = Pattern.compile(
            "/accounts/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
    );

    private static final Pattern CSRF_META_PATTERN = Pattern.compile(
            "name=[\"']csrf-token[\"'][^>]*content=[\"']([^\"']+)[\"']|"
                    + "content=[\"']([^\"']+)[\"'][^>]*name=[\"']csrf-token[\"']"
    );

    private static final Pattern CSRF_INPUT_PATTERN = Pattern.compile(
            "name=[\"']authenticity_token[\"'][^>]*value=[\"']([^\"']+)[\"']|"
                    + "value=[\"']([^\"']+)[\"'][^>]*name=[\"']authenticity_token[\"']"
    );

    private delete_pay_method() {
        // Utility class
    }

    // =========================================================
    // GET ACCOUNT PAGE
    // =========================================================

    public static Response getAccountPage(WebDriver driver, String accountId) {

        Map<String, String> cookies = extractSeleniumCookies(driver);

        String accountEndpoint = "/accounts/" + accountId;

        Response response = RestAssured.given()
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .cookies(cookies)
                .header("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Upgrade-Insecure-Requests", "1")
                .when()
                .get(accountEndpoint)
                .then()
                .extract()
                .response();

        syncCookiesToDriver(driver, response);

        return response;
    }

    // =========================================================
    // EXTRACT LATEST PAYMENT METHOD ID
    // =========================================================

    public static String extractLatestPaymentMethodId(
            WebDriver driver,
            String accountId,
            Response accountPageResponse) {

        System.out.println("\n========== EXTRACTING LATEST PAYMENT METHOD ID ==========");

        // 1. Primary: Database lookup

        String dbPaymentMethodId = fetchLatestPaymentMethodIdFromDb(accountId);

        if (dbPaymentMethodId != null && !dbPaymentMethodId.isBlank()) {

            System.out.println("Extracted Latest Payment Method ID (from DB): "
                    + dbPaymentMethodId);

            System.out.println("=========================================================\n");

            return dbPaymentMethodId;
        }

        // 2. Fallback: Account HTML

        if (accountPageResponse != null && accountPageResponse.getBody() != null) {

            String html = accountPageResponse.getBody().asString();

            String htmlPaymentMethodId =
                    extractLastUuidMatch(PAYMENT_METHOD_UUID_PATTERN, html);

            if (htmlPaymentMethodId != null) {

                System.out.println("Extracted Latest Payment Method ID (from HTML): "
                        + htmlPaymentMethodId);

                return htmlPaymentMethodId;
            }
        }

        // 3. Fallback: Selenium DOM

        if (driver != null) {

            try {

                String domPaymentMethodId =
                        extractLastUuidMatch(
                                PAYMENT_METHOD_UUID_PATTERN,
                                driver.getPageSource()
                        );

                if (domPaymentMethodId != null) {

                    System.out.println("Extracted Latest Payment Method ID (from DOM): "
                            + domPaymentMethodId);

                    return domPaymentMethodId;
                }

            } catch (Exception ignored) {
            }
        }

        return null;
    }

    // =========================================================
    // DELETE LATEST PAYMENT METHOD - AUTO ACCOUNT ID
    // =========================================================

    public static Response deletePaymentMethod(WebDriver driver) {

        Assert.assertNotNull(driver, "WebDriver instance cannot be null.");

        String accountId = resolveAccountId(driver);

        Assert.assertNotNull(
                accountId,
                "Could not resolve Account ID from current browser URL or database."
        );

        return deletePaymentMethod(driver, accountId);
    }

    // =========================================================
    // DELETE LATEST PAYMENT METHOD - BY ACCOUNT ID
    // =========================================================

    public static Response deletePaymentMethod(
            WebDriver driver,
            String accountId) {

        Assert.assertNotNull(driver, "WebDriver instance cannot be null.");

        Assert.assertFalse(
                accountId == null || accountId.isBlank(),
                "accountId cannot be null or blank."
        );

        String cleanAccountId = accountId.trim();

        // 1. Fetch account page

        Response accountPageResponse =
                getAccountPage(driver, cleanAccountId);

        // 2. Extract latest payment method ID

        String latestPaymentMethodId =
                extractLatestPaymentMethodId(
                        driver,
                        cleanAccountId,
                        accountPageResponse
                );

        Assert.assertNotNull(
                latestPaymentMethodId,
                "Could not dynamically extract the latest Payment Method ID for account: "
                        + cleanAccountId
        );

        // 3. Execute deletion

        return executeDeletePaymentMethodPost(
                driver,
                cleanAccountId,
                latestPaymentMethodId,
                accountPageResponse
        );
    }

    // =========================================================
    // DELETE PAYMENT METHOD - EXPLICIT ID
    // =========================================================

    public static Response deletePaymentMethodById(
            WebDriver driver,
            String accountId,
            String paymentMethodId) {

        Assert.assertNotNull(driver, "WebDriver instance cannot be null.");

        Assert.assertFalse(
                accountId == null || accountId.isBlank(),
                "accountId cannot be null or blank."
        );

        Assert.assertFalse(
                paymentMethodId == null || paymentMethodId.isBlank(),
                "paymentMethodId cannot be null or blank."
        );

        String cleanAccountId = accountId.trim();
        String cleanPaymentMethodId = paymentMethodId.trim();

        System.out.println(
                "Using Explicit Payment Method ID: " + cleanPaymentMethodId
        );

        Response accountPageResponse =
                getAccountPage(driver, cleanAccountId);

        return executeDeletePaymentMethodPost(
                driver,
                cleanAccountId,
                cleanPaymentMethodId,
                accountPageResponse
        );
    }

    // =========================================================
    // CORE DELETE PAYMENT METHOD POST
    // =========================================================

    private static Response executeDeletePaymentMethodPost(
            WebDriver driver,
            String accountId,
            String paymentMethodId,
            Response accountPageResponse) {

        String authenticityToken =
                extractAuthenticityToken(
                        accountPageResponse,
                        driver,
                        paymentMethodId
                );

        Assert.assertNotNull(
                authenticityToken,
                "Could not extract CSRF authenticity_token for POST /payment_methods/"
                        + paymentMethodId
        );

        Map<String, String> cookies =
                extractSeleniumCookies(driver);

        if (accountPageResponse != null) {
            cookies.putAll(accountPageResponse.getCookies());
        }

        String dynamicEndpoint =
                PAYMENT_METHODS_ENDPOINT + paymentMethodId;

        String refererUrl =
                BASE_URL + "/accounts/" + accountId;

        System.out.println(
                "Executing DELETE Payment Method via POST: "
                        + BASE_URL + dynamicEndpoint
                        + "?set_auto_pay_off=true"
        );

        Response response = RestAssured.given()
                .baseUri(BASE_URL)
                .redirects().follow(false)
                .cookies(cookies)
                .queryParam("set_auto_pay_off", "true")
                .contentType("application/x-www-form-urlencoded")
                .header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                )
                .header("Origin", BASE_URL)
                .header("Referer", refererUrl)
                .header("Upgrade-Insecure-Requests", "1")
                .formParam("set_auto_pay_off", "true")
                .formParam("_method", "delete")
                .formParam("authenticity_token", authenticityToken)
                .when()
                .post(dynamicEndpoint)
                .then()
                .extract()
                .response();

        // Sync updated session cookies

        syncCookiesToDriver(driver, response);

        // Redirect browser

        String redirectUrl =
                getRedirectUrl(response, accountId);

        System.out.println(
                "Redirecting browser after Payment Method deletion to: "
                        + redirectUrl
        );

        driver.get(redirectUrl);

        // =====================================================
        // DATABASE CLEANUP
        // =====================================================

        System.out.println("\n========== DATABASE CLEANUP ==========");

        cleanupPaymentMethodsAndInvoices();

        return response;
    }

    // =========================================================
    // VALIDATE DELETE RESPONSE
    // =========================================================

    public static void validateDeletePaymentMethodResponse(
            Response response) {

        Assert.assertNotNull(
                response,
                "Delete Payment Method API response is null."
        );

        int statusCode = response.getStatusCode();

        Assert.assertEquals(
                statusCode,
                302,
                "Expected HTTP 302 Found from POST /payment_methods/"
                        + " but got: " + statusCode
        );

        String location = response.getHeader("Location");

        Assert.assertNotNull(
                location,
                "Redirect Location header is missing in 302 response."
        );

        Assert.assertTrue(
                location.contains("/accounts/"),
                "Unexpected redirect Location header: " + location
        );
    }

    // =========================================================
    // GET REDIRECT URL
    // =========================================================

    public static String getRedirectUrl(
            Response response,
            String accountId) {

        Assert.assertNotNull(response, "Response cannot be null.");

        String location = response.getHeader("Location");

        if (location == null || location.isBlank()) {
            return BASE_URL + "/accounts/" + accountId;
        }

        if (location.startsWith("http://")
                || location.startsWith("https://")) {
            return location;
        }

        return BASE_URL
                + (location.startsWith("/") ? location : "/" + location);
    }

    // =========================================================
    // PRINT RESPONSE DETAILS
    // =========================================================

    public static void printResponseDetails(Response response) {

        if (response == null) {
            System.out.println(
                    "Delete Payment Method API Response is null."
            );
            return;
        }

        System.out.println(
                "\n========== DELETE PAYMENT METHOD API RESPONSE =========="
        );

        System.out.println("Status Code : " + response.getStatusCode());
        System.out.println("Location    : " + response.getHeader("Location"));
        System.out.println("Request ID  : " + response.getHeader("x-request-id"));
        System.out.println("Runtime (s) : " + response.getHeader("x-runtime"));

        System.out.println("========================================================\n");
    }

    // =========================================================
    // DATABASE CLEANUP
    // DELETE ALL PAYMENT METHODS AND INVOICES
    // =========================================================

    private static void cleanupPaymentMethodsAndInvoices() {

        String deletePaymentMethods =
                "DELETE FROM payment_methods";

        String deleteInvoices =
                "DELETE FROM invoices";

        try (Connection conn = DatabaseConnection.getConnection()) {

            conn.setAutoCommit(false);

            try {

                // 1. Delete all payment methods

                int paymentMethodsDeleted;

                try (PreparedStatement stmt =
                             conn.prepareStatement(deletePaymentMethods)) {

                    paymentMethodsDeleted = stmt.executeUpdate();
                }

                System.out.println(
                        "Payment Methods Deleted: " + paymentMethodsDeleted
                );

                // 2. Delete all invoices

                int invoicesDeleted;

                try (PreparedStatement stmt =
                             conn.prepareStatement(deleteInvoices)) {

                    invoicesDeleted = stmt.executeUpdate();
                }

                System.out.println(
                        "Invoices Deleted: " + invoicesDeleted
                );

                // Commit both operations

                conn.commit();

                System.out.println(
                        "\n========== DATABASE CLEANUP COMPLETED =========="
                );

                System.out.println("Payment Methods: " + paymentMethodsDeleted);
                System.out.println("Invoices       : " + invoicesDeleted);

                System.out.println(
                        "=================================================\n"
                );

            } catch (Exception e) {

                conn.rollback();

                System.err.println(
                        "Database cleanup transaction rolled back: "
                                + e.getMessage()
                );

                throw new RuntimeException(
                        "Failed to clean database tables.",
                        e
                );
            }

        } catch (Exception e) {

            System.err.println(
                    "Database cleanup failed: " + e.getMessage()
            );

            throw new RuntimeException(
                    "Database cleanup connection or execution failed.",
                    e
            );
        }
    }

    // =========================================================
    // FETCH LATEST PAYMENT METHOD ID FROM DATABASE
    // =========================================================

    private static String fetchLatestPaymentMethodIdFromDb(
            String accountId) {

        String sqlWithAccount = """
                SELECT id, plugin_name, created_date
                FROM payment_methods
                WHERE account_id = ? AND is_active = 1
                ORDER BY record_id DESC
                LIMIT 1
                """;

        String sqlLatestOverall = """
                SELECT id, account_id, plugin_name, created_date
                FROM payment_methods
                WHERE is_active = 1
                ORDER BY record_id DESC
                LIMIT 1
                """;

        try (Connection conn = DatabaseConnection.getConnection()) {

            if (accountId != null && !accountId.isBlank()) {

                try (PreparedStatement stmt =
                             conn.prepareStatement(sqlWithAccount)) {

                    stmt.setString(1, accountId.trim());

                    try (ResultSet rs = stmt.executeQuery()) {

                        if (rs.next()) {

                            String id = rs.getString("id");

                            System.out.println(
                                    "DB Match -> Plugin: "
                                            + rs.getString("plugin_name")
                                            + " | Created Date: "
                                            + rs.getTimestamp("created_date")
                            );

                            return id;
                        }
                    }
                }
            }

            try (PreparedStatement stmt =
                         conn.prepareStatement(sqlLatestOverall);
                 ResultSet rs = stmt.executeQuery()) {

                if (rs.next()) {

                    String id = rs.getString("id");

                    System.out.println(
                            "DB Latest Match -> Account ID: "
                                    + rs.getString("account_id")
                                    + " | Plugin: "
                                    + rs.getString("plugin_name")
                                    + " | Created Date: "
                                    + rs.getTimestamp("created_date")
                    );

                    return id;
                }
            }

        } catch (Exception e) {

            System.err.println(
                    "DB lookup for latest payment_method_id failed: "
                            + e.getMessage()
            );
        }

        return null;
    }

    // =========================================================
    // RESOLVE ACCOUNT ID
    // =========================================================

    private static String resolveAccountId(WebDriver driver) {

        // 1. Current Selenium URL

        if (driver != null) {

            try {

                String currentUrl = driver.getCurrentUrl();

                if (currentUrl != null) {

                    Matcher urlMatcher =
                            ACCOUNT_UUID_PATTERN.matcher(currentUrl);

                    if (urlMatcher.find()) {
                        return urlMatcher.group(1);
                    }
                }

            } catch (Exception ignored) {
            }
        }

        // 2. Database fallback

        String sql = """
                SELECT account_id
                FROM payment_methods
                WHERE is_active = 1
                ORDER BY record_id DESC
                LIMIT 1
                """;

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                return rs.getString("account_id");
            }

        } catch (Exception ignored) {
        }

        return null;
    }

    // =========================================================
    // EXTRACT LAST UUID MATCH
    // =========================================================

    private static String extractLastUuidMatch(
            Pattern pattern,
            String content) {

        if (content == null || content.isBlank()) {
            return null;
        }

        Matcher matcher = pattern.matcher(content);

        List<String> matches = new ArrayList<>();

        while (matcher.find()) {
            matches.add(matcher.group(1));
        }

        return matches.isEmpty()
                ? null
                : matches.get(matches.size() - 1);
    }

    // =========================================================
    // EXTRACT SELENIUM COOKIES
    // =========================================================

    private static Map<String, String> extractSeleniumCookies(
            WebDriver driver) {

        Map<String, String> cookieMap = new HashMap<>();

        Set<Cookie> seleniumCookies =
                driver.manage().getCookies();

        for (Cookie cookie : seleniumCookies) {

            cookieMap.put(
                    cookie.getName(),
                    cookie.getValue()
            );
        }

        return cookieMap;
    }

    // =========================================================
    // SYNC COOKIES TO SELENIUM
    // =========================================================

    private static void syncCookiesToDriver(
            WebDriver driver,
            Response response) {

        if (driver == null
                || response == null
                || response.getCookies().isEmpty()) {
            return;
        }

        String location = response.getHeader("Location");

        if (location != null
                && location.contains("/users/sign_in")) {
            return;
        }

        try {

            for (Map.Entry<String, String> entry :
                    response.getCookies().entrySet()) {

                String name = entry.getKey();
                String value = entry.getValue();

                if (value != null && !value.isBlank()) {

                    driver.manage().deleteCookieNamed(name);

                    driver.manage().addCookie(
                            new Cookie(name, value, "/")
                    );
                }
            }

        } catch (Exception ignored) {
        }
    }

    // =========================================================
    // EXTRACT CSRF AUTHENTICITY TOKEN
    // =========================================================

    private static String extractAuthenticityToken(
            Response pageResponse,
            WebDriver driver,
            String paymentMethodId) {

        if (pageResponse != null
                && pageResponse.getBody() != null) {

            String html = pageResponse.getBody().asString();

            // 1. Exact payment method form token

            Pattern specificFormPattern = Pattern.compile(
                    "action=[\"']/payment_methods/"
                            + Pattern.quote(paymentMethodId)
                            + "[^\"']*[\"'][\\s\\S]*?"
                            + "name=[\"']authenticity_token[\"']"
                            + "[^>]*value=[\"']([^\"']+)[\"']"
            );

            Matcher specificMatcher =
                    specificFormPattern.matcher(html);

            if (specificMatcher.find()) {
                return specificMatcher.group(1);
            }

            // 2. Global CSRF meta token

            Matcher metaMatcher =
                    CSRF_META_PATTERN.matcher(html);

            if (metaMatcher.find()) {

                return metaMatcher.group(1) != null
                        ? metaMatcher.group(1)
                        : metaMatcher.group(2);
            }

            // 3. Generic authenticity input token

            Matcher inputMatcher =
                    CSRF_INPUT_PATTERN.matcher(html);

            if (inputMatcher.find()) {

                return inputMatcher.group(1) != null
                        ? inputMatcher.group(1)
                        : inputMatcher.group(2);
            }
        }

        // 4. Selenium meta fallback

        try {

            return driver.findElement(
                    By.cssSelector("meta[name='csrf-token']")
            ).getAttribute("content");

        } catch (Exception ignored) {
        }

        // 5. Selenium input fallback

        try {

            return driver.findElement(
                    By.name("authenticity_token")
            ).getAttribute("value");

        } catch (Exception ignored) {
        }

        return null;
    }

    // =========================================================
    // GET BASE URL
    // =========================================================

    private static String getBaseUrl() {

        String url = System.getProperty("BASE_URL");

        if (url == null || url.isBlank()) {
            url = System.getenv("BASE_URL");
        }

        if (url == null || url.isBlank()) {
            url = dotenv.get("BASE_URL");
        }

        if (url == null || url.isBlank()) {

            throw new RuntimeException(
                    "BASE_URL is not defined in src/main/java/env/.env"
            );
        }

        return url.trim().replaceAll("/+$", "");
    }
}
