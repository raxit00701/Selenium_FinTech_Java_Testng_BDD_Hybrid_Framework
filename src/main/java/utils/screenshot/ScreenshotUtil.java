package utils.screenshot;

import com.assertthat.selenium_shutterbug.core.Capture;
import com.assertthat.selenium_shutterbug.core.PageSnapshot;
import com.assertthat.selenium_shutterbug.core.Shutterbug;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private static final Pattern INTERNAL_POLLING_PATTERN =
            Pattern.compile(INTERNAL_POLLING_LOCATORS, Pattern.CASE_INSENSITIVE);

    /** Marker attribute put on every node the highlight injects, so they can be removed again. */
    private static final String HIGHLIGHT_ATTR = "data-failure-highlight";

    /** Height of the red "TEST FAILED" banner in CSS px (scaled by devicePixelRatio). */
    private static final int BANNER_HEIGHT_PX = 80;

    private static final Pattern QUOTED_LITERAL = Pattern.compile("['\"]([^'\"]+)['\"]");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{3,}");
    private static final Pattern XPATH_TAG = Pattern.compile("^//([a-zA-Z0-9]+)\\[");

    /** Words that are locator/XPath syntax or generic HTML, not hints about the intended element. */
    private static final Set<String> STOP_WORDS = Set.of(
            "xpath", "normalize", "space", "contains", "text", "input", "div", "span", "button",
            "type", "submit", "form", "label", "class", "name", "value", "href", "placeholder",
            "following", "preceding", "sibling", "ancestor", "descendant", "child", "parent",
            "self", "starts", "with", "and", "not", "position", "last", "first", "css",
            "selector", "linktext", "partiallinktext", "tagname");

    /** For these tags a "first visible element of that tag" guess is misleading, so we don't make it. */
    private static final Set<String> FORM_TAGS = Set.of("input", "button", "select", "textarea", "a");

    // =========================================================================
    // BROWSER-SIDE SCRIPTS
    // =========================================================================

    /**
     * Draws the red box + label as real DOM nodes, so the browser positions them. This removes every
     * coordinate/scale conversion (device pixel ratio, stitched scroll height, scrollbar width, ...)
     * that previously made the box drift away from the element.
     *
     * args: [0] element, [1] label text, [2] marker attribute name.  Returns true if drawn.
     */
    private static final String HIGHLIGHT_SCRIPT = """
            var el = arguments[0], label = arguments[1], attr = arguments[2];
            var doc = el.ownerDocument, win = doc.defaultView, root = doc.documentElement;

            // Reveal the element inside any nested scroll container, then reset the window scroll:
            // the full-page capture starts at the top, so page coordinates must be measured at scroll 0
            // (this also keeps position:fixed targets correct).
            try { el.scrollIntoView({block: 'center', inline: 'nearest', behavior: 'instant'}); } catch (e) {}
            try { win.scrollTo({top: 0, left: 0, behavior: 'instant'}); } catch (e) { win.scrollTo(0, 0); }

            var r = el.getBoundingClientRect();
            if (r.width === 0 && r.height === 0) { return false; }

            function apply(node, props) {
              for (var k in props) { node.style.setProperty(k, props[k], 'important'); }
              node.setAttribute(attr, 'true');
            }

            // 4px border sits fully OUTSIDE the element so it never covers the element itself.
            var pad = 4;
            var left = Math.round(r.left + (win.pageXOffset || 0)) - pad;
            var top = Math.round(r.top + (win.pageYOffset || 0)) - pad;
            var width = Math.round(r.width) + pad * 2;
            var height = Math.round(r.height) + pad * 2;

            var box = doc.createElement('div');
            apply(box, {
              'position': 'absolute', 'left': left + 'px', 'top': top + 'px',
              'width': width + 'px', 'height': height + 'px', 'box-sizing': 'border-box',
              'border': '4px solid #ff0000', 'background': 'rgba(255, 0, 0, 0.12)',
              'margin': '0', 'padding': '0', 'transform': 'none', 'opacity': '1',
              'display': 'block', 'pointer-events': 'none', 'z-index': '2147483647'
            });
            root.appendChild(box);

            var tag = doc.createElement('div');
            tag.textContent = label;
            apply(tag, {
              'position': 'absolute', 'left': '0px', 'top': '0px', 'margin': '0',
              'padding': '4px 8px', 'box-sizing': 'border-box', 'white-space': 'nowrap',
              'background': '#c80000', 'color': '#ffffff', 'font': 'bold 13px/16px Arial, sans-serif',
              'pointer-events': 'none', 'z-index': '2147483647'
            });
            root.appendChild(tag);

            var tw = tag.offsetWidth, th = tag.offsetHeight;
            var docW = Math.max(root.scrollWidth, doc.body ? doc.body.scrollWidth : 0);
            var tagLeft = Math.max(0, Math.min(left, docW - tw));
            var tagTop = (top - th >= 0) ? (top - th) : (top + height);
            apply(tag, {'left': tagLeft + 'px', 'top': tagTop + 'px'});
            return true;
            """;

    private static final String REMOVE_HIGHLIGHT_SCRIPT = """
            var d = (arguments[0] && arguments[0].ownerDocument) || document;
            var nodes = d.querySelectorAll('[' + arguments[1] + ']');
            for (var i = 0; i < nodes.length; i++) { nodes[i].parentNode.removeChild(nodes[i]); }
            """;

    /**
     * Scores every visible control against the identifiers in the broken locator and returns the best one.
     * args: [0] phrases (normalized whole identifiers), [1] words, [2] element to skip, [3] xpath tag or null.
     */
    private static final String MATCH_SCRIPT = """
            var phrases = arguments[0], words = arguments[1], lastEl = arguments[2], tag = arguments[3];
            function norm(s) { return (s == null ? '' : String(s)).toLowerCase().replace(/[^a-z0-9]/g, ''); }
            function attr(el, n) { return el.getAttribute ? el.getAttribute(n) : null; }
            function has(list, w) { return list.some(function (a) { return a.indexOf(w) >= 0; }); }

            function score(el) {
              // id / name / test-ids identify an element; placeholder / label / text only describe it.
              var strong = [el.id, el.name, attr(el, 'data-testid'), attr(el, 'data-test'), attr(el, 'data-qa')]
                  .map(norm).filter(Boolean);
              var weak = [el.placeholder, attr(el, 'aria-label'), attr(el, 'title'), attr(el, 'value'),
                          (el.innerText || '').slice(0, 60)].map(norm).filter(Boolean);
              var type = norm(el.type);
              var s = 0;
              phrases.forEach(function (p) {
                strong.forEach(function (a) {
                  if (a === p) { s += 100; }
                  else if (p.length >= 4 && a.length >= 4 && (a.indexOf(p) >= 0 || p.indexOf(a) >= 0)) { s += 40; }
                });
                weak.forEach(function (a) {
                  if (a === p) { s += 60; }
                  else if (p.length >= 4 && a.length >= 4 && a.indexOf(p) >= 0) { s += 20; }
                });
              });
              words.forEach(function (w) {
                if (has(strong, w)) { s += w.length * 2; }
                else if (has(weak, w)) { s += w.length; }
                if (type === w) { s += w.length; }
              });
              if (s > 0 && tag && el.tagName.toLowerCase() === tag) { s += 5; }
              return s;
            }

            var els = document.querySelectorAll(
                'input:not([type="hidden"]), button, select, textarea, a, label, h1, h2, h3, h4, h5, h6, ' +
                '[role="button"], [data-testid], [data-test], [data-qa]');
            var best = null, bestScore = 0;
            for (var i = 0; i < els.length; i++) {
              var el = els[i];
              if (el === lastEl || el.offsetWidth === 0 || el.offsetHeight === 0) { continue; }
              var s = score(el);
              if (s > bestScore) { best = el; bestScore = s; }
            }
            return bestScore >= 4 ? best : null;
            """;

    private static final String FIRST_VISIBLE_TAG_SCRIPT = """
            var els = document.getElementsByTagName(arguments[0]);
            for (var i = 0; i < els.length; i++) {
              if (els[i] !== arguments[1] && els[i].offsetWidth > 0 && els[i].offsetHeight > 0) return els[i];
            }
            return null;
            """;

    private static final String FIRST_HEADING_OR_FORM_SCRIPT = """
            var els = document.querySelectorAll('h1, h2, h3, h4, h5, h6, [role="heading"], form');
            for (var i = 0; i < els.length; i++) {
              if (els[i].offsetWidth > 0 && els[i].offsetHeight > 0) return els[i];
            }
            return null;
            """;

    private static final String ERROR_ALERT_SCRIPT = """
            var selectors = ['[role="alert"]', '.alert-danger', '.alert-error', '.flash-error',
                             '.error-message', '.toast-error', '.invalid-feedback'];
            for (var s = 0; s < selectors.length; s++) {
              var els = document.querySelectorAll(selectors[s]);
              for (var i = 0; i < els.length; i++) {
                var el = els[i];
                if (el.offsetWidth > 0 && el.offsetHeight > 0 && el.innerText.trim().length > 0) return el;
              }
            }
            // Also check visible banners containing error keywords. Pick the SMALLEST match so we mark
            // the message itself and not a wide wrapper around it.
            var keywords = ['invalid username', 'invalid password', 'need to sign in', 'error', 'failed'];
            var best = null, bestArea = Infinity;
            var nodes = document.querySelectorAll('div, p, span');
            for (var j = 0; j < nodes.length; j++) {
              var d = nodes[j];
              if (d.offsetWidth > 100 && d.offsetHeight > 20 && d.offsetHeight < 120 && d.children.length <= 2) {
                var txt = (d.innerText || '').trim().toLowerCase();
                if (keywords.some(function (k) { return txt.indexOf(k) >= 0; })) {
                  var area = d.offsetWidth * d.offsetHeight;
                  if (area < bestArea) { best = d; bestArea = area; }
                }
              }
            }
            return best;
            """;

    // =========================================================================
    // PUBLIC API
    // =========================================================================

    public static String captureFullPage(
            WebDriver driver,
            String testName,
            WebElement lastElement,
            By failedLocator,
            String errorMessage) {

        JavascriptExecutor js = (JavascriptExecutor) driver;
        WebElement elementToHighlight = null;
        boolean highlightInjected = false;

        try {
            new File(SCREENSHOT_DIR).mkdirs();
            String highlightReason = "Failure Target";

            // Internal polling locators (flash-error, notification, ...) are not the real failure target
            if (failedLocator != null && INTERNAL_POLLING_PATTERN.matcher(failedLocator.toString()).find()) {
                failedLocator = null;
            }

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

                // 1B. Smart Fuzzy Recovery: score visible controls against the identifiers in the
                // broken locator (e.g. #user_password -> id/name "user_password" / "password")
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
                elementToHighlight = (WebElement) js.executeScript(FIRST_HEADING_OR_FORM_SCRIPT);
                if (elementToHighlight != null) {
                    highlightReason = (failedLocator != null)
                            ? "Element Not Found on Page: " + failedLocator
                            : "Failure Context Area";
                }
            }

            // 6. Draw the highlight INSIDE the live page (browser positions it on the real element)
            if (elementToHighlight != null) {
                highlightInjected = injectHighlight(js, elementToHighlight, highlightReason);
            }

            // 7. Capture full-page screenshot via Shutterbug (highlight is now part of the page)
            double dpr = devicePixelRatio(js);
            try {
                PageSnapshot snapshot = Shutterbug.shootPage(driver, Capture.FULL_SCROLL, true);
                snapshot.withName(testName).save(SCREENSHOT_DIR);
            } finally {
                if (highlightInjected) {
                    removeHighlight(js, elementToHighlight);
                }
            }

            File screenshotFile = new File(SCREENSHOT_DIR + testName + ".png");
            BufferedImage source = ImageIO.read(screenshotFile);

            if (source == null) {
                throw new IllegalStateException("Unable to read generated screenshot.");
            }

            // 8. Add the failure banner ABOVE the page (extra canvas) so it never covers page content
            BufferedImage annotated = addFailureBanner(source, testName, errorMessage, dpr);
            ImageIO.write(annotated, "png", screenshotFile);
            return screenshotFile.getAbsolutePath();

        } catch (Exception e) {
            e.printStackTrace();
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

    // =========================================================================
    // HIGHLIGHT / BANNER HELPERS
    // =========================================================================

    private static boolean injectHighlight(JavascriptExecutor js, WebElement element, String label) {
        try {
            String text = label.length() > 120 ? label.substring(0, 117) + "..." : label;
            Object drawn = js.executeScript(HIGHLIGHT_SCRIPT, element, text, HIGHLIGHT_ATTR);
            return Boolean.TRUE.equals(drawn);
        } catch (Exception e) {
            System.err.println("Highlight injection failed: " + e.getMessage());
            return false;
        }
    }

    private static void removeHighlight(JavascriptExecutor js, WebElement element) {
        try {
            js.executeScript(REMOVE_HIGHLIGHT_SCRIPT, element, HIGHLIGHT_ATTR);
        } catch (Exception ignored) {
        }
    }

    private static double devicePixelRatio(JavascriptExecutor js) {
        try {
            Object value = js.executeScript("return window.devicePixelRatio || 1;");
            if (value instanceof Number number) {
                return Math.max(1.0, number.doubleValue());
            }
        } catch (Exception ignored) {
        }
        return 1.0;
    }

    private static BufferedImage addFailureBanner(
            BufferedImage source, String testName, String errorMessage, double scale) {

        int bannerHeight = (int) Math.round(BANNER_HEIGHT_PX * scale);
        BufferedImage out = new BufferedImage(
                source.getWidth(), source.getHeight() + bannerHeight, BufferedImage.TYPE_INT_RGB);

        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.drawImage(source, 0, bannerHeight, null);

            g.setColor(new Color(180, 0, 0));
            g.fillRect(0, 0, out.getWidth(), bannerHeight);

            String message = (errorMessage == null || errorMessage.isBlank())
                    ? "Test Failed"
                    : errorMessage.replaceAll("\\s+", " ");

            int padding = (int) Math.round(20 * scale);
            int maxTextWidth = out.getWidth() - 2 * padding;

            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, (int) Math.round(18 * scale)));
            g.drawString(fitText(g, "TEST FAILED: " + testName, maxTextWidth),
                    padding, (int) Math.round(30 * scale));

            g.setFont(new Font("SansSerif", Font.PLAIN, (int) Math.round(13 * scale)));
            g.drawString(fitText(g, message, maxTextWidth),
                    padding, (int) Math.round(58 * scale));
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Trims text with "..." so it fits the given pixel width in the graphics' current font. */
    private static String fitText(Graphics2D g, String text, int maxWidth) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int end = text.length();
        while (end > 0 && fm.stringWidth(text.substring(0, end) + ellipsis) > maxWidth) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    // =========================================================================
    // ELEMENT DISCOVERY HELPERS
    // =========================================================================

    /**
     * Detects visible error banners/alerts on the page (e.g., "Invalid username or password.",
     * role="alert", .alert-danger, .error, etc.) when a login or form submission fails.
     */
    private static WebElement findVisibleErrorAlertOnPage(JavascriptExecutor js) {
        try {
            return (WebElement) js.executeScript(ERROR_ALERT_SCRIPT);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Works out which element a broken locator was meant to hit. Identifiers are pulled out of the
     * locator (for XPath only the quoted literals count), then every visible control is scored in the
     * browser: an exact id/name match beats a partial one, id/name beat placeholder/label/text, and
     * longer matching words beat shorter ones. So "#user_password" lands on the password field and not
     * on the first field that merely contains "user". Runs via JS so ElementTracker isn't polluted.
     */
    private static WebElement findIntendedElementFromBrokenLocator(
            JavascriptExecutor js,
            By failedLocator,
            WebElement lastElement) {

        try {
            String raw = failedLocator.toString();
            int colonIdx = raw.indexOf(':');
            String selectorPart = (colonIdx >= 0) ? raw.substring(colonIdx + 1).trim() : raw;
            boolean isXpath = raw.startsWith("By.xpath:");

            Set<String> phrases = new LinkedHashSet<>();
            Set<String> words = new LinkedHashSet<>();
            String identifierSource = selectorPart;
            String tag = null;

            if (isXpath) {
                Matcher tagMatcher = XPATH_TAG.matcher(selectorPart);
                if (tagMatcher.find()) {
                    tag = tagMatcher.group(1).toLowerCase();
                }

                // Only quoted literals carry meaning ('user_password', 'Sign in'); the rest is XPath syntax
                StringBuilder literals = new StringBuilder();
                Matcher literalMatcher = QUOTED_LITERAL.matcher(selectorPart);
                while (literalMatcher.find()) {
                    addPhrase(phrases, literalMatcher.group(1));
                    literals.append(' ').append(literalMatcher.group(1));
                }
                if (literals.length() > 0) {
                    identifierSource = literals.toString();
                }
            }

            Matcher identifierMatcher = IDENTIFIER.matcher(identifierSource);
            while (identifierMatcher.find()) {
                String identifier = identifierMatcher.group();
                addPhrase(phrases, identifier);

                // split on _ - and camelCase boundaries: "userPassword" / "user_password" -> user, password
                for (String part : identifier.split("(?<=[a-z0-9])(?=[A-Z])|[_-]+")) {
                    String word = part.toLowerCase();
                    if (word.length() >= 3 && !STOP_WORDS.contains(word)) {
                        words.add(word);
                    }
                }
            }

            if (!phrases.isEmpty() || !words.isEmpty()) {
                WebElement match = (WebElement) js.executeScript(
                        MATCH_SCRIPT,
                        new ArrayList<>(phrases),
                        new ArrayList<>(words),
                        lastElement,
                        tag);
                if (match != null) {
                    return match;
                }
            }

            // Last resort for non-form tags only (e.g. //h5[...] -> first visible h5).
            // For input/button/etc. a blind "first one" is usually the WRONG field, so skip it.
            if (tag != null && !FORM_TAGS.contains(tag)) {
                return (WebElement) js.executeScript(FIRST_VISIBLE_TAG_SCRIPT, tag, lastElement);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void addPhrase(Set<String> phrases, String value) {
        String normalized = value.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (normalized.length() >= 3 && !STOP_WORDS.contains(normalized)) {
            phrases.add(normalized);
        }
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
}