package com.gmidi.midi;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.Instrument;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Patch;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Transmitter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Facade around the Java Sound API that exposes incoming MIDI data as strongly typed events. The
 * service keeps only one input device open at a time and forwards events to registered listeners.
 */
public class MidiService {

    private final List<MidiMessageListener> listeners = new CopyOnWriteArrayList<>();
    private MidiDevice currentDevice;
    private Transmitter currentTransmitter;
    private MidiInput currentInput;
    private Synthesizer synthesizer;
    private Receiver synthReceiver;
    private Soundbank customSoundbank;
    private Soundbank defaultSoundbank;
    private boolean defaultBankLoaded;
    private MidiChannel primaryChannel;
    private String currentSoundFontPath;
    private final List<String> instrumentNames = new ArrayList<>();
    private Instrument[] availableInstruments = new Instrument[0];
    private MidiProgram currentProgram = MidiProgram.gmPiano();
    private volatile int transposeSemis;
    private volatile ReverbPreset reverbPreset = ReverbPreset.ROOM;
    private final VelocityMap velocityMap = new VelocityMap();

    public List<MidiInput> listInputs() throws MidiUnavailableException {
        List<MidiInput> result = new ArrayList<>();
        for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
            MidiDevice device = MidiSystem.getMidiDevice(info);
            if (device.getMaxTransmitters() == 0) {
                continue;
            }
            result.add(new MidiInput(info.getName(), info));
        }
        return result;
    }

    public void setListener(MidiMessageListener listener) {
        listeners.clear();
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public MidiInput getCurrentInput() {
        return currentInput;
    }

    public void open(MidiInput input) throws MidiUnavailableException {
        Objects.requireNonNull(input, "input");
        if (input.equals(currentInput)) {
            return;
        }
        close();
        MidiDevice device = MidiSystem.getMidiDevice(input.info());
        device.open();
        Transmitter transmitter = device.getTransmitter();
        transmitter.setReceiver(new DispatchingReceiver());
        currentDevice = device;
        currentTransmitter = transmitter;
        currentInput = input;
    }

    public void close() {
        if (currentTransmitter != null) {
            currentTransmitter.close();
            currentTransmitter = null;
        }
        if (currentDevice != null && currentDevice.isOpen()) {
            currentDevice.close();
        }
        currentDevice = null;
        currentInput = null;
    }

    public void addListener(MidiMessageListener listener) {
        listeners.add(listener);
    }

    public void removeListener(MidiMessageListener listener) {
        listeners.remove(listener);
    }

    /**
     * Opens the shared synthesizer and optionally loads a custom SoundFont. Failures bubble up so
     * the caller can surface a friendly message and fall back to the default bank.
     */
    public synchronized void initSynth(String soundFontPath) throws Exception {
        if (synthesizer == null) {
            synthesizer = MidiSystem.getSynthesizer();
            synthesizer.open();
            synthReceiver = synthesizer.getReceiver();
            defaultSoundbank = synthesizer.getDefaultSoundbank();
            if (defaultSoundbank != null) {
                defaultBankLoaded = synthesizer.loadAllInstruments(defaultSoundbank);
            }
        }
        String normalized = normalize(soundFontPath);
        if (!Objects.equals(currentSoundFontPath, normalized)) {
            applySoundFont(normalized);
        }
        MidiChannel[] channels = synthesizer.getChannels();
        primaryChannel = channels.length > 0 ? channels[0] : null;
        refreshInstruments();
        applyReverb(reverbPreset);
    }

    /**
     * Chooses an instrument by name if possible. The search is case-insensitive and falls back to
     * General MIDI program 0 when no match is found.
     */
    public synchronized String applyInstrument(String preferName, Integer gmProgram) {
        ensureSynthReady();
        Instrument chosen = findInstrument(preferName);
        if (chosen != null) {
            synthesizer.loadInstrument(chosen);
            Patch patch = chosen.getPatch();
            int program = patch.getProgram() & 0x7F;
            int bank = patch.getBank();
            int bankMsb = (bank >> 7) & 0x7F;
            int bankLsb = bank & 0x7F;
            sendProgram(bankMsb, bankLsb, program);
            currentProgram = new MidiProgram(bankMsb, bankLsb, program, chosen.getName());
        } else {
            int program = gmProgram == null ? 0 : gmProgram;
            sendProgram(0, 0, program);
            currentProgram = new MidiProgram(0, 0, program, "GM Program " + program);
        }
        applyReverb(reverbPreset);
        return currentProgram.displayName();
    }

    /**
     * Exposes the primary melodic channel so callers can perform advanced control changes if
     * needed. Channel 9/10 is intentionally avoided to steer clear of the percussion kit.
     */
    public synchronized MidiChannel channel() {
        ensureSynthReady();
        return primaryChannel;
    }

    public synchronized Synthesizer getSynthesizer() {
        return synthesizer;
    }

    public synchronized String getCurrentSoundFontPath() {
        return currentSoundFontPath;
    }

    public synchronized Soundbank getCustomSoundbank() {
        return customSoundbank;
    }

    public synchronized List<String> getInstrumentNames() {
        return List.copyOf(instrumentNames);
    }

    public synchronized MidiProgram getCurrentProgram() {
        return currentProgram;
    }

    public synchronized void shutdown() {
        close();
        if (synthReceiver != null) {
            synthReceiver.close();
            synthReceiver = null;
        }
        if (synthesizer != null && synthesizer.isOpen()) {
            synthesizer.close();
        }
        synthesizer = null;
        customSoundbank = null;
        currentSoundFontPath = null;
        availableInstruments = new Instrument[0];
        instrumentNames.clear();
        currentProgram = MidiProgram.gmPiano();
        defaultBankLoaded = false;
        defaultSoundbank = null;
        primaryChannel = null;
        transposeSemis = 0;
    }

    public int getTranspose() {
        return transposeSemis;
    }

    public void setTranspose(int semitones) {
        int clamped = Math.max(-24, Math.min(24, semitones));
        transposeSemis = clamped;
        sendAllNotesOff();
    }

    public void setVelocityCurve(VelCurve curve) {
        velocityMap.setCurve(curve);
    }

    public VelocityMap getVelocityMap() {
        return velocityMap;
    }

    public ReverbPreset getReverbPreset() {
        return reverbPreset;
    }

    public void setReverbPreset(ReverbPreset preset) {
        if (preset == null) {
            preset = ReverbPreset.ROOM;
        }
        reverbPreset = preset;
        applyReverb(preset);
    }

    public void applyReverb(ReverbPreset preset) {
        if (synthesizer == null) {
            return;
        }
        if (preset == null) {
            preset = ReverbPreset.ROOM;
        }
        MidiChannel[] channels = synthesizer.getChannels();
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

    private void publishNoteOn(int note, int velocity, long nanoTime) {
        for (MidiMessageListener listener : listeners) {
            listener.onNoteOn(note, velocity, nanoTime);
        }
    }

    private void publishNoteOff(int note, long nanoTime) {
        for (MidiMessageListener listener : listeners) {
            listener.onNoteOff(note, nanoTime);
        }
    }

    private void publishSustain(boolean on, long nanoTime) {
        for (MidiMessageListener listener : listeners) {
            listener.onSustain(on, nanoTime);
        }
    }

    private void publishTempo(int microsecondsPerQuarter, long nanoTime) {
        for (MidiMessageListener listener : listeners) {
            listener.onTempoChange(microsecondsPerQuarter, nanoTime);
        }
    }

    private void applySoundFont(String soundFontPath) throws Exception {
        if (synthesizer == null) {
            return;
        }
        if (soundFontPath == null) {
            if (customSoundbank != null) {
                synthesizer.unloadAllInstruments(customSoundbank);
            }
            customSoundbank = null;
            currentSoundFontPath = null;
            if (defaultSoundbank != null && !defaultBankLoaded) {
                defaultBankLoaded = synthesizer.loadAllInstruments(defaultSoundbank);
            }
            return;
        }
        File file = new File(soundFontPath);
        if (!file.isFile()) {
            throw new IOException("SoundFont not found: " + file.getAbsolutePath());
        }
        Soundbank soundbank = MidiSystem.getSoundbank(file);
        if (soundbank == null) {
            throw new IOException("Unsupported SoundFont: " + file.getAbsolutePath());
        }
        Soundbank previousCustom = customSoundbank;
        String previousPath = currentSoundFontPath;
        boolean wasDefaultLoaded = defaultBankLoaded;
        if (previousCustom != null) {
            synthesizer.unloadAllInstruments(previousCustom);
        }
        if (defaultBankLoaded && defaultSoundbank != null) {
            synthesizer.unloadAllInstruments(defaultSoundbank);
            defaultBankLoaded = false;
        }
        try {
            if (!synthesizer.loadAllInstruments(soundbank)) {
                throw new IOException("Unable to load SoundFont: " + file.getAbsolutePath());
            }
        } catch (RuntimeException | IOException ex) {
            restorePreviousBanks(previousCustom, previousPath, wasDefaultLoaded);
            throw ex;
        }
        customSoundbank = soundbank;
        currentSoundFontPath = soundFontPath;
    }

    private void restorePreviousBanks(Soundbank previousCustom, String previousPath, boolean wasDefaultLoaded) {
        if (previousCustom != null) {
            synthesizer.loadAllInstruments(previousCustom);
            customSoundbank = previousCustom;
            currentSoundFontPath = previousPath;
            defaultBankLoaded = false;
        } else if (defaultSoundbank != null && wasDefaultLoaded) {
            defaultBankLoaded = synthesizer.loadAllInstruments(defaultSoundbank);
            customSoundbank = null;
            currentSoundFontPath = null;
        } else {
            defaultBankLoaded = wasDefaultLoaded;
        }
    }

    private void refreshInstruments() {
        if (synthesizer == null) {
            availableInstruments = new Instrument[0];
            instrumentNames.clear();
            return;
        }
        availableInstruments = synthesizer.getAvailableInstruments();
        instrumentNames.clear();
        for (Instrument instrument : availableInstruments) {
            instrumentNames.add(instrument.getName());
        }
    }

    private Instrument findInstrument(String preferName) {
        // Adjust the keywords below if a different default patch should be favoured.
        if (availableInstruments == null || availableInstruments.length == 0) {
            return null;
        }
        if (preferName != null && !preferName.isBlank()) {
            Instrument match = findByTokens(preferName);
            if (match != null) {
                return match;
            }
        }
        Instrument grand = findByTokens("grand");
        if (grand != null) {
            return grand;
        }
        return findByTokens("piano");
    }

    private Instrument findByTokens(String phrase) {
        if (phrase == null || phrase.isBlank() || availableInstruments == null) {
            return null;
        }
        String[] tokens = phrase.toLowerCase().split("\\s+");
        Instrument loose = null;
        for (Instrument instrument : availableInstruments) {
            String name = instrument.getName();
            String lower = name.toLowerCase();
            boolean allMatch = true;
            for (String token : tokens) {
                if (!lower.contains(token)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                return instrument;
            }
            if (loose == null) {
                for (String token : tokens) {
                    if (!token.isBlank() && lower.contains(token)) {
                        loose = instrument;
                        break;
                    }
                }
            }
        }
        return loose;
    }

    private void sendProgram(int bankMsb, int bankLsb, int program) {
        if (primaryChannel == null) {
            return;
        }
        primaryChannel.controlChange(0, clamp7bit(bankMsb));
        primaryChannel.controlChange(32, clamp7bit(bankLsb));
        primaryChannel.programChange(clamp7bit(program));
    }

    private void ensureSynthReady() {
        if (synthesizer == null) {
            throw new IllegalStateException("Synthesizer not initialised");
        }
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

    private String normalize(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void sendToSynth(MidiMessage message) {
        Receiver receiver = synthReceiver;
        if (receiver != null) {
            receiver.send(message, -1);
        }
    }

    private final class DispatchingReceiver implements Receiver {

        @Override
        public void send(MidiMessage message, long timeStamp) {
            long now = System.nanoTime();
            if (message instanceof ShortMessage shortMessage) {
                handleShortMessage(shortMessage, now);
            } else if (message instanceof MetaMessage metaMessage) {
                handleMetaMessage(metaMessage, now);
            }
        }

        private void handleShortMessage(ShortMessage shortMessage, long now) {
            ShortMessage routed = transposeIfNeeded(shortMessage);
            int command = routed.getCommand();
            switch (command) {
                case ShortMessage.NOTE_ON -> handleNoteOn(routed, now);
                case ShortMessage.NOTE_OFF -> {
                    sendToSynth(routed);
                    publishNoteOff(routed.getData1(), now);
                }
                case ShortMessage.CONTROL_CHANGE -> {
                    sendToSynth(routed);
                    int controller = routed.getData1();
                    if (controller == 64) {
                        publishSustain(routed.getData2() >= 64, now);
                    }
                }
                default -> sendToSynth(routed);
            }
        }

        private void handleNoteOn(ShortMessage message, long now) {
            int velocity = message.getData2();
            int note = message.getData1();
            if (velocity == 0) {
                sendToSynth(message);
                publishNoteOff(note, now);
                return;
            }
            int mapped = velocityMap.map(velocity);
            try {
                ShortMessage mappedMessage = new ShortMessage();
                mappedMessage.setMessage(
                        ShortMessage.NOTE_ON,
                        message.getChannel(),
                        note,
                        mapped);
                sendToSynth(mappedMessage);
            } catch (InvalidMidiDataException ex) {
                sendToSynth(message);
            }
            publishNoteOn(note, mapped, now);
        }

        private void handleMetaMessage(MetaMessage message, long now) {
            if (message.getType() == 0x51) { // Set Tempo
                byte[] data = message.getData();
                if (data.length == 3) {
                    int tempo = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
                    publishTempo(tempo, now);
                }
            }
        }

        @Override
        public void close() {
            // Devices close their own receivers when the transmitter is closed.
        }
    }

    private ShortMessage transposeIfNeeded(ShortMessage message) {
        int semis = transposeSemis;
        if (semis == 0) {
            return message;
        }
        int command = message.getCommand();
        int channel = message.getChannel();
        if ((command == ShortMessage.NOTE_ON || command == ShortMessage.NOTE_OFF) && channel != 9) {
            int note = clamp7bit(message.getData1() + semis);
            int velocity = message.getData2();
            try {
                ShortMessage shifted = new ShortMessage();
                shifted.setMessage(command, channel, note, velocity);
                return shifted;
            } catch (InvalidMidiDataException ex) {
                return message;
            }
        }
        return message;
    }

    private void sendAllNotesOff() {
        if (synthesizer == null) {
            return;
        }
        MidiChannel[] channels = synthesizer.getChannels();
        if (channels == null) {
            return;
        }
        for (MidiChannel channel : channels) {
            if (channel != null) {
                channel.allNotesOff();
                channel.allSoundOff();
            }
        }
    }

    public record MidiInput(String name, MidiDevice.Info info) {
        @Override
        public String toString() {
            return name;
        }
    }

    public interface MidiMessageListener {
        void onNoteOn(int note, int velocity, long timestampNanos);

        void onNoteOff(int note, long timestampNanos);

        void onSustain(boolean sustainOn, long timestampNanos);

        void onTempoChange(int microsecondsPerQuarterNote, long timestampNanos);
    }

    /**
     * Simple record of the currently active patch so both live playback and replays stay in sync.
     */
    public record MidiProgram(int bankMsb, int bankLsb, int program, String displayName) {
        private static MidiProgram gmPiano() {
            return new MidiProgram(0, 0, 0, "GM Program 0");
        }
    }

    public enum ReverbPreset {
        ROOM("Room", 40, 10),
        HALL("Hall", 75, 20),
        STADIUM("Stadium", 100, 35);

        private final String label;
        private final int reverbCc;
        private final int chorusCc;

        ReverbPreset(String label, int reverbCc, int chorusCc) {
            this.label = label;
            this.reverbCc = reverbCc;
            this.chorusCc = chorusCc;
        }

        public int reverbCc() {
            return reverbCc;
        }

        public int chorusCc() {
            return chorusCc;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
