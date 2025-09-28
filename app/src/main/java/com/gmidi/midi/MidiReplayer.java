package com.gmidi.midi;

import javafx.application.Platform;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiDevice;
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
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;

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
    private final IntSupplier transposeSupplier;
    private final VisualSink visualSink;

    private MidiService.MidiProgram program = new MidiService.MidiProgram(0, 0, 0, "GM Program 0");
    private Sequence currentSequence;
    private boolean sequenceFromFile;
    private Path sequenceFile;

    private Runnable finishedListener;

    /**
     * Callback invoked whenever the sequencer produces note events so the UI can mirror playback.
     */
    public interface VisualSink {
        void noteOn(int midi, int velocity, long nanoTime);

        void noteOff(int midi, long nanoTime);
    }

    public MidiReplayer(VisualSink visualSink,
                        Synthesizer synthesizer,
                        IntSupplier transposeSupplier) throws MidiUnavailableException {
        this.visualSink = Objects.requireNonNull(visualSink, "visualSink");
        this.synthesizer = Objects.requireNonNull(synthesizer, "synthesizer");
        if (!synthesizer.isOpen()) {
            synthesizer.open();
        }
        this.synthReceiver = synthesizer.getReceiver();
        this.transposeSupplier = Objects.requireNonNull(transposeSupplier, "transposeSupplier");
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
        Receiver synthTarget = new TransposeReceiver(synthReceiver, transposeSupplier);
        Receiver visualTarget = new TransposeReceiver(visualReceiver, transposeSupplier);
        this.transmitter.setReceiver(new TeeReceiver(synthTarget, visualTarget));
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
        javax.sound.midi.Track track = sequence.createTrack();
        insertProgramChange(track);
        MidiEventBuilder builder = new MidiEventBuilder(track);
        for (MidiRecorder.Event event : events) {
            builder.add(event.message(), Math.max(0, event.tick()));
        }
        builder.finish(ppq);
        sequencer.setSequence(sequence);
        sequencer.setTempoInBPM(bpm);
        sequencer.setTickPosition(0);
        currentSequence = sequence;
        sequenceFromFile = false;
        sequenceFile = null;
    }

    public void setProgram(MidiService.MidiProgram program) {
        this.program = program != null ? program : new MidiService.MidiProgram(0, 0, 0, "GM Program 0");
    }

    public void play() {
        if (currentSequence == null) {
            return;
        }
        if (sequencer.getTickPosition() >= sequencer.getTickLength()) {
            sequencer.setTickPosition(0);
        }
        sequencer.start();
    }

    public void pause() {
        if (sequencer.isRunning()) {
            sequencer.stop();
        }
    }

    public void stop() {
        stopInternal(true);
    }

    public boolean isPlaying() {
        return sequencer.isRunning();
    }

    public boolean isPaused() {
        return !sequencer.isRunning() && sequencer.getTickPosition() > 0
                && sequencer.getTickPosition() < sequencer.getTickLength();
    }

    public boolean hasSequence() {
        return currentSequence != null;
    }

    public boolean isAtEnd() {
        return currentSequence != null && sequencer.getTickPosition() >= sequencer.getTickLength();
    }

    public Sequence getCurrentSequence() {
        return currentSequence;
    }

    public boolean isSequenceFromFile() {
        return sequenceFromFile;
    }

    public Path getSequenceFile() {
        return sequenceFile;
    }

    public void rewind() {
        sequencer.setTickPosition(0);
    }

    public void setTempoFactor(float factor) {
        sequencer.setTempoFactor(factor);
    }

    public void loadSequenceFromFile(File midiFile, MidiService.MidiProgram program)
            throws IOException, InvalidMidiDataException {
        Objects.requireNonNull(midiFile, "midiFile");
        Sequence sequence = MidiSystem.getSequence(midiFile);
        this.program = program != null ? program : new MidiService.MidiProgram(0, 0, 0, "GM Program 0");
        ensureProgramEvent(sequence, this.program);
        sequencer.setSequence(sequence);
        sequencer.setTempoFactor(1.0f);
        sequencer.setTickPosition(0);
        currentSequence = sequence;
        sequenceFromFile = true;
        sequenceFile = midiFile.toPath();
    }

    public void renderToWav(Sequence sequence,
                            Path output,
                            MidiService.ReverbPreset preset,
                            int transpose)
            throws IOException, MidiUnavailableException {
        Objects.requireNonNull(sequence, "sequence");
        Objects.requireNonNull(output, "output");
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        AudioSynthesizerFacade audioSynth = findAudioSynthesizer();
        if (audioSynth == null) {
            throw new IOException("No AudioSynthesizer implementation available");
        }
        AudioFormat format = new AudioFormat(44_100, 16, 2, true, false);
        try (AudioInputStream stream = audioSynth.openStream(format)) {
            Sequencer offlineSequencer = MidiSystem.getSequencer(false);
            offlineSequencer.open();
            try {
                Receiver synthReceiver = audioSynth.getReceiver();
                Receiver transposed = new TransposeReceiver(synthReceiver, () -> transpose);
                offlineSequencer.getTransmitter().setReceiver(transposed);
                applyPreset(audioSynth.getSynthesizer(), preset);
                offlineSequencer.setSequence(sequence);
                offlineSequencer.setTickPosition(0);
                Thread finisher = new Thread(() -> {
                    try {
                        while (offlineSequencer.isRunning()) {
                            Thread.sleep(10);
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }, "gmidi-audio-render");
                finisher.setDaemon(true);
                offlineSequencer.start();
                finisher.start();
                AudioSystem.write(stream, AudioFileFormat.Type.WAVE, output.toFile());
                try {
                    finisher.join();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            } catch (InvalidMidiDataException e) {
                throw new RuntimeException(e);
            } finally {
                offlineSequencer.stop();
                offlineSequencer.close();
            }
        } finally {
            audioSynth.close();
        }
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

    private void insertProgramChange(javax.sound.midi.Track track) throws InvalidMidiDataException {
        MidiService.MidiProgram patch = program;
        if (patch == null) {
            patch = new MidiService.MidiProgram(0, 0, 0, "GM Program 0");
        }
        addProgramEvents(track, patch);
    }

    private int clamp7bit(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 127) {
            return 127;
        }
        return value;
    }

    private void ensureProgramEvent(Sequence sequence, MidiService.MidiProgram patch) throws InvalidMidiDataException {
        if (patch == null) {
            patch = new MidiService.MidiProgram(0, 0, 0, "GM Program 0");
        }
        javax.sound.midi.Track[] tracks = sequence.getTracks();
        if (tracks.length == 0) {
            sequence.createTrack();
            tracks = sequence.getTracks();
        }
        addProgramEvents(tracks[0], patch);
    }

    private void addProgramEvents(javax.sound.midi.Track track, MidiService.MidiProgram patch)
            throws InvalidMidiDataException {
        if (patch.bankMsb() != 0 || patch.bankLsb() != 0) {
            ShortMessage bankMsb = new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 0, clamp7bit(patch.bankMsb()));
            track.add(new MidiEvent(bankMsb, 0));
            ShortMessage bankLsb = new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 32, clamp7bit(patch.bankLsb()));
            track.add(new MidiEvent(bankLsb, 0));
        }
        ShortMessage pc = new ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, clamp7bit(patch.program()), 0);
        track.add(new MidiEvent(pc, 0));
    }

    private AudioSynthesizerFacade findAudioSynthesizer() throws MidiUnavailableException {
        Class<?> audioSynthClass = resolveAudioSynthesizerClass();
        if (audioSynthClass == null) {
            return null;
        }
        Synthesizer synth = MidiSystem.getSynthesizer();
        if (audioSynthClass.isInstance(synth)) {
            return AudioSynthesizerFacade.wrap(synth, audioSynthClass);
        }
        for (javax.sound.midi.MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            MidiDevice device = MidiSystem.getMidiDevice(info);
            if (audioSynthClass.isInstance(device) && device instanceof Synthesizer found) {
                return AudioSynthesizerFacade.wrap(found, audioSynthClass);
            }
        }
        return null;
    }

    private Class<?> resolveAudioSynthesizerClass() {
        try {
            return Class.forName("com.sun.media.sound.AudioSynthesizer");
        } catch (ClassNotFoundException ex) {
            return null;
        }
    }

    private void applyPreset(Synthesizer synth, MidiService.ReverbPreset preset) {
        if (preset == null) {
            preset = MidiService.ReverbPreset.ROOM;
        }
        MidiChannel[] channels = synth.getChannels();
        if (channels == null) {
            return;
        }
        for (MidiChannel channel : channels) {
            if (channel != null) {
                channel.controlChange(91, clamp7bit(preset.reverbCc()));
                channel.controlChange(93, clamp7bit(preset.chorusCc()));
            }
        }
    }

    private static final class AudioSynthesizerFacade implements AutoCloseable {
        private final Synthesizer synth;
        private final Method openStreamMethod;

        private AudioSynthesizerFacade(Synthesizer synth, Method openStreamMethod) {
            this.synth = synth;
            this.openStreamMethod = openStreamMethod;
        }

        static AudioSynthesizerFacade wrap(Synthesizer synth, Class<?> audioSynthClass)
                throws MidiUnavailableException {
            try {
                Method openStream = audioSynthClass.getMethod("openStream", AudioFormat.class, Map.class);
                if (!synth.isOpen()) {
                    synth.open();
                }
                return new AudioSynthesizerFacade(synth, openStream);
            } catch (NoSuchMethodException ex) {
                throw new IllegalStateException("AudioSynthesizer does not expose expected openStream method", ex);
            }
        }

        AudioInputStream openStream(AudioFormat format) throws IOException, MidiUnavailableException {
            Map<String, Object> info = new HashMap<>();
            try {
                return (AudioInputStream) openStreamMethod.invoke(synth, format, info);
            } catch (IllegalAccessException ex) {
                throw new IOException("Unable to access AudioSynthesizer#openStream", ex);
            } catch (InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof MidiUnavailableException mie) {
                    throw mie;
                }
                if (cause instanceof IOException ioe) {
                    throw ioe;
                }
                throw new IOException("AudioSynthesizer#openStream invocation failed", cause);
            }
        }

        Receiver getReceiver() throws MidiUnavailableException {
            return synth.getReceiver();
        }

        Synthesizer getSynthesizer() {
            return synth;
        }

        @Override
        public void close() {
            synth.close();
        }
    }

    private final class TransposeReceiver implements Receiver {
        private final Receiver out;
        private final IntSupplier semitoneSupplier;

        private TransposeReceiver(Receiver out, IntSupplier semitoneSupplier) {
            this.out = out;
            this.semitoneSupplier = semitoneSupplier;
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (!(message instanceof ShortMessage shortMessage)) {
                out.send(message, timeStamp);
                return;
            }
            int command = shortMessage.getCommand();
            int channel = shortMessage.getChannel();
            if ((command == ShortMessage.NOTE_ON || command == ShortMessage.NOTE_OFF) && channel != 9) {
                int semis = semitoneSupplier.getAsInt();
                if (semis != 0) {
                    int note = clamp7bit(shortMessage.getData1() + semis);
                    int velocity = shortMessage.getData2();
                    try {
                        ShortMessage shifted = new ShortMessage();
                        shifted.setMessage(command, channel, note, velocity);
                        out.send(shifted, timeStamp);
                        return;
                    } catch (InvalidMidiDataException ignored) {
                        // Fall through to original event.
                    }
                }
            }
            out.send(message, timeStamp);
        }

        @Override
        public void close() {
            out.close();
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
