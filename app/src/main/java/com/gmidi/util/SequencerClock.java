package com.gmidi.util;

import javax.sound.midi.Sequencer;

public final class SequencerClock implements Clock {
    private final Sequencer sequencer;

    public SequencerClock(Sequencer sequencer) {
        this.sequencer = sequencer;
    }

    @Override
    public long nowMicros() {
        return sequencer == null ? 0L : sequencer.getMicrosecondPosition();
    }
}
