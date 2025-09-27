package com.gmidi.midi;

import java.util.ArrayList;
import java.util.List;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;

/**
 * Utility helpers for enumerating MIDI devices.
 */
public final class MidiDeviceUtils {

    private MidiDeviceUtils() {
    }

    /**
     * Returns a list of MIDI devices that support transmitting events (inputs).
     */
    public static List<MidiDevice.Info> listInputDevices() {
        MidiDevice.Info[] infos = MidiSystem.getMidiDeviceInfo();
        List<MidiDevice.Info> inputs = new ArrayList<>(infos.length);
        for (MidiDevice.Info info : infos) {
            try {
                MidiDevice device = MidiSystem.getMidiDevice(info);
                int maxTransmitters = device.getMaxTransmitters();
                if (maxTransmitters != 0) {
                    inputs.add(info);
                }
            } catch (MidiUnavailableException ignored) {
                // Ignore devices that cannot be opened right now.
            }
        }
        return inputs;
    }
}
