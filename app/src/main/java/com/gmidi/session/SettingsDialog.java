package com.gmidi.session;

import com.gmidi.midi.MidiService;
import com.gmidi.ui.KeyFallCanvas.VelCurve;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.Scene;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Modal dialog that exposes basic encoder configuration such as resolution, FPS, preset, and output
 * directory.
 */
public class SettingsDialog extends Dialog<SettingsDialog.Result> {

    private final AtomicLong ffmpegProbeCounter = new AtomicLong();
    private static final String DARK_THEME = Objects.requireNonNull(
            SettingsDialog.class.getResource("/DarkTheme.css"))
            .toExternalForm();

    public SettingsDialog(VideoSettings current,
                         String currentSoundFont,
                         List<String> instrumentNames,
                         String currentInstrument,
                         VelCurve currentCurve,
                         int currentTranspose,
                         MidiService.ReverbPreset reverbPreset,
                         Node owner) {
        setTitle("Recorder Settings");
        getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        getDialogPane().getStyleClass().add("settings-dialog");
        if (owner != null && owner.getScene() != null) {
            initOwner(owner.getScene().getWindow());
        }
        getDialogPane().sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                applyDarkTheme(newScene);
            }
        });
        if (getDialogPane().getScene() != null) {
            applyDarkTheme(getDialogPane().getScene());
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
        TextField soundFontField = new TextField(currentSoundFont == null ? "" : currentSoundFont);
        soundFontField.setPrefColumnCount(24);
        soundFontField.setPromptText("Optional: path to SoundFont (.sf2)");
        Button soundFontBrowse = new Button("Browse…");
        soundFontBrowse.getStyleClass().add("accent-button");
        soundFontBrowse.setOnAction(evt -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Select SoundFont");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SoundFont", "*.sf2"));
            String currentText = soundFontField.getText();
            if (currentText != null && !currentText.isBlank()) {
                File existing = new File(currentText);
                if (existing.getParentFile() != null && existing.getParentFile().isDirectory()) {
                    chooser.setInitialDirectory(existing.getParentFile());
                }
            }
            Window window = owner != null && owner.getScene() != null ? owner.getScene().getWindow() : getOwner();
            File chosen = chooser.showOpenDialog(window);
            if (chosen != null) {
                soundFontField.setText(chosen.getAbsolutePath());
            }
        });

        List<String> safeNames = instrumentNames == null ? List.of() : instrumentNames;
        ObservableList<String> instrumentOptions = FXCollections.observableArrayList(safeNames);
        if (currentInstrument != null && !currentInstrument.isBlank()
                && instrumentOptions.stream().noneMatch(name -> name.equalsIgnoreCase(currentInstrument))) {
            instrumentOptions.add(0, currentInstrument);
        }
        ChoiceBox<String> instrumentChoice = new ChoiceBox<>(instrumentOptions);
        if (!instrumentOptions.isEmpty()) {
            instrumentChoice.setValue(instrumentOptions.stream()
                    .filter(name -> name.equalsIgnoreCase(currentInstrument))
                    .findFirst()
                    .orElse(instrumentOptions.get(0)));
        } else {
            instrumentChoice.setDisable(true);
        }

        ObservableList<VelCurve> curveOptions = FXCollections.observableArrayList(VelCurve.values());
        ChoiceBox<VelCurve> velocityChoice = new ChoiceBox<>(curveOptions);
        velocityChoice.setValue(currentCurve == null ? VelCurve.LINEAR : currentCurve);

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
        grid.addRow(5, new Label("SoundFont"), soundFontField, soundFontBrowse);
        GridPane.setHgrow(soundFontField, Priority.ALWAYS);
        grid.addRow(6, new Label("Instrument"), instrumentChoice);
        grid.addRow(7, new Label("Velocity curve"), velocityChoice);

        SpinnerValueFactory.IntegerSpinnerValueFactory transposeFactory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(-24, 24, currentTranspose, 1);
        Spinner<Integer> transposeSpinner = new Spinner<>(transposeFactory);
        transposeSpinner.setEditable(false);
        grid.addRow(8, new Label("Transpose"), transposeSpinner);

        ComboBox<MidiService.ReverbPreset> reverbChoice =
                new ComboBox<>(FXCollections.observableArrayList(MidiService.ReverbPreset.values()));
        reverbChoice.setValue(reverbPreset == null ? MidiService.ReverbPreset.ROOM : reverbPreset);
        grid.addRow(9, new Label("Reverb"), reverbChoice);

        grid.addRow(10, new Label("FFmpeg path"), ffmpegField);
        grid.add(ffmpegStatus, 1, 11, 2, 1);

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
            String soundFontText = soundFontField.getText().trim();
            String resolvedSoundFont = soundFontText.isEmpty() ? null : soundFontText;
            String chosenInstrument = instrumentChoice.isDisabled() ? null : instrumentChoice.getValue();
            VelCurve chosenCurve = velocityChoice.getValue() == null ? VelCurve.LINEAR : velocityChoice.getValue();
            int transpose = transposeSpinner.getValue();
            MidiService.ReverbPreset chosenPreset =
                    reverbChoice.getValue() == null ? MidiService.ReverbPreset.ROOM : reverbChoice.getValue();
            return new Result(updated, resolvedSoundFont, chosenInstrument, chosenCurve, transpose, chosenPreset);
        });
    }

    private void applyDarkTheme(Scene scene) {
        if (scene != null && !scene.getStylesheets().contains(DARK_THEME)) {
            scene.getStylesheets().add(DARK_THEME);
        }
    }

    public static final class Result {
        private final VideoSettings videoSettings;
        private final String soundFontPath;
        private final String instrumentName;
        private final VelCurve velocityCurve;
        private final int transposeSemis;
        private final MidiService.ReverbPreset reverbPreset;

        Result(VideoSettings videoSettings,
               String soundFontPath,
               String instrumentName,
               VelCurve velocityCurve,
               int transposeSemis,
               MidiService.ReverbPreset reverbPreset) {
            this.videoSettings = videoSettings;
            this.soundFontPath = soundFontPath;
            this.instrumentName = instrumentName;
            this.velocityCurve = velocityCurve;
            this.transposeSemis = transposeSemis;
            this.reverbPreset = reverbPreset;
        }

        public VideoSettings videoSettings() {
            return videoSettings;
        }

        public Optional<String> soundFontPath() {
            return Optional.ofNullable(soundFontPath);
        }

        public Optional<String> instrumentName() {
            return Optional.ofNullable(instrumentName);
        }

        public VelCurve velocityCurve() {
            return velocityCurve;
        }

        public int transposeSemis() {
            return transposeSemis;
        }

        public MidiService.ReverbPreset reverbPreset() {
            return reverbPreset;
        }
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
