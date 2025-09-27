package com.gmidi.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.sound.midi.MidiDevice;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
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

/**
 * Primary JavaFX layout for the MIDI recorder application.
 */
final class MidiRecorderView extends BorderPane {

    private static final String STATUS_SELECT_DEVICE = "Select a MIDI input and press Record.";
    private static final String STATUS_NO_DEVICE = "No MIDI input devices found.";

    private final ObservableList<MidiDevice.Info> availableDevices = FXCollections.observableArrayList();
    private final ComboBox<MidiDevice.Info> deviceSelector = new ComboBox<>(availableDevices);
    private final Button refreshButton = new Button("Refresh devices");
    private final Button recordButton = new Button("Record");
    private final Button stopButton = new Button("Stop");
    private final Label statusLabel = new Label(STATUS_SELECT_DEVICE);
    private final PianoKeyboardView keyboardView = new PianoKeyboardView();

    MidiRecorderView() {
        setPadding(new Insets(16));
        setTop(buildDeviceBar());
        setCenter(buildKeyboardPane());
        setBottom(buildControls());

        deviceSelector.setPrefWidth(320);
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        stopButton.setDisable(true);
        recordButton.setDisable(true);

        showIdleState(false);
    }

    private VBox buildDeviceBar() {
        Label label = new Label("MIDI input:");

        HBox row = new HBox(12, label, deviceSelector, refreshButton);
        row.setPadding(new Insets(0, 0, 12, 0));

        VBox container = new VBox(row, new Separator());
        container.setSpacing(12);
        return container;
    }

    private ScrollPane buildKeyboardPane() {
        ScrollPane scrollPane = new ScrollPane(keyboardView);
        scrollPane.setFitToHeight(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setPadding(new Insets(12, 0, 12, 0));
        return scrollPane;
    }

    private VBox buildControls() {
        HBox buttons = new HBox(12, recordButton, stopButton);

        Region spacer = new Region();
        HBox statusRow = new HBox(12, buttons, spacer, statusLabel);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        statusRow.setPadding(new Insets(12, 0, 0, 0));

        VBox container = new VBox(new Separator(), statusRow);
        container.setSpacing(12);
        return container;
    }

    void setOnRefreshDevices(Runnable action) {
        refreshButton.setOnAction(event -> action.run());
    }

    ComboBox<MidiDevice.Info> deviceSelector() {
        return deviceSelector;
    }

    Button recordButton() {
        return recordButton;
    }

    Button stopButton() {
        return stopButton;
    }

    PianoKeyboardView keyboardView() {
        return keyboardView;
    }

    void setDevices(List<MidiDevice.Info> devices) {
        MidiDevice.Info previous = deviceSelector.getSelectionModel().getSelectedItem();
        availableDevices.setAll(devices);
        MidiDevice.Info match = findMatch(previous, devices);
        if (match != null) {
            deviceSelector.getSelectionModel().select(match);
        } else if (!devices.isEmpty()) {
            deviceSelector.getSelectionModel().selectFirst();
        } else {
            deviceSelector.getSelectionModel().clearSelection();
        }
    }

    private MidiDevice.Info findMatch(MidiDevice.Info previous, List<MidiDevice.Info> devices) {
        if (previous == null) {
            return null;
        }
        for (MidiDevice.Info info : devices) {
            if (Objects.equals(info.getName(), previous.getName())
                    && Objects.equals(info.getVendor(), previous.getVendor())
                    && Objects.equals(info.getVersion(), previous.getVersion())
                    && Objects.equals(info.getDescription(), previous.getDescription())) {
                return info;
            }
        }
        return null;
    }

    void showIdleState(boolean hasDevice) {
        recordButton.setDisable(!hasDevice);
        stopButton.setDisable(true);
        deviceSelector.setDisable(false);
        refreshButton.setDisable(false);
        statusLabel.setText(hasDevice ? STATUS_SELECT_DEVICE : STATUS_NO_DEVICE);
        keyboardView.clearPressedNotes();
    }

    void showPreparingState() {
        recordButton.setDisable(true);
        stopButton.setDisable(true);
        deviceSelector.setDisable(true);
        refreshButton.setDisable(true);
        statusLabel.setText("Preparing to record…");
        keyboardView.clearPressedNotes();
    }

    void showArmedState() {
        stopButton.setDisable(false);
        statusLabel.setText("Ready. Play when you are ready – recording starts immediately.");
    }

    void showRecordingState() {
        statusLabel.setText("Recording… Press Stop when finished.");
    }

    void showStoppingState() {
        stopButton.setDisable(true);
        statusLabel.setText("Stopping…");
    }

    void showCancellationState() {
        recordButton.setDisable(!hasSelectedDevice());
        stopButton.setDisable(true);
        deviceSelector.setDisable(false);
        refreshButton.setDisable(false);
        statusLabel.setText("Recording cancelled.");
        keyboardView.clearPressedNotes();
    }

    void showFailureState(String message) {
        recordButton.setDisable(!hasSelectedDevice());
        stopButton.setDisable(true);
        deviceSelector.setDisable(false);
        refreshButton.setDisable(false);
        statusLabel.setText(message);
        keyboardView.clearPressedNotes();
    }

    void showRecordingComplete(Path outputPath) {
        recordButton.setDisable(!hasSelectedDevice());
        stopButton.setDisable(true);
        deviceSelector.setDisable(false);
        refreshButton.setDisable(false);
        statusLabel.setText(String.format(Locale.ROOT,
                "Saved recording to %s", outputPath.toAbsolutePath()));
        keyboardView.clearPressedNotes();
    }

    boolean hasSelectedDevice() {
        return deviceSelector.getSelectionModel().getSelectedItem() != null;
    }
}
