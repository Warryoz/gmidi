package com.gmidi.midi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class MidiDeviceUtilsTest {

    @Test
    void listInputDevicesDoesNotThrow() {
        assertDoesNotThrow(MidiDeviceUtils::listInputDevices);
    }
}
