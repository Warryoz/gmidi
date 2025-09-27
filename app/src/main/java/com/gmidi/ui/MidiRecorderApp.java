package com.gmidi.ui;

import com.gmidi.midi.MidiDeviceUtils;
import com.gmidi.midi.MidiRecordingSession;
import com.gmidi.midi.RecordingFileNamer;
import com.gmidi.recorder.RecordingInteraction;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * JavaFX application that visualises a piano keyboard and records from MIDI devices.
 */
public final class MidiRecorderApp extends Application {

    private final ObservableList<MidiDevice.Info> availableDevices = FXCollections.observableArrayList();

    private ComboBox<MidiDevice.Info> deviceSelector;
    private Button recordButton;
    private Button stopButton;
    private Label statusLabel;
    private PianoKeyboardView keyboardView;
    private Stage primaryStage;

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
        stage.setTitle("GMIDI Recorder");
        stage.setScene(new Scene(buildRoot(), 960, 360));
        stage.show();
        refreshDevices();
    }

    @Override
    public void stop() {
        FxRecordingInteraction interaction = currentInteraction;
        if (interaction != null) {
            interaction.requestStop();
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private BorderPane buildRoot() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(16));
        root.setTop(buildDeviceBar());
        root.setCenter(buildKeyboardPane());
        root.setBottom(buildControls());
        return root;
    }

    private VBox buildDeviceBar() {
        Label label = new Label("MIDI input:");
        deviceSelector = new ComboBox<>(availableDevices);
        deviceSelector.setPrefWidth(320);
        deviceSelector.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            if (currentInteraction == null) {
                restoreIdleControls();
            }
        });
        Button refreshButton = new Button("Refresh devices");
        refreshButton.setOnAction(event -> refreshDevices());

        HBox row = new HBox(12, label, deviceSelector, refreshButton);
        row.setPadding(new Insets(0, 0, 12, 0));

        VBox container = new VBox(row, new Separator());
        container.setSpacing(12);
        return container;
    }

    private ScrollPane buildKeyboardPane() {
        keyboardView = new PianoKeyboardView();
        ScrollPane scrollPane = new ScrollPane(keyboardView);
        scrollPane.setFitToHeight(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setPadding(new Insets(12, 0, 12, 0));
        return scrollPane;
    }

    private VBox buildControls() {
        recordButton = new Button("Record");
        recordButton.setOnAction(event -> startRecording());
        stopButton = new Button("Stop");
        stopButton.setDisable(true);
        stopButton.setOnAction(event -> stopRecording());

        HBox buttons = new HBox(12, recordButton, stopButton);

        statusLabel = new Label("Select a MIDI input and press Record.");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        Region spacer = new Region();
        HBox statusRow = new HBox(12, buttons, spacer, statusLabel);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        statusRow.setPadding(new Insets(12, 0, 0, 0));

        VBox container = new VBox(new Separator(), statusRow);
        container.setSpacing(12);
        return container;
    }

    private void refreshDevices() {
        List<MidiDevice.Info> devices = MidiDeviceUtils.listInputDevices();
        availableDevices.setAll(devices);
        if (devices.isEmpty()) {
            deviceSelector.getSelectionModel().clearSelection();
            statusLabel.setText("No MIDI input devices found.");
        } else {
            deviceSelector.getSelectionModel().selectFirst();
            statusLabel.setText("Select a MIDI input and press Record.");
        }
        if (currentInteraction == null) {
            restoreIdleControls();
        }
    }

    private void startRecording() {
        MidiDevice.Info deviceInfo = deviceSelector.getSelectionModel().getSelectedItem();
        if (deviceInfo == null) {
            showAlert(Alert.AlertType.WARNING, "No MIDI input", "Choose a MIDI input before recording.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save MIDI recording");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("MIDI files", "*.mid", "*.midi"));
        chooser.setInitialFileName(RecordingFileNamer.defaultFileName());
        java.io.File file = chooser.showSaveDialog(primaryStage);
        if (file == null) {
            statusLabel.setText("Recording cancelled.");
            restoreIdleControls();
            return;
        }

        Path outputPath = file.toPath();
        recordButton.setDisable(true);
        stopButton.setDisable(true);
        deviceSelector.setDisable(true);
        statusLabel.setText("Preparing to record…");
        keyboardView.clearPressedNotes();

        FxRecordingInteraction interaction = new FxRecordingInteraction(
                recordButton, stopButton, statusLabel, keyboardView, primaryStage);
        currentInteraction = interaction;

        executor.submit(() -> runRecording(deviceInfo, outputPath, interaction));
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
            Platform.runLater(this::restoreIdleControls);
        }
    }

    private void handleFailure(String message, Exception ex) {
        Platform.runLater(() -> {
            statusLabel.setText(String.format(Locale.ROOT, "%s: %s", message, ex.getMessage()));
            restoreIdleControls();
            showAlert(Alert.AlertType.ERROR, "Recording error",
                    String.format(Locale.ROOT, "%s.%n%s", message, ex.getMessage()));
        });
    }

    private void stopRecording() {
        FxRecordingInteraction interaction = currentInteraction;
        if (interaction != null) {
            stopButton.setDisable(true);
            statusLabel.setText("Stopping…");
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

    private void restoreIdleControls() {
        if (recordButton == null || stopButton == null) {
            return;
        }
        boolean hasDevice = deviceSelector != null
                && deviceSelector.getSelectionModel().getSelectedItem() != null;
        recordButton.setDisable(!hasDevice);
        stopButton.setDisable(true);
        if (deviceSelector != null) {
            deviceSelector.setDisable(false);
        }
        if (keyboardView != null) {
            keyboardView.clearPressedNotes();
        }
    }

    private static final class FxRecordingInteraction implements RecordingInteraction {

        private final Button recordButton;
        private final Button stopButton;
        private final Label statusLabel;
        private final PianoKeyboardView keyboardView;
        private final Stage owner;
        private final CountDownLatch startLatch = new CountDownLatch(1);
        private final CountDownLatch stopLatch = new CountDownLatch(1);

        FxRecordingInteraction(Button recordButton,
                               Button stopButton,
                               Label statusLabel,
                               PianoKeyboardView keyboardView,
                               Stage owner) {
            this.recordButton = Objects.requireNonNull(recordButton, "recordButton");
            this.stopButton = Objects.requireNonNull(stopButton, "stopButton");
            this.statusLabel = Objects.requireNonNull(statusLabel, "statusLabel");
            this.keyboardView = Objects.requireNonNull(keyboardView, "keyboardView");
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        void requestStop() {
            stopLatch.countDown();
        }

        @Override
        public void onReadyToRecord() {
            Platform.runLater(() -> {
                statusLabel.setText("Ready. Play when you are ready – recording starts immediately.");
                stopButton.setDisable(false);
            });
            startLatch.countDown();
        }

        @Override
        public void awaitStart() {
            try {
                startLatch.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void onRecordingStarted() {
            Platform.runLater(() -> statusLabel.setText("Recording… Press Stop when finished."));
        }

        @Override
        public void awaitStop() {
            try {
                stopLatch.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void onRecordingFinished(Path outputPath) {
            Platform.runLater(() -> {
                statusLabel.setText(String.format(Locale.ROOT,
                        "Saved recording to %s", outputPath.toAbsolutePath()));
                recordButton.setDisable(false);
                stopButton.setDisable(true);
                keyboardView.clearPressedNotes();
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                if (owner.isShowing()) {
                    alert.initOwner(owner);
                }
                alert.setTitle("Recording complete");
                alert.setHeaderText(null);
                alert.setContentText(String.format(Locale.ROOT,
                        "Recording saved to:%n%s", outputPath.toAbsolutePath()));
                if (owner.isShowing()) {
                    alert.showAndWait();
                } else {
                    alert.show();
                }
            });
        }

        @Override
        public Receiver decorateReceiver(Receiver downstream) {
            return new PianoAwareReceiver(downstream, keyboardView);
        }
    }

    private static final class PianoAwareReceiver implements Receiver {

        private final Receiver delegate;
        private final PianoKeyboardView keyboardView;

        private PianoAwareReceiver(Receiver delegate, PianoKeyboardView keyboardView) {
            this.delegate = delegate;
            this.keyboardView = keyboardView;
        }

        @Override
        public void send(javax.sound.midi.MidiMessage message, long timeStamp) {
            delegate.send(message, timeStamp);
            if (message instanceof ShortMessage shortMessage) {
                int command = shortMessage.getCommand();
                int note = shortMessage.getData1();
                int velocity = shortMessage.getData2();
                if (command == ShortMessage.NOTE_ON && velocity > 0) {
                    keyboardView.noteOn(note);
                } else if (command == ShortMessage.NOTE_OFF ||
                        (command == ShortMessage.NOTE_ON && velocity == 0)) {
                    keyboardView.noteOff(note);
                }
            }
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
