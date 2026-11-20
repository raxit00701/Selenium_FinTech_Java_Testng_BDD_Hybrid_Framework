
package API;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class account {

    private static final String BASE_URI = loadBaseUrl();
    private static final String NEW_ACCOUNT_ENDPOINT = "/accounts/new";

    private account() {
    }

    private static String loadBaseUrl() {

        // Priority 1: Environment variable (GitHub Actions / CI)
        String baseUrl = System.getenv("API_BASE_URL");

        if (baseUrl != null && !baseUrl.trim().isEmpty()) {
            System.out.println("API_BASE_URL loaded from environment variable.");
            return normalizeUrl(baseUrl);
        }

        // Priority 2: Local .env file
        String envPath = "src/main/java/env/.env";
        Properties properties = new Properties();

        try (FileInputStream input = new FileInputStream(envPath)) {

            properties.load(input);

            baseUrl = properties.getProperty("API_BASE_URL");

            if (baseUrl == null || baseUrl.trim().isEmpty()) {
                throw new IllegalStateException(
                    "API_BASE_URL is missing in environment variables and .env file."
                );
            }

            System.out.println("API_BASE_URL loaded from local .env file.");

            return normalizeUrl(baseUrl);

        } catch (IOException e) {

            throw new IllegalStateException(
                "Unable to load API_BASE_URL. "
                + "Configure API_BASE_URL as an environment variable "
                + "or provide " + envPath,
                e
            );
        }
    }

    private static String normalizeUrl(String baseUrl) {
        return baseUrl.trim().replaceAll("/+$", "");
    }

    public static Response getNewAccountPage(WebDriver driver) {

        if (driver == null) {
            throw new IllegalArgumentException("WebDriver must not be null.");
        }

        String currentUrl = driver.getCurrentUrl();

        if (currentUrl == null || !currentUrl.startsWith(BASE_URI)) {
            throw new IllegalStateException(
                "Browser must be on the configured API_BASE_URL."
            );
        }

        Map<String, String> browserCookies = new HashMap<>();

        for (Cookie cookie : driver.manage().getCookies()) {
            browserCookies.put(cookie.getName(), cookie.getValue());
        }

        RequestSpecification request = RestAssured.given()
                .baseUri(BASE_URI)
                .accept("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Referer", currentUrl)
                .cookies(browserCookies);

        return request
                .when()
                .get(NEW_ACCOUNT_ENDPOINT)
                .then()
                .extract()
                .response();
    }

    public static void validateNewAccountPage(Response response) {

        if (response == null) {
            throw new AssertionError("API response is null.");
        }

        response.then()
                .statusCode(200)
                .contentType(ContentType.HTML);

        String html = response.getBody().asString();

        if (html == null || html.trim().isEmpty()) {
            throw new AssertionError("HTML response is empty.");
        }

        String normalizedHtml = html.toLowerCase();

        if (!normalizedHtml.contains("<html")
                && !normalizedHtml.contains("<!doctype html")) {
            throw new AssertionError("Invalid HTML response.");
        }
    }

    public static void printResponseDetails(Response response) {

        if (response == null) {
            System.out.println("Response is null");
            return;
        }

        System.out.println("\n========== ACCOUNT API RESPONSE ==========");
        System.out.println("Request: GET " + BASE_URI + NEW_ACCOUNT_ENDPOINT);
        System.out.println("Status Code: " + response.getStatusCode());
        System.out.println("Content-Type: " + response.getContentType());
        System.out.println("Response Time: " + response.getTime() + " ms");
        System.out.println("Response Body Length: "
                + response.getBody().asString().length());
        System.out.println("===========================================\n");
    }
}
