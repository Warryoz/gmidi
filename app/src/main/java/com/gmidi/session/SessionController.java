package com.gmidi.session;

import com.gmidi.midi.MidiRecorder;
import com.gmidi.midi.MidiService;
import com.gmidi.ui.KeyFallCanvas;
import com.gmidi.ui.KeyboardView;
import com.gmidi.video.VideoRecorder;
import com.gmidi.video.VideoSettings;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiUnavailableException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Coordinates MIDI input, recording, the piano visualiser, and video capture. The controller is the
 * glue between the service layer and the JavaFX scene graph.
 */
public class SessionController {

    private static final DateTimeFormatter FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final MidiService midiService;
    private final MidiRecorder midiRecorder = new MidiRecorder();
    private final KeyboardView keyboardView;
    private final KeyFallCanvas keyFallCanvas;
    private final Node captureNode;
    private final ComboBox<MidiService.MidiInput> deviceCombo;
    private final ToggleButton midiRecordToggle;
    private final ToggleButton videoRecordToggle;
    private final Label fpsLabel;
    private final Label elapsedLabel;
    private final Label frameLabel;
    private final Label droppedLabel;
    private final Label statusLabel;
    private final ProgressBar progressBar;

    private final VideoSettings videoSettings = new VideoSettings();
    private VideoRecorder videoRecorder;

    private MidiService.MidiInput selectedInput;
    private long sessionStartNanos;
    private long lastFrameNanos;
    private double smoothedFps = 60.0;
    private long lastVideoCaptureNanos;
    private long videoFrameIntervalNanos;

    private Path currentMidiFile;
    private Path currentVideoFile;

    private final AnimationTimer animationTimer;

    public SessionController(MidiService midiService,
                             KeyboardView keyboardView,
                             KeyFallCanvas keyFallCanvas,
                             Node captureNode,
                             ComboBox<MidiService.MidiInput> deviceCombo,
                             ToggleButton midiRecordToggle,
                             ToggleButton videoRecordToggle,
                             Label fpsLabel,
                             Label elapsedLabel,
                             Label frameLabel,
                             Label droppedLabel,
                             Label statusLabel,
                             ProgressBar progressBar) {
        this.midiService = midiService;
        this.keyboardView = keyboardView;
        this.keyFallCanvas = keyFallCanvas;
        this.captureNode = captureNode;
        this.deviceCombo = deviceCombo;
        this.midiRecordToggle = midiRecordToggle;
        this.videoRecordToggle = videoRecordToggle;
        this.fpsLabel = fpsLabel;
        this.elapsedLabel = elapsedLabel;
        this.frameLabel = frameLabel;
        this.droppedLabel = droppedLabel;
        this.statusLabel = statusLabel;
        this.progressBar = progressBar;

        deviceCombo.setOnAction(e -> connectToSelectedDevice());
        midiRecordToggle.selectedProperty().addListener((obs, oldV, recording) -> {
            if (recording) {
                startMidiRecording();
            } else {
                stopMidiRecording();
            }
        });
        videoRecordToggle.selectedProperty().addListener((obs, oldV, recording) -> {
            if (recording) {
                startVideoRecording();
            } else {
                stopVideoRecording();
            }
        });

        animationTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                onAnimationFrame(now);
            }
        };
        animationTimer.start();
    }

    public void refreshMidiInputs() {
        try {
            List<MidiService.MidiInput> devices = midiService.listInputs();
            deviceCombo.getItems().setAll(devices);
            if (selectedInput != null && devices.contains(selectedInput)) {
                deviceCombo.getSelectionModel().select(selectedInput);
                statusLabel.setText("Connected to " + selectedInput.name());
            } else {
                selectedInput = null;
                deviceCombo.getSelectionModel().clearSelection();
                if (devices.isEmpty()) {
                    statusLabel.setText("No MIDI inputs found");
                } else {
                    statusLabel.setText("Select a MIDI input");
                    if (!midiRecorder.isRecording()) {
                        deviceCombo.getSelectionModel().selectFirst();
                    }
                }
            }
        } catch (MidiUnavailableException ex) {
            statusLabel.setText("MIDI unavailable: " + ex.getMessage());
        }
    }

    private void connectToSelectedDevice() {
        MidiService.MidiInput input = deviceCombo.getSelectionModel().getSelectedItem();
        if (input == null) {
            return;
        }
        try {
            midiService.open(input);
            midiService.setListener(new MidiEventRelay());
            selectedInput = input;
            statusLabel.setText("Connected to " + input.name());
        } catch (MidiUnavailableException ex) {
            statusLabel.setText("Unable to open device: " + ex.getMessage());
            deviceCombo.getSelectionModel().clearSelection();
        }
    }

    private void startMidiRecording() {
        if (selectedInput == null) {
            statusLabel.setText("Select a MIDI input first");
            Platform.runLater(() -> midiRecordToggle.setSelected(false));
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            String baseName = FILE_FORMAT.format(now);
            Path outputDir = Optional.ofNullable(videoSettings.getOutputDirectory()).orElse(Paths.get("recordings"));
            currentMidiFile = outputDir.resolve(baseName + ".mid");
            sessionStartNanos = System.nanoTime();
            keyFallCanvas.clear();
            midiRecorder.start(currentMidiFile, selectedInput.name(), sessionStartNanos);
            statusLabel.setText("Recording MIDI → " + currentMidiFile.getFileName());
            progressBar.setProgress(0);
            elapsedLabel.setText("00:00");
            frameLabel.setText("Frames: 0");
            droppedLabel.setText("Dropped: 0");
            lastVideoCaptureNanos = 0;
            currentVideoFile = null;
        } catch (IOException | InvalidMidiDataException ex) {
            statusLabel.setText("Unable to start MIDI recording: " + ex.getMessage());
            midiRecordToggle.setSelected(false);
        }
    }

    private void stopMidiRecording() {
        try {
            midiRecorder.stop();
            statusLabel.setText(currentMidiFile != null ? "Saved " + currentMidiFile.getFileName() : "Recording stopped");
        } catch (IOException ex) {
            statusLabel.setText("Unable to save MIDI: " + ex.getMessage());
        } finally {
            if (videoRecordToggle.isSelected()) {
                videoRecordToggle.setSelected(false);
            }
        }
    }

    private void startVideoRecording() {
        if (!midiRecordToggle.isSelected()) {
            videoRecordToggle.setSelected(false);
            return;
        }
        if (videoSettings.getWidth() <= 0 || videoSettings.getHeight() <= 0) {
            statusLabel.setText("Invalid video resolution");
            videoRecordToggle.setSelected(false);
            return;
        }
        if (captureNode instanceof Region region) {
            if (region.getWidth() <= 0 || region.getHeight() <= 0) {
                region.applyCss();
                region.layout();
            }
        }
        String baseName = currentMidiFile != null ? stripExtension(currentMidiFile.getFileName().toString())
                : FILE_FORMAT.format(LocalDateTime.now());
        Path outputDir = Optional.ofNullable(videoSettings.getOutputDirectory()).orElse(Paths.get("recordings"));
        currentVideoFile = outputDir.resolve(baseName + ".mp4");
        videoRecorder = new VideoRecorder();
        try {
            videoRecorder.start(currentVideoFile, videoSettings);
            videoFrameIntervalNanos = Math.max(1L, Math.round(1_000_000_000.0 / Math.max(1, videoSettings.getFps())));
            lastVideoCaptureNanos = 0;
            statusLabel.setText("Recording video → " + currentVideoFile.getFileName());
        } catch (IOException ex) {
            statusLabel.setText(ex.getMessage());
            videoRecordToggle.setSelected(false);
            videoRecorder = null;
            currentVideoFile = null;
        }
    }

    private void stopVideoRecording() {
        if (videoRecorder == null || !videoRecorder.isRunning()) {
            return;
        }
        try {
            videoRecorder.stop(Duration.ofSeconds(5));
            statusLabel.setText(currentVideoFile != null ? "Saved video " + currentVideoFile.getFileName() : "Video stopped");
        } catch (IOException | InterruptedException ex) {
            statusLabel.setText("Video stop failed: " + ex.getMessage());
        } finally {
            videoRecorder = null;
        }
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(0, dot) : filename;
    }

    private void onAnimationFrame(long now) {
        keyFallCanvas.tick(now);
        updateFps(now);
        updateElapsed(now);
        if (videoRecorder != null && videoRecorder.isRunning()) {
            maybeCaptureFrame(now);
            frameLabel.setText("Frames: " + videoRecorder.getFramesWritten());
            droppedLabel.setText("Dropped: " + videoRecorder.getFramesDropped());
        } else {
            frameLabel.setText("Frames: 0");
            droppedLabel.setText("Dropped: 0");
        }
    }

    private void updateFps(long now) {
        if (lastFrameNanos > 0) {
            double deltaSeconds = (now - lastFrameNanos) / 1_000_000_000.0;
            if (deltaSeconds > 0) {
                double current = 1.0 / deltaSeconds;
                smoothedFps = (smoothedFps * 0.9) + (current * 0.1);
            }
        }
        lastFrameNanos = now;
        fpsLabel.setText(String.format("%.1f FPS", smoothedFps));
    }

    private void updateElapsed(long now) {
        if (!midiRecorder.isRecording()) {
            progressBar.setProgress(0);
            return;
        }
        double seconds = (now - sessionStartNanos) / 1_000_000_000.0;
        int minutes = (int) (seconds / 60);
        int secs = (int) (seconds % 60);
        elapsedLabel.setText(String.format("%02d:%02d", minutes, secs));
        progressBar.setProgress(Math.min(1.0, seconds / 600.0));
    }

    private void maybeCaptureFrame(long now) {
        if (videoRecorder == null) {
            return;
        }
        if (lastVideoCaptureNanos != 0 && now - lastVideoCaptureNanos < videoFrameIntervalNanos) {
            return;
        }
        double width = captureNode.getLayoutBounds().getWidth();
        double height = captureNode.getLayoutBounds().getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.rgb(18, 18, 18));
        double scaleLimit = Math.min(
                1.0,
                Math.min(videoSettings.getWidth() / width, videoSettings.getHeight() / height)
        );
        if (scaleLimit <= 0) {
            scaleLimit = 1.0;
        }
        if (scaleLimit != 1.0) {
            params.setTransform(Transform.scale(scaleLimit, scaleLimit));
        }
        int frameWidth = Math.max(1, (int) Math.round(width * scaleLimit));
        int frameHeight = Math.max(1, (int) Math.round(height * scaleLimit));
        WritableImage frame = new WritableImage(frameWidth, frameHeight);
        WritableImage snapshot = captureNode.snapshot(params, frame);
        boolean accepted = videoRecorder.writeFrame(snapshot);
        if (!accepted) {
            droppedLabel.setText("Dropped: " + videoRecorder.getFramesDropped());
        }
        lastVideoCaptureNanos = now;
    }

    public void showSettingsDialog(Node owner) {
        SettingsDialog dialog = new SettingsDialog(videoSettings, owner);
        dialog.showAndWait().ifPresent(updated -> {
            videoSettings.setOutputDirectory(updated.getOutputDirectory());
            videoSettings.setWidth(updated.getWidth());
            videoSettings.setHeight(updated.getHeight());
            videoSettings.setFps(updated.getFps());
            videoSettings.setCrf(updated.getCrf());
            videoSettings.setPreset(updated.getPreset());
            videoSettings.setFfmpegExecutable(updated.getFfmpegExecutable());
            statusLabel.setText("Updated settings");
        });
    }

    public void shutdown() {
        animationTimer.stop();
        stopVideoRecording();
        try {
            midiRecorder.stop();
        } catch (IOException ignored) {
        }
        midiService.close();
    }

    private final class MidiEventRelay implements MidiService.MidiMessageListener {
        @Override
        public void onNoteOn(int note, int velocity, long timestampNanos) {
            Platform.runLater(() -> {
                keyboardView.press(note);
                keyFallCanvas.onNoteOn(note, velocity, timestampNanos);
                midiRecorder.recordNoteOn(note, velocity, timestampNanos);
            });
        }

        @Override
        public void onNoteOff(int note, long timestampNanos) {
            Platform.runLater(() -> {
                keyboardView.release(note);
                keyFallCanvas.onNoteOff(note, timestampNanos);
                midiRecorder.recordNoteOff(note, timestampNanos);
            });
        }

        @Override
        public void onSustain(boolean sustainOn, long timestampNanos) {
            Platform.runLater(() -> {
                keyFallCanvas.onSustain(sustainOn, timestampNanos);
                midiRecorder.recordSustain(sustainOn, timestampNanos);
            });
        }

        @Override
        public void onTempoChange(int microsecondsPerQuarterNote, long timestampNanos) {
            Platform.runLater(() -> midiRecorder.recordTempoChange(microsecondsPerQuarterNote, timestampNanos));
        }
    }
}
