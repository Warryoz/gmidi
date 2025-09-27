package com.gmidi;

import com.gmidi.midi.MidiService;
import com.gmidi.session.SessionController;
import com.gmidi.ui.KeyFallCanvas;
import com.gmidi.ui.KeyboardView;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.Objects;

/**
 * Entry point for the GMIDI Recorder desktop application. The scene wires the MIDI service,
 * visualiser, and recording controls together.
 */
public class MainApp extends Application {

    private SessionController controller;
    private MidiService midiService;

    @Override
    public void start(Stage stage) {
        midiService = new MidiService();

        KeyboardView keyboardView = new KeyboardView();
        keyboardView.setMaxWidth(Double.MAX_VALUE);

        KeyFallCanvas keyFallCanvas = new KeyFallCanvas();
        StackPane keyFallViewport = new StackPane();
        keyFallViewport.getStyleClass().add("keyfall-container");
        keyFallViewport.setPadding(new Insets(12, 16, 16, 16));
        keyFallViewport.getChildren().add(keyFallCanvas); // child 0: canvas
        StackPane overlayLayer = new StackPane();
        overlayLayer.setMouseTransparent(true);
        overlayLayer.setPickOnBounds(false);
        keyFallViewport.getChildren().add(overlayLayer); // child 1: overlays placeholder
        keyFallCanvas.bindTo(keyFallViewport);

        ComboBox<MidiService.MidiInput> deviceCombo = getMidiInputComboBox();

        Button refreshButton = new Button("Refresh");
        refreshButton.getStyleClass().add("accent-button");
        Tooltip.install(refreshButton, new Tooltip("Refresh MIDI device list"));

        ToggleButton midiRecordToggle = new ToggleButton("Record MIDI");
        midiRecordToggle.getStyleClass().add("accent-toggle");
        Tooltip.install(midiRecordToggle, new Tooltip("Start or stop MIDI capture"));

        ToggleButton videoRecordToggle = new ToggleButton("Record Video");
        videoRecordToggle.getStyleClass().add("accent-toggle");
        Tooltip.install(videoRecordToggle, new Tooltip("Capture MP4 of the visualiser"));

        Label fpsLabel = new Label("60.0 FPS");
        fpsLabel.getStyleClass().add("info-label");

        Button settingsButton = new Button("⚙");
        settingsButton.getStyleClass().add("icon-button");
        Tooltip.install(settingsButton, new Tooltip("Open settings"));

        ToggleButton darkToggle = new ToggleButton("Dark mode");
        darkToggle.setSelected(true);
        darkToggle.getStyleClass().add("mode-toggle");

        HBox toolbar = new HBox(10, deviceCombo, refreshButton, midiRecordToggle, videoRecordToggle, fpsLabel, settingsButton, darkToggle);
        toolbar.setPadding(new Insets(16, 18, 12, 18));
        toolbar.getStyleClass().add("toolbar");

        BorderPane visualiserPane = new BorderPane();
        visualiserPane.setCenter(keyFallViewport);
        BorderPane.setMargin(keyFallViewport, Insets.EMPTY);
        visualiserPane.setBottom(keyboardView);
        BorderPane.setMargin(keyboardView, new Insets(0, 16, 16, 16));

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(Double.MAX_VALUE);
        progressBar.getStyleClass().add("session-progress");

        Label elapsedLabel = new Label("00:00");
        elapsedLabel.getStyleClass().add("info-label");
        Label framesLabel = new Label("Frames: 0");
        framesLabel.getStyleClass().add("info-label");
        Label droppedLabel = new Label("Dropped: 0");
        droppedLabel.getStyleClass().add("info-label");
        Label statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("status-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox statusBar = new HBox(18, elapsedLabel, framesLabel, droppedLabel, spacer, statusLabel);
        statusBar.setPadding(new Insets(10, 18, 16, 18));
        statusBar.getStyleClass().add("status-bar");

        VBox bottom = new VBox(progressBar, statusBar);
        bottom.getStyleClass().add("bottom-panel");

        BorderPane root = new BorderPane();
        root.setTop(toolbar);
        root.setCenter(visualiserPane);
        root.setBottom(bottom);
        root.getStyleClass().add("app-root");

        Scene scene = new Scene(root, 1200, 800);
        String darkTheme = Objects.requireNonNull(MainApp.class.getResource("/com/gmidi/dark.css")).toExternalForm();
        scene.getStylesheets().add(darkTheme);
        darkToggle.selectedProperty().addListener((obs, oldV, selected) -> {
            if (selected) {
                if (!scene.getStylesheets().contains(darkTheme)) {
                    scene.getStylesheets().add(darkTheme);
                }
            } else {
                scene.getStylesheets().remove(darkTheme);
            }
        });

        controller = new SessionController(
                midiService,
                keyboardView,
                keyFallCanvas,
                visualiserPane,
                deviceCombo,
                midiRecordToggle,
                videoRecordToggle,
                fpsLabel,
                elapsedLabel,
                framesLabel,
                droppedLabel,
                statusLabel,
                progressBar
        );
        refreshButton.setOnAction(e -> controller.refreshMidiInputs());
        settingsButton.setOnAction(e -> controller.showSettingsDialog(settingsButton));
        videoRecordToggle.disableProperty().bind(midiRecordToggle.selectedProperty().not());

        controller.refreshMidiInputs();

        stage.setTitle("GMIDI Recorder");
        stage.setScene(scene);
        stage.setMinWidth(960);
        stage.setMinHeight(640);
        stage.show();
        controller.startAnimation();
    }

    private static ComboBox<MidiService.MidiInput> getMidiInputComboBox() {
        ComboBox<MidiService.MidiInput> deviceCombo = new ComboBox<>();
        deviceCombo.setPromptText("MIDI input");
        deviceCombo.setPrefWidth(240);
        deviceCombo.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(MidiService.MidiInput item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        deviceCombo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(MidiService.MidiInput item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "MIDI input" : item.name());
            }
        });
        return deviceCombo;
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
