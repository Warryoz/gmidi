package com.gmidi.ui;

import com.gmidi.midi.MidiDeviceUtils;
import com.gmidi.midi.MidiRecordingSession;
import com.gmidi.midi.RecordingFileNamer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiUnavailableException;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * JavaFX application that visualises a piano keyboard and records from MIDI devices.
 */
public final class MidiRecorderApp extends Application {

    private Stage primaryStage;
    private MidiRecorderView view;
    private ExecutorService executor;
    private final MidiRecordingSession recordingSession = new MidiRecordingSession();
    private volatile FxRecordingInteraction currentInteraction;

    public static void launchApp() {
        launch(MidiRecorderApp.class);
    }

    @Override
    public void init() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "gmidi-recording");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        view = new MidiRecorderView();
        stage.setTitle("GMIDI Recorder");
        stage.setScene(new Scene(view, 960, 360));
        stage.show();

        view.setOnRefreshDevices(this::refreshDevices);
        view.recordButton().setOnAction(event -> startRecording());
        view.stopButton().setOnAction(event -> stopRecording());
        view.deviceSelector().getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (currentInteraction == null) {
                view.showIdleState(newValue != null);
            }
        });

        refreshDevices();
    }

    @Override
    public void stop() {
        FxRecordingInteraction interaction = currentInteraction;
        if (interaction != null) {
            interaction.requestStop();
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void refreshDevices() {
        List<MidiDevice.Info> devices = MidiDeviceUtils.listInputDevices();
        view.setDevices(devices);
        if (currentInteraction == null) {
            view.showIdleState(!devices.isEmpty());
        }
    }

    private void startRecording() {
        MidiDevice.Info deviceInfo = view.deviceSelector().getSelectionModel().getSelectedItem();
        if (deviceInfo == null) {
            showAlert(Alert.AlertType.WARNING, "No MIDI input", "Choose a MIDI input before recording.");
            return;
        }

        Path outputPath = chooseOutputPath();
        if (outputPath == null) {
            view.showCancellationState();
            return;
        }

        view.showPreparingState();
        FxRecordingInteraction interaction = new FxRecordingInteraction(view, primaryStage);
        currentInteraction = interaction;
        executor.submit(() -> runRecording(deviceInfo, outputPath, interaction));
    }

    private Path chooseOutputPath() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save MIDI recording");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("MIDI files", "*.mid", "*.midi"));
        chooser.setInitialFileName(RecordingFileNamer.defaultFileName());
        java.io.File file = chooser.showSaveDialog(primaryStage);
        return file != null ? file.toPath() : null;
    }

    private void runRecording(MidiDevice.Info deviceInfo, Path outputPath, FxRecordingInteraction interaction) {
        try {
            recordingSession.record(deviceInfo, outputPath, interaction);
        } catch (MidiUnavailableException ex) {
            handleFailure("Failed to open MIDI device", ex);
        } catch (IOException ex) {
            handleFailure("Unable to write MIDI file", ex);
        } catch (RuntimeException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof InvalidMidiDataException) {
                handleFailure("Encountered invalid MIDI data", ex);
            } else {
                handleFailure("Unexpected error during recording", ex);
            }
        } finally {
            currentInteraction = null;
        }
    }

    private void handleFailure(String message, Exception ex) {
        Platform.runLater(() -> {
            String formatted = String.format(Locale.ROOT, "%s: %s", message, ex.getMessage());
            view.showFailureState(formatted);
            showAlert(Alert.AlertType.ERROR, "Recording error",
                    String.format(Locale.ROOT, "%s.%n%s", message, ex.getMessage()));
        });
    }

    private void stopRecording() {
        FxRecordingInteraction interaction = currentInteraction;
        if (interaction != null) {
            view.showStoppingState();
            interaction.requestStop();
        }
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        if (primaryStage != null && primaryStage.isShowing()) {
            alert.initOwner(primaryStage);
        }
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
