package com.gmidi.midi;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Persists incoming MIDI events into a Standard MIDI File. The recorder tracks real-time microsecond
 * deltas and maps them to PPQ ticks so sequencers can reproduce the human timing of the original
 * performance.
 */
public class MidiRecorder {

    private static final int PPQ = 960;
    private static final int DEFAULT_TEMPO_US = 500_000; // 120 BPM
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private Sequence sequence;
    private Track track;
    private boolean recording;
    private long startNanos;
    private long lastEventMicros;
    private long lastTick;
    private int currentTempoUs = DEFAULT_TEMPO_US;
    private Path outputFile;

    public void start(Path output, String deviceName, long startNanos) throws IOException, InvalidMidiDataException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.sequence = new Sequence(Sequence.PPQ, PPQ);
        this.track = sequence.createTrack();
        this.recording = true;
        this.startNanos = startNanos;
        this.lastEventMicros = 0;
        this.lastTick = 0;
        this.currentTempoUs = DEFAULT_TEMPO_US;
        this.outputFile = output;

        addMetaEvent(createTextMeta(0x03, "GMIDI Recorder"), 0); // Track name
        if (deviceName != null && !deviceName.isBlank()) {
            addMetaEvent(createTextMeta(0x01, "Device: " + deviceName), 0);
        }
        addMetaEvent(createTextMeta(0x01, "Date: " + DATE_FORMAT.format(Instant.now().atZone(ZoneId.systemDefault()))), 0);
        addTempoMeta(DEFAULT_TEMPO_US, 0);
    }

    public boolean isRecording() {
        return recording;
    }

    public void recordNoteOn(int note, int velocity, long timestampNanos) {
        if (!recording) {
            return;
        }
        long micros = nanosToMicros(timestampNanos);
        enqueue(shortMessage(ShortMessage.NOTE_ON, note, velocity), micros);
    }

    public void recordNoteOff(int note, long timestampNanos) {
        if (!recording) {
            return;
        }
        long micros = nanosToMicros(timestampNanos);
        enqueue(shortMessage(ShortMessage.NOTE_OFF, note, 0), micros);
    }

    public void recordTempoChange(int microsecondsPerQuarter, long timestampNanos) {
        if (!recording) {
            return;
        }
        long micros = nanosToMicros(timestampNanos);
        addTempoMeta(microsecondsPerQuarter, micros);
        currentTempoUs = microsecondsPerQuarter;
    }

    public void recordSustain(boolean sustainOn, long timestampNanos) {
        if (!recording) {
            return;
        }
        long micros = nanosToMicros(timestampNanos);
        int value = sustainOn ? 127 : 0;
        enqueue(shortMessage(ShortMessage.CONTROL_CHANGE, 64, value), micros);
    }

    private long nanosToMicros(long timestampNanos) {
        return Math.max(0, (timestampNanos - startNanos) / 1_000);
    }

    private void enqueue(ShortMessage message, long micros) {
        long deltaMicros = micros - lastEventMicros;
        long deltaTicks = microsToTicks(deltaMicros);
        lastTick += deltaTicks;
        lastEventMicros = micros;
        track.add(new MidiEvent(message, lastTick));
    }

    private void addTempoMeta(int microsecondsPerQuarter, long micros) {
        try {
            long deltaMicros = micros - lastEventMicros;
            long deltaTicks = microsToTicks(deltaMicros);
            lastTick += deltaTicks;
            lastEventMicros = micros;
            MetaMessage meta = new MetaMessage();
            byte[] data = new byte[] {
                    (byte) ((microsecondsPerQuarter >> 16) & 0xFF),
                    (byte) ((microsecondsPerQuarter >> 8) & 0xFF),
                    (byte) (microsecondsPerQuarter & 0xFF)
            };
            meta.setMessage(0x51, data, data.length);
            addMetaEvent(meta, lastTick);
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException("Unable to record tempo change", e);
        }
    }

    private void addMetaEvent(MetaMessage meta, long tick) {
        track.add(new MidiEvent(meta, Math.max(0, tick)));
    }

    private MetaMessage createTextMeta(int type, String text) throws InvalidMidiDataException {
        MetaMessage meta = new MetaMessage();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        meta.setMessage(type, bytes, bytes.length);
        return meta;
    }

    private ShortMessage shortMessage(int command, int note, int velocity) {
        try {
            return new ShortMessage(command, 0, note, velocity);
        } catch (InvalidMidiDataException e) {
            throw new IllegalStateException("Unable to create MIDI message", e);
        }
    }

    private long microsToTicks(long micros) {
        if (micros <= 0) {
            return 0;
        }
        return Math.max(1, (micros * PPQ) / currentTempoUs);
    }

    public void stop() throws IOException {
        if (!recording) {
            return;
        }
        recording = false;
        MidiSystem.write(sequence, 1, outputFile.toFile());
    }
}
