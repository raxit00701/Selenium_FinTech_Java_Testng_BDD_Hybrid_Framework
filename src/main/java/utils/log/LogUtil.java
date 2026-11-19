package utils.log;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.routing.RoutingAppender;
import org.apache.logging.log4j.core.config.Configuration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public class LogUtil {

    private static final String LOG_DIR = System.getProperty("user.dir")
            + File.separator + "test-output"
            + File.separator + "logs"
            + File.separator;

    public static Logger getLogger(Class<?> clazz) {
        return LogManager.getLogger(clazz);
    }

    public static void startTestLogging(String testName) {
        new File(LOG_DIR).mkdirs();
        ThreadContext.put("testName", testName);
    }

    public static void stopTestLogging() {
        String testName = ThreadContext.get("testName");
        ThreadContext.remove("testName");
        if (testName != null) {
            releaseAppenderLock(testName);
        }
    }

    public static String getLogFilePath(String testName) {
        return LOG_DIR + testName + ".log";
    }

    public static void deleteLogFileIfPassed(String testName) {
        try {
            stopTestLogging();
            Path logPath = Path.of(getLogFilePath(testName));
            Files.deleteIfExists(logPath);
        } catch (Exception e) {
            System.err.println("Could not delete log file for passed test: " + testName);
        }
    }

    /**
     * Forces Log4j2 RoutingAppender to close the file stream for the given testName
     * immediately so Windows releases the file lock.
     */
    private static void releaseAppenderLock(String testName) {
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();
            Appender appender = config.getAppender("RoutingLogger");
            if (appender instanceof RoutingAppender routingAppender) {
                routingAppender.deleteAppender(testName);
            }
        } catch (Exception ignored) {
        }
    }
}