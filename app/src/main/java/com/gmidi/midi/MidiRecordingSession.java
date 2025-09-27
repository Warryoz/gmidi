package com.gmidi.midi;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Scanner;
import javax.sound.midi.*;

/**
 * Handles recording events from a MIDI device into a Standard MIDI File.
 */
public final class MidiRecordingSession {

    private static final int DEFAULT_RESOLUTION = 480;

    private final PrintStream out;

    public MidiRecordingSession(PrintStream out) {
        this.out = Objects.requireNonNull(out, "out");
    }

    public void record(MidiDevice.Info deviceInfo, Path outputPath, Scanner scanner)
            throws MidiUnavailableException, IOException {
        Objects.requireNonNull(deviceInfo, "deviceInfo");
        Objects.requireNonNull(outputPath, "outputPath");
        Objects.requireNonNull(scanner, "scanner");

        try (MidiDevice device = MidiSystem.getMidiDevice(deviceInfo);
             Sequencer sequencer = MidiSystem.getSequencer(false)) {
            device.open();
            sequencer.open();

            Sequence sequence = new Sequence(Sequence.PPQ, DEFAULT_RESOLUTION);
            sequencer.setSequence(sequence);
            Track track = sequence.createTrack();
            sequencer.recordEnable(track, -1);

            try (Transmitter transmitter = device.getTransmitter();
                 Receiver receiver = sequencer.getReceiver()) {
                transmitter.setReceiver(receiver);

                out.println();
                out.println("Press Enter when you are ready to start recording.");
                scanner.nextLine();

                sequencer.startRecording();
                out.println("Recording... Press Enter to stop.");
                scanner.nextLine();
            }

            if (sequencer.isRecording()) {
                sequencer.stopRecording();
            }
            sequencer.stop();
            sequencer.recordDisable(track);

            MidiSystem.write(sequence, 1, outputPath.toFile());
            out.printf("Saved recording to %s%n", outputPath);
        } catch (InvalidMidiDataException e) {
            throw new RuntimeException(e);
        }
    }
}
