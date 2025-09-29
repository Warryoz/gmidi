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
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<NoteKey, Long> pendingReleases = new ConcurrentHashMap<>();

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

        default void keyDownUntil(int midi, long releaseMicros) {
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
            int midi = note.key;
            if (note.channel != 9) {
                midi = clamp7bit(midi + transposeSupplier.getAsInt());
            }
            if (midi < 0 || midi >= 128) {
                continue;
            }
            int velocity = velocityMap.map(note.velocity);
            long release = Math.max(note.onMicros, note.releaseMicros);
            visualSink.spawnVisual(midi, velocity, spawnMicros, note.onMicros, release);
            pendingReleases.put(new NoteKey(note.channel, midi), release);
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
            int channel = shortMessage.getChannel();
            int midi = shortMessage.getData1();
            int velocity = shortMessage.getData2();
            long positionMicros = sequencer.getMicrosecondPosition();
            long now = positionMicros >= 0 ? positionMicros * 1_000L : 0L;
            if (command == ShortMessage.NOTE_ON && velocity > 0) {
                long releaseMicros = Math.max(0L, positionMicros);
                Long scheduled = pendingReleases.remove(new NoteKey(channel, midi));
                if (scheduled != null) {
                    releaseMicros = Math.max(releaseMicros, scheduled);
                }
                long finalRelease = releaseMicros;
                Platform.runLater(() -> {
                    visualSink.noteOn(midi, velocity, now);
                    visualSink.keyDownUntil(midi, finalRelease);
                });
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
        try {
            visualNotes = collectVisualNotes(sequence);
        } catch (Exception ex) {
            visualNotes = List.of();
            resetVisualQueue();
            throw new IllegalStateException("Failed to resolve MIDI note timings", ex);
        }
        resetVisualQueue();
    }

    private void resetVisualQueue() {
        nextVisualIndex = 0;
        lastVisualMicros = Long.MIN_VALUE;
        pendingReleases.clear();
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

    public static List<VisualNote> collectVisualNotes(Sequence sequence) throws Exception {
        if (sequence == null) {
            return List.of();
        }
        List<EventRef> events = new ArrayList<>();
        long order = 0L;
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                MidiEvent event = track.get(i);
                MidiMessage message = event.getMessage();
                if (message instanceof ShortMessage shortMessage) {
                    events.add(new EventRef(event.getTick(), order++, shortMessage));
                }
            }
        }
        events.sort(Comparator.comparingLong(EventRef::tick).thenComparingLong(EventRef::order));
        Map<NoteKey, RawNote> active = new HashMap<>();
        Map<Integer, SustainTracker> sustain = new HashMap<>();
        List<RawNote> finished = new ArrayList<>();
        long tailTick = 0L;
        for (EventRef ref : events) {
            ShortMessage message = ref.message();
            int command = message.getCommand();
            int channel = message.getChannel();
            int data1 = message.getData1();
            int data2 = message.getData2();
            tailTick = Math.max(tailTick, ref.tick());
            if (command == ShortMessage.NOTE_ON && data2 > 0) {
                active.put(new NoteKey(channel, data1), new RawNote(channel, data1, data2, ref.tick()));
                continue;
            }
            if (command == ShortMessage.NOTE_OFF || (command == ShortMessage.NOTE_ON && data2 == 0)) {
                NoteKey key = new NoteKey(channel, data1);
                RawNote note = active.remove(key);
                if (note != null) {
                    note.offTick = Math.max(ref.tick(), note.onTick);
                    finished.add(note);
                }
                continue;
            }
            if (command == ShortMessage.CONTROL_CHANGE && data1 == 64) {
                sustain.computeIfAbsent(channel, ch -> new SustainTracker()).update(ref.tick(), data2);
            }
        }
        tailTick = Math.max(tailTick, sequence.getTickLength());
        for (RawNote dangling : active.values()) {
            dangling.offTick = Math.max(tailTick, dangling.onTick);
            finished.add(dangling);
        }
        for (SustainTracker tracker : sustain.values()) {
            tracker.closeOpenInterval(tailTick);
        }
        List<VisualNote> notes = new ArrayList<>(finished.size());
        try (TickClock clock = new TickClock(sequence)) {
            for (RawNote note : finished) {
                SustainTracker tracker = sustain.get(note.channel);
                long releaseTick = tracker != null
                        ? tracker.resolveReleaseTick(note.offTick)
                        : note.offTick;
                long onMicros = Math.max(0L, clock.microsAt(note.onTick));
                long releaseMicros = Math.max(onMicros, clock.microsAt(releaseTick));
                notes.add(new VisualNote(note.key, note.channel, onMicros, releaseMicros, note.velocity));
            }
        }
        notes.sort(Comparator.comparingLong(VisualNote::onMicros));
        return notes;
    }

    public static record VisualNote(int key, int channel, long onMicros, long releaseMicros, int velocity) {
    }

    private static final class RawNote {
        final int channel;
        final int key;
        final int velocity;
        final long onTick;
        long offTick;

        RawNote(int channel, int key, int velocity, long onTick) {
            this.channel = channel;
            this.key = key;
            this.velocity = velocity;
            this.onTick = onTick;
            this.offTick = onTick;
        }
    }

    private static final class SustainTracker {
        private final List<Interval> intervals = new ArrayList<>();
        private long activeStart = Long.MIN_VALUE;

        void update(long tick, int value) {
            boolean down = value >= 64;
            if (down) {
                if (activeStart == Long.MIN_VALUE) {
                    activeStart = tick;
                }
                return;
            }
            if (activeStart != Long.MIN_VALUE) {
                intervals.add(new Interval(activeStart, tick));
                activeStart = Long.MIN_VALUE;
            }
        }

        void closeOpenInterval(long tailTick) {
            if (activeStart != Long.MIN_VALUE) {
                intervals.add(new Interval(activeStart, tailTick));
                activeStart = Long.MIN_VALUE;
            }
        }

        long resolveReleaseTick(long offTick) {
            for (Interval interval : intervals) {
                if (offTick >= interval.start && offTick < interval.end) {
                    return Math.max(offTick, interval.end);
                }
            }
            return offTick;
        }
    }

    private record Interval(long start, long end) {
    }

    private record EventRef(long tick, long order, ShortMessage message) {
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

    private record NoteKey(int channel, int midi) {
    }

    static final class TickClock implements AutoCloseable {
        private final Sequencer seq;

        TickClock(Sequence sequence) throws Exception {
            seq = MidiSystem.getSequencer(false);
            seq.open();
            seq.setSequence(sequence);
        }

        long microsAt(long tick) {
            try {
                seq.setTickPosition(tick);
                return seq.getMicrosecondPosition();
            } catch (Exception ex) {
                return 0L;
            }
        }

        @Override
        public void close() {
            seq.close();
        }
    }
}
