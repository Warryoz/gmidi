package com.gmidi.midi;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Transmitter;
import javax.sound.midi.MetaMessage;
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
            switch (shortMessage.getCommand()) {
                case ShortMessage.NOTE_ON -> {
                    int velocity = shortMessage.getData2();
                    int note = shortMessage.getData1();
                    if (velocity == 0) {
                        publishNoteOff(note, now);
                    } else {
                        publishNoteOn(note, velocity, now);
                    }
                }
                case ShortMessage.NOTE_OFF -> publishNoteOff(shortMessage.getData1(), now);
                case ShortMessage.CONTROL_CHANGE -> {
                    int controller = shortMessage.getData1();
                    if (controller == 64) {
                        publishSustain(shortMessage.getData2() >= 64, now);
                    }
                }
                default -> {
                }
            }
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
}
