package com.gmidi.session;

import com.gmidi.video.FfmpegLocator;
import com.gmidi.video.VideoSettings;
import javafx.application.Platform;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Modal dialog that exposes basic encoder configuration such as resolution, FPS, preset, and output
 * directory.
 */
public class SettingsDialog extends Dialog<VideoSettings> {

    private final AtomicLong ffmpegProbeCounter = new AtomicLong();

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
        Label ffmpegStatus = new Label();
        ffmpegStatus.setWrapText(true);

        grid.addRow(0, new Label("Recordings folder"), outputField, browse);
        GridPane.setHgrow(outputField, Priority.ALWAYS);
        grid.addRow(1, new Label("Video FPS"), fpsChoice);
        grid.addRow(2, new Label("Resolution"), resolutionChoice);
        grid.addRow(3, new Label("x264 preset"), presetChoice);
        grid.addRow(4, new Label("CRF"), crfField);
        grid.addRow(5, new Label("FFmpeg path"), ffmpegField);
        grid.add(ffmpegStatus, 1, 6, 2, 1);

        getDialogPane().setContent(grid);

        ffmpegField.textProperty().addListener((obs, oldText, newText) -> {
            if (!Objects.equals(oldText, newText)) {
                updateFfmpegStatus(newText, ffmpegStatus);
            }
        });
        updateFfmpegStatus(ffmpegField.getText(), ffmpegStatus);

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

    private void updateFfmpegStatus(String override, Label statusLabel) {
        String trimmed = override == null ? "" : override.trim();
        long token = ffmpegProbeCounter.incrementAndGet();
        statusLabel.setText(trimmed.isEmpty() ? "Checking FFmpeg (default paths)…" : "Checking FFmpeg override…");
        CompletableFuture
                .supplyAsync(() -> probeFfmpeg(trimmed))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (ffmpegProbeCounter.get() != token) {
                        return;
                    }
                    if (error != null) {
                        String message = error.getMessage();
                        if (message == null || message.isBlank()) {
                            message = error.toString();
                        }
                        statusLabel.setText("FFmpeg check failed: " + message);
                        return;
                    }
                    statusLabel.setText(result.ok
                            ? "FFmpeg OK: " + result.message
                            : "FFmpeg unavailable: " + result.message);
                }));
    }

    private ProbeResult probeFfmpeg(String override) {
        String executable;
        if (!override.isEmpty()) {
            Path configured;
            try {
                configured = Paths.get(override);
            } catch (InvalidPathException ex) {
                return new ProbeResult(false, "Invalid FFmpeg path: " + ex.getInput());
            }
            try {
                executable = FfmpegLocator.normalizeConfigured(configured);
            } catch (IOException ex) {
                return new ProbeResult(false, ex.getMessage());
            }
        } else {
            executable = FfmpegLocator.defaultExecutable();
        }

        ProcessBuilder builder = new ProcessBuilder(executable, "-version");
        builder.redirectErrorStream(true);
        Process process = null;
        try {
            process = builder.start();
            String versionLine = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (versionLine == null && !line.isBlank()) {
                        versionLine = line.trim();
                    }
                }
            }
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ProbeResult(false, "Timed out probing " + executable);
            }
            int exit = process.exitValue();
            if (exit == 0) {
                String detail = versionLine != null ? versionLine : "version check succeeded";
                return new ProbeResult(true, executable + " (" + detail + ")");
            }
            return new ProbeResult(false, "ffmpeg exited with code " + exit + " when probing " + executable);
        } catch (IOException ex) {
            return new ProbeResult(false, "Unable to run " + executable + ": " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ProbeResult(false, "Probe interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private record ProbeResult(boolean ok, String message) {
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
