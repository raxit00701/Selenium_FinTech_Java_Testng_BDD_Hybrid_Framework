
package API;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.cdimascio.dotenv.Dotenv;
import io.restassured.RestAssured;
import io.restassured.config.RedirectConfig;
import io.restassured.response.Response;

import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class create_accountApi {

    private static final String NEW_ACCOUNT_PATH = "/accounts/new";
    private static final String CREATE_ACCOUNT_PATH = "/accounts";

    private static final String DATA_FILE =
            "src/main/resources/data/account.json";

    private static final String BASE_URL = loadBaseUrl();

    private create_accountApi() {
        // Utility class.
    }

    // =========================================================
    // LOAD BASE URL FROM ENV
    // =========================================================
    private static String loadBaseUrl() {

        Dotenv dotenv = Dotenv.configure()
                .directory("src/main/java/env")
                .filename(".env")
                .ignoreIfMissing()
                .load();

        String value = dotenv.get("API_BASE_URL");

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(
                    "API_BASE_URL is missing from src/main/java/env/.env"
            );
        }

        return value.trim().replaceAll("/+$", "");
    }

    // =========================================================
    // CREATE ACCOUNT USING ITERATION DATA
    // =========================================================
    public static Response createAccount(
            WebDriver driver,
            int iteration
    ) {

        if (driver == null) {
            throw new IllegalArgumentException(
                    "WebDriver must not be null."
            );
        }

        String origin = driver.getCurrentUrl();

        if (origin == null || !origin.startsWith(BASE_URL)) {
            throw new IllegalStateException(
                    "Selenium must first be logged in to " + BASE_URL
                            + ". Current URL: " + origin
            );
        }

        // Get account data for the requested iteration.
        JsonNode iterationData = getIterationData(iteration);

        JsonNode account = iterationData.get("account");

        if (account == null || !account.isObject()) {
            throw new IllegalStateException(
                    "Account object is missing in iteration " + (iteration + 1)
            );
        }

        System.out.println(
                "\n========== ACCOUNT ITERATION "
                        + (iteration + 1) + " =========="
        );

        // Open account creation form.
        driver.get(BASE_URL + NEW_ACCOUNT_PATH);

        // Extract CSRF token.
        String authenticityToken = driver.findElement(
                By.name("authenticity_token")
        ).getAttribute("value");

        if (authenticityToken == null
                || authenticityToken.trim().isEmpty()) {

            throw new IllegalStateException(
                    "The account form did not expose an authenticity_token."
            );
        }

        // Capture browser cookies.
        Map<String, String> cookies = getBrowserCookies(driver);

        // Build API request.
        var request = RestAssured.given()
                .baseUri(BASE_URL)
                .config(RestAssured.config().redirect(
                        RedirectConfig.redirectConfig()
                                .followRedirects(false)
                ))
                .cookies(cookies)
                .header("Origin", BASE_URL)
                .header("Referer", BASE_URL + NEW_ACCOUNT_PATH)
                .header("Upgrade-Insecure-Requests", "1")
                .accept("text/html,application/xhtml+xml,application/xml")
                .contentType("application/x-www-form-urlencoded")
                .formParam("authenticity_token", authenticityToken);

        // Map JSON properties to Rails nested form parameters.
        Iterator<Map.Entry<String, JsonNode>> fields =
                account.fields();

        while (fields.hasNext()) {

            Map.Entry<String, JsonNode> field = fields.next();

            JsonNode value = field.getValue();

            request.formParam(
                    "account[" + field.getKey() + "]",
                    value == null || value.isNull()
                            ? ""
                            : value.asText()
            );
        }

        // Add top-level hide parameter.
        JsonNode hide = iterationData.get("hide");

        if (hide != null && !hide.isNull()) {
            request.formParam("hide", hide.asText());
        }

        // Submit POST request.
        Response response = request
                .when()
                .post(CREATE_ACCOUNT_PATH)
                .then()
                .extract()
                .response();

        printResponseDetails(response);

        return response;
    }

    // =========================================================
    // GET EXPECTED EXTERNAL KEY FOR THE SAME ITERATION
    // =========================================================
    public static String getExpectedExternalKeyFromJson(
            int iteration
    ) {

        JsonNode iterationData = getIterationData(iteration);

        JsonNode externalKeyNode =
                iterationData.path("account").path("external_key");

        if (externalKeyNode.isMissingNode()
                || externalKeyNode.isNull()
                || externalKeyNode.asText().trim().isEmpty()) {

            throw new IllegalStateException(
                    "external_key is missing or empty in account.json "
                            + "for iteration " + (iteration + 1)
            );
        }

        return externalKeyNode.asText().trim();
    }

    // =========================================================
    // GET ITERATION DATA FROM JSON
    // =========================================================
    private static JsonNode getIterationData(int iteration) {

        JsonNode root = readTestData();

        JsonNode iterations = root.get("iterations");

        if (iterations == null || !iterations.isArray()) {
            throw new IllegalStateException(
                    "account.json must contain an 'iterations' array."
            );
        }

        if (iteration < 0 || iteration >= iterations.size()) {
            throw new IllegalArgumentException(
                    "Invalid iteration index: " + iteration
                            + ". Available iterations: " + iterations.size()
            );
        }

        return iterations.get(iteration);
    }

    public static int getTotalIterations() {

    JsonNode root = readTestData();
    JsonNode iterations = root.get("iterations");

    if (iterations == null || !iterations.isArray()) {
        throw new IllegalStateException(
                "account.json must contain an iterations array."
        );
    }

    return iterations.size();
}

    // =========================================================
    // GET BROWSER COOKIES
    // =========================================================
    private static Map<String, String> getBrowserCookies(
            WebDriver driver
    ) {

        Map<String, String> cookies = new HashMap<>();

        for (Cookie cookie : driver.manage().getCookies()) {
            cookies.put(cookie.getName(), cookie.getValue());
        }

        if (cookies.isEmpty()) {
            throw new IllegalStateException(
                    "No Selenium cookies found after opening the account form."
            );
        }

        return cookies;
    }

    // =========================================================
    // READ JSON FILE
    // =========================================================
    private static JsonNode readTestData() {

        Path path = Path.of(DATA_FILE);

        if (!Files.exists(path)) {
            throw new IllegalStateException(
                    "Account JSON file not found: "
                            + path.toAbsolutePath()
            );
        }

        try {
            return new ObjectMapper().readTree(path.toFile());

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not read account test data from " + path,
                    e
            );
        }
    }

    // =========================================================
    // VALIDATE CREATE ACCOUNT RESPONSE
    // =========================================================
    public static void validateCreateAccountResponse(
            Response response
    ) {

        if (response == null) {
            throw new AssertionError(
                    "Create-account response is null."
            );
        }

        int status = response.getStatusCode();

        if (status != 302 && status != 303) {
            throw new AssertionError(
                    "Expected HTTP 302/303 but received "
                            + status
                            + ". Response body:\n"
                            + response.getBody().asString()
            );
        }

        String location = response.getHeader("Location");

        if (location == null || location.trim().isEmpty()) {
            throw new AssertionError(
                    "POST returned a redirect status but no Location header."
            );
        }

        if (!location.contains("/accounts/")) {
            throw new AssertionError(
                    "Redirect did not point to an account record: "
                            + location
            );
        }
    }

    // =========================================================
    // GET CREATED ACCOUNT URL
    // =========================================================
    public static String getCreatedAccountUrl(Response response) {

        if (response == null) {
            throw new IllegalArgumentException(
                    "Response must not be null."
            );
        }

        String location = response.getHeader("Location");

        if (location == null || location.trim().isEmpty()) {
            throw new IllegalStateException(
                    "No Location header was returned by POST /accounts."
            );
        }

        if (location.startsWith("http://")
                || location.startsWith("https://")) {
            return location;
        }

        return BASE_URL
                + (location.startsWith("/") ? location : "/" + location);
    }

    // =========================================================
    // PRINT API RESPONSE DETAILS
    // =========================================================
    public static void printResponseDetails(Response response) {

        System.out.println(
                "\n========== CREATE ACCOUNT API =========="
        );

        if (response == null) {
            System.out.println("Response: null");

        } else {
            System.out.println(
                    "Request: POST " + BASE_URL + CREATE_ACCOUNT_PATH
            );

            System.out.println(
                    "Status Code: " + response.getStatusCode()
            );

            System.out.println(
                    "Content-Type: " + response.getContentType()
            );

            System.out.println(
                    "Location: " + response.getHeader("Location")
            );

            System.out.println(
                    "Response Time: " + response.getTime() + " ms"
            );

            System.out.println(
                    "Response Body: " + response.getBody().asString()
            );
        }

        System.out.println(
                "========================================\n"
        );
    }
}
