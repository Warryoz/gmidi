package com.gmidi.ui;

import com.gmidi.recorder.RecordingInteraction;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import javax.sound.midi.Receiver;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

/**
 * JavaFX implementation of {@link RecordingInteraction} that coordinates UI state changes.
 */
final class FxRecordingInteraction implements RecordingInteraction {

    private final MidiRecorderView view;
    private final Stage owner;
    private final CountDownLatch startLatch = new CountDownLatch(1);
    private final CountDownLatch stopLatch = new CountDownLatch(1);

    FxRecordingInteraction(MidiRecorderView view, Stage owner) {
        this.view = Objects.requireNonNull(view, "view");
        this.owner = owner;
    }

    void requestStop() {
        stopLatch.countDown();
    }

    @Override
    public void onReadyToRecord() {
        Platform.runLater(view::showArmedState);
        startLatch.countDown();
    }

    @Override
    public void awaitStart() {
        awaitLatch(startLatch);
    }

    @Override
    public void onRecordingStarted() {
        Platform.runLater(view::showRecordingState);
    }

    @Override
    public void awaitStop() {
        awaitLatch(stopLatch);
    }

    @Override
    public void onRecordingFinished(Path outputPath) {
        Platform.runLater(() -> {
            view.showRecordingComplete(outputPath);
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            if (owner != null && owner.isShowing()) {
                alert.initOwner(owner);
            }
            alert.setTitle("Recording complete");
            alert.setHeaderText(null);
            alert.setContentText(String.format(Locale.ROOT,
                    "Recording saved to:%n%s", outputPath.toAbsolutePath()));
            if (owner != null && owner.isShowing()) {
                alert.showAndWait();
            } else {
                alert.show();
            }
        });
    }

    @Override
    public Receiver decorateReceiver(Receiver downstream) {
        return new PianoAwareReceiver(downstream, view.keyboardView());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
