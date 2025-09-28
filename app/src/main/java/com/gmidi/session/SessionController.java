package com.gmidi.session;

import com.gmidi.midi.MidiRecorder;
import com.gmidi.midi.MidiReplayer;
import com.gmidi.midi.MidiService;
import com.gmidi.midi.MidiService.ReverbPreset;
import com.gmidi.ui.KeyFallCanvas;
import com.gmidi.ui.KeyFallCanvas.VelCurve;
import com.gmidi.ui.KeyboardView;
import com.gmidi.ui.PianoKeyLayout;
import com.gmidi.util.Clock;
import com.gmidi.util.SequencerClock;
import com.gmidi.util.SystemClock;
import com.gmidi.video.VideoRecorder;
import com.gmidi.video.VideoSettings;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Sequence;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.nio.file.StandardCopyOption;

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
    private final Slider fallDurationSlider;
    private final Label fallDurationLabel;
    private final Button replayPlayButton;
    private final Button replayPauseButton;
    private final Button replayStopButton;
    private final Button openMidiButton;

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
    private Path currentVideoTempFile;
    private Path currentAudioFile;
    private Path loadedReplayFile;
    private boolean recordingReplay;

    private final AnimationTimer animationTimer;
    private Clock clock = new SystemClock();
    private MidiReplayer midiReplayer;
    private Synthesizer synthesizer;
    private String soundFontPath;
    private final List<String> availableInstruments = new ArrayList<>();
    private String preferredInstrumentName = "Grand Piano";
    private VelCurve velocityCurve = VelCurve.LINEAR;
    private boolean playbackActive;
    private final DoubleProperty keyboardHeightRatio = new SimpleDoubleProperty(PianoKeyLayout.KEYBOARD_HEIGHT_RATIO);

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
                             ProgressBar progressBar,
                             Slider fallDurationSlider,
                             Label fallDurationLabel,
                             Button replayPlayButton,
                             Button replayPauseButton,
                             Button replayStopButton,
                             Button openMidiButton) {
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
        this.fallDurationSlider = fallDurationSlider;
        this.fallDurationLabel = fallDurationLabel;
        this.replayPlayButton = replayPlayButton;
        this.replayPauseButton = replayPauseButton;
        this.replayStopButton = replayStopButton;
        this.openMidiButton = openMidiButton;
        this.soundFontPath = midiService.getCurrentSoundFontPath();

        this.keyFallCanvas.setOnImpact((note, intensity) -> keyboardView.flash(note, intensity));
        this.keyFallCanvas.setVelocityCurve(velocityCurve);

        configureFallDurationSlider();
        configurePlaybackControls();
        configureKeyboardSizing();
        initialiseSynth();

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
    }

    private void configureFallDurationSlider() {
        if (fallDurationSlider == null || fallDurationLabel == null) {
            return;
        }
        double initial = fallDurationSlider.getValue();
        if (initial <= 0) {
            initial = keyFallCanvas.getFallDurationSeconds();
            fallDurationSlider.setValue(initial);
        }
        double clampedInitial = Math.max(1.0, initial);
        updateFallDurationLabel(clampedInitial);
        fallDurationSlider.valueProperty().addListener((obs, oldV, newV) -> {
            double seconds = Math.max(1.0, newV.doubleValue());
            updateFallDurationLabel(seconds);
        });
    }

    private void configureKeyboardSizing() {
        keyboardView.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null) {
                keyboardView.prefHeightProperty().unbind();
            }
            if (newScene != null) {
                bindKeyboardHeight(newScene.heightProperty());
            }
        });
        if (keyboardView.getScene() != null) {
            bindKeyboardHeight(keyboardView.getScene().heightProperty());
        }
    }

    private void bindKeyboardHeight(ReadOnlyDoubleProperty sceneHeight) {
        if (sceneHeight == null) {
            return;
        }
        keyboardView.prefHeightProperty().bind(Bindings.createDoubleBinding(
                () -> clampKeyboardHeight(sceneHeight.get() * keyboardHeightRatio.get()),
                sceneHeight,
                keyboardHeightRatio));
    }

    private double clampKeyboardHeight(double desired) {
        double min = keyboardView.getMinHeight();
        double max = keyboardView.getMaxHeight();
        return Math.max(min, Math.min(max, desired));
    }

    private void updateFallDurationLabel(double seconds) {
        fallDurationLabel.setText(String.format("Fall: %.1fs", seconds));
        keyFallCanvas.setFallDurationSeconds(seconds);
    }

    private void configurePlaybackControls() {
        if (replayPlayButton == null || replayPauseButton == null || replayStopButton == null
                || openMidiButton == null) {
            return;
        }
        replayPlayButton.setOnAction(e -> playRecording());
        replayPauseButton.setOnAction(e -> pausePlayback());
        replayStopButton.setOnAction(e -> stopPlayback());
        openMidiButton.setOnAction(e -> openMidiFile());
        refreshPlaybackControls();
    }

    private void initialiseSynth() {
        try {
            midiService.initSynth(soundFontPath);
            synthesizer = midiService.getSynthesizer();
            soundFontPath = midiService.getCurrentSoundFontPath();
            updateAvailableInstruments();
            midiReplayer = createMidiReplayer();
            applyInstrumentSelection(preferredInstrumentName);
            midiReplayer.setProgram(midiService.getCurrentProgram());
            updateSoundFontStatus();
            refreshPlaybackControls();
        } catch (Exception ex) {
            statusLabel.setText("Synth unavailable: " + ex.getMessage());
            replayPlayButton.setDisable(true);
            replayStopButton.setDisable(true);
            midiReplayer = null;
        }
    }

    private MidiReplayer createMidiReplayer() throws MidiUnavailableException {
        MidiReplayer replayer = new MidiReplayer(new MidiReplayer.VisualSink() {
            @Override
            public void noteOn(int midi, int velocity, long tNanos) {
                keyFallCanvas.onNoteOn(midi, velocity, tNanos);
                keyboardView.press(midi);
            }

            @Override
            public void noteOff(int midi, long tNanos) {
                keyFallCanvas.onNoteOff(midi, tNanos);
                keyboardView.release(midi);
            }
        }, synthesizer, midiService::getTranspose);
        replayer.setOnFinished(this::onPlaybackFinished);
        replayer.getSequencer().addMetaEventListener(meta -> {
            if (meta.getType() == 0x2F) {
                Platform.runLater(this::refreshPlaybackControls);
            }
        });
        return replayer;
    }

    private void refreshPlaybackControls() {
        if (replayPlayButton == null || replayPauseButton == null || replayStopButton == null) {
            return;
        }
        boolean recordedAvailable = !midiRecorder.getEvents().isEmpty();
        boolean sequenceLoaded = midiReplayer != null && midiReplayer.hasSequence();
        boolean playing = midiReplayer != null && midiReplayer.isPlaying();
        boolean paused = midiReplayer != null && midiReplayer.isPaused();
        boolean playable = recordedAvailable || sequenceLoaded;
        replayPlayButton.setDisable(!playable || playing);
        replayPauseButton.setDisable(!playing);
        replayStopButton.setDisable(!(playing || paused));
    }

    private void playRecording() {
        if (midiReplayer == null) {
            statusLabel.setText("Playback unavailable");
            return;
        }
        boolean sequenceReady = midiReplayer.hasSequence();
        if (!sequenceReady) {
            sequenceReady = prepareRecordedSequence();
        }
        if (!sequenceReady) {
            statusLabel.setText("Load a MIDI file or record a take first");
            refreshPlaybackControls();
            return;
        }
        if (midiReplayer.isAtEnd()) {
            midiReplayer.rewind();
        }
        keyFallCanvas.clear();
        useSequencerClock();
        midiReplayer.play();
        playbackActive = true;
        if (midiReplayer.isSequenceFromFile() && midiReplayer.getSequenceFile() != null) {
            statusLabel.setText("Playing " + midiReplayer.getSequenceFile().getFileName());
        } else {
            statusLabel.setText("Replaying MIDI capture");
        }
        refreshPlaybackControls();
    }

    private boolean prepareRecordedSequence() {
        List<MidiRecorder.Event> events = midiRecorder.getEvents();
        if (events.isEmpty()) {
            return false;
        }
        try {
            midiReplayer.setProgram(midiService.getCurrentProgram());
            midiReplayer.setSequenceFromRecorded(events, MidiRecorder.PPQ, midiRecorder.getInitialBpm());
            loadedReplayFile = currentMidiFile;
            return true;
        } catch (InvalidMidiDataException ex) {
            statusLabel.setText("Replay failed: " + ex.getMessage());
            return false;
        }
    }

    private void pausePlayback() {
        if (midiReplayer != null && midiReplayer.isPlaying()) {
            midiReplayer.pause();
            playbackActive = false;
            statusLabel.setText("Playback paused");
        }
        refreshPlaybackControls();
    }

    private void openMidiFile() {
        if (midiReplayer == null) {
            statusLabel.setText("Synth unavailable for playback");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open MIDI File");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("MIDI Files", "*.mid", "*.midi"));
        if (loadedReplayFile != null && loadedReplayFile.getParent() != null) {
            File parent = loadedReplayFile.getParent().toFile();
            if (parent.isDirectory()) {
                chooser.setInitialDirectory(parent);
            }
        }
        Window window = resolveWindow();
        File selected = chooser.showOpenDialog(window);
        if (selected == null) {
            return;
        }
        stopPlayback();
        keyFallCanvas.clear();
        try {
            midiReplayer.loadSequenceFromFile(selected);
            useSequencerClock();
            loadedReplayFile = selected.toPath();
            statusLabel.setText("Loaded MIDI file → " + selected.getName());
        } catch (IOException | InvalidMidiDataException ex) {
            statusLabel.setText("Load failed: " + ex.getMessage());
        }
        refreshPlaybackControls();
    }

    private void stopPlayback() {
        if (midiReplayer != null && (midiReplayer.isPlaying() || midiReplayer.isPaused())) {
            midiReplayer.stop();
            playbackActive = false;
            keyFallCanvas.clear();
            statusLabel.setText("Playback stopped");
        }
        useSystemClock();
        refreshPlaybackControls();
    }

    private void onPlaybackFinished() {
        if (playbackActive) {
            playbackActive = false;
            statusLabel.setText("Playback finished");
        }
        useSystemClock();
        refreshPlaybackControls();
    }

    private void applySoundFontSettings(String candidatePath, String requestedInstrument) {
        String normalized = candidatePath == null || candidatePath.isBlank() ? null : candidatePath.trim();
        try {
            midiService.initSynth(normalized);
            synthesizer = midiService.getSynthesizer();
            soundFontPath = midiService.getCurrentSoundFontPath();
            updateAvailableInstruments();
            if (midiReplayer == null) {
                midiReplayer = createMidiReplayer();
            }
            applyInstrumentSelection(requestedInstrument);
            if (midiReplayer != null) {
                midiReplayer.setProgram(midiService.getCurrentProgram());
            }
            updateSoundFontStatus();
        } catch (Exception ex) {
            statusLabel.setText("SoundFont failed: " + ex.getMessage() + ". Using default bank.");
            try {
                midiService.initSynth(null);
                synthesizer = midiService.getSynthesizer();
                soundFontPath = midiService.getCurrentSoundFontPath();
                updateAvailableInstruments();
                if (midiReplayer == null) {
                    midiReplayer = createMidiReplayer();
                }
                applyInstrumentSelection(null);
                if (midiReplayer != null) {
                    midiReplayer.setProgram(midiService.getCurrentProgram());
                }
                updateSoundFontStatus();
            } catch (Exception fallbackEx) {
                statusLabel.setText("Synth unavailable: " + fallbackEx.getMessage());
            }
        }
    }

    private void updateAvailableInstruments() {
        availableInstruments.clear();
        availableInstruments.addAll(midiService.getInstrumentNames());
        ensureInstrumentListed(preferredInstrumentName);
    }

    private void applyInstrumentSelection(String requestedInstrument) {
        if (requestedInstrument != null && !requestedInstrument.isBlank()) {
            preferredInstrumentName = requestedInstrument;
        }
        if (preferredInstrumentName == null || preferredInstrumentName.isBlank()) {
            preferredInstrumentName = pickDefaultInstrumentName();
        }
        ensureInstrumentListed(preferredInstrumentName);
        String applied = midiService.applyInstrument(preferredInstrumentName, 0);
        preferredInstrumentName = applied;
        ensureInstrumentListed(preferredInstrumentName);
    }

    private void ensureInstrumentListed(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        for (String existing : availableInstruments) {
            if (existing.equalsIgnoreCase(name)) {
                return;
            }
        }
        availableInstruments.add(name);
    }

    private String pickDefaultInstrumentName() {
        if (availableInstruments.isEmpty()) {
            return preferredInstrumentName != null ? preferredInstrumentName : "Grand Piano";
        }
        for (String name : availableInstruments) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("grand")) {
                return name;
            }
        }
        for (String name : availableInstruments) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("piano")) {
                return name;
            }
        }
        return availableInstruments.get(0);
    }

    private void updateSoundFontStatus() {
        if (statusLabel == null) {
            return;
        }
        String instrumentLabel = preferredInstrumentName != null ? preferredInstrumentName : "GM Program 0";
        if (soundFontPath == null) {
            statusLabel.setText("Using default SoundFont → " + instrumentLabel);
        } else {
            String name = new File(soundFontPath).getName();
            statusLabel.setText("Loaded SoundFont: " + name + " → " + instrumentLabel);
        }
    }

    /**
     * Starts the visualiser animation loop. This should be invoked once the scene has been shown so
     * the viewport has valid bounds.
     */
    public void startAnimation() {
        animationTimer.start();
    }

    private Clock ensureClock() {
        if (clock == null) {
            clock = new SystemClock();
        }
        return clock;
    }

    private void useSystemClock() {
        clock = new SystemClock();
    }

    private void useSequencerClock() {
        if (midiReplayer != null) {
            clock = new SequencerClock(midiReplayer.getSequencer());
        } else {
            clock = new SystemClock();
        }
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
            useSystemClock();
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
            stopPlayback();
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
            refreshPlaybackControls();
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
            refreshPlaybackControls();
            if (videoRecordToggle.isSelected()) {
                videoRecordToggle.setSelected(false);
            }
        }
    }

    private void startVideoRecording() {
        boolean capturingLive = midiRecordToggle.isSelected();
        boolean sequenceReady = midiReplayer != null && midiReplayer.hasSequence();
        if (!capturingLive && !sequenceReady) {
            if (prepareRecordedSequence()) {
                sequenceReady = true;
            }
        }
        boolean capturingReplayNow = !capturingLive && sequenceReady;
        if (!capturingLive && !capturingReplayNow) {
            statusLabel.setText("Start playback or recording before capturing video");
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
        Sequence sequence = capturingReplayNow ? midiReplayer.getCurrentSequence() : null;
        if (capturingReplayNow && sequence == null) {
            statusLabel.setText("No MIDI sequence ready for replay capture");
            videoRecordToggle.setSelected(false);
            return;
        }
        Path outputDir = Optional.ofNullable(videoSettings.getOutputDirectory()).orElse(Paths.get("recordings"));
        String baseName;
        if (capturingLive && currentMidiFile != null) {
            baseName = stripExtension(currentMidiFile.getFileName().toString());
        } else if (capturingReplayNow && midiReplayer.getSequenceFile() != null) {
            baseName = stripExtension(midiReplayer.getSequenceFile().getFileName().toString());
        } else if (loadedReplayFile != null) {
            baseName = stripExtension(loadedReplayFile.getFileName().toString());
        } else {
            baseName = FILE_FORMAT.format(LocalDateTime.now());
        }
        Path finalVideo = outputDir.resolve(baseName + ".mp4");
        Path rawVideo = capturingReplayNow ? outputDir.resolve(baseName + "-video.mp4") : finalVideo;
        currentVideoFile = finalVideo;
        currentVideoTempFile = rawVideo;
        currentAudioFile = null;
        recordingReplay = false;

        if (capturingReplayNow && sequence != null) {
            Path audioOut = outputDir.resolve(baseName + ".wav");
            try {
                midiReplayer.renderToWav(sequence, audioOut, midiService.getReverbPreset(), midiService.getTranspose());
                currentAudioFile = audioOut;
                recordingReplay = true;
            } catch (Exception ex) {
                statusLabel.setText("Audio render failed: " + ex.getMessage() + ". Video will be silent.");
                recordingReplay = false;
                currentAudioFile = null;
            }
        }

        videoRecorder = new VideoRecorder();
        try {
            double captureWidth = captureNode.getLayoutBounds().getWidth();
            double captureHeight = captureNode.getLayoutBounds().getHeight();
            int viewportW = (int) Math.round(captureWidth);
            int viewportH = (int) Math.round(captureHeight);
            System.out.printf("[Session] Capture viewport: %dx%d (video target %dx%d @ %d FPS)%n",
                    viewportW,
                    viewportH,
                    videoSettings.getWidth(),
                    videoSettings.getHeight(),
                    videoSettings.getFps());
            videoRecorder.start(rawVideo, videoSettings);
            videoFrameIntervalNanos = Math.max(1L, Math.round(1_000_000_000.0 / Math.max(1, videoSettings.getFps())));
            lastVideoCaptureNanos = 0;
            String targetLabel = recordingReplay && currentAudioFile != null ? "video+audio" : "video";
            statusLabel.setText("Recording " + targetLabel + " → " + finalVideo.getFileName());
        } catch (IOException ex) {
            statusLabel.setText(ex.getMessage());
            videoRecordToggle.setSelected(false);
            videoRecorder = null;
            currentVideoFile = null;
            currentVideoTempFile = null;
            if (currentAudioFile != null) {
                try {
                    Files.deleteIfExists(currentAudioFile);
                } catch (IOException ignored) {
                }
            }
            currentAudioFile = null;
            recordingReplay = false;
        }
    }

    private void stopVideoRecording() {
        if (videoRecorder == null || !videoRecorder.isRunning()) {
            return;
        }
        try {
            videoRecorder.stop(Duration.ofSeconds(5));
            Path videoSource = currentVideoTempFile != null ? currentVideoTempFile : currentVideoFile;
            if (recordingReplay && currentAudioFile != null && videoSource != null) {
                Path target = currentVideoFile != null ? currentVideoFile : videoSource;
                try {
                    videoRecorder.muxWithAudio(videoSource, currentAudioFile, target, videoSettings);
                    if (!videoSource.equals(target)) {
                        Files.deleteIfExists(videoSource);
                    }
                    Files.deleteIfExists(currentAudioFile);
                    currentVideoFile = target;
                    statusLabel.setText("Saved video " + target.getFileName());
                } catch (IOException muxEx) {
                    statusLabel.setText("Mux failed: " + muxEx.getMessage() + ". Video saved without audio.");
                    if (currentVideoFile != null && !videoSource.equals(currentVideoFile)) {
                        Files.move(videoSource, currentVideoFile, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        currentVideoFile = videoSource;
                    }
                }
            } else {
                String message;
                if (currentVideoFile == null) {
                    currentVideoFile = videoSource;
                    message = currentVideoFile != null ? "Saved video " + currentVideoFile.getFileName() : "Video stopped";
                } else if (videoSource != null && !videoSource.equals(currentVideoFile)) {
                    try {
                        Files.move(videoSource, currentVideoFile, StandardCopyOption.REPLACE_EXISTING);
                        message = "Saved video " + currentVideoFile.getFileName();
                    } catch (IOException moveEx) {
                        message = "Video saved, but move failed: " + moveEx.getMessage();
                        currentVideoFile = videoSource;
                    }
                } else {
                    message = currentVideoFile != null ? "Saved video " + currentVideoFile.getFileName() : "Video stopped";
                }
                statusLabel.setText(message);
            }
        } catch (IOException | InterruptedException ex) {
            statusLabel.setText("Video stop failed: " + ex.getMessage());
        } finally {
            videoRecorder = null;
            recordingReplay = false;
            currentVideoTempFile = null;
            if (currentAudioFile != null) {
                try {
                    Files.deleteIfExists(currentAudioFile);
                } catch (IOException ignored) {
                }
            }
            currentAudioFile = null;
        }
    }

    private Window resolveWindow() {
        if (openMidiButton != null && openMidiButton.getScene() != null) {
            return openMidiButton.getScene().getWindow();
        }
        if (captureNode != null && captureNode.getScene() != null) {
            return captureNode.getScene().getWindow();
        }
        return null;
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(0, dot) : filename;
    }

    private void onAnimationFrame(long now) {
        keyFallCanvas.tickMicros(ensureClock().nowMicros());
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
        SettingsDialog dialog = new SettingsDialog(
                videoSettings,
                soundFontPath,
                availableInstruments,
                preferredInstrumentName,
                velocityCurve,
                midiService.getTranspose(),
                midiService.getReverbPreset(),
                owner);
        dialog.showAndWait().ifPresent(result -> {
            VideoSettings updated = result.videoSettings();
            videoSettings.setOutputDirectory(updated.getOutputDirectory());
            videoSettings.setWidth(updated.getWidth());
            videoSettings.setHeight(updated.getHeight());
            videoSettings.setFps(updated.getFps());
            videoSettings.setCrf(updated.getCrf());
            videoSettings.setPreset(updated.getPreset());
            videoSettings.setFfmpegExecutable(updated.getFfmpegExecutable());

            boolean soundSettingsChanged = false;
            boolean velocityChanged = false;
            boolean transposeChanged = false;
            boolean reverbChanged = false;

            String newSoundFont = result.soundFontPath().orElse(null);
            String newInstrument = result.instrumentName().orElse(null);
            VelCurve newCurve = result.velocityCurve();
            int requestedTranspose = result.transposeSemis();
            ReverbPreset requestedPreset = result.reverbPreset();
            int previousTranspose = midiService.getTranspose();
            ReverbPreset previousPreset = midiService.getReverbPreset();

            String normalizedCurrent = soundFontPath == null ? null : soundFontPath;
            String normalizedNew = (newSoundFont == null || newSoundFont.isBlank()) ? null : newSoundFont.trim();
            if (!Objects.equals(normalizedCurrent, normalizedNew)) {
                applySoundFontSettings(newSoundFont, newInstrument);
                soundSettingsChanged = true;
            } else if (newInstrument != null && (preferredInstrumentName == null
                    || !newInstrument.equalsIgnoreCase(preferredInstrumentName))) {
                applyInstrumentSelection(newInstrument);
                if (midiReplayer != null) {
                    midiReplayer.setProgram(midiService.getCurrentProgram());
                }
                updateSoundFontStatus();
                soundSettingsChanged = true;
            }

            if (newCurve != null && newCurve != velocityCurve) {
                velocityCurve = newCurve;
                keyFallCanvas.setVelocityCurve(newCurve);
                velocityChanged = true;
            }

            if (requestedTranspose != previousTranspose) {
                midiService.setTranspose(requestedTranspose);
                transposeChanged = true;
            }

            if (requestedPreset != null && requestedPreset != previousPreset) {
                midiService.setReverbPreset(requestedPreset);
                reverbChanged = true;
            }

            StringBuilder feedback = new StringBuilder();
            if (soundSettingsChanged) {
                feedback.append("Sound updated");
            }
            if (velocityChanged) {
                if (feedback.length() > 0) {
                    feedback.append(" · ");
                }
                String curveLabel = velocityCurve.name().charAt(0)
                        + velocityCurve.name().substring(1).toLowerCase(Locale.ROOT);
                feedback.append("Velocity curve: ").append(curveLabel);
            }
            if (transposeChanged) {
                if (feedback.length() > 0) {
                    feedback.append(" · ");
                }
                feedback.append(String.format("Transpose: %+d", requestedTranspose));
            }
            if (reverbChanged) {
                if (feedback.length() > 0) {
                    feedback.append(" · ");
                }
                feedback.append("Reverb: ").append(requestedPreset);
            }
            if (feedback.length() == 0) {
                statusLabel.setText("Updated settings");
            } else {
                statusLabel.setText(feedback.toString());
            }
        });
    }

    public void shutdown() {
        animationTimer.stop();
        stopVideoRecording();
        try {
            midiRecorder.stop();
        } catch (IOException ignored) {
        }
        stopPlayback();
        if (midiReplayer != null) {
            midiReplayer.close();
            midiReplayer = null;
        }
        midiService.shutdown();
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
