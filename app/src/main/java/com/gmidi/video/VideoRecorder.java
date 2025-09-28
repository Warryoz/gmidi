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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
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
    private String ffmpegExecutablePath;
    private List<String> ffmpegCommand = Collections.emptyList();
    private boolean manualPush;

    public void start(Path outputFile, VideoSettings settings) throws IOException {
        startInternal(outputFile, settings, false);
    }

    public void beginFramePush(Path outputFile, VideoSettings settings) throws IOException {
        startInternal(outputFile, settings, true);
    }

    public void begin(int fps, int width, int height, Path outputFile) throws IOException {
        if (fps <= 0) {
            throw new IllegalArgumentException("FPS must be positive");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Video dimensions must be positive");
        }
        VideoSettings settings = new VideoSettings();
        settings.setFps(fps);
        settings.setWidth(width);
        settings.setHeight(height);
        try {
            beginFramePush(outputFile, settings);
        } catch (IOException ex) {
            throw friendly(ex);
        }
    }

    private void startInternal(Path outputFile, VideoSettings settings, boolean manual) throws IOException {
        Objects.requireNonNull(outputFile, "outputFile");
        Objects.requireNonNull(settings, "settings");
        if (running.get()) {
            throw new IllegalStateException("Video recording already running");
        }

        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        ffmpegExecutablePath = FfmpegLocator.resolve(settings);

        List<String> command = new ArrayList<>();
        command.add(ffmpegExecutablePath);
        command.add("-y");
        command.add("-f");
        command.add("image2pipe");
        command.add("-vcodec");
        command.add("png");
        command.add("-r");
        command.add(Integer.toString(settings.getFps()));
        command.add("-i");
        command.add("-");
        command.add("-vf");
        command.add("scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p");
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add(settings.getPreset());
        command.add("-crf");
        command.add(Integer.toString(settings.getCrf()));
        command.add("-movflags");
        command.add("+faststart");
        command.add(outputFile.toAbsolutePath().toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        try {
            ffmpegProcess = builder.start();
        } catch (IOException ex) {
            ffmpegExecutablePath = null;
            throw friendly(new IOException("Unable to start ffmpeg at " + builder.command().get(0) + ": " + ex.getMessage(), ex));
        }

        ffmpegCommand = List.copyOf(builder.command());
        logCommand();

        ffmpegInput = ffmpegProcess.getOutputStream();
        running.set(true);
        manualPush = manual;
        framesWritten.set(0);
        framesDropped.set(0);
        stderrBuffer.setLength(0);

        if (!manual) {
            ThreadFactory factory = runnable -> {
                Thread t = new Thread(runnable, "gmidi-ffmpeg-writer");
                t.setDaemon(true);
                return t;
            };
            int queueCapacity = Math.max(4, settings.getFps() / 2);
            BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueCapacity);
            encoderExecutor = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    queue,
                    factory,
                    (runnable, executor) -> {
                        if (executor.isShutdown()) {
                            throw new RejectedExecutionException("Encoder shutdown");
                        }
                        Runnable dropped = executor.getQueue().poll();
                        if (dropped != null) {
                            framesDropped.incrementAndGet();
                        }
                        executor.execute(runnable);
                    }
            );
        } else {
            encoderExecutor = null;
        }
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

    public void end() throws IOException, InterruptedException {
        stop(Duration.ofSeconds(5));
    }

    public boolean writeFrame(WritableImage image) {
        if (!running.get()) {
            return false;
        }
        if (manualPush) {
            try {
                pushFrame(image);
                return true;
            } catch (IOException ex) {
                framesDropped.incrementAndGet();
                return false;
            }
        }
        ExecutorService executor = encoderExecutor;
        if (executor == null) {
            return false;
        }
        try {
            executor.submit(() -> {
                try {
                    encode(image);
                    framesWritten.incrementAndGet();
                } catch (IOException ex) {
                    framesDropped.incrementAndGet();
                }
            });
            return true;
        } catch (RejectedExecutionException ex) {
            framesDropped.incrementAndGet();
            return false;
        }
    }

    public void pushFrame(WritableImage image) throws IOException {
        if (!manualPush || !running.get()) {
            throw new IllegalStateException("Video recorder not in frame push mode");
        }
        encode(image);
        framesWritten.incrementAndGet();
    }

    private void encode(WritableImage image) throws IOException {
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
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("Failed to encode frame", ex);
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
        ExecutorService executor = encoderExecutor;
        if (executor != null) {
            executor.shutdown();
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        }
        try {
            if (ffmpegInput != null) {
                ffmpegInput.close();
            }
        } catch (IOException ignored) {
        }
        int exit = ffmpegProcess != null ? ffmpegProcess.waitFor() : 0;
        if (stderrDrainer != null) {
            stderrDrainer.join();
            stderrDrainer = null;
        }
        if (exit != 0) {
            String stderr;
            synchronized (stderrBuffer) {
                stderr = stderrBuffer.toString();
            }
            throw new IOException("ffmpeg exited with code " + exit + "\n" + summarise(stderr));
        }
        encoderExecutor = null;
        ffmpegProcess = null;
        ffmpegInput = null;
        ffmpegExecutablePath = null;
        ffmpegCommand = Collections.emptyList();
        manualPush = false;
    }

    public void abortQuietly() {
        if (!running.getAndSet(false)) {
            return;
        }
        ExecutorService executor = encoderExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }
        try {
            if (ffmpegInput != null) {
                ffmpegInput.close();
            }
        } catch (IOException ignored) {
        }
        if (ffmpegProcess != null && ffmpegProcess.isAlive()) {
            ffmpegProcess.destroyForcibly();
        }
        encoderExecutor = null;
        ffmpegProcess = null;
        ffmpegInput = null;
        ffmpegExecutablePath = null;
        ffmpegCommand = Collections.emptyList();
        manualPush = false;
    }

    private void logCommand() {
        if (ffmpegCommand.isEmpty()) {
            return;
        }
        System.out.println("[VideoRecorder] Using FFmpeg executable: " + ffmpegExecutablePath);
        List<String> sanitised = new ArrayList<>(ffmpegCommand);
        if (!sanitised.isEmpty()) {
            int last = sanitised.size() - 1;
            try {
                Path output = Path.of(sanitised.get(last));
                sanitised.set(last, output.getFileName().toString());
            } catch (Exception ignored) {
            }
        }
        System.out.println("[VideoRecorder] Command: " + String.join(" ", sanitised));
    }

    public void muxWithAudio(Path videoFile, Path audioFile, Path outputFile, VideoSettings settings) throws IOException {
        Objects.requireNonNull(videoFile, "videoFile");
        Objects.requireNonNull(audioFile, "audioFile");
        Objects.requireNonNull(outputFile, "outputFile");
        Objects.requireNonNull(settings, "settings");
        Path parent = outputFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String ffmpegPath = FfmpegLocator.resolve(settings);
        List<String> command = List.of(
                ffmpegPath,
                "-y",
                "-i",
                videoFile.toAbsolutePath().toString(),
                "-i",
                audioFile.toAbsolutePath().toString(),
                "-c:v",
                "copy",
                "-c:a",
                "aac",
                "-shortest",
                outputFile.toAbsolutePath().toString()
        );
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = null;
        try {
            process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("ffmpeg mux failed with code " + exit + "\n" + output);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("ffmpeg mux interrupted", ex);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String summarise(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "(no stderr output)";
        }
        String[] lines = stderr.split("\\R");
        if (lines.length <= 100) {
            return stderr;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("--- ffmpeg stderr (first 50 lines) ---\n");
        for (int i = 0; i < Math.min(50, lines.length); i++) {
            builder.append(lines[i]).append('\n');
        }
        builder.append("--- snip ---\n");
        builder.append("--- ffmpeg stderr (last 50 lines) ---\n");
        int start = Math.max(50, lines.length - 50);
        for (int i = start; i < lines.length; i++) {
            builder.append(lines[i]).append('\n');
        }
        return builder.toString();
    }

    private IOException friendly(IOException ex) {
        String message = ex.getMessage();
        if (message != null) {
            String lower = message.toLowerCase();
            if (lower.contains("createprocess error=2") || lower.contains("no such file") || lower.contains("not found")) {
                return new IOException("FFmpeg executable not found. Configure it in settings.", ex);
            }
        }
        return ex;
    }
}
