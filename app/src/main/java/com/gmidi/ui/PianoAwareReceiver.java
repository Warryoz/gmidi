package com.gmidi.ui;

import java.util.Objects;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;

/**
 * Receiver decorator that mirrors incoming note events on the piano keyboard view.
 */
final class PianoAwareReceiver implements Receiver {

    private final Receiver delegate;
    private final PianoKeyboardView keyboardView;

    PianoAwareReceiver(Receiver delegate, PianoKeyboardView keyboardView) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.keyboardView = Objects.requireNonNull(keyboardView, "keyboardView");
    }

    @Override
    public void send(MidiMessage message, long timeStamp) {
        delegate.send(message, timeStamp);
        if (message instanceof ShortMessage shortMessage) {
            int command = shortMessage.getCommand();
            int note = shortMessage.getData1();
            int velocity = shortMessage.getData2();
            if (command == ShortMessage.NOTE_ON && velocity > 0) {
                keyboardView.noteOn(note);
            } else if (command == ShortMessage.NOTE_OFF
                    || (command == ShortMessage.NOTE_ON && velocity == 0)) {
                keyboardView.noteOff(note);
            }
        }
    }

    @Override
    public void close() {
        delegate.close();
    }
}
