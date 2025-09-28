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
import javax.sound.midi.Track;
import javax.sound.midi.Transmitter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final VelocityMap velocityMap;

    private MidiService.MidiProgram program = new MidiService.MidiProgram(0, 0, 0, "GM Program 0");
    private Sequence currentSequence;
    private boolean sequenceFromFile;
    private Path sequenceFile;
    private List<VisualNote> visualNotes = List.of();
    private int nextVisualIndex;
    private long lastVisualMicros = Long.MIN_VALUE;

    private MidiService.ReverbPreset reverbPreset = MidiService.ReverbPreset.ROOM;

    private Runnable finishedListener;

    /**
     * Callback invoked whenever the sequencer produces note events so the UI can mirror playback.
     */
    public interface VisualSink {
        void noteOn(int midi, int velocity, long nanoTime);

        void noteOff(int midi, long nanoTime);

        default void spawnVisual(int midi,
                                 int velocity,
                                 long spawnMicros,
                                 long impactMicros,
                                 long releaseMicros) {
        }
    }

    public MidiReplayer(VisualSink visualSink,
                        Synthesizer synthesizer,
                        IntSupplier transposeSupplier,
                        VelocityMap velocityMap) throws MidiUnavailableException {
        this.visualSink = Objects.requireNonNull(visualSink, "visualSink");
        this.synthesizer = Objects.requireNonNull(synthesizer, "synthesizer");
        if (!synthesizer.isOpen()) {
            synthesizer.open();
        }
        this.synthReceiver = synthesizer.getReceiver();
        this.transposeSupplier = Objects.requireNonNull(transposeSupplier, "transposeSupplier");
        this.velocityMap = Objects.requireNonNull(velocityMap, "velocityMap");
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
        Receiver synthTarget = new VelocityReceiver(new TransposeReceiver(synthReceiver, transposeSupplier), this.velocityMap);
        Receiver visualTarget = new VelocityReceiver(new TransposeReceiver(visualReceiver, transposeSupplier), this.velocityMap);
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
        MidiEventBuilder builder = new MidiEventBuilder(track);
        for (MidiRecorder.Event event : events) {
            builder.add(event.message(), Math.max(0, event.tick()));
        }
        builder.finish(ppq);
        try {
            insertPatchAndReverbAtTick0(sequence, program, reverbPreset);
        } catch (Exception ex) {
            InvalidMidiDataException wrapped = new InvalidMidiDataException(ex.getMessage());
            wrapped.initCause(ex);
            throw wrapped;
        }
        sequencer.setSequence(sequence);
        sequencer.setTempoInBPM(bpm);
        sequencer.setTickPosition(0);
        currentSequence = sequence;
        sequenceFromFile = false;
        sequenceFile = null;
        buildVisualNotes(sequence);
    }

    public void setProgram(MidiService.MidiProgram program) {
        this.program = program != null ? program : new MidiService.MidiProgram(0, 0, 0, "GM Program 0");
    }

    public void setReverbPreset(MidiService.ReverbPreset preset) {
        this.reverbPreset = preset != null ? preset : MidiService.ReverbPreset.ROOM;
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

    public Sequencer getSequencer() {
        return sequencer;
    }

    public boolean isAtEnd() {
        return currentSequence != null && sequencer.getTickPosition() >= sequencer.getTickLength();
    }

    public Sequence getCurrentSequence() {
        return currentSequence;
    }

    public long getSequenceLengthMicros() {
        Sequence sequence = sequencer.getSequence();
        return sequence != null ? sequence.getMicrosecondLength() : 0L;
    }

    public boolean isSequenceFromFile() {
        return sequenceFromFile;
    }

    public Path getSequenceFile() {
        return sequenceFile;
    }

    public void rewind() {
        sequencer.setTickPosition(0);
        resetVisualQueue();
    }

    public void setTempoFactor(float factor) {
        sequencer.setTempoFactor(factor);
    }

    public void pumpVisuals(long travelMicros) {
        if (visualNotes.isEmpty() || travelMicros <= 0 || !sequencer.isRunning()) {
            lastVisualMicros = sequencer.getMicrosecondPosition();
            return;
        }
        long currentMicros = sequencer.getMicrosecondPosition();
        if (currentMicros < lastVisualMicros) {
            nextVisualIndex = 0;
        }
        lastVisualMicros = currentMicros;
        while (nextVisualIndex < visualNotes.size()) {
            VisualNote note = visualNotes.get(nextVisualIndex);
            long spawnMicros = note.onMicros - travelMicros;
            if (spawnMicros > currentMicros) {
                break;
            }
            nextVisualIndex++;
            int midi = note.midi;
            if (note.channel != 9) {
                midi = clamp7bit(midi + transposeSupplier.getAsInt());
            }
            if (midi < 0 || midi >= 128) {
                continue;
            }
            int velocity = velocityMap.map(note.velocity);
            long release = Math.max(note.onMicros, note.offMicros);
            visualSink.spawnVisual(midi, velocity, spawnMicros, note.onMicros, release);
        }
    }

    public void loadSequenceFromFile(File midiFile) throws IOException, InvalidMidiDataException {
        Objects.requireNonNull(midiFile, "midiFile");
        Sequence sequence = MidiSystem.getSequence(midiFile);
        try {
            insertPatchAndReverbAtTick0(sequence, program, reverbPreset);
        } catch (Exception ex) {
            InvalidMidiDataException wrapped = new InvalidMidiDataException(ex.getMessage());
            wrapped.initCause(ex);
            throw wrapped;
        }
        sequencer.setSequence(sequence);
        sequencer.setTempoFactor(1.0f);
        sequencer.setTickPosition(0);
        currentSequence = sequence;
        sequenceFromFile = true;
        sequenceFile = midiFile.toPath();
        buildVisualNotes(sequence);
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
            long positionMicros = sequencer.getMicrosecondPosition();
            long now = positionMicros >= 0 ? positionMicros * 1_000L : 0L;
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
        resetVisualQueue();
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

    private void buildVisualNotes(Sequence sequence) {
        if (sequence == null) {
            visualNotes = List.of();
            resetVisualQueue();
            return;
        }
        visualNotes = MidiTimebase.extractNotesWithMicros(sequence);
        resetVisualQueue();
    }

    private void resetVisualQueue() {
        nextVisualIndex = 0;
        lastVisualMicros = Long.MIN_VALUE;
    }

    private static int clamp7bit(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 127) {
            return 127;
        }
        return value;
    }

    static void insertPatchAndReverbAtTick0(Sequence seq,
                                            MidiService.MidiProgram prog,
                                            MidiService.ReverbPreset rv) throws Exception {
        MidiService.MidiProgram patch = prog != null ? prog : new MidiService.MidiProgram(0, 0, 0, "GM Program 0");
        MidiService.ReverbPreset preset = rv != null ? rv : MidiService.ReverbPreset.ROOM;
        boolean[] used = new boolean[16];
        for (Track track : seq.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiMessage message = track.get(i).getMessage();
                if (message instanceof ShortMessage sm) {
                    int ch = sm.getChannel();
                    if (ch >= 0 && ch < 16) {
                        used[ch] = true;
                    }
                }
            }
        }
        Track target = seq.getTracks().length > 0 ? seq.getTracks()[0] : seq.createTrack();
        for (int ch = 0; ch < 16; ch++) {
            if (!used[ch] || ch == 9) {
                continue;
            }
            target.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 0, clamp7bit(patch.bankMsb())), 0));
            target.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 32, clamp7bit(patch.bankLsb())), 0));
            target.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, ch, clamp7bit(patch.program()), 0), 0));
            target.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 91, clamp7bit(preset.reverbCc())), 0));
            target.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, ch, 93, clamp7bit(preset.chorusCc())), 0));
        }
    }

    public static final class MidiTimebase {
        private MidiTimebase() {
        }

        public static List<VisualNote> extractNotesWithMicros(Sequence sequence) {
            if (sequence == null) {
                return List.of();
            }
            List<EventRef> events = new ArrayList<>();
            for (Track track : sequence.getTracks()) {
                for (int i = 0; i < track.size(); i++) {
                    MidiEvent event = track.get(i);
                    MidiMessage message = event.getMessage();
                    if (message instanceof ShortMessage shortMessage) {
                        int command = shortMessage.getCommand();
                        if (command == ShortMessage.NOTE_ON || command == ShortMessage.NOTE_OFF) {
                            events.add(new EventRef(event.getTick(), shortMessage));
                        }
                    }
                }
            }
            events.sort(Comparator.comparingLong(EventRef::tick));
            Map<NoteKey, NoteOn> active = new HashMap<>();
            List<VisualNote> notes = new ArrayList<>(events.size());
            try (Sequencer helper = MidiSystem.getSequencer(false)) {
                helper.open();
                helper.setSequence(sequence);
                for (EventRef ref : events) {
                    helper.setTickPosition(ref.tick());
                    long micros = Math.max(0L, helper.getMicrosecondPosition());
                    ShortMessage message = ref.message();
                    int command = message.getCommand();
                    int channel = message.getChannel();
                    int midi = message.getData1();
                    int velocity = message.getData2();
                    NoteKey key = new NoteKey(channel, midi);
                    if (command == ShortMessage.NOTE_ON && velocity > 0) {
                        active.put(key, new NoteOn(channel, midi, velocity, micros));
                    } else {
                        NoteOn on = active.remove(key);
                        if (on != null) {
                            long offMicros = Math.max(micros, on.onMicros);
                            notes.add(new VisualNote(on.channel, on.midi, on.velocity, on.onMicros, offMicros));
                        }
                    }
                }
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to resolve MIDI note timings", ex);
            }
            long tailMicros = Math.max(0L, sequence.getMicrosecondLength());
            for (NoteOn dangling : active.values()) {
                long offMicros = Math.max(tailMicros, dangling.onMicros);
                notes.add(new VisualNote(dangling.channel, dangling.midi, dangling.velocity, dangling.onMicros, offMicros));
            }
            notes.sort(Comparator.comparingLong(VisualNote::onMicros));
            return notes;
        }

        private record EventRef(long tick, ShortMessage message) {
        }

        private record NoteKey(int channel, int midi) {
        }

        private record NoteOn(int channel, int midi, int velocity, long onMicros) {
        }
    }

    public static record VisualNote(int channel, int midi, int velocity, long onMicros, long offMicros) {
    }

    private static final class VelocityReceiver implements Receiver {
        private final Receiver out;
        private final VelocityMap velocityMap;

        private VelocityReceiver(Receiver out, VelocityMap velocityMap) {
            this.out = out;
            this.velocityMap = velocityMap;
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (message instanceof ShortMessage shortMessage) {
                int command = shortMessage.getCommand();
                if (command == ShortMessage.NOTE_ON) {
                    int velocity = shortMessage.getData2();
                    if (velocity > 0) {
                        int mapped = velocityMap.map(velocity);
                        if (mapped != velocity) {
                            try {
                                ShortMessage remapped = new ShortMessage();
                                remapped.setMessage(ShortMessage.NOTE_ON,
                                        shortMessage.getChannel(),
                                        shortMessage.getData1(),
                                        mapped);
                                out.send(remapped, timeStamp);
                                return;
                            } catch (InvalidMidiDataException ignored) {
                                // Fall through to send original event.
                            }
                        }
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
