package com.gmidi.midi;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Provides timestamped file names for MIDI recordings.
 */
public final class RecordingFileNamer {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private RecordingFileNamer() {
    }

    public static String defaultFileName() {
        return defaultFileName(Clock.systemDefaultZone());
    }

    public static String defaultFileName(Clock clock) {
        LocalDateTime now = LocalDateTime.now(clock);
        return "recording-" + FORMATTER.format(now) + ".mid";
    }
}
