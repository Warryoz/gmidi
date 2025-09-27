package com.gmidi.midi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class RecordingFileNamerTest {

    @Test
    void generatesTimestampedName() {
        Clock fixedClock = Clock.fixed(Instant.parse("2024-12-31T23:59:45Z"), ZoneId.of("UTC"));
        String name = RecordingFileNamer.defaultFileName(fixedClock);
        assertEquals("recording-20241231-235945.mid", name);
    }
}
