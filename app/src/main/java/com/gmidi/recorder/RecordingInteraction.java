package com.gmidi.recorder;

import java.nio.file.Path;

/**
 * Abstraction for UI layers that guide the user through the recording lifecycle.
 *
 * <p>The callbacks are invoked by {@link com.gmidi.midi.MidiRecordingSession}
 * as the underlying MIDI session progresses. CLI and graphical front-ends can
 * provide implementations that display prompts, update widgets, or block until
 * user actions occur.</p>
 */
public interface RecordingInteraction {

    /** Called when the session is armed and waiting for the user to start. */
    void onReadyToRecord();

    /** Blocks until the user indicates recording should begin. */
    void awaitStart();

    /** Called right after the sequencer starts recording. */
    void onRecordingStarted();

    /** Blocks until the user indicates recording should stop. */
    void awaitStop();

    /** Called once the recording has been persisted to disk. */
    void onRecordingFinished(Path outputPath);
}
