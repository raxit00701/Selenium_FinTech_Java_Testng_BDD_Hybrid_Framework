package utils.Videocapture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.openqa.selenium.Point;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public class VideoRecorder {

    private static final Logger LOGGER = LogManager.getLogger(VideoRecorder.class);

    private static final String VIDEO_DIR = System.getProperty("user.dir")
            + File.separator + "test-output"
            + File.separator + "video"
            + File.separator;

    private FFmpegFrameRecorder recorder;
    private Thread recordThread;
    private File videoFile;
    private volatile boolean isRecording = false;

    private static final int FRAME_RATE = 15;

    /**
     * Starts recording only the browser window in a background thread.
     *
     * @param testName The name of the test
     * @param driver   The WebDriver instance to get window dimensions
     */
    public void startRecording(String testName, WebDriver driver) {
        try {
            Files.createDirectories(Path.of(VIDEO_DIR));
            videoFile = new File(VIDEO_DIR + testName + ".mp4");

            // 1. Get exact Browser dimensions and position from Selenium
            org.openqa.selenium.Dimension size;
            Point position;
            try {
                size = driver.manage().window().getSize();
                position = driver.manage().window().getPosition();
            } catch (WebDriverException e) {
                LOGGER.warn("Could not get browser dimensions. Falling back to default rectangle.", e);
                size = new org.openqa.selenium.Dimension(1280, 720);
                position = new Point(0, 0);
            }

            // FFmpeg requires width and height to be strictly divisible by 2
            int width = size.width % 2 == 0 ? size.width : size.width - 1;
            int height = size.height % 2 == 0 ? size.height : size.height - 1;
            
            // Define the strict capture zone targeting only the browser
            Rectangle browserRect = new Rectangle(position.x, position.y, width, height);

            // 2. Setup FFmpeg Recorder
            recorder = new FFmpegFrameRecorder(videoFile, width, height);
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setFormat("mp4");
            recorder.setFrameRate(FRAME_RATE);
            recorder.setVideoQuality(0); 
            recorder.start();

            isRecording = true;

            // 3. Spin up background recording thread
            recordThread = new Thread(() -> {
                Robot robot;
                try {
                    robot = new Robot();
                } catch (Exception e) {
                    LOGGER.error("Failed to initialize AWT Robot", e);
                    return;
                }

                try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
                    while (isRecording) {
                        long startTime = System.currentTimeMillis();

                        // Grab only the browser rectangle, ignoring VS Code and Taskbar
                        BufferedImage screenCapture = robot.createScreenCapture(browserRect);
                        recorder.record(converter.getFrame(screenCapture));

                        long sleepTime = (1000 / FRAME_RATE) - (System.currentTimeMillis() - startTime);
                        if (sleepTime > 0) {
                            Thread.sleep(sleepTime);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("Error during recording", e);
                }
            });

            recordThread.start();
            LOGGER.info("Started targeted browser video recording for test: {}", testName);

        } catch (Exception e) {
            LOGGER.error("Failed to setup FFmpeg video recording", e);
        }
    }

    public void stopRecording(boolean keepVideo) {
        if (!isRecording) {
            return;
        }

        isRecording = false;

        try {
            if (recordThread != null) {
                recordThread.join(2000); 
            }

            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }

            if (keepVideo) {
                LOGGER.info("Video successfully saved to: {}", videoFile.getAbsolutePath());
            } else {
                Files.deleteIfExists(videoFile.toPath());
            }

        } catch (Exception e) {
            LOGGER.error("Error occurred while stopping video recording", e);
        }
    }
}