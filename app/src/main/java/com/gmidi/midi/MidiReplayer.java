package com.gmidi.midi;

import javafx.application.Platform;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Transmitter;
import java.util.List;
import java.util.Objects;

/**
 * Replays recorded MIDI events through a shared {@link Sequencer}. Events are mirrored to both the
 * synthesizer for audio output and to a {@link VisualSink} so the UI stays in sync with playback.
 */
public final class MidiReplayer implements AutoCloseable {

    private final Sequencer sequencer;
    private final Synthesizer synthesizer;
    private final Receiver synthReceiver;
    private final Receiver visualReceiver;
    private final Transmitter transmitter;
    private final VisualSink visualSink;

    private Runnable finishedListener;

    /**
     * Callback invoked whenever the sequencer produces note events so the UI can mirror playback.
     */
    public interface VisualSink {
        void noteOn(int midi, int velocity, long nanoTime);

        void noteOff(int midi, long nanoTime);
    }

    public MidiReplayer(VisualSink visualSink, Synthesizer synthesizer) throws MidiUnavailableException {
        this.visualSink = Objects.requireNonNull(visualSink, "visualSink");
        this.synthesizer = Objects.requireNonNull(synthesizer, "synthesizer");
        if (!synthesizer.isOpen()) {
            synthesizer.open();
        }
        this.synthReceiver = synthesizer.getReceiver();
        this.sequencer = MidiSystem.getSequencer(false);
        sequencer.open();
        this.visualReceiver = new Receiver() {
            @Override
            public void send(MidiMessage message, long timeStamp) {
                handleVisualMessage(message);
            }

            @Override
            public void close() {
                // Nothing to do; the receiver lives for the duration of the application.
            }
        };
        this.transmitter = sequencer.getTransmitter();
        this.transmitter.setReceiver(new TeeReceiver(synthReceiver, visualReceiver));
        sequencer.addMetaEventListener(meta -> {
            if (meta.getType() == 0x2F) {
                handleEndOfTrack();
            }
        });
    }

    public void setOnFinished(Runnable listener) {
        this.finishedListener = listener;
    }

    public void setSequenceFromRecorded(List<MidiRecorder.Event> events, int ppq, float bpm)
            throws InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, ppq);
        MidiEventBuilder builder = new MidiEventBuilder(sequence.createTrack());
        for (MidiRecorder.Event event : events) {
            builder.add(event.message(), Math.max(0, event.tick()));
        }
        builder.finish(ppq);
        sequencer.setSequence(sequence);
        sequencer.setTempoInBPM(bpm);
    }

    public void play() {
        sequencer.stop();
        sequencer.setTickPosition(0);
        sequencer.start();
    }

    public void stop() {
        stopInternal(true);
    }

    public boolean isPlaying() {
        return sequencer.isRunning();
    }

    public void setTempoFactor(float factor) {
        sequencer.setTempoFactor(factor);
    }

    @Override
    public void close() {
        stopInternal(false);
        transmitter.close();
        sequencer.close();
    }

    private void handleVisualMessage(MidiMessage message) {
        if (message instanceof ShortMessage shortMessage) {
            int command = shortMessage.getCommand();
            int midi = shortMessage.getData1();
            int velocity = shortMessage.getData2();
            long now = System.nanoTime();
            if (command == ShortMessage.NOTE_ON && velocity > 0) {
                Platform.runLater(() -> visualSink.noteOn(midi, velocity, now));
            } else if (command == ShortMessage.NOTE_OFF || (command == ShortMessage.NOTE_ON && velocity == 0)) {
                Platform.runLater(() -> visualSink.noteOff(midi, now));
            }
        }
    }

    private void handleEndOfTrack() {
        stopInternal(false);
        if (finishedListener != null) {
            Platform.runLater(finishedListener);
        }
    }

    private void stopInternal(boolean notifyFinished) {
        sequencer.stop();
        sequencer.setTickPosition(0);
        flushNotes();
        if (notifyFinished && finishedListener != null) {
            Platform.runLater(finishedListener);
        }
    }

    private void flushNotes() {
        try {
            for (int note = 0; note < 128; note++) {
                ShortMessage off = new ShortMessage(ShortMessage.NOTE_OFF, 0, note, 0);
                synthReceiver.send(off, -1);
                visualReceiver.send(off, -1);
            }
            for (int channel = 0; channel < 16; channel++) {
                ShortMessage allSoundOff = new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 120, 0);
                synthReceiver.send(allSoundOff, -1);
                ShortMessage allNotesOff = new ShortMessage(ShortMessage.CONTROL_CHANGE, channel, 123, 0);
                synthReceiver.send(allNotesOff, -1);
            }
        } catch (InvalidMidiDataException ex) {
            // The controller numbers are constant; if construction fails there is nothing we can do.
        }
    }

    private static final class TeeReceiver implements Receiver {
        private final Receiver[] targets;

        TeeReceiver(Receiver... targets) {
            this.targets = targets;
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            for (Receiver receiver : targets) {
                receiver.send(message, timeStamp);
            }
        }

        @Override
        public void close() {
            // Receivers are owned by the caller (synthesizer, visual sink).
        }
    }

    private static final class MidiEventBuilder {
        private final javax.sound.midi.Track track;

        MidiEventBuilder(javax.sound.midi.Track track) {
            this.track = track;
        }

        void add(MidiMessage message, long tick) {
            track.add(new MidiEvent((MidiMessage) message.clone(), Math.max(0, tick)));
        }

        void finish(int ppq) throws InvalidMidiDataException {
            long lastTick = track.ticks();
            MetaMessage end = new MetaMessage();
            end.setMessage(0x2F, new byte[0], 0);
            track.add(new MidiEvent(end, lastTick + ppq));
        }
    }
}
