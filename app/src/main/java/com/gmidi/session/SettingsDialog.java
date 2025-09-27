package com.gmidi.session;

import com.gmidi.video.VideoSettings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Modal dialog that exposes basic encoder configuration such as resolution, FPS, preset, and output
 * directory.
 */
public class SettingsDialog extends Dialog<VideoSettings> {

    public SettingsDialog(VideoSettings current, Node owner) {
        setTitle("Recorder Settings");
        getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        getDialogPane().getStyleClass().add("settings-dialog");
        if (owner != null && owner.getScene() != null) {
            initOwner(owner.getScene().getWindow());
        }

        GridPane grid = new GridPane();
        grid.setVgap(10);
        grid.setHgap(12);
        grid.setPadding(new Insets(20));

        TextField outputField = new TextField(current.getOutputDirectory().toString());
        outputField.setPrefColumnCount(24);
        Button browse = new Button("Browse…");
        browse.getStyleClass().add("accent-button");
        browse.setOnAction(evt -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select recordings folder");
            Path currentPath = Paths.get(outputField.getText().isBlank() ? "recordings" : outputField.getText());
            File candidate = currentPath.toFile();
            if (candidate.isDirectory()) {
                chooser.setInitialDirectory(candidate);
            }
            Window window = null;
            if (owner != null && owner.getScene() != null) {
                window = owner.getScene().getWindow();
            }
            if (window == null) {
                window = getOwner();
            }
            File chosen = chooser.showDialog(window);
            if (chosen != null) {
                outputField.setText(chosen.getAbsolutePath());
            }
        });

        ChoiceBox<String> fpsChoice = new ChoiceBox<>(FXCollections.observableArrayList("30", "60"));
        String fpsString = Integer.toString(current.getFps());
        if (!fpsChoice.getItems().contains(fpsString)) {
            fpsChoice.getItems().add(fpsString);
        }
        fpsChoice.setValue(fpsString);

        ObservableList<String> resolutions = FXCollections.observableArrayList("1280x720", "1920x1080", "2560x1440");
        String currentResolution = current.getWidth() + "x" + current.getHeight();
        if (!resolutions.contains(currentResolution)) {
            resolutions.add(0, currentResolution);
        }
        ChoiceBox<String> resolutionChoice = new ChoiceBox<>(resolutions);
        resolutionChoice.setValue(currentResolution);

        ChoiceBox<String> presetChoice = new ChoiceBox<>(FXCollections.observableArrayList(
                "ultrafast", "superfast", "veryfast", "faster", "fast", "medium"));
        if (!presetChoice.getItems().contains(current.getPreset())) {
            presetChoice.getItems().add(current.getPreset());
        }
        presetChoice.setValue(current.getPreset());

        TextField crfField = new TextField(Integer.toString(current.getCrf()));
        TextField ffmpegField = new TextField(current.getFfmpegExecutable() == null
                ? ""
                : current.getFfmpegExecutable().toString());
        ffmpegField.setPromptText("Optional: path to ffmpeg executable");

        grid.addRow(0, new Label("Recordings folder"), outputField, browse);
        GridPane.setHgrow(outputField, Priority.ALWAYS);
        grid.addRow(1, new Label("Video FPS"), fpsChoice);
        grid.addRow(2, new Label("Resolution"), resolutionChoice);
        grid.addRow(3, new Label("x264 preset"), presetChoice);
        grid.addRow(4, new Label("CRF"), crfField);
        grid.addRow(5, new Label("FFmpeg path"), ffmpegField);

        getDialogPane().setContent(grid);

        setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            VideoSettings updated = new VideoSettings();
            Path dir;
            try {
                dir = Paths.get(outputField.getText().isBlank() ? "recordings" : outputField.getText());
            } catch (InvalidPathException ex) {
                dir = current.getOutputDirectory();
            }
            updated.setOutputDirectory(dir);
            updated.setPreset(presetChoice.getValue());
            updated.setCrf(parseInt(crfField.getText(), current.getCrf()));
            updated.setFps(parseInt(fpsChoice.getValue(), current.getFps()));
            int[] wh = parseResolution(resolutionChoice.getValue(), current.getWidth(), current.getHeight());
            updated.setWidth(wh[0]);
            updated.setHeight(wh[1]);
            String ffmpegText = ffmpegField.getText().trim();
            if (ffmpegText.isEmpty()) {
                updated.setFfmpegExecutable(null);
            } else {
                try {
                    updated.setFfmpegExecutable(Paths.get(ffmpegText));
                } catch (InvalidPathException ex) {
                    updated.setFfmpegExecutable(current.getFfmpegExecutable());
                }
            }
            return updated;
        });
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int[] parseResolution(String value, int fallbackWidth, int fallbackHeight) {
        if (value == null || !value.contains("x")) {
            return new int[] {fallbackWidth, fallbackHeight};
        }
        String[] parts = value.toLowerCase().split("x");
        try {
            int w = Integer.parseInt(parts[0].trim());
            int h = Integer.parseInt(parts[1].trim());
            if (w <= 0 || h <= 0) {
                return new int[] {fallbackWidth, fallbackHeight};
            }
            return new int[] {w, h};
        } catch (NumberFormatException ex) {
            return new int[] {fallbackWidth, fallbackHeight};
        }
    }
}
