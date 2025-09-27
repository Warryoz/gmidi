package com.gmidi.video;

import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/**
 * Pipes PNG frames into an ffmpeg process. Frames are encoded on a background thread so the JavaFX
 * application thread remains responsive even when the encoder performs heavy work.
 */
public class VideoRecorder {

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong framesWritten = new AtomicLong();
    private final AtomicLong framesDropped = new AtomicLong();

    private Process ffmpegProcess;
    private ExecutorService encoderExecutor;
    private OutputStream ffmpegInput;
    private Thread stderrDrainer;
    private final StringBuilder stderrBuffer = new StringBuilder();

    public void start(Path outputFile, VideoSettings settings) throws IOException {
        Objects.requireNonNull(outputFile, "outputFile");
        Objects.requireNonNull(settings, "settings");
        if (running.get()) {
            throw new IllegalStateException("Video recording already running");
        }

        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        ProcessBuilder builder = new ProcessBuilder(
                "ffmpeg",
                "-y",
                "-f", "image2pipe",
                "-r", Integer.toString(settings.getFps()),
                "-i", "-",
                "-c:v", "libx264",
                "-pix_fmt", "yuv420p",
                "-preset", settings.getPreset(),
                "-crf", Integer.toString(settings.getCrf()),
                outputFile.toAbsolutePath().toString()
        );
        builder.redirectErrorStream(false);
        try {
            ffmpegProcess = builder.start();
        } catch (IOException ex) {
            throw new IOException("Unable to start ffmpeg. Ensure it is installed and visible on PATH.", ex);
        }

        ffmpegInput = ffmpegProcess.getOutputStream();
        ThreadFactory factory = runnable -> {
            Thread t = new Thread(runnable, "gmidi-ffmpeg-writer");
            t.setDaemon(true);
            return t;
        };
        int queueCapacity = Math.max(4, settings.getFps() / 2);
        encoderExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                factory,
                new ThreadPoolExecutor.AbortPolicy()
        );
        running.set(true);
        framesWritten.set(0);
        framesDropped.set(0);
        stderrBuffer.setLength(0);
        startStderrDrainer();
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getFramesWritten() {
        return framesWritten.get();
    }

    public long getFramesDropped() {
        return framesDropped.get();
    }

    public boolean writeFrame(WritableImage image) {
        if (!running.get()) {
            return false;
        }
        try {
            encoderExecutor.submit(() -> encode(image));
            return true;
        } catch (RejectedExecutionException ex) {
            framesDropped.incrementAndGet();
            return false;
        }
    }

    private void encode(WritableImage image) {
        try {
            BufferedImage buffered = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            PixelReader reader = image.getPixelReader();
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                reader.getPixels(0, y, width, 1, PixelFormat.getIntArgbInstance(), row, 0, width);
                buffered.setRGB(0, y, width, 1, row, 0, width);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(buffered, "png", baos);
            byte[] bytes = baos.toByteArray();
            synchronized (this) {
                ffmpegInput.write(bytes);
                ffmpegInput.flush();
            }
            framesWritten.incrementAndGet();
        } catch (Exception ex) {
            framesDropped.incrementAndGet();
        }
    }

    private void startStderrDrainer() {
        stderrDrainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(ffmpegProcess.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderrBuffer) {
                        stderrBuffer.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
            }
        }, "gmidi-ffmpeg-stderr");
        stderrDrainer.setDaemon(true);
        stderrDrainer.start();
    }

    public void stop(Duration timeout) throws IOException, InterruptedException {
        if (!running.getAndSet(false)) {
            return;
        }
        encoderExecutor.shutdown();
        if (!encoderExecutor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            encoderExecutor.shutdownNow();
        }
        try {
            ffmpegInput.close();
        } catch (IOException ignored) {
        }
        int exit = ffmpegProcess.waitFor();
        if (stderrDrainer != null) {
            stderrDrainer.join();
        }
        if (exit != 0) {
            String stderr;
            synchronized (stderrBuffer) {
                stderr = stderrBuffer.toString();
            }
            throw new IOException("ffmpeg exited with code " + exit + "\n" + stderr);
        }
        encoderExecutor = null;
        ffmpegProcess = null;
        ffmpegInput = null;
    }
}
