package utils.screenshot;

import com.assertthat.selenium_shutterbug.core.Capture;
import com.assertthat.selenium_shutterbug.core.PageSnapshot;
import com.assertthat.selenium_shutterbug.core.Shutterbug;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

public class ScreenshotUtil {

    private static final String SCREENSHOT_DIR =
            System.getProperty("user.dir")
                    + File.separator + "test-output"
                    + File.separator + "screenshots"
                    + File.separator;

    // Exclude internal framework locators from being treated as the target if a post-click wait failed
    private static final String INTERNAL_POLLING_LOCATORS =
            "flash-error|alert-danger|notification";

    public static String captureFullPage(
            WebDriver driver,
            String testName,
            WebElement lastElement,
            By failedLocator,
            String errorMessage) {

        try {
            new File(SCREENSHOT_DIR).mkdirs();
            JavascriptExecutor js = (JavascriptExecutor) driver;
            WebElement elementToHighlight = null;
            String highlightReason = "Failure Target";

            // =========================================================================
            // STEP 1: IF A SPECIFIC LOCATOR FAILED, HIGHLIGHT IT OR ITS INTENDED TARGET
            // =========================================================================
            if (failedLocator != null) {
                String cleanLocatorText = failedLocator.toString();

                // 1A. Check if the failedLocator is actually visible on screen right now
                // (e.g., if it failed an assertion or visibility check earlier)
                try {
                    List<WebElement> exactMatches = driver.findElements(failedLocator);
                    for (WebElement el : exactMatches) {
                        if (el.isDisplayed() && !el.equals(lastElement)) {
                            elementToHighlight = el;
                            highlightReason = "Failed Locator: " + cleanLocatorText;
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }

                // 1B. Smart Fuzzy Recovery: Strip typos (like #user_password -> user_password)
                // and find the intended element on the page!
                if (elementToHighlight == null) {
                    elementToHighlight = findIntendedElementFromBrokenLocator(js, failedLocator, lastElement);
                    if (elementToHighlight != null) {
                        highlightReason = "Not Found (" + cleanLocatorText + ") - Intended Target";
                        System.out.println("Smart Recovery matched intended element for broken locator: " + cleanLocatorText);
                    }
                }
            }

            // =========================================================================
            // STEP 2: CHECK IF AN ERROR / ALERT BANNER APPEARED ON THE PAGE
            // (e.g., "Invalid username or password." after clicking Sign In)
            // =========================================================================
            if (elementToHighlight == null) {
                WebElement errorBanner = findVisibleErrorAlertOnPage(js);
                if (errorBanner != null) {
                    elementToHighlight = errorBanner;
                    highlightReason = (failedLocator != null)
                            ? "UI Error Displayed (While waiting for: " + failedLocator + ")"
                            : "UI Error / Alert Banner Detected";
                    System.out.println("Highlighted visible UI error alert banner on page.");
                }
            }

            // =========================================================================
            // STEP 3: NEXT SIBLING INPUT FALLBACK FOR BROKEN FORM LOCATORS
            // =========================================================================
            if (elementToHighlight == null && failedLocator != null && lastElement != null) {
                elementToHighlight = findNextFormElementAfter(js, lastElement);
                if (elementToHighlight != null) {
                    highlightReason = "Not Found (" + failedLocator + ") - Expected Here";
                }
            }

            // =========================================================================
            // STEP 4: IF NO LOCATOR FAILED (PURE ASSERTION FAILURE), USE LAST ELEMENT
            // =========================================================================
            if (elementToHighlight == null && failedLocator == null && lastElement != null) {
                try {
                    if (lastElement.isDisplayed()) {
                        elementToHighlight = lastElement;
                        highlightReason = "Last Interacted Element";
                    }
                } catch (Exception ignored) {
                }
            }

            // =========================================================================
            // STEP 5: FALLBACK TO PAGE HEADING OR FORM CONTAINER
            // =========================================================================
            if (elementToHighlight == null) {
                elementToHighlight = (WebElement) js.executeScript(
                        "var els = document.querySelectorAll('h1, h2, h3, h4, h5, h6, [role=\"heading\"], form');" +
                        "for (var i = 0; i < els.length; i++) {" +
                        "  if (els[i].offsetWidth > 0 && els[i].offsetHeight > 0) return els[i];" +
                        "}" +
                        "return null;"
                );
                if (elementToHighlight != null) {
                    highlightReason = (failedLocator != null)
                            ? "Element Not Found on Page: " + failedLocator
                            : "Failure Context Area";
                }
            }

            // 6. Extract coordinates safely using List<Number>
            double[] coordinates = null;
            if (elementToHighlight != null) {
                try {
                    js.executeScript("arguments[0].scrollIntoView({block: 'center', inline: 'nearest'});", elementToHighlight);

                    @SuppressWarnings("unchecked")
                    List<Number> rawCoords = (List<Number>) js.executeScript(
                            "var r = arguments[0].getBoundingClientRect();" +
                            "return [" +
                            "r.left + window.scrollX, " +
                            "r.top + window.scrollY, " +
                            "r.width, " +
                            "r.height, " +
                            "Math.max(document.documentElement.scrollWidth, document.body ? document.body.scrollWidth : 0), " +
                            "Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0)" +
                            "];",
                            elementToHighlight
                    );

                    if (rawCoords != null && rawCoords.size() == 6) {
                        coordinates = new double[6];
                        for (int i = 0; i < 6; i++) {
                            coordinates[i] = rawCoords.get(i).doubleValue();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Coordinate extraction failed: " + e.getMessage());
                }
            }

            // 7. Capture full-page screenshot via Shutterbug
            PageSnapshot snapshot = Shutterbug.shootPage(driver, Capture.FULL_SCROLL, true);
            snapshot.withName(testName).save(SCREENSHOT_DIR);

            File screenshotFile = new File(SCREENSHOT_DIR + testName + ".png");
            BufferedImage image = ImageIO.read(screenshotFile);

            if (image == null) {
                throw new IllegalStateException("Unable to read generated screenshot.");
            }

            Graphics2D graphics = image.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // 8. Draw red rectangle + callout label directly onto final PNG
            if (coordinates != null && coordinates[2] > 0 && coordinates[3] > 0) {
                double scaleX = coordinates[4] > 0 ? (double) image.getWidth() / coordinates[4] : 1.0;
                double scaleY = coordinates[5] > 0 ? (double) image.getHeight() / coordinates[5] : 1.0;

                int x = Math.max(0, (int) Math.round(coordinates[0] * scaleX));
                int y = Math.max(0, (int) Math.round(coordinates[1] * scaleY));
                int width = Math.max(20, (int) Math.round(coordinates[2] * scaleX));
                int height = Math.max(20, (int) Math.round(coordinates[3] * scaleY));

                // Semi-transparent red fill
                graphics.setColor(new Color(255, 0, 0, 35));
                graphics.fillRect(x, y, width, height);

                // Solid 4px red border
                graphics.setColor(Color.RED);
                graphics.setStroke(new BasicStroke(4));
                graphics.drawRect(x, y, width, height);

                // Red label tag above or below the box
                Font labelFont = new Font("SansSerif", Font.BOLD, 13);
                graphics.setFont(labelFont);
                FontMetrics fm = graphics.getFontMetrics();
                int labelWidth = fm.stringWidth(highlightReason) + 16;
                int labelHeight = 24;
                int labelY = (y - labelHeight > 85) ? (y - labelHeight) : (y + height + 4);

                graphics.setColor(new Color(200, 0, 0));
                graphics.fillRect(x, labelY, labelWidth, labelHeight);
                graphics.setColor(Color.WHITE);
                graphics.drawString(highlightReason, x + 8, labelY + 17);
            }

            // 9. Add top failure banner
            String message = (errorMessage == null || errorMessage.isBlank())
                    ? "Test Failed"
                    : errorMessage.replaceAll("\\s+", " ");

            if (message.length() > 160) {
                message = message.substring(0, 160) + "...";
            }

            int bannerHeight = 80;
            graphics.setColor(new Color(180, 0, 0));
            graphics.fillRect(0, 0, image.getWidth(), bannerHeight);

            graphics.setColor(Color.WHITE);
            graphics.setFont(new Font("SansSerif", Font.BOLD, 18));
            graphics.drawString("TEST FAILED: " + testName, 20, 30);

            graphics.setFont(new Font("SansSerif", Font.PLAIN, 13));
            graphics.drawString(message, 20, 58);

            graphics.dispose();

            ImageIO.write(image, "png", screenshotFile);
            return screenshotFile.getAbsolutePath();

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Detects visible error banners/alerts on the page (e.g., "Invalid username or password.",
     * role="alert", .alert-danger, .error, etc.) when a login or form submission fails.
     */
    private static WebElement findVisibleErrorAlertOnPage(JavascriptExecutor js) {
        try {
            return (WebElement) js.executeScript(
                    "var selectors = [" +
                    "  '[role=\"alert\"]', '.alert-danger', '.alert-error', '.flash-error', " +
                    "  '.error-message', '.toast-error', '.invalid-feedback'" +
                    "];" +
                    "for (var s of selectors) {" +
                    "  var els = document.querySelectorAll(s);" +
                    "  for (var el of els) {" +
                    "    if (el.offsetWidth > 0 && el.offsetHeight > 0 && el.innerText.trim().length > 0) return el;" +
                    "  }" +
                    "}" +
                    "// Also check any visible banner containing error keywords\n" +
                    "var divs = document.querySelectorAll('div, p, span');" +
                    "for (var d of divs) {" +
                    "  if (d.offsetWidth > 100 && d.offsetHeight > 20 && d.offsetHeight < 120 && d.children.length <= 2) {" +
                    "    var txt = (d.innerText || '').trim().toLowerCase();" +
                    "    if (txt.includes('invalid username') || txt.includes('invalid password') || " +
                    "        txt.includes('need to sign in') || txt.includes('error') || txt.includes('failed')) {" +
                    "      return d;" +
                    "    }" +
                    "  }" +
                    "}" +
                    "return null;"
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Extracts meaningful tokens from a broken locator (e.g., By.id("#user_password") -> "user_password", "password")
     * and searches the DOM via JS (so ElementTracker isn't polluted) for the intended target.
     */
    private static WebElement findIntendedElementFromBrokenLocator(
            JavascriptExecutor js,
            By failedLocator,
            WebElement lastElement) {

        try {
            String raw = failedLocator.toString();
            int colonIdx = raw.indexOf(':');
            String selectorPart = (colonIdx >= 0) ? raw.substring(colonIdx + 1).trim() : raw;

            // 1. If XPath starts with a specific tag like //h5[...], check visible tags of that type via JS
            if (raw.startsWith("By.xpath:")) {
                Matcher tagMatcher = Pattern.compile("^//([a-zA-Z0-9]+)\\[").matcher(selectorPart);
                if (tagMatcher.find()) {
                    String tagName = tagMatcher.group(1);
                    WebElement tagMatch = (WebElement) js.executeScript(
                            "var els = document.getElementsByTagName(arguments[0]);" +
                            "for (var i = 0; i < els.length; i++) {" +
                            "  if (els[i] !== arguments[1] && els[i].offsetWidth > 0 && els[i].offsetHeight > 0) return els[i];" +
                            "}" +
                            "return null;",
                            tagName,
                            lastElement
                    );
                    if (tagMatch != null) {
                        return tagMatch;
                    }
                }
            }

            // 2. Extract alphanumeric words/identifiers from the broken locator
            List<String> tokens = new ArrayList<>();
            String cleaned = selectorPart.replaceAll("^[^a-zA-Z0-9_]+|[^a-zA-Z0-9_]+$", "");
            if (cleaned.length() >= 3) {
                tokens.add(cleaned.toLowerCase());
            }

            Matcher wordMatcher = Pattern.compile("[a-zA-Z]{3,}").matcher(selectorPart);
            while (wordMatcher.find()) {
                String word = wordMatcher.group().toLowerCase();
                if (!List.of("xpath", "normalize", "space", "contains", "text", "input", "div", "span", "button", "type", "submit").contains(word)
                        && !tokens.contains(word)) {
                    tokens.add(word);
                }
            }

            // 3. Search the DOM via JS for any visible element matching the cleaned ID, name, type, or placeholder
            for (String token : tokens) {
                WebElement match = (WebElement) js.executeScript(
                        "var tok = arguments[0];" +
                        "var lastEl = arguments[1];" +
                        "var els = document.querySelectorAll('input, button, select, textarea, a, h1, h2, h3, h4, h5, h6');" +
                        "for (var i = 0; i < els.length; i++) {" +
                        "  var el = els[i];" +
                        "  if (el === lastEl || el.offsetWidth === 0 || el.offsetHeight === 0) continue;" +
                        "  var id = (el.id || '').toLowerCase();" +
                        "  var name = (el.name || '').toLowerCase();" +
                        "  var type = (el.type || '').toLowerCase();" +
                        "  var ph = (el.placeholder || '').toLowerCase();" +
                        "  if (id === tok || name === tok || id.includes(tok) || name.includes(tok) || type === tok || ph.includes(tok)) {" +
                        "    return el;" +
                        "  }" +
                        "}" +
                        "return null;",
                        token,
                        lastElement
                );

                if (match != null) {
                    return match;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static WebElement findNextFormElementAfter(JavascriptExecutor js, WebElement lastElement) {
        try {
            return (WebElement) js.executeScript(
                    "var lastEl = arguments[0];" +
                    "var controls = Array.from(document.querySelectorAll('input:not([type=\"hidden\"]), button, select, textarea'));" +
                    "var idx = controls.indexOf(lastEl);" +
                    "if (idx >= 0 && idx + 1 < controls.length) {" +
                    "  var nextEl = controls[idx + 1];" +
                    "  if (nextEl.offsetWidth > 0 && nextEl.offsetHeight > 0) return nextEl;" +
                    "}" +
                    "return null;",
                    lastElement
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String captureFullPage(
            WebDriver driver,
            String testName,
            WebElement lastElement,
            String errorMessage) {

        return captureFullPage(driver, testName, lastElement, null, errorMessage);
    }
}