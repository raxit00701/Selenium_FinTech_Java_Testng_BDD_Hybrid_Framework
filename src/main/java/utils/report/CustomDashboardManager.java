package utils.report;

import io.github.cdimascio.dotenv.Dotenv;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CustomDashboardManager {

    private static final String REPORT_DIR = System.getProperty("user.dir")
            + File.separator + "test-output"
            + File.separator + "dashboard"
            + File.separator;

    private static final String REPORT_FILE_PATH = REPORT_DIR + "ExecutionDashboard.html";

    // Folders used to find evidence files, whatever base the caller used for the path
    private static final Path DASH_DIR = Path.of(REPORT_DIR).toAbsolutePath().normalize();
    private static final Path OUTPUT_DIR = DASH_DIR.getParent() != null ? DASH_DIR.getParent() : DASH_DIR;
    private static final Path PROJECT_DIR = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

    private static final Instant SUITE_START_TIME = Instant.now();
    private static final String RUN_TIMESTAMP =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm:ss a"));

    // Size limits that keep the HTML light on very large runs
    private static final int MAX_MESSAGE_CHARS = 3_000;
    private static final int MAX_TRACE_CHARS = 12_000;
    private static final int MAX_LOG_BYTES = 32_000;
    private static final long LOG_EMBED_BUDGET_CHARS = 6_000_000L;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z0-9_]+)\\}\\}");

    // Thread-safe lock-free queue for parallel execution
    private static final Queue<TestRecord> RESULTS = new ConcurrentLinkedQueue<>();

    private static final Dotenv dotenv = Dotenv.configure()
            .directory("src/main/java/env")
            .filename(".env")
            .ignoreIfMissing()
            .load();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(CustomDashboardManager::generateDashboard));
    }

    private CustomDashboardManager() {}

    public record TestRecord(
            String testId,
            String className,
            String methodName,
            String status,       // PASS, FAIL, SKIP
            long durationMs,
            String startTime,
            String errorMessage,
            String stackTrace,
            String failedLocator,
            String screenshotPath,
            String videoPath,
            String logPath
    ) {}

    public static void recordTest(
            String testId,
            String className,
            String methodName,
            String status,
            long durationMs,
            String startTime,
            Throwable throwable,
            String failedLocator,
            String screenshotRelPath,
            String videoRelPath,
            String logRelPath) {

        String errMsg = "";
        String trace = "";

        if (throwable != null) {
            errMsg = throwable.getMessage() != null ? throwable.getMessage() : throwable.toString();
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            trace = sw.toString();
        }

        RESULTS.add(new TestRecord(
                testId,
                className,
                methodName,
                normalizeStatus(status),
                durationMs,
                startTime,
                cap(errMsg, MAX_MESSAGE_CHARS),
                cap(trace, MAX_TRACE_CHARS),
                failedLocator != null ? failedLocator : "N/A",
                screenshotRelPath != null ? screenshotRelPath : "",
                videoRelPath != null ? videoRelPath : "",
                logRelPath != null ? logRelPath : ""
        ));
    }

    public static void clearRecords() {
        RESULTS.clear();
    }

    public static synchronized void generateDashboard() {
        try {
            Files.createDirectories(Path.of(REPORT_DIR));

            List<TestRecord> records = new ArrayList<>(RESULTS);
            int total = records.size();
            long passed = records.stream().filter(r -> "PASS".equals(r.status())).count();
            long failed = records.stream().filter(r -> "FAIL".equals(r.status())).count();
            long skipped = records.stream().filter(r -> "SKIP".equals(r.status())).count();
            long totalMs = records.stream().mapToLong(TestRecord::durationMs).sum();
            long suiteDurationMs = Duration.between(SUITE_START_TIME, Instant.now()).toMillis();

            String tester = getConfigValue("TESTER_NAME", "Raxit Sharma");
            String user = tester.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");

            Map<String, String> v = new LinkedHashMap<>();
            v.put("TESTER", h(tester));
            v.put("TESTER_USER", h(user.isEmpty() ? "operator" : user));
            v.put("BROWSER", h(getConfigValue("BROWSER", "chrome").toUpperCase(Locale.ROOT)));
            v.put("ENV", h(getConfigValue("TEST_ENV", "QA")));
            v.put("BASEURL", h(getConfigValue("BASE_URL", "Configured via .env")));
            v.put("MODE", h(System.getProperty("execution.mode", "serial").toUpperCase(Locale.ROOT)));
            v.put("THREADS", h(System.getProperty("thread.count", "1")));
            v.put("OS", h(System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")"));
            v.put("JAVA", h(System.getProperty("java.version")));
            v.put("RUN", h(RUN_TIMESTAMP));
            v.put("TOTAL", String.valueOf(total));
            v.put("PASSED", String.valueOf(passed));
            v.put("FAILED", String.valueOf(failed));
            v.put("SKIPPED", String.valueOf(skipped));
            v.put("PASS_PCT", pct(passed, total));
            v.put("FAIL_PCT", pct(failed, total));
            v.put("SKIP_PCT", pct(skipped, total));
            v.put("RATE", pct(passed, total));
            v.put("DURATION", formatDuration(suiteDurationMs));
            v.put("AVG", formatDuration(total > 0 ? totalMs / total : 0));
            v.put("CSS", TPL_CSS);
            v.put("JS", TPL_JS);
            v.put("DATA", buildJsonArray(records));
            v.put("META", "{\"run\":" + jsonStr(RUN_TIMESTAMP) + ",\"total\":" + total + "}");

            Files.writeString(Path.of(REPORT_FILE_PATH), render(v), StandardCharsets.UTF_8);
            System.out.println("Custom Execution Dashboard generated: " + REPORT_FILE_PATH + " (" + total + " tests)");

        } catch (Exception e) {
            System.err.println("Failed to generate custom HTML dashboard: " + e);
        }
    }

    // =====================================================================
    // Template rendering (single pass, so inserted data is never re-scanned)
    // =====================================================================

    private static String render(Map<String, String> vars) {
        Matcher m = PLACEHOLDER.matcher(TPL_BODY);
        StringBuilder sb = new StringBuilder(TPL_BODY.length() + TPL_CSS.length() + TPL_JS.length()
                + vars.get("DATA").length() + 4096);
        while (m.find()) {
            String value = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // =====================================================================
    // JSON building + evidence resolution (supports multiple screenshots)
    // =====================================================================

    private static String buildJsonArray(List<TestRecord> records) {
        StringBuilder sb = new StringBuilder(Math.max(1024, records.size() * 600)).append('[');
        long logBudget = LOG_EMBED_BUDGET_CHARS;
        boolean first = true;

        for (TestRecord r : records) {
            if (!first) {
                sb.append(',');
            }
            first = false;

            Map<String, String> missing = new LinkedHashMap<>();
            List<Path> ssFiles = locateScreenshotsOrRecord(r.screenshotPath(), "ss", missing);
            Path primarySs = ssFiles.isEmpty() ? null : ssFiles.get(0);
            Path vidFile = locateOrRecord(r.videoPath(), "vid", missing);
            Path logFile = locateOrRecord(r.logPath(), "log", missing);

            String logText = "";
            long logBytes = -1;
            if (logFile != null) {
                logBytes = fileSize(logFile);
                if (logBudget > 0 && logBytes > 0) {
                    logText = readTail(logFile, MAX_LOG_BYTES);
                    logBudget -= logText.length();
                }
            }

            sb.append('{')
              .append("\"id\":").append(jsonStr(r.testId())).append(',')
              .append("\"cls\":").append(jsonStr(r.className())).append(',')
              .append("\"method\":").append(jsonStr(r.methodName())).append(',')
              .append("\"status\":").append(jsonStr(r.status())).append(',')
              .append("\"duration\":").append(r.durationMs()).append(',')
              .append("\"time\":").append(jsonStr(r.startTime())).append(',')
              .append("\"err\":").append(jsonStr(r.errorMessage())).append(',')
              .append("\"trace\":").append(jsonStr(r.stackTrace())).append(',')
              .append("\"locator\":").append(jsonStr(r.failedLocator())).append(',')
              .append("\"ss\":").append(jsonStr(primarySs == null ? "" : toUrl(primarySs))).append(',')
              .append("\"ssList\":").append(toUrlArrayJson(ssFiles)).append(',')
              .append("\"vid\":").append(jsonStr(vidFile == null ? "" : toUrl(vidFile))).append(',')
              .append("\"log\":").append(jsonStr(logFile == null ? "" : toUrl(logFile))).append(',')
              .append("\"logTxt\":").append(jsonStr(logText)).append(',')
              .append("\"logBytes\":").append(logBytes).append(',')
              .append("\"miss\":").append(missJson(missing))
              .append('}');
        }
        return sb.append(']').toString();
    }

    /** Resolves one or more comma-separated screenshot paths. */
    private static List<Path> locateScreenshotsOrRecord(String rawCsv, String key, Map<String, String> missing) {
        List<Path> found = new ArrayList<>();
        if (rawCsv == null || rawCsv.isBlank()) {
            return found;
        }

        String[] parts = rawCsv.split(",");
        List<String> notFound = new ArrayList<>();

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path p = locate(trimmed);
            if (p != null) {
                found.add(p);
            } else {
                notFound.add(trimmed);
            }
        }

        if (!notFound.isEmpty() && found.isEmpty()) {
            missing.put(key, String.join(", ", notFound));
        }
        return found;
    }

    private static String toUrlArrayJson(List<Path> files) {
        if (files == null || files.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jsonStr(toUrl(files.get(i))));
        }
        return sb.append(']').toString();
    }

    private static Path locateOrRecord(String raw, String key, Map<String, String> missing) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path p = locate(raw);
        if (p == null) {
            missing.put(key, raw);
        }
        return p;
    }

    /** Finds an evidence file whether the caller gave a path relative to the dashboard, test-output, the project, or absolute. */
    private static Path locate(String raw) {
        String s = raw.trim();
        try {
            if (s.regionMatches(true, 0, "file:", 0, 5)) {
                Path p = Path.of(URI.create(s)).normalize();
                return Files.isRegularFile(p) ? p : null;
            }
            Path p = Path.of(s.replace('\\', '/'));
            List<Path> candidates = new ArrayList<>();
            if (p.isAbsolute()) {
                candidates.add(p);
            } else {
                candidates.add(DASH_DIR.resolve(p));
                candidates.add(OUTPUT_DIR.resolve(p));
                candidates.add(PROJECT_DIR.resolve(p));
            }
            for (Path c : candidates) {
                Path n = c.normalize();
                if (Files.isRegularFile(n)) {
                    return n;
                }
            }
        } catch (RuntimeException ignored) {
            // invalid path syntax: treated as missing
        }
        return null;
    }

    /** Relative, URL-encoded link from the dashboard folder to the file. */
    private static String toUrl(Path file) {
        Path rel;
        try {
            rel = DASH_DIR.relativize(file);
        } catch (IllegalArgumentException e) {
            return file.toUri().toString();
        }
        StringBuilder sb = new StringBuilder();
        for (Path seg : rel) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(seg.toString(), StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private static long fileSize(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Reads the last maxBytes of a text file (the end of a log is where the failure is). */
    private static String readTail(Path file, int maxBytes) {
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            long size = ch.size();
            long start = Math.max(0, size - maxBytes);
            ch.position(start);
            ByteBuffer buf = ByteBuffer.allocate((int) (size - start));
            while (buf.hasRemaining() && ch.read(buf) > 0) {
                // keep reading until the buffer is full
            }
            String text = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
            if (start > 0) {
                int nl = text.indexOf('\n');
                text = "[... earlier output truncated ...]\n" + (nl >= 0 ? text.substring(nl + 1) : text);
            }
            return text;
        } catch (Exception e) {
            return "[unable to read log: " + e.getMessage() + "]";
        }
    }

    private static String missJson(Map<String, String> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(jsonStr(e.getKey())).append(':').append(jsonStr(e.getValue()));
        }
        return sb.append('}').toString();
    }

    /** JSON string literal. The less-than sign is escaped so the data can never close the script tag. */
    private static String jsonStr(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                // dropped
            } else if (c == '\t') {
                sb.append("  ");
            } else if (c == '<') {
                sb.append("\\u003c");
            } else if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // =====================================================================
    // Small helpers
    // =====================================================================

    private static String normalizeStatus(String status) {
        String s = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("PASS")) return "PASS";
        if (s.startsWith("FAIL")) return "FAIL";
        if (s.startsWith("SKIP")) return "SKIP";
        return s.isEmpty() ? "FAIL" : s;
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "\n... [truncated " + (s.length() - max) + " chars]";
    }

    private static String h(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String pct(long part, long whole) {
        return whole > 0 ? String.format(Locale.ROOT, "%.1f", part * 100.0 / whole) : "0.0";
    }

    private static String formatDuration(long ms) {
        long seconds = ms / 1000;
        long hours = seconds / 3600;
        long mins = (seconds % 3600) / 60;
        long remSec = seconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%dh %02dm", hours, mins);
        }
        if (mins > 0) {
            return String.format(Locale.ROOT, "%dm %02ds", mins, remSec);
        }
        return String.format(Locale.ROOT, "%d.%02ds", remSec, (ms % 1000) / 10);
    }

    private static String getConfigValue(String key, String defaultValue) {
        String val = System.getProperty(key);
        if (val != null && !val.isBlank()) return val;
        val = System.getProperty(key.toLowerCase(Locale.ROOT));
        if (val != null && !val.isBlank()) return val;
        val = System.getenv(key);
        if (val != null && !val.isBlank()) return val;
        val = dotenv.get(key);
        if (val != null && !val.isBlank()) return val;
        return defaultValue;
    }

    // =====================================================================
    // Page template: HTML body, CSS, JS (kept as three blocks, see render())
    // Note: inside the blocks below a literal backslash must be written twice.
    // =====================================================================

    private static final String TPL_BODY = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>KILL_BILL // Automation Ops Console</title>
<noscript><style>#boot{display:none}</style></noscript>
<style>
{{CSS}}</style>
</head>
<body>
<canvas id="rain"></canvas>
<div id="boot"><pre id="boot-out"></pre><div class="skip">[ click or press any key to skip ]</div></div>

<header class="top">
  <div class="logo"><span class="glitch" data-text="KILL_BILL">KILL_BILL</span><small>automation ops console</small></div>
  <div class="prompt"><span class="u">{{TESTER_USER}}@killbill</span><span class="mu">:</span><span class="d">~/{{ENV}}</span><span class="mu">$</span> <span class="cmd">./run --browser {{BROWSER}} --mode {{MODE}} --threads {{THREADS}}</span><i class="cursor"></i></div>
  <div class="tools"><span class="clk" id="clock">00:00:00</span><button class="btn on" id="btn-rain" data-act="rain" title="toggle matrix rain (m)">matrix</button><button class="btn" data-act="export" title="export the filtered list as CSV (e)">export csv</button></div>
</header>
<div class="sys"><span>tester <b>{{TESTER}}</b></span><span>env <b>{{ENV}}</b></span><span>url <b>{{BASEURL}}</b></span><span>os <b>{{OS}}</b></span><span>java <b>{{JAVA}}</b></span><span>started <b>{{RUN}}</b></span></div>

<section class="kpis">
  <div class="win k"><div class="l">total tests</div><div class="v c-t" data-count="{{TOTAL}}">{{TOTAL}}</div><div class="s">execution records</div></div>
  <div class="win k"><div class="l">passed</div><div class="v c-g" data-count="{{PASSED}}">{{PASSED}}</div><div class="s">{{PASS_PCT}}% of run</div></div>
  <div class="win k"><div class="l">failed</div><div class="v c-r" data-count="{{FAILED}}">{{FAILED}}</div><div class="s">{{FAIL_PCT}}% of run</div></div>
  <div class="win k"><div class="l">skipped</div><div class="v c-y" data-count="{{SKIPPED}}">{{SKIPPED}}</div><div class="s">{{SKIP_PCT}}% of run</div></div>
  <div class="win k"><div class="l">pass rate</div><div class="v c-v" data-count="{{RATE}}" data-dec="1" data-suf="%">{{RATE}}%</div><div class="s">suite health index</div></div>
  <div class="win k"><div class="l">wall clock</div><div class="v c-c">{{DURATION}}</div><div class="s">avg {{AVG}} per test</div></div>
</section>

<nav class="nav">
  <button class="tb on" data-act="view" data-v="overview">overview<kbd>1</kbd></button>
  <button class="tb" data-act="view" data-v="explorer">explorer<kbd>2</kbd><em id="nav-n">{{TOTAL}}</em></button>
  <span class="sp"></span>
  <span class="thr" id="thr"></span>
</nav>

<main>
  <div id="v-overview" class="view on"></div>
  <div id="v-explorer" class="view">
    <section class="win lc">
      <div class="wh"><span class="dots"><i></i><i></i><i></i></span><span>test index</span><span class="sp"></span><span id="cnt"></span></div>
      <div class="ctl">
        <label class="sbox"><span>&gt;</span><input id="q" type="text" placeholder="search  |  status:fail cls:login loc:xpath" autocomplete="off" spellcheck="false"></label>
        <div class="fbs">
          <button class="fb on" data-act="filter" data-v="ALL">all <em id="n-ALL"></em></button>
          <button class="fb" data-act="filter" data-v="FAIL">fail <em id="n-FAIL"></em></button>
          <button class="fb" data-act="filter" data-v="PASS">pass <em id="n-PASS"></em></button>
          <button class="fb" data-act="filter" data-v="SKIP">skip <em id="n-SKIP"></em></button>
        </div>
        <div class="r2">
          <select id="sort" aria-label="sort order">
            <option value="seq">order: execution</option>
            <option value="slow">order: slowest first</option>
            <option value="fast">order: fastest first</option>
            <option value="name">order: name</option>
            <option value="status">order: failures first</option>
          </select>
          <span id="chip"></span>
        </div>
      </div>
      <div id="list" class="list"></div>
      <div class="pg"><button class="btn" id="pg-prev" data-act="prev">prev</button><span id="pi"></span><button class="btn" id="pg-next" data-act="next">next</button></div>
    </section>
    <section id="ins" class="ins"><div class="win"><div class="wb empty">select a test from the index to inspect it</div></div></section>
  </div>
</main>

<footer class="foot">
  <span class="mode" id="mode">OVERVIEW</span>
  <span><kbd>1</kbd><kbd>2</kbd>views <kbd>/</kbd>search <kbd>j</kbd><kbd>k</kbd>move <kbd>f</kbd><kbd>p</kbd><kbd>s</kbd><kbd>a</kbd>filter <kbd>m</kbd>matrix <kbd>e</kbd>export <kbd>esc</kbd>close</span>
  <span class="sp"></span>
  <span id="fs"></span>
</footer>

<div id="lb"><img id="lb-img" alt="screenshot, full size"></div>

<script id="kb-data" type="application/json">{{DATA}}</script>
<script id="kb-meta" type="application/json">{{META}}</script>
<script>
{{JS}}</script>
</body>
</html>
""";

    private static final String TPL_CSS = """
:root{--bg:#03060a;--panel:rgba(6,14,19,.9);--panel2:rgba(10,24,31,.96);--line:#0e2a22;--line2:#175040;--g:#00ff9d;--gd:#00a86b;--c:#19e3ff;--r:#ff3b6b;--y:#ffc233;--v:#a78bfa;--tx:#c6e6d6;--mu:#6a8f7c;--mono:"JetBrains Mono","Fira Code","Cascadia Code","SF Mono",Consolas,"Liberation Mono",Menlo,monospace}
*{box-sizing:border-box;margin:0;padding:0;scrollbar-width:thin;scrollbar-color:#14402f transparent}
html,body{height:100%}
body{display:flex;flex-direction:column;overflow:hidden;font:12.5px/1.55 var(--mono);color:var(--tx);background:radial-gradient(ellipse at center,transparent 55%,rgba(0,0,0,.6)),linear-gradient(rgba(0,255,157,.045) 1px,transparent 1px) 0 0/34px 34px,linear-gradient(90deg,rgba(0,255,157,.045) 1px,transparent 1px) 0 0/34px 34px,radial-gradient(1100px 560px at 85% -8%,rgba(0,255,157,.10),transparent 60%),radial-gradient(900px 520px at -8% 108%,rgba(25,227,255,.07),transparent 60%),var(--bg)}
button,select,input{font:inherit;color:inherit}
:focus-visible{outline:2px solid var(--c);outline-offset:2px}
em{font-style:normal}
a{color:inherit}
kbd{font:inherit}
.sp{flex:1}
.mu{color:var(--mu)}
.c-t{color:var(--tx)}.c-g{color:var(--g)}.c-r{color:var(--r)}.c-y{color:var(--y)}.c-c{color:var(--c)}.c-v{color:var(--v)}
#rain{position:fixed;inset:0;width:100%;height:100%;z-index:0;opacity:.16;pointer-events:none}
#rain.off{display:none}
@keyframes blink{50%{opacity:0}}
@keyframes glitch{0%{clip-path:inset(15% 0 55% 0)}33%{clip-path:inset(60% 0 8% 0)}66%{clip-path:inset(35% 0 35% 0)}100%{clip-path:inset(5% 0 75% 0)}}

.top{position:relative;z-index:2;display:flex;align-items:center;gap:20px;padding:10px 20px;border-bottom:1px solid var(--line2);background:linear-gradient(180deg,rgba(0,255,157,.07),transparent),var(--panel)}
.logo{display:flex;align-items:baseline;gap:12px;white-space:nowrap}
.logo small{color:var(--mu);font-size:11px;letter-spacing:.08em}
.glitch{position:relative;font-size:21px;font-weight:800;letter-spacing:.14em;color:var(--g);text-shadow:0 0 14px rgba(0,255,157,.65)}
.glitch::before,.glitch::after{content:attr(data-text);position:absolute;left:0;top:0;opacity:0}
.glitch::before{color:var(--c);transform:translateX(-2px)}
.glitch::after{color:var(--r);transform:translateX(2px)}
.glitch:hover::before,.glitch:hover::after{opacity:.85;animation:glitch .4s steps(1) infinite}
.prompt{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--mu)}
.prompt .u{color:var(--g)}.prompt .d{color:var(--c)}.prompt .cmd{color:var(--tx)}
.cursor{display:inline-block;width:8px;height:14px;margin-left:4px;vertical-align:-2px;background:var(--g);animation:blink 1s steps(1) infinite}
.tools{display:flex;align-items:center;gap:10px}
.clk{color:var(--g);letter-spacing:.12em;text-shadow:0 0 10px rgba(0,255,157,.5)}
.btn{display:inline-block;padding:5px 11px;background:transparent;border:1px solid var(--line2);color:var(--g);cursor:pointer;text-decoration:none;font-size:11px;letter-spacing:.06em;white-space:nowrap;transition:.15s}
.btn:hover{border-color:var(--g);background:rgba(0,255,157,.12);box-shadow:0 0 12px rgba(0,255,157,.35)}
.btn.on{background:var(--g);border-color:var(--g);color:#00180e;font-weight:700}
.btn:disabled{opacity:.35;cursor:default;box-shadow:none;background:transparent}
.sys{position:relative;z-index:2;display:flex;gap:26px;padding:5px 20px;overflow:auto;white-space:nowrap;background:rgba(0,0,0,.4);border-bottom:1px solid var(--line);color:var(--mu);font-size:11px}
.sys b{color:var(--c);font-weight:500}
.kpis{position:relative;z-index:2;display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:12px;padding:14px 20px 0}
.k{padding:10px 14px}
.k .l{font-size:11px;color:var(--mu)}
.k .v{font-size:28px;font-weight:700;line-height:1.25;text-shadow:0 0 16px currentColor}
.k .s{font-size:11px;color:var(--mu)}
body.in-explorer .kpis{padding-top:8px}
body.in-explorer .k{flex-direction:row;align-items:baseline;gap:10px;padding:4px 12px}
body.in-explorer .k .v{font-size:17px;line-height:1.4}
body.in-explorer .k .s{display:none}

.win{position:relative;min-width:0;display:flex;flex-direction:column;background:var(--panel);border:1px solid var(--line2);box-shadow:0 6px 18px rgba(0,0,0,.4)}
.win::before,.win::after{content:"";position:absolute;width:11px;height:11px;border:2px solid var(--g);pointer-events:none}
.win::before{top:-2px;left:-2px;border-right:0;border-bottom:0}
.win::after{bottom:-2px;right:-2px;border-left:0;border-top:0}
.wh{display:flex;align-items:center;gap:10px;padding:7px 12px;border-bottom:1px solid var(--line);color:var(--mu);font-size:11px;letter-spacing:.04em;white-space:nowrap}
.wh b{color:var(--g);font-weight:600}
.dots{display:flex;gap:5px}
.dots i{width:7px;height:7px;border-radius:50%;background:#ff5f56}
.dots i:nth-child(2){background:#ffbd2e}
.dots i:nth-child(3){background:#27c93f}
.wb{padding:12px 14px;min-width:0}
.empty{color:var(--mu);text-align:center;padding:22px 10px}

.nav{position:relative;z-index:2;display:flex;align-items:flex-end;gap:6px;padding:12px 20px 0}
.tb{padding:7px 16px;background:transparent;border:1px solid var(--line);border-bottom:0;color:var(--mu);cursor:pointer;font-size:12px}
.tb:hover{color:var(--tx)}
.tb.on{color:var(--g);background:var(--panel2);border-color:var(--line2);box-shadow:inset 0 2px 0 var(--g)}
.tb kbd{margin-left:8px;padding:0 5px;border:1px solid var(--line2);color:var(--mu);font-size:10px}
.tb em{margin-left:8px;color:var(--c)}
.thr{padding-bottom:6px;font-size:11px;color:var(--mu)}
main{position:relative;z-index:2;flex:1;min-height:0;margin:0 20px;border-top:1px solid var(--line2)}
.view{position:absolute;inset:0;display:none;overflow:auto;padding:14px 0}
.view.on{display:block}
.grid{display:grid;grid-template-columns:repeat(12,minmax(0,1fr));gap:14px}
.s4{grid-column:span 4}.s6{grid-column:span 6}.s12{grid-column:span 12}

.dwrap{display:flex;align-items:center;justify-content:center;gap:18px;flex-wrap:wrap}
.dn{position:relative;width:150px;height:150px;flex:none}
.dc{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center}
.dc b{font-size:24px;text-shadow:0 0 14px currentColor}
.dc span{font-size:10px;color:var(--mu)}
.leg{display:flex;flex-direction:column;gap:7px;min-width:150px}
.leg div{display:grid;grid-template-columns:10px 1fr auto auto;gap:8px;align-items:center}
.leg i{width:10px;height:10px}
.leg span{min-width:48px;text-align:right;color:var(--mu)}
.meter{display:grid;grid-template-columns:repeat(30,1fr);gap:2px;height:10px;margin-top:14px}
.meter i{background:#0c1d17}
.meter i.on.g{background:var(--g);box-shadow:0 0 6px var(--g)}
.meter i.on.y{background:var(--y);box-shadow:0 0 6px var(--y)}
.meter i.on.r{background:var(--r);box-shadow:0 0 6px var(--r)}
.risk{margin-top:10px;font-size:11px;color:var(--mu)}
.fx{display:flex;flex-direction:column;gap:6px;margin-top:14px;padding-top:10px;border-top:1px solid var(--line)}
.fx div{display:flex;justify-content:space-between;gap:10px}
.fx span{color:var(--mu)}
.fx b{font-weight:600;text-align:right}

.rl{display:flex;flex-direction:column;gap:7px}
.ob{position:relative;overflow:hidden;display:grid;grid-template-columns:auto minmax(0,1fr);gap:10px;align-items:center;width:100%;text-align:left;padding:8px 10px;background:rgba(0,255,157,.025);border:1px solid var(--line);cursor:pointer;transition:.15s}
.ob:hover{border-color:var(--g);background:rgba(0,255,157,.08)}
.ob.cl{grid-template-columns:minmax(0,1fr) auto}
.ob .tt{display:flex;flex-direction:column;gap:3px;min-width:0}
.ob .t{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.ob .sub{color:var(--mu);font-size:11px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.ob .bar{position:absolute;left:0;bottom:0;height:2px;background:var(--r);box-shadow:0 0 8px var(--r)}
.n{min-width:46px;text-align:center;padding:2px 7px;font-size:11px;font-weight:700;border:1px solid;white-space:nowrap}
.n.FAIL,.tag.FAIL{color:var(--r);border-color:rgba(255,59,107,.55);background:rgba(255,59,107,.1)}
.n.PASS,.tag.PASS{color:var(--g);border-color:rgba(0,255,157,.5);background:rgba(0,255,157,.08)}
.n.SKIP,.tag.SKIP{color:var(--y);border-color:rgba(255,194,51,.55);background:rgba(255,194,51,.1)}
.tag{padding:1px 8px;font-size:10px;font-weight:700;letter-spacing:.08em;border:1px solid;white-space:nowrap}
.sb{display:flex;height:6px;background:#0a1a15;overflow:hidden}
.sb i{display:block;height:100%}

.mx{display:flex;flex-wrap:wrap;gap:3px;margin-top:12px}
.cell{width:11px;height:11px;background:#0b5c3b;border-radius:1px;cursor:pointer;transition:transform .1s}
.cell:hover{transform:scale(1.7);position:relative;z-index:3}
.cell.FAIL{background:var(--r)}
.cell.SKIP{background:var(--y)}
.lgd{display:flex;gap:14px;color:var(--mu);font-size:11px}
.lgd i{display:inline-block;width:9px;height:9px;margin-right:5px;vertical-align:-1px}
.lgd i.PASS{background:#0b5c3b}.lgd i.FAIL{background:var(--r)}.lgd i.SKIP{background:var(--y)}

#v-explorer.on{display:grid;grid-template-columns:minmax(320px,440px) minmax(0,1fr);grid-template-rows:minmax(0,1fr);gap:14px;overflow:hidden}
.lc{min-height:0}
.ctl{display:flex;flex-direction:column;gap:8px;padding:10px;border-bottom:1px solid var(--line)}
.sbox{display:flex;align-items:center;gap:8px;padding:6px 10px;background:#02080b;border:1px solid var(--line2)}
.sbox:focus-within{border-color:var(--g);box-shadow:0 0 14px rgba(0,255,157,.25)}
.sbox span{color:var(--g)}
.sbox input{flex:1;min-width:0;background:transparent;border:0;outline:0}
.sbox input::placeholder{color:#47685a}
.fbs{display:flex;gap:6px}
.fb{flex:1;padding:5px 4px;background:transparent;border:1px solid var(--line);color:var(--mu);cursor:pointer;font-size:11px;white-space:nowrap}
.fb:hover{border-color:var(--g);color:var(--tx)}
.fb.on{background:var(--g);border-color:var(--g);color:#00180e;font-weight:700}
.fb em{opacity:.75}
.r2{display:flex;gap:8px;align-items:center;min-width:0}
select{flex:1;min-width:0;padding:5px 8px;background:#02080b;border:1px solid var(--line2);cursor:pointer;font-size:11px}
select option{background:#02080b}
.chip{max-width:230px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;padding:3px 8px;background:rgba(25,227,255,.1);border:1px solid rgba(25,227,255,.5);color:var(--c);cursor:pointer;font-size:11px}
.list{flex:1;min-height:0;overflow:auto}
.row{display:grid;grid-template-columns:3px minmax(0,1fr) auto;gap:11px;align-items:center;padding:9px 12px 9px 0;border-bottom:1px solid var(--line);cursor:pointer}
.row::before{content:"";align-self:stretch}
.row.FAIL::before{background:var(--r);box-shadow:0 0 8px var(--r)}
.row.PASS::before{background:var(--gd)}
.row.SKIP::before{background:var(--y)}
.row:hover{background:rgba(0,255,157,.06)}
.row.sel{background:rgba(0,255,157,.12);box-shadow:inset 0 0 0 1px var(--g)}
.rt{min-width:0}
.rt .a{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-weight:600}
.rt .b{margin-top:2px;color:var(--mu);font-size:11px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.pg{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:8px 10px;border-top:1px solid var(--line);color:var(--mu);font-size:11px}

.ins{min-height:0;overflow:auto;display:flex;flex-direction:column;gap:14px;padding-right:3px}
.ttl{margin-bottom:10px;font-size:15px;font-weight:700;color:var(--g);word-break:break-all;text-shadow:0 0 10px rgba(0,255,157,.35)}
.kv{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px 18px}
.kv span{display:block;font-size:11px;color:var(--mu)}
.kv b{font-weight:600;word-break:break-word}
.tabs{display:flex;flex-wrap:wrap;border-bottom:1px solid var(--line)}
.tabs button{padding:8px 16px;background:transparent;border:0;border-bottom:2px solid transparent;color:var(--mu);cursor:pointer;font-size:12px}
.tabs button:hover{color:var(--tx)}
.tabs button.on{color:var(--g);border-bottom-color:var(--g);background:rgba(0,255,157,.06)}
.warn{color:var(--y);font-weight:700}
.bar2{display:flex;align-items:center;gap:8px;margin:2px 0 10px;color:var(--mu);font-size:11px}
.loc{margin-bottom:10px;padding:9px 12px;color:#ff9db3;background:rgba(255,59,107,.08);border:1px dashed rgba(255,59,107,.6);word-break:break-all}
.errbox{margin-bottom:14px;padding:10px 12px;color:#ffb3c2;background:rgba(255,59,107,.06);border-left:3px solid var(--r);white-space:pre-wrap;word-break:break-word}
.passbox{padding:14px;color:var(--g);background:rgba(0,255,157,.06);border:1px solid rgba(0,255,157,.35);margin-bottom:14px}
.miss{margin-bottom:10px;padding:12px 14px;color:var(--y);background:rgba(255,194,51,.07);border:1px dashed rgba(255,194,51,.6);word-break:break-all}
.code{padding:12px;max-height:48vh;overflow:auto;background:#02070a;border:1px solid var(--line);font-size:12px;white-space:pre-wrap;word-break:break-word}
.code.logv{white-space:normal}
.code .lib{color:#4f7563}.code .app{color:var(--c)}.code .cb{color:var(--r);font-weight:700}
.ln{display:block;white-space:pre-wrap;word-break:break-word}
.ln .no{display:inline-block;min-width:40px;margin-right:12px;text-align:right;color:#46695a;user-select:none}
.lv-e{color:#ff7a99}.lv-w{color:var(--y)}.lv-i{color:#9ee6c3}.lv-d{color:#5f8272}
.shot-grid{display:flex;flex-direction:column;gap:16px}
.shot-card{padding:10px 12px;background:#02070a;border:1px solid var(--line2)}
.shot-step{color:var(--c);font-weight:700}
.media{display:block;max-width:100%;max-height:62vh;background:#000;border:1px solid var(--line2)}
img.media{cursor:zoom-in}

.foot{position:relative;z-index:2;display:flex;align-items:center;gap:16px;padding:6px 20px;border-top:1px solid var(--line2);background:var(--panel);color:var(--mu);font-size:11px;white-space:nowrap;overflow:hidden}
.foot .mode{padding:1px 10px;background:var(--g);color:#00180e;font-weight:700;letter-spacing:.08em}
.foot kbd{padding:0 4px;margin-right:3px;border:1px solid var(--line2);color:var(--g)}

#boot{position:fixed;inset:0;z-index:9500;padding:9vh 8vw;background:#010304;color:var(--g);font-size:14px;line-height:1.9;cursor:pointer;text-shadow:0 0 8px rgba(0,255,157,.6);animation:bootfail 0s 5s forwards}
#boot pre{font:inherit;white-space:pre-wrap}
#boot .skip{margin-top:18px;color:var(--mu);font-size:11px;text-shadow:none}
#boot.out{opacity:0;transition:opacity .45s;pointer-events:none}
@keyframes bootfail{to{opacity:0;visibility:hidden;pointer-events:none}}
#lb{position:fixed;inset:0;z-index:9000;display:none;overflow:auto;background:rgba(0,0,0,.94);cursor:zoom-out}
#lb.on{display:flex}
#lb img{margin:auto;max-width:96vw;max-height:94vh;border:1px solid var(--g);box-shadow:0 0 34px rgba(0,255,157,.4);cursor:zoom-in}
#lb img.z{max-width:none;max-height:none;cursor:zoom-out}

@media(max-width:1100px){.s4{grid-column:span 6}.kpis{grid-template-columns:repeat(3,minmax(0,1fr))}}
@media(max-width:820px){.s4,.s6{grid-column:span 12}.top{flex-wrap:wrap}.prompt{order:3;flex-basis:100%}.kpis{grid-template-columns:repeat(2,minmax(0,1fr))}#v-explorer.on{grid-template-columns:minmax(0,1fr);grid-template-rows:minmax(240px,45vh) minmax(0,1fr)}}
@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}#boot{animation:bootfail 0s 5s forwards!important}}
""";

    private static final String TPL_JS = """
(function(){
'use strict';
const $=(s,r)=>(r||document).querySelector(s);
const $$=(s,r)=>Array.from((r||document).querySelectorAll(s));
const esc=s=>String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
const store={
  get(k,d){try{const v=localStorage.getItem('kb.'+k);return v===null?d:v;}catch(e){return d;}},
  set(k,v){try{localStorage.setItem('kb.'+k,v);}catch(e){}}
};

let TESTS=[],META={};
try{TESTS=JSON.parse($('#kb-data').textContent);META=JSON.parse($('#kb-meta').textContent);}
catch(e){console.error('dashboard data error',e);}

const PAGE=100;
const RANK={FAIL:0,SKIP:1,PASS:2};
const LIBS=['org.openqa','org.testng','org.junit','org.apache','org.bytedeco','io.restassured','com.aventstack','com.assertthat','java.','javax.','jdk.','sun.'];
const TABN={diag:'diagnostics',log:'exec log',shot:'screenshot',vid:'video'};
const MISSKEY={log:'log',shot:'ss',vid:'vid'};
const fmt=ms=>{ms=ms||0;if(ms<1000)return ms+'ms';const s=ms/1000;if(s<60)return s.toFixed(2)+'s';return Math.floor(s/60)+'m '+('0'+Math.floor(s%60)).slice(-2)+'s';};
const S={view:'overview',status:'ALL',q:'',scope:null,sort:'seq',page:1,sel:-1,tab:'diag',errOnly:false};
const REDUCE=(()=>{try{return window.matchMedia('(prefers-reduced-motion: reduce)').matches;}catch(e){return false;}})();
let F=[];
const cnt={PASS:0,FAIL:0,SKIP:0};

// ---------- data prep ----------
function sigOf(t){
  if(t.status==='PASS')return '';
  let m=(t.err||'').split('\\n')[0].trim();
  if(!m)m=((t.trace||'').split('\\n')[0]||'unknown error').trim();
  m=m.replace(/0x[0-9a-fA-F]+/g,'0x#').replace(/[0-9]+/g,'#').replace(/"[^"]*"/g,'"?"').replace(/'[^']*'/g,"'?'");
  return m.length>150?m.slice(0,150)+'...':m;
}
TESTS.forEach((t,i)=>{
  t.i=i;t.status=String(t.status||'').toUpperCase();cnt[t.status]=(cnt[t.status]||0)+1;
  t.cls=t.cls||'';t.miss=t.miss||{};
  t.ssList=Array.isArray(t.ssList)&&t.ssList.length?t.ssList:(t.ss?[t.ss]:[]);
  t.h=[t.id,t.cls,t.method,t.status,t.err,t.locator].join(' ').toLowerCase();
  t.sig=sigOf(t);
});
const total=TESTS.length,rate=total?cnt.PASS*100/total:0;
function groupBy(arr,fn){
  const m=new Map();
  arr.forEach(x=>{const k=fn(x);if(!k)return;let g=m.get(k);if(!g){g={key:k,items:[]};m.set(k,g);}g.items.push(x);});
  return Array.from(m.values());
}
const bad=TESTS.filter(t=>t.status!=='PASS');
const clusters=groupBy(bad,t=>t.sig).sort((a,b)=>b.items.length-a.items.length);
const locs=groupBy(bad,t=>t.locator&&t.locator!=='N/A'?t.locator:'').sort((a,b)=>b.items.length-a.items.length);
const classes=groupBy(TESTS,t=>t.cls||'(no class)').map(g=>{
  g.f=g.items.filter(t=>t.status==='FAIL').length;
  g.s=g.items.filter(t=>t.status==='SKIP').length;
  g.p=g.items.length-g.f-g.s;
  return g;
}).sort((a,b)=>(b.f+b.s)-(a.f+a.s)||b.items.length-a.items.length);
const slow=TESTS.slice().sort((a,b)=>b.duration-a.duration).slice(0,6);

function risk(){
  if(!total)return['NO DATA','y'];
  if(!cnt.FAIL&&!cnt.SKIP)return['CLEAR','g'];
  if(rate>=95)return['LOW','g'];
  if(rate>=85)return['ELEVATED','y'];
  if(rate>=60)return['HIGH','r'];
  return['CRITICAL','r'];
}

// ---------- overview ----------
const wh=(t,r)=>'<div class="wh"><span class="dots"><i></i><i></i><i></i></span><span>'+t+'</span><span class="sp"></span>'+(r||'')+'</div>';

function donut(){
  const R=52,C=2*Math.PI*R,tot=total||1;let off=0,segs='';
  [['PASS','#00ff9d'],['FAIL','#ff3b6b'],['SKIP','#ffc233']].forEach(a=>{
    const v=cnt[a[0]]||0;if(!v)return;
    const len=C*v/tot;
    segs+='<circle cx="70" cy="70" r="'+R+'" fill="none" stroke="'+a[1]+'" stroke-width="14" stroke-dasharray="'+len.toFixed(2)+' '+(C-len).toFixed(2)+'" stroke-dashoffset="'+(-off).toFixed(2)+'" style="filter:drop-shadow(0 0 4px '+a[1]+')"/>';
    off+=len;
  });
  return '<svg viewBox="0 0 140 140" width="150" height="150" style="transform:rotate(-90deg)"><circle cx="70" cy="70" r="'+R+'" fill="none" stroke="#0d2219" stroke-width="14"/>'+segs+'</svg>';
}
function fxHtml(){
  const top=clusters[0],row=(k,v)=>'<div><span>'+k+'</span><b>'+v+'</b></div>';
  return '<div class="fx">'+row('unique root causes',clusters.length)+row('broken locators',locs.length)+row('biggest single cause',top?top.items.length+(top.items.length===1?' test':' tests'):'none')+row('slowest test',slow.length?fmt(slow[0].duration):'-')+'</div>';
}
function cardHealth(){
  const th=risk(),n=30,on=Math.round(rate/100*n);let m='';
  for(let i=0;i<n;i++)m+='<i class="'+(i<on?'on '+th[1]:'')+'"></i>';
  const leg=(k,c)=>'<div><i style="background:'+c+'"></i><span style="text-align:left;color:inherit">'+k.toLowerCase()+'</span><b>'+(cnt[k]||0)+'</b><span>'+(total?((cnt[k]||0)*100/total).toFixed(1):'0.0')+'%</span></div>';
  return '<section class="win s4">'+wh('suite health')+'<div class="wb"><div class="dwrap"><div class="dn">'+donut()+'<div class="dc"><b class="c-'+th[1]+'">'+rate.toFixed(1)+'%</b><span>pass rate</span></div></div><div class="leg">'+leg('PASS','#00ff9d')+leg('FAIL','#ff3b6b')+leg('SKIP','#ffc233')+'</div></div><div class="meter">'+m+'</div><div class="risk">risk level: <b class="c-'+th[1]+'">'+th[0]+'</b></div>'+fxHtml()+'</div></section>';
}
function listCard(title,groups,type,empty){
  let b;
  if(!groups.length)b='<div class="empty">'+empty+'</div>';
  else{
    const mx=groups[0].items.length;
    b='<div class="rl">'+groups.slice(0,6).map((g,i)=>'<button class="ob" data-act="scope" data-type="'+type+'" data-v="'+i+'"><span class="n FAIL">x'+g.items.length+'</span><span class="tt"><span class="t">'+esc(g.key)+'</span><span class="sub">'+esc(g.items[0].id)+(g.items.length>1?' (+'+(g.items.length-1)+' more)':'')+'</span></span><span class="bar" style="width:'+(g.items.length*100/mx).toFixed(1)+'%"></span></button>').join('')+'</div>';
  }
  return '<section class="win s4">'+wh(title,groups.length?'<span>'+groups.length+' found</span>':'')+'<div class="wb">'+b+'</div></section>';
}
function cardClasses(){
  const b=classes.slice(0,8).map((g,i)=>{
    const t=g.items.length,seg=(v,c)=>'<i style="width:'+(v*100/t).toFixed(2)+'%;background:'+c+'"></i>';
    return '<button class="ob cl" data-act="scope" data-type="cls" data-v="'+i+'"><span class="tt"><span class="t">'+esc(g.key)+'</span><span class="sb">'+seg(g.p,'var(--g)')+seg(g.f,'var(--r)')+seg(g.s,'var(--y)')+'</span></span><span class="sub">'+g.p+' / '+g.f+' / '+g.s+'</span></button>';
  }).join('');
  return '<section class="win s6">'+wh('class health <b>--sort failures</b>','<span>pass / fail / skip</span>')+'<div class="wb"><div class="rl">'+(b||'<div class="empty">no classes</div>')+'</div></div></section>';
}
function cardSlow(){
  const mx=slow.length?(slow[0].duration||1):1;
  const b=slow.map(t=>'<button class="ob" data-act="pick" data-i="'+t.i+'"><span class="n '+t.status+'">'+fmt(t.duration)+'</span><span class="tt"><span class="t">'+esc(t.id)+'</span><span class="sub">'+esc(t.cls)+'</span></span><span class="bar" style="width:'+((t.duration||0)*100/mx).toFixed(1)+'%;background:var(--c);box-shadow:0 0 8px var(--c)"></span></button>').join('');
  return '<section class="win s6">'+wh('slowest tests <b>--top 6</b>')+'<div class="wb"><div class="rl">'+(b||'<div class="empty">no data</div>')+'</div></div></section>';
}
function cardMap(){
  const MAXC=1400,b=Math.max(1,Math.ceil(total/MAXC));let cells='';
  for(let s=0;s<total;s+=b){
    let rep=TESTS[s];const e=Math.min(total,s+b);
    for(let j=s+1;j<e;j++){if(RANK[TESTS[j].status]<RANK[rep.status])rep=TESTS[j];}
    cells+='<i class="cell '+rep.status+'" data-act="pick" data-i="'+rep.i+'" title="#'+(s+1)+(b>1?'-'+e:'')+'  '+esc(rep.id)+'  ['+rep.status+']"></i>';
  }
  return '<section class="win s12">'+wh('run map <b>--execution order</b>','<span class="lgd"><span><i class="PASS"></i>pass</span><span><i class="FAIL"></i>fail</span><span><i class="SKIP"></i>skip</span></span>')+'<div class="wb"><div class="mu">one cell per test'+(b>1?' (each cell groups '+b+' tests, colored by its worst result)':'')+'. click a cell to open it.</div><div class="mx">'+cells+'</div></div></section>';
}
function renderOverview(){
  const th=risk();
  $('#thr').innerHTML='risk level: <b class="c-'+th[1]+'">'+th[0]+'</b>';
  if(!total){$('#v-overview').innerHTML='<div class="win"><div class="wb empty">no execution records were captured in this run</div></div>';return;}
  $('#v-overview').innerHTML='<div class="grid">'+cardHealth()+listCard('root causes <b>--top 6</b>',clusters,'sig','[ OK ] no failure clusters')+listCard('broken locators <b>--top 6</b>',locs,'loc','[ OK ] no locator failures')+cardClasses()+cardSlow()+cardMap()+'</div>';
}

// ---------- explorer: filter, sort, list ----------
function parseQ(q){
  const kv=[],free=[];
  q.toLowerCase().split(' ').forEach(w=>{if(!w)return;const p=w.indexOf(':');if(p>0&&p<w.length-1)kv.push([w.slice(0,p),w.slice(p+1)]);else free.push(w);});
  return{kv:kv,free:free};
}
const has=(v,s)=>String(v||'').toLowerCase().indexOf(s)>=0;
function matchKV(t,k,v){
  switch(k){
    case 'status':case 's':return t.status.toLowerCase().indexOf(v)===0;
    case 'cls':case 'class':return has(t.cls,v);
    case 'id':return has(t.id,v);
    case 'm':case 'method':return has(t.method,v);
    case 'err':return has(t.err,v);
    case 'loc':return has(t.locator,v);
    default:return t.h.indexOf(k+':'+v)>=0;
  }
}
function inScope(t){
  const c=S.scope;if(!c)return true;
  if(c.type==='sig')return t.sig===c.value;
  if(c.type==='loc')return t.locator===c.value;
  return(t.cls||'(no class)')===c.value;
}
const SORTS={
  seq:null,
  slow:(a,b)=>b.duration-a.duration,
  fast:(a,b)=>a.duration-b.duration,
  name:(a,b)=>a.id.localeCompare(b.id),
  status:(a,b)=>(RANK[a.status]-RANK[b.status])||(a.i-b.i)
};
function applyFilters(){
  const p=parseQ(S.q);
  F=TESTS.filter(t=>(S.status==='ALL'||t.status===S.status)&&inScope(t)&&p.kv.every(x=>matchKV(t,x[0],x[1]))&&p.free.every(w=>t.h.indexOf(w)>=0));
  if(SORTS[S.sort])F.sort(SORTS[S.sort]);
  S.page=1;renderList();renderChip();
  $('#cnt').textContent=F.length+' / '+total;
  $('#fs').textContent='showing '+F.length+' of '+total;
}
function renderChip(){
  const c=$('#chip');
  if(!S.scope){c.innerHTML='';return;}
  const lab={sig:'cause',loc:'locator',cls:'class'}[S.scope.type],v=S.scope.value;
  c.innerHTML='<button class="chip" data-act="clear-scope" title="clear this filter">'+lab+': '+esc(v.length>34?v.slice(0,34)+'...':v)+'  x</button>';
}
function renderList(){
  const mp=Math.max(1,Math.ceil(F.length/PAGE));
  if(S.page>mp)S.page=mp;if(S.page<1)S.page=1;
  const s=(S.page-1)*PAGE,items=F.slice(s,s+PAGE);
  $('#pi').textContent='page '+S.page+' / '+mp;
  $('#pg-prev').disabled=S.page<=1;$('#pg-next').disabled=S.page>=mp;
  $('#list').innerHTML=items.length?items.map(t=>'<div class="row '+t.status+(t.i===S.sel?' sel':'')+'" data-act="pick" data-i="'+t.i+'"><div class="rt"><div class="a" title="'+esc(t.id)+'">'+esc(t.id)+'</div><div class="b">'+esc(t.cls)+' | '+fmt(t.duration)+' | '+esc(t.time)+(t.ssList.length?' | '+t.ssList.length+' shot'+(t.ssList.length>1?'s':''):'')+'</div></div><span class="tag '+t.status+'">'+t.status+'</span></div>').join(''):'<div class="empty">no records match the current filter</div>';
}
function markSel(){
  $$('.row.sel').forEach(e=>e.classList.remove('sel'));
  const el=$('.row[data-i="'+S.sel+'"]');if(el)el.classList.add('sel');
}
function setStatus(s){
  S.status=s;
  $$('.fb').forEach(b=>b.classList.toggle('on',b.getAttribute('data-v')===s));
  applyFilters();
}
function setScope(sc){
  S.scope=sc;S.status='ALL';S.q='';$('#q').value='';
  $$('.fb').forEach(b=>b.classList.toggle('on',b.getAttribute('data-v')==='ALL'));
  applyFilters();setView('explorer');
  if(F.length)select(F[0].i,false);
}
function setView(v){
  S.view=v;
  $$('.view').forEach(e=>e.classList.toggle('on',e.id==='v-'+v));
  $$('.tb').forEach(b=>b.classList.toggle('on',b.getAttribute('data-v')===v));
  $('#mode').textContent=v.toUpperCase();
  document.body.classList.toggle('in-explorer',v==='explorer');
  if(v==='explorer'&&S.sel<0&&F.length)select((F.find(x=>x.status==='FAIL')||F[0]).i,false);
}

// ---------- inspector ----------
const kvh=(k,v)=>'<div><span>'+k+'</span><b>'+esc(v||'-')+'</b></div>';
function headHtml(t){
  const loc=t.locator&&t.locator!=='N/A'?t.locator:'-';
  return '<section class="win"><div class="wh"><span class="dots"><i></i><i></i><i></i></span><span>inspect <b>--test</b> #'+(t.i+1)+'</span><span class="sp"></span><span class="tag '+t.status+'">'+t.status+'</span></div><div class="wb"><div class="ttl">'+esc(t.id)+'</div><div class="kv">'+kvh('class',t.cls)+kvh('method',t.method)+kvh('duration',fmt(t.duration))+kvh('started',t.time)+kvh('screenshots',t.ssList.length?String(t.ssList.length):'0')+kvh('locator',loc)+'</div></div></section>';
}
function tabsFor(t){
  const a=['diag'];
  if(t.logTxt||t.log||t.miss.log)a.push('log');
  if(t.ssList.length||t.ss||t.miss.ss)a.push('shot');
  if(t.vid||t.miss.vid)a.push('vid');
  return a;
}
function tabLabel(t,k){
  if(k==='shot'&&t.ssList.length>1)return 'screenshots ('+t.ssList.length+')';
  return TABN[k];
}
function renderInspector(t){
  const tabs=tabsFor(t);if(tabs.indexOf(S.tab)<0)S.tab=tabs[0];
  $('#ins').innerHTML=headHtml(t)+'<section class="win"><div class="tabs">'+tabs.map(k=>'<button class="'+(k===S.tab?'on':'')+'" data-act="tab" data-v="'+k+'">'+tabLabel(t,k)+(MISSKEY[k]&&t.miss[MISSKEY[k]]?' <em class="warn">!</em>':'')+'</button>').join('')+'</div><div class="wb" id="pane"></div></section>';
  renderPane(t);
}
function traceHtml(txt){
  return txt.split('\\n').map(l=>{
    const s=l.trim();let c='';
    if(s.indexOf('Caused by:')===0)c='cb';
    else if(s.indexOf('at ')===0)c=LIBS.some(p=>s.indexOf('at '+p)===0)?'lib':'app';
    else if(s.indexOf('Exception')>=0||s.indexOf('Error')>=0)c='cb';
    return '<span class="'+c+'">'+esc(l)+'</span>';
  }).join('\\n');
}
function logHtml(txt,errOnly){
  const lines=txt.split('\\n'),out=[];let all=0;
  for(let n=0;n<lines.length;n++){
    const l=lines[n];
    if(l===''&&n===lines.length-1)continue;
    all++;
    if(l.indexOf('[... ')===0){out.push('<span class="ln lv-d">'+esc(l)+'</span>');continue;}
    let c='lv-i';
    if(l.indexOf('ERROR')>=0||l.indexOf('FATAL')>=0||l.indexOf('Exception')>=0)c='lv-e';
    else if(l.indexOf('WARN')>=0)c='lv-w';
    else if(l.indexOf('DEBUG')>=0||l.indexOf('TRACE')>=0)c='lv-d';
    if(errOnly&&c!=='lv-e'&&c!=='lv-w')continue;
    out.push('<span class="ln '+c+'"><span class="no">'+(n+1)+'</span>'+esc(l)+'</span>');
  }
  return{h:out.join('')||'<span class="ln lv-d">no matching lines</span>',shown:out.length,all:all};
}
const rawLink=(u,label)=>u?'<a class="btn" href="'+esc(u)+'" target="_blank" rel="noopener">'+label+'</a>':'';
function missBox(t,key,what){
  return '<div class="miss"><b>'+what+' file not found on disk</b><br>expected: '+esc(t.miss[key])+'<br>it was deleted, renamed, or never written.</div>';
}
function paneDiag(t){
  if(t.status==='PASS'){
    let h='<div class="passbox"><b>[ OK ]</b> all assertions passed.</div>';
    if(t.ssList.length)h+=paneShot(t);
    return h;
  }
  let h='';
  if(t.locator&&t.locator!=='N/A')h+='<div class="loc"><b>target locator</b><br>'+esc(t.locator)+'</div>';
  h+='<div class="errbox"><b>'+(t.status==='SKIP'?'skip reason':'error')+'</b><br>'+esc(t.err||'no message recorded')+'</div>';
  h+='<div class="bar2"><span>stack trace  (app frames highlighted)</span><span class="sp"></span><button class="btn" data-act="copy" data-v="trace">copy</button></div>';
  h+='<div class="code">'+traceHtml(t.trace||t.err||'')+'</div>';
  return h;
}
function paneLog(t){
  if(t.logTxt){
    const L=logHtml(t.logTxt,S.errOnly);
    return '<div class="bar2"><span>execution log  ('+(S.errOnly?L.shown+' of '+L.all:L.all)+' lines)</span><span class="sp"></span><button class="btn'+(S.errOnly?' on':'')+'" data-act="errs">errors only</button><button class="btn" data-act="copy" data-v="log">copy</button>'+rawLink(t.log,'open raw')+'</div><div class="code logv">'+L.h+'</div>';
  }
  if(t.log&&t.logBytes===0)return '<div class="miss"><b>log file is empty (0 bytes)</b><br>the file exists but nothing was written to it, or it was overwritten.</div>'+rawLink(t.log,'open raw');
  if(t.log)return '<div class="miss"><b>log is too large to embed</b></div>'+rawLink(t.log,'open raw log');
  if(t.miss.log)return missBox(t,'log','log');
  return '<div class="empty">no log recorded for this test</div>';
}
function paneShot(t){
  const list=t.ssList&&t.ssList.length?t.ssList:(t.ss?[t.ss]:[]);
  if(list.length){
    return '<div class="shot-grid">'+list.map((u,idx)=>{
      let fname=u.split('/').pop()||('screenshot_'+(idx+1)+'.png');
      try{fname=decodeURIComponent(fname);}catch(e){}
      const stepTag=list.length>1?'<span class="shot-step">[step '+(idx+1)+'/'+list.length+']</span> ':'';
      return '<div class="shot-card"><div class="bar2"><span>'+stepTag+esc(fname)+'  (click image to zoom)</span><span class="sp"></span>'+rawLink(u,'open png')+'</div><img class="media" data-act="zoom" src="'+esc(u)+'" alt="'+esc(fname)+'"></div>';
    }).join('')+'</div>';
  }
  return missBox(t,'ss','screenshot');
}
function paneVid(t){
  if(t.vid)return '<div class="bar2"><span>browser execution video</span><span class="sp"></span>'+rawLink(t.vid,'open mp4')+'</div><video class="media" controls preload="metadata" src="'+esc(t.vid)+'"></video>';
  return missBox(t,'vid','video');
}
function renderPane(t){
  const p=$('#pane');if(!p)return;
  p.innerHTML=S.tab==='log'?paneLog(t):S.tab==='shot'?paneShot(t):S.tab==='vid'?paneVid(t):paneDiag(t);
}
function select(i,jump,keepTab){
  const t=TESTS[i];if(!t)return;
  S.sel=i;
  if(jump!==false)setView('explorer');
  let idx=F.indexOf(t);
  if(idx<0){
    S.status='ALL';S.q='';S.scope=null;$('#q').value='';
    $$('.fb').forEach(b=>b.classList.toggle('on',b.getAttribute('data-v')==='ALL'));
    applyFilters();idx=F.indexOf(t);
  }
  const pg=Math.floor(idx/PAGE)+1;
  if(pg!==S.page){S.page=pg;renderList();}else markSel();
  if(!keepTab)S.tab='diag';
  renderInspector(t);
  const el=$('.row.sel');if(el&&el.scrollIntoView)el.scrollIntoView({block:'nearest'});
  try{history.replaceState(null,'','#t'+i);}catch(e){}
}
function step(d){
  if(S.view!=='explorer'||!F.length)return;
  let idx=F.findIndex(t=>t.i===S.sel);
  idx=idx<0?0:Math.min(F.length-1,Math.max(0,idx+d));
  select(F[idx].i,false,true);
}

// ---------- helpers: copy, export, lightbox ----------
function fallbackCopy(txt,done){
  const ta=document.createElement('textarea');ta.value=txt;ta.style.position='fixed';ta.style.opacity='0';
  document.body.appendChild(ta);ta.select();
  try{document.execCommand('copy');done();}catch(e){}
  document.body.removeChild(ta);
}
function copy(txt,btn){
  const done=()=>{if(btn){const o=btn.textContent;btn.textContent='copied';setTimeout(()=>{btn.textContent=o;},1200);}};
  if(navigator.clipboard&&navigator.clipboard.writeText)navigator.clipboard.writeText(txt).then(done,()=>fallbackCopy(txt,done));
  else fallbackCopy(txt,done);
}
function exportCsv(){
  const cols=['id','cls','method','status','duration','time','locator','err'];
  const q=v=>'"'+String(v==null?'':v).split('"').join('""').split('\\n').join(' ')+'"';
  const rows=[cols.join(',')].concat(F.map(t=>cols.map(c=>q(t[c])).join(',')));
  try{
    const b=new Blob([rows.join('\\r\\n')],{type:'text/csv'}),u=URL.createObjectURL(b),a=document.createElement('a');
    a.href=u;a.download='kill-bill-results.csv';document.body.appendChild(a);a.click();document.body.removeChild(a);
    setTimeout(()=>URL.revokeObjectURL(u),1000);
  }catch(e){console.error(e);}
}
function openLb(src){const im=$('#lb-img');im.src=src;im.className='';$('#lb').classList.add('on');}
function closeLb(){$('#lb').classList.remove('on');}
$('#lb').addEventListener('click',e=>{if(e.target.tagName==='IMG')e.target.classList.toggle('z');else closeLb();});
document.addEventListener('error',e=>{
  const el=e.target;
  if(el&&el.classList&&el.classList.contains('media')){
    const d=document.createElement('div');d.className='miss';
    d.textContent='evidence failed to load: '+(el.getAttribute('src')||'');
    el.replaceWith(d);
  }
},true);

// ---------- matrix rain (optional, faint) ----------
const RN={cv:$('#rain'),cx:null,on:false,W:0,H:0,cols:0,drops:[],timer:null,pool:'01abcdef#$%&*+<>[]{}/='.split('')};
for(let i=0;i<40;i++)RN.pool.push(String.fromCharCode(0xFF66+i));
function rainSize(){
  RN.W=RN.cv.width=window.innerWidth;RN.H=RN.cv.height=window.innerHeight;
  RN.cols=Math.ceil(RN.W/14);RN.drops=[];
  for(let i=0;i<RN.cols;i++)RN.drops.push(-Math.random()*40);
}
function rainStep(){
  if(document.hidden)return;
  const cx=RN.cx;
  cx.fillStyle='rgba(3,6,10,0.14)';cx.fillRect(0,0,RN.W,RN.H);
  cx.fillStyle='#00ff9d';cx.font='14px monospace';
  for(let i=0;i<RN.cols;i++){
    cx.fillText(RN.pool[(Math.random()*RN.pool.length)|0],i*14,RN.drops[i]*14);
    if(RN.drops[i]*14>RN.H&&Math.random()>0.975)RN.drops[i]=0;
    RN.drops[i]++;
  }
}
function setRain(on){
  RN.on=on;store.set('rain',on?'1':'0');
  const b=$('#btn-rain');if(b)b.classList.toggle('on',on);
  if(!RN.cv)return;
  RN.cv.classList.toggle('off',!on);
  clearInterval(RN.timer);RN.timer=null;
  if(!on)return;
  try{RN.cx=RN.cx||RN.cv.getContext('2d');}catch(e){RN.cx=null;}
  if(!RN.cx)return;
  rainSize();RN.timer=setInterval(rainStep,55);
}
window.addEventListener('resize',()=>{if(RN.timer)rainSize();});
const toggleRain=()=>setRain(!RN.on);

// ---------- boot sequence (once per tab and run) ----------
function boot(done){
  const el=$('#boot');let seen=false;
  try{seen=sessionStorage.getItem('kb.boot.'+META.run)==='1';}catch(e){}
  if(!el||seen||REDUCE){if(el)el.remove();done();return;}
  try{sessionStorage.setItem('kb.boot.'+META.run,'1');}catch(e){}
  const out=$('#boot-out'),NL=String.fromCharCode(10);
  const L=['> KILL_BILL ops console :: secure shell','> mounting /test-output ............ [ OK ]','> indexing '+total+' execution records ...... [ OK ]','> correlating logs, screenshots, video .. [ OK ]','> clustering failures by root cause ..... [ OK ]','> ACCESS GRANTED'];
  let i=0,fin=false;
  const end=()=>{if(fin)return;fin=true;el.classList.add('out');setTimeout(()=>{el.remove();},500);done();};
  el.addEventListener('click',end);
  document.addEventListener('keydown',end,{once:true});
  (function next(){
    if(fin)return;
    if(i>=L.length){setTimeout(end,350);return;}
    out.textContent+=L[i++]+NL;setTimeout(next,170);
  })();
}
function countUp(){
  if(REDUCE)return;
  $$('[data-count]').forEach(el=>{
    const to=parseFloat(el.getAttribute('data-count'))||0,dec=+(el.getAttribute('data-dec')||0),suf=el.getAttribute('data-suf')||'';
    const t0=performance.now(),dur=700;
    (function f(now){
      const p=Math.min(1,(now-t0)/dur),e=1-Math.pow(1-p,3);
      el.textContent=(to*e).toFixed(dec)+suf;
      if(p<1)requestAnimationFrame(f);
    })(t0);
  });
}

// ---------- events ----------
document.addEventListener('click',e=>{
  const el=e.target.closest?e.target.closest('[data-act]'):null;if(!el)return;
  const a=el.getAttribute('data-act'),v=el.getAttribute('data-v');
  if(a==='view')setView(v);
  else if(a==='pick')select(+el.getAttribute('data-i'));
  else if(a==='scope'){const ty=el.getAttribute('data-type'),src=ty==='sig'?clusters:ty==='loc'?locs:classes;setScope({type:ty,value:src[+v].key});}
  else if(a==='clear-scope'){S.scope=null;applyFilters();}
  else if(a==='filter')setStatus(v);
  else if(a==='prev'){S.page=Math.max(1,S.page-1);renderList();}
  else if(a==='next'){S.page++;renderList();}
  else if(a==='tab'){S.tab=v;const t=TESTS[S.sel];if(t)renderInspector(t);}
  else if(a==='errs'){S.errOnly=!S.errOnly;const t=TESTS[S.sel];if(t)renderPane(t);}
  else if(a==='copy'){const t=TESTS[S.sel];if(t)copy(v==='log'?t.logTxt:(t.trace||t.err||''),el);}
  else if(a==='zoom')openLb(el.getAttribute('src'));
  else if(a==='rain')toggleRain();
  else if(a==='export')exportCsv();
});
let qt=null;
$('#q').addEventListener('input',e=>{clearTimeout(qt);const v=e.target.value;qt=setTimeout(()=>{S.q=v;applyFilters();},120);});
$('#sort').addEventListener('change',e=>{S.sort=e.target.value;applyFilters();});
document.addEventListener('keydown',e=>{
  const tg=e.target&&e.target.tagName,typing=tg==='INPUT'||tg==='TEXTAREA'||tg==='SELECT';
  if(e.key==='Escape'){closeLb();if(typing)e.target.blur();return;}
  if(typing||e.ctrlKey||e.metaKey||e.altKey)return;
  const inExp=S.view==='explorer';
  switch(e.key){
    case '1':setView('overview');break;
    case '2':setView('explorer');break;
    case '/':e.preventDefault();setView('explorer');$('#q').focus();break;
    case 'j':case 'ArrowDown':if(inExp){e.preventDefault();step(1);}break;
    case 'k':case 'ArrowUp':if(inExp){e.preventDefault();step(-1);}break;
    case 'f':setView('explorer');setStatus('FAIL');break;
    case 'p':setView('explorer');setStatus('PASS');break;
    case 's':setView('explorer');setStatus('SKIP');break;
    case 'a':setView('explorer');setStatus('ALL');break;
    case 'm':toggleRain();break;
    case 'e':exportCsv();break;
  }
});

// ---------- init ----------
function tick(){const d=new Date();$('#clock').textContent=[d.getHours(),d.getMinutes(),d.getSeconds()].map(n=>('0'+n).slice(-2)).join(':');}
tick();setInterval(tick,1000);
['ALL','FAIL','PASS','SKIP'].forEach(k=>{const e=$('#n-'+k);if(e)e.textContent=k==='ALL'?total:(cnt[k]||0);});
renderOverview();
applyFilters();
const hm=/^#t([0-9]+)$/.exec(window.location.hash||'');
if(hm&&TESTS[+hm[1]])select(+hm[1]);else setView('overview');
setRain(store.get('rain',REDUCE?'0':'1')==='1');
boot(countUp);
})();
""";
}