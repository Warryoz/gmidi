package com.gmidi.video;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Locates the ffmpeg executable based on user configuration, sensible defaults, and the system PATH.
 */
public final class FfmpegLocator {

    public static final String WINDOWS_CHOCOLATEY_PATH = "C:\\ProgramData\\chocolatey\\bin\\ffmpeg.exe";

    private FfmpegLocator() {
    }

    public static String resolve(VideoSettings settings) throws IOException {
        Objects.requireNonNull(settings, "settings");
        Path configured = settings.getFfmpegExecutable();
        if (configured != null) {
            Path normalised = configured.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalised)) {
                throw new IOException("Configured FFmpeg path is not a file: " + normalised);
            }
            if (!Files.isExecutable(normalised)) {
                throw new IOException("Configured FFmpeg path is not executable: " + normalised);
            }
            return normalised.toString();
        }

        String windowsDefault = windowsChocolateyExecutable();
        if (windowsDefault != null) {
            return windowsDefault;
        }

        String located = locateOnPath();
        if (located != null) {
            return located;
        }

        return "ffmpeg";
    }

    public static String effectiveExecutable(String configured) {
        if (configured != null && !configured.isBlank()) {
            try {
                Path candidate = Paths.get(configured.trim());
                if (Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            } catch (InvalidPathException ignored) {
            }
        }
        String windowsDefault = windowsChocolateyExecutable();
        return windowsDefault != null ? windowsDefault : "ffmpeg";
    }

    public static String locateOnPath() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        String executable = isWindows() ? "ffmpeg.exe" : "ffmpeg";
        String[] entries = path.split(File.pathSeparator);
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            try {
                Path candidate = Paths.get(entry.trim()).resolve(executable);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            } catch (InvalidPathException ignored) {
            }
        }
        return null;
    }

    private static String windowsChocolateyExecutable() {
        if (!isWindows()) {
            return null;
        }
        Path candidate = Paths.get(WINDOWS_CHOCOLATEY_PATH);
        if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
            return candidate.toAbsolutePath().toString();
        }
        return null;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
    }
}
