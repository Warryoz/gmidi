package com.gmidi.midi;

import com.gmidi.recorder.RecordingInteraction;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.Track;
import javax.sound.midi.Transmitter;

/**
 * Handles recording events from a MIDI device into a Standard MIDI File.
 */
public final class MidiRecordingSession {

    private static final int DEFAULT_RESOLUTION = 480;

    public void record(
            MidiDevice.Info deviceInfo,
            Path outputPath,
            RecordingInteraction interaction) throws MidiUnavailableException, IOException {
        Objects.requireNonNull(deviceInfo, "deviceInfo");
        Objects.requireNonNull(outputPath, "outputPath");
        Objects.requireNonNull(interaction, "interaction");

        try (MidiDevice device = MidiSystem.getMidiDevice(deviceInfo);
             Sequencer sequencer = MidiSystem.getSequencer(false)) {
            device.open();
            sequencer.open();

            Sequence sequence = new Sequence(Sequence.PPQ, DEFAULT_RESOLUTION);
            sequencer.setSequence(sequence);
            Track track = sequence.createTrack();
            sequencer.recordEnable(track, -1);

            try (Transmitter transmitter = device.getTransmitter();
                 Receiver receiver = interaction.decorateReceiver(sequencer.getReceiver())) {
                transmitter.setReceiver(receiver);

                interaction.onReadyToRecord();
                interaction.awaitStart();

                sequencer.startRecording();
                interaction.onRecordingStarted();
                interaction.awaitStop();
            }

            if (sequencer.isRecording()) {
                sequencer.stopRecording();
            }
            sequencer.stop();
            sequencer.recordDisable(track);

            MidiSystem.write(sequence, 1, outputPath.toFile());
            interaction.onRecordingFinished(outputPath);
        } catch (InvalidMidiDataException e) {
            throw new RuntimeException(e);
        }
    }
}
