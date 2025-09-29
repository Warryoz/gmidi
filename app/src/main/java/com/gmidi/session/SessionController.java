package com.gmidi.session;

import com.gmidi.Prefs;
import com.gmidi.midi.MidiRecorder;
import com.gmidi.midi.MidiReplayer;
import com.gmidi.midi.MidiService;
import com.gmidi.midi.MidiService.ReverbPreset;
import com.gmidi.midi.OfflineAudioRenderer;
import com.gmidi.midi.VelCurve;
import com.gmidi.midi.VelocityMap;
import com.gmidi.ui.KeyFallCanvas;
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
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Transform;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Synthesizer;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    private MidiService.ReverbPreset preferredReverb = MidiService.ReverbPreset.ROOM;
    private boolean playbackActive;
    private static final double DEFAULT_KB_HEIGHT_RATIO = 0.24;
    private static final double KB_MIN = 140.0;
    private static final double KB_MAX = 260.0;
    private static final String DARK_THEME = Objects.requireNonNull(
            SessionController.class.getResource("/DarkTheme.css"))
            .toExternalForm();

    private double keyboardHeightRatio = DEFAULT_KB_HEIGHT_RATIO;
    private int visualOffsetMillis;

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
        this.soundFontPath = normalizeSoundFontPath(Prefs.getSoundFontPath());
        this.preferredInstrumentName = sanitizeInstrumentName(Prefs.progName());
        this.velocityCurve = resolveVelocityCurve(Prefs.getVelCurve());
        this.preferredReverb = resolveReverbPreset(Prefs.getReverb());
        this.keyboardHeightRatio = clampKeyboardHeightRatio(Prefs.getKbRatio());
        this.visualOffsetMillis = Prefs.getVisualOffsetMillis();
        double storedFallSeconds = Math.max(1.0, Prefs.getFallSeconds());

        midiService.setVelocityCurve(velocityCurve);
        midiService.setTranspose(Prefs.getTranspose());

        Path storedDir = resolveDirectory(Prefs.getLastExportDir());
        if (storedDir != null) {
            videoSettings.setOutputDirectory(storedDir);
        }

        this.keyFallCanvas.setOnImpact((note, intensity) -> keyboardView.flash(note, intensity));
        this.keyFallCanvas.setVelocityCurve(velocityCurve);
        this.keyFallCanvas.setVisualOffsetMillis(visualOffsetMillis);
        this.keyFallCanvas.setFallDurationSeconds(storedFallSeconds);
        if (fallDurationSlider != null) {
            fallDurationSlider.setValue(storedFallSeconds);
        }

        configureViewportLayout();
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

    /**
     * Ensures the canvas lives inside a clipped stack pane so resizes never collapse to zero
     * pixels and the keyboard stays anchored at the bottom.
     */
    private void configureViewportLayout() {
        if (!(captureNode instanceof Region region)) {
            keyFallCanvas.setMouseTransparent(true);
            return;
        }

        StackPane resolvedPane = null;
        if (region instanceof BorderPane borderPane) {
            Node center = borderPane.getCenter();
            if (center instanceof StackPane stack) {
                resolvedPane = stack;
            } else {
                resolvedPane = new StackPane();
                if (center != null) {
                    resolvedPane.getChildren().add(center);
                }
                borderPane.setCenter(resolvedPane);
            }
            borderPane.setBottom(keyboardView);
        } else if (region instanceof StackPane stack) {
            resolvedPane = stack;
        }

        if (resolvedPane == null) {
            resolvedPane = new StackPane();
        }

        resolvedPane.setMinSize(100, 100);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(resolvedPane.widthProperty());
        clip.heightProperty().bind(resolvedPane.heightProperty());
        resolvedPane.setClip(clip);

        if (!resolvedPane.getChildren().contains(keyFallCanvas)) {
            resolvedPane.getChildren().add(keyFallCanvas);
        }
        keyFallCanvas.setMouseTransparent(true);

        keyFallCanvas.bindTo(resolvedPane);
    }

    private String normalizeSoundFontPath(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String sanitizeInstrumentName(String name) {
        if (name == null || name.isBlank()) {
            return "Grand Piano";
        }
        return name;
    }

    private VelCurve resolveVelocityCurve(String value) {
        if (value == null || value.isBlank()) {
            return VelCurve.LINEAR;
        }
        try {
            return VelCurve.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return VelCurve.LINEAR;
        }
    }

    private MidiService.ReverbPreset resolveReverbPreset(String label) {
        if (label != null && !label.isBlank()) {
            for (MidiService.ReverbPreset preset : MidiService.ReverbPreset.values()) {
                if (preset.toString().equalsIgnoreCase(label)
                        || preset.name().equalsIgnoreCase(label)) {
                    return preset;
                }
            }
        }
        return MidiService.ReverbPreset.ROOM;
    }

    private Path resolveDirectory(String value) throws InvalidPathException {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Paths.get(value.trim());
    }

    private double clampKeyboardHeightRatio(double ratio) {
        if (!Double.isFinite(ratio)) {
            return DEFAULT_KB_HEIGHT_RATIO;
        }
        if (ratio < 0.05) {
            return 0.05;
        }
        if (ratio > 0.6) {
            return 0.6;
        }
        return ratio;
    }

    private void refreshKeyboardHeightBinding() {
        Scene scene = keyboardView.getScene();
        if (scene == null) {
            return;
        }
        keyboardView.prefHeightProperty().unbind();
        bindResponsiveKeyboardHeight(scene, keyboardView);
    }

    private void persistCurrentProgram() {
        MidiService.MidiProgram current = midiService.getCurrentProgram();
        if (current != null) {
            Prefs.putProgram(current.bankMsb(), current.bankLsb(), current.program(), current.displayName());
        }
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
            Prefs.putFallSeconds(seconds);
        });
    }

    private void configureKeyboardSizing() {
        keyboardView.setMinHeight(KB_MIN);
        keyboardView.setMaxHeight(KB_MAX);
        keyboardView.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null) {
                keyboardView.prefHeightProperty().unbind();
            }
            if (newScene != null) {
                bindResponsiveKeyboardHeight(newScene, keyboardView);
                applyDarkTheme(newScene);
            }
        });
        Scene scene = keyboardView.getScene();
        if (scene != null) {
            bindResponsiveKeyboardHeight(scene, keyboardView);
            applyDarkTheme(scene);
        }
    }

    private void bindResponsiveKeyboardHeight(Scene scene, KeyboardView kb) {
        var kbHeight = Bindings.createDoubleBinding(() -> {
            double ratio = clampKeyboardHeightRatio(keyboardHeightRatio);
            double h = scene.getHeight() * ratio;
            if (h < KB_MIN) {
                h = KB_MIN;
            }
            if (h > KB_MAX) {
                h = KB_MAX;
            }
            return h;
        }, scene.heightProperty());
        kb.minHeightProperty().set(KB_MIN);
        kb.maxHeightProperty().set(KB_MAX);
        kb.prefHeightProperty().bind(kbHeight);
    }

    private void applyDarkTheme(Scene scene) {
        if (scene == null) {
            return;
        }
        if (!scene.getStylesheets().contains(DARK_THEME)) {
            scene.getStylesheets().add(DARK_THEME);
        }
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
            Prefs.putSoundFontPath(soundFontPath);
            updateAvailableInstruments();
            if (midiReplayer == null) {
                midiReplayer = createMidiReplayer();
            }
            applyInstrumentSelection(preferredInstrumentName);
            if (midiReplayer != null) {
                midiReplayer.setProgram(midiService.getCurrentProgram());
                midiReplayer.setReverbPreset(preferredReverb);
            }
            midiService.setReverbPreset(preferredReverb);
            preferredReverb = midiService.getReverbPreset();
            Prefs.putReverb(preferredReverb.toString());
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
            public void spawnVisual(int midi, int velocity, long spawnMicros, long impactMicros, long releaseMicros) {
                keyFallCanvas.spawnScheduled(midi, velocity, spawnMicros, impactMicros, releaseMicros);
            }

            @Override
            public void noteOn(int midi, int velocity, long tNanos) {
            }

            @Override
            public void noteOff(int midi, long tNanos) {
                keyFallCanvas.onNoteOff(midi, tNanos);
            }

            @Override
            public void keyDownUntil(int midi, long releaseMicros) {
                keyboardView.keyDownUntil(midi, releaseMicros);
            }

            @Override
            public void noteOnFallback(int midi, int velocity, long tNanos) {
                keyboardView.noteOnFallback(midi);
            }

            @Override
            public void noteOffSchedule(int midi, long releaseMicros) {
                keyboardView.noteOffSchedule(midi, releaseMicros);
            }
        }, synthesizer, midiService::getTranspose, midiService.getVelocityMap());
        replayer.setOnFinished(this::onPlaybackFinished);
        replayer.getSequencer().addMetaEventListener(meta -> {
            if (meta.getType() == 0x2F) {
                Platform.runLater(this::refreshPlaybackControls);
            }
        });
        replayer.setReverbPreset(preferredReverb);
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
                midiReplayer.setReverbPreset(preferredReverb);
            }
            midiService.setReverbPreset(preferredReverb);
            preferredReverb = midiService.getReverbPreset();
            Prefs.putReverb(preferredReverb.toString());
            Prefs.putSoundFontPath(soundFontPath);
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
                    midiReplayer.setReverbPreset(preferredReverb);
                }
                midiService.setReverbPreset(preferredReverb);
                preferredReverb = midiService.getReverbPreset();
                Prefs.putReverb(preferredReverb.toString());
                Prefs.putSoundFontPath(soundFontPath);
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
        preferredInstrumentName = sanitizeInstrumentName(preferredInstrumentName);
        ensureInstrumentListed(preferredInstrumentName);
        String applied = midiService.applyInstrument(preferredInstrumentName, Prefs.program());
        preferredInstrumentName = sanitizeInstrumentName(applied);
        ensureInstrumentListed(preferredInstrumentName);
        persistCurrentProgram();
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
        if (!capturingLive && !sequenceReady && prepareRecordedSequence()) {
            sequenceReady = true;
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

        if (capturingReplayNow) {
            Sequence sequence = midiReplayer.getCurrentSequence();
            if (sequence == null) {
                statusLabel.setText("No MIDI sequence ready for replay capture");
                videoRecordToggle.setSelected(false);
                return;
            }
            exportReplaySequence(sequence, baseName, outputDir);
            return;
        }

        Path rawVideo = finalVideo;
        currentVideoFile = finalVideo;
        currentVideoTempFile = rawVideo;
        currentAudioFile = null;
        recordingReplay = false;

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
            statusLabel.setText("Recording video → " + finalVideo.getFileName());
        } catch (IOException ex) {
            statusLabel.setText(ex.getMessage());
            videoRecordToggle.setSelected(false);
            videoRecorder = null;
            currentVideoFile = null;
            currentVideoTempFile = null;
        }
    }

    private void exportReplaySequence(Sequence sequence, String baseName, Path defaultDir) {
        if (sequence == null) {
            statusLabel.setText("No MIDI sequence ready for export");
            return;
        }
        videoRecordToggle.setDisable(true);
        Window owner = resolveWindow();
        Stage dialog = new Stage();
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Export Video");
        dialog.setResizable(false);

        ComboBox<Integer> fpsBox = new ComboBox<>(FXCollections.observableArrayList(30, 60));
        fpsBox.getSelectionModel().select(Integer.valueOf(60));

        ComboBox<String> resolutionBox = new ComboBox<>(FXCollections.observableArrayList("Current window", "1280×720", "1920×1080"));
        resolutionBox.getSelectionModel().selectFirst();

        ToggleGroup rangeGroup = new ToggleGroup();
        RadioButton entireButton = new RadioButton("Entire piece");
        entireButton.setToggleGroup(rangeGroup);
        entireButton.setSelected(true);
        RadioButton rangeButton = new RadioButton("Range (mm:ss)");
        rangeButton.setToggleGroup(rangeGroup);

        TextField fromField = new TextField(formatMicrosAsTime(0));
        TextField toField = new TextField(formatMicrosAsTime(sequence.getMicrosecondLength()));
        fromField.setDisable(true);
        toField.setDisable(true);
        rangeGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            boolean range = newToggle == rangeButton;
            fromField.setDisable(!range);
            toField.setDisable(!range);
        });

        TextField outputField = new TextField();
        outputField.setPrefColumnCount(28);
        Button browseButton = new Button("Browse…");

        Path lastDir = null;
        try {
            lastDir = resolveDirectory(Prefs.getLastExportDir());
        } catch (Exception ignored) {
        }
        Path initialDir = lastDir != null ? lastDir : defaultDir;
        if (initialDir == null) {
            initialDir = Paths.get(System.getProperty("user.home", "."));
        }
        Path suggestedFile = initialDir.resolve(baseName + ".mp4");
        outputField.setText(suggestedFile.toString());

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Video");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("MP4 Video", "*.mp4"));
        Path finalInitialDir = initialDir;
        browseButton.setOnAction(e -> {
            if (!outputField.getText().isBlank()) {
                File candidate = new File(outputField.getText());
                if (candidate.getParentFile() != null && candidate.getParentFile().isDirectory()) {
                    chooser.setInitialDirectory(candidate.getParentFile());
                    chooser.setInitialFileName(candidate.getName());
                }
            } else if (finalInitialDir != null && Files.isDirectory(finalInitialDir)) {
                chooser.setInitialDirectory(finalInitialDir.toFile());
                chooser.setInitialFileName(baseName + ".mp4");
            }
            File selected = chooser.showSaveDialog(dialog);
            if (selected != null) {
                outputField.setText(selected.getAbsolutePath());
            }
        });

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.add(new Label("FPS"), 0, 0);
        form.add(fpsBox, 1, 0);
        form.add(new Label("Resolution"), 0, 1);
        form.add(resolutionBox, 1, 1);
        form.add(entireButton, 0, 2, 2, 1);
        HBox rangeFields = new HBox(6, rangeButton, new Label("From"), fromField, new Label("To"), toField);
        form.add(rangeFields, 0, 3, 2, 1);
        form.add(new Label("Output"), 0, 4);
        HBox outputBox = new HBox(6, outputField, browseButton);
        HBox.setHgrow(outputField, Priority.ALWAYS);
        form.add(outputBox, 1, 4);

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(360);
        progressBar.setVisible(false);
        Label progressLabel = new Label();
        progressLabel.setVisible(false);

        Button exportButton = new Button("Export");
        Button cancelButton = new Button("Cancel");
        HBox buttons = new HBox(10, exportButton, cancelButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(12, form, progressLabel, progressBar, buttons);
        content.setPadding(new Insets(16));
        dialog.setScene(new Scene(content));

        AtomicBoolean running = new AtomicBoolean(false);
        AtomicBoolean cancelRequested = new AtomicBoolean(false);
        List<Node> inputs = List.of(fpsBox, resolutionBox, entireButton, rangeButton, fromField, toField, outputField, browseButton);

        cancelButton.setOnAction(e -> {
            if (running.get()) {
                cancelRequested.set(true);
                cancelButton.setDisable(true);
            } else {
                dialog.close();
            }
        });

        dialog.setOnCloseRequest(e -> {
            if (running.get()) {
                cancelRequested.set(true);
                cancelButton.setDisable(true);
                e.consume();
            }
        });

        dialog.setOnHidden(e -> {
            videoRecordToggle.setDisable(false);
            videoRecordToggle.setSelected(false);
        });

        exportButton.setOnAction(e -> {
            progressLabel.setVisible(false);
            progressLabel.setText("");
            progressBar.setVisible(false);
            cancelRequested.set(false);

            Integer fpsValue = fpsBox.getValue();
            if (fpsValue == null) {
                progressLabel.setText("Select an FPS.");
                progressLabel.setVisible(true);
                return;
            }
            int fps = fpsValue;

            int width;
            int height;
            String resolution = resolutionBox.getValue();
            if (resolution != null && resolution.contains("1280")) {
                width = 1280;
                height = 720;
            } else if (resolution != null && resolution.contains("1920")) {
                width = 1920;
                height = 1080;
            } else {
                double captureWidth = captureNode.getLayoutBounds().getWidth();
                double captureHeight = captureNode.getLayoutBounds().getHeight();
                if (captureNode instanceof Region region) {
                    if (captureWidth <= 0 || captureHeight <= 0) {
                        region.applyCss();
                        region.layout();
                        captureWidth = region.getLayoutBounds().getWidth();
                        captureHeight = region.getLayoutBounds().getHeight();
                    }
                }
                width = Math.max(1, (int) Math.round(Math.max(1.0, captureWidth)));
                height = Math.max(1, (int) Math.round(Math.max(1.0, captureHeight)));
            }

            long totalMicros = sequence.getMicrosecondLength();
            long startMicros = 0L;
            long endMicros = totalMicros;
            if (rangeButton.isSelected()) {
                try {
                    startMicros = parseTimeToMicros(fromField.getText());
                    endMicros = parseTimeToMicros(toField.getText());
                } catch (IllegalArgumentException ex) {
                    progressLabel.setText(ex.getMessage());
                    progressLabel.setVisible(true);
                    return;
                }
                if (endMicros <= startMicros) {
                    progressLabel.setText("End time must be after start time.");
                    progressLabel.setVisible(true);
                    return;
                }
                startMicros = Math.max(0L, Math.min(startMicros, totalMicros));
                endMicros = Math.max(startMicros, Math.min(endMicros, totalMicros));
            }
            if (endMicros <= startMicros) {
                progressLabel.setText("Select a non-empty time range.");
                progressLabel.setVisible(true);
                return;
            }

            String outputText = outputField.getText().trim();
            if (outputText.isEmpty()) {
                progressLabel.setText("Choose an output file.");
                progressLabel.setVisible(true);
                return;
            }
            Path outputPath;
            try {
                outputPath = Paths.get(outputText);
            } catch (InvalidPathException ex) {
                progressLabel.setText("Invalid output path.");
                progressLabel.setVisible(true);
                return;
            }
            Path parent = outputPath.getParent();
            if (parent == null) {
                progressLabel.setText("Output file must include a directory.");
                progressLabel.setVisible(true);
                return;
            }

            ExportOptions options = new ExportOptions(fps, width, height, startMicros, endMicros, outputPath);

            progressBar.setProgress(0);
            progressBar.setVisible(true);
            progressLabel.setVisible(true);
            progressLabel.setText("Rendering audio…");
            inputs.forEach(node -> node.setDisable(true));
            exportButton.setDisable(true);
            cancelButton.setDisable(false);
            running.set(true);
            statusLabel.setText("Exporting video → " + outputPath.getFileName());

            AtomicReference<String> finalStatus = new AtomicReference<>();
            Task<Void> task = exportMidiToVideo(sequence, options, cancelRequested, finalStatus);
            progressBar.progressProperty().bind(task.progressProperty());
            progressLabel.textProperty().bind(task.messageProperty());

            task.setOnSucceeded(ev -> {
                running.set(false);
                progressBar.progressProperty().unbind();
                progressLabel.textProperty().unbind();
                progressBar.setProgress(1.0);
                String statusText = finalStatus.get();
                progressLabel.setText(statusText != null ? statusText : "Done");
                cancelButton.setDisable(false);
                Path parentDir = options.output().toAbsolutePath().getParent();
                if (parentDir != null) {
                    Prefs.putLastExportDir(parentDir.toString());
                }
                if (statusText != null) {
                    statusLabel.setText(statusText);
                } else {
                    statusLabel.setText("Saved video " + options.output().getFileName());
                }
                dialog.close();
            });

            task.setOnFailed(ev -> {
                running.set(false);
                cancelRequested.set(false);
                progressBar.progressProperty().unbind();
                progressLabel.textProperty().unbind();
                progressBar.setProgress(0);
                Throwable ex = task.getException();
                String message = ex != null && ex.getMessage() != null ? ex.getMessage() : "Export failed";
                progressLabel.setText("Export failed: " + message);
                progressLabel.setVisible(true);
                cancelButton.setDisable(false);
                inputs.forEach(node -> node.setDisable(false));
                exportButton.setDisable(false);
                statusLabel.setText("Export failed: " + message);
            });

            task.setOnCancelled(ev -> {
                running.set(false);
                progressBar.progressProperty().unbind();
                progressLabel.textProperty().unbind();
                progressLabel.setText("Export cancelled");
                progressLabel.setVisible(true);
                cancelButton.setDisable(false);
                statusLabel.setText("Export cancelled");
                dialog.close();
            });

            Thread worker = new Thread(task, "gmidi-export");
            worker.setDaemon(true);
            worker.start();
        });

        dialog.show();
    }

    private Task<Void> exportMidiToVideo(Sequence sequence,
                                         ExportOptions options,
                                         AtomicBoolean cancelFlag,
                                         AtomicReference<String> finalStatus) {
        return new Task<>() {
            @Override
            protected Void call() throws Exception {
                updateProgress(0.0, 1.0);
                updateMessage("Rendering audio…");
                Path tempDir = Files.createTempDirectory("gmidi-export");
                Path audioFile = tempDir.resolve("audio.wav");
                Path videoFile = tempDir.resolve("video.mp4");
                String audioWarning = null;
                boolean audioRendered = false;
                try {
                    Sequence clipped = clipSequence(sequence, options.startMicros(), options.endMicros());
                    try {
                        audioFile = OfflineAudioRenderer.renderWav(
                                clipped,
                                midiService.getCurrentProgram(),
                                midiService.getTranspose(),
                                midiService.getVelocityMap(),
                                midiService.getReverbPreset(),
                                midiService.getCustomSoundbankOrNull(),
                                soundFontPath,
                                audioFile.toFile(),
                                micros -> {
                                    long span = Math.max(1L, options.endMicros() - options.startMicros());
                                    double portion = Math.min(1.0, Math.max(0.0, (double) micros / span));
                                    updateProgress(portion * 0.5, 1.0);
                                },
                                cancelFlag);
                        audioRendered = Files.exists(audioFile) && Files.size(audioFile) > 0L;
                    } catch (OfflineAudioRenderer.AudioUnavailableException ex) {
                        audioWarning = ex.getMessage();
                        audioRendered = false;
                    } catch (InterruptedException ex) {
                        if (cancelFlag.get()) {
                            updateMessage("Cancelling…");
                            cancel();
                            return null;
                        }
                        throw ex;
                    }
                    if (cancelFlag.get()) {
                        updateMessage("Cancelling…");
                        cancel();
                        return null;
                    }

                    updateProgress(0.5, 1.0);
                    updateMessage("Rendering video…");

                    Sequence prepared = OfflineAudioRenderer.prepareSequence(
                            clipped,
                            midiService.getCurrentProgram(),
                            midiService.getReverbPreset(),
                            midiService.getTranspose());
                    List<MidiReplayer.VisualNote> notes = buildVisualNotes(prepared, midiService.getVelocityMap());
                    double[] captureSize;
                    try {
                        captureSize = resolveCaptureSize();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        updateMessage("Cancelling…");
                        cancel();
                        return null;
                    }
                    double captureWidth = captureSize[0];
                    double captureHeight = captureSize[1];
                    double scale = Math.min(options.width() / Math.max(1.0, captureWidth),
                            options.height() / Math.max(1.0, captureHeight));
                    if (!Double.isFinite(scale) || scale <= 0.0) {
                        scale = 1.0;
                    }
                    long stepMicros = Math.max(1L, 1_000_000L / Math.max(1, options.fps()));
                    long totalMicros = Math.max(0L, prepared.getMicrosecondLength());
                    long travelMicros = Math.max(0L, keyFallCanvas.getTravelMicros());
                    long tailMicros = travelMicros;
                    long limitMicros = totalMicros + tailMicros;

                    VideoRecorder recorder = new VideoRecorder();
                    boolean finished = false;
                    try {
                        recorder.begin(options.fps(), options.width(), options.height(), videoFile);
                        FrameRenderer renderer = new FrameRenderer(notes, travelMicros);
                        try {
                            runOnFxAndWait(renderer::reset);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            updateMessage("Cancelling…");
                            cancel();
                            return null;
                        }
                        boolean interrupted = false;
                        for (long micros = 0; micros <= limitMicros && !cancelFlag.get(); micros += stepMicros) {
                            WritableImage frame;
                            try {
                                frame = captureFrameAtMicros(renderer, micros, options.width(), options.height(), scale);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                interrupted = true;
                                break;
                            }
                            recorder.pushFrame(frame);
                            double ratio = limitMicros == 0 ? 1.0 : (double) micros / (double) limitMicros;
                            updateProgress(0.5 + 0.5 * Math.min(1.0, ratio), 1.0);
                        }
                        if (!cancelFlag.get() && !interrupted && limitMicros % stepMicros != 0) {
                            try {
                                WritableImage frame = captureFrameAtMicros(renderer, limitMicros, options.width(), options.height(), scale);
                                recorder.pushFrame(frame);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                interrupted = true;
                            }
                        }
                        if (cancelFlag.get() || interrupted) {
                            updateMessage("Cancelling…");
                            cancel();
                            return null;
                        }
                        recorder.end();
                        finished = true;
                    } finally {
                        if (!finished) {
                            recorder.abortQuietly();
                        }
                        try {
                            runOnFxAndWait(() -> {
                                keyFallCanvas.clear();
                                releaseAllKeys();
                            });
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    if (cancelFlag.get()) {
                        updateMessage("Cancelling…");
                        cancel();
                        return null;
                    }

                    updateMessage("Muxing…");
                    updateProgress(0.99, 1.0);
                    long audioSize = Files.exists(audioFile) ? Files.size(audioFile) : 0L;
                    long videoSize = Files.exists(videoFile) ? Files.size(videoFile) : 0L;
                    if (videoSize <= 0) {
                        throw new IOException("No video frames rendered; aborting mux.");
                    }
                    if (audioRendered && audioSize > 0) {
                        new VideoRecorder().muxWithAudio(videoFile, audioFile, options.output(), videoSettings);
                        updateProgress(1.0, 1.0);
                        updateMessage("Done");
                        return null;
                    }

                    Path output = options.output();
                    Path parent = output.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.move(videoFile, output, StandardCopyOption.REPLACE_EXISTING);
                    String warning = audioWarning != null
                            ? audioWarning
                            : "Audio export unavailable: Java SoftSynth couldn't be accessed. Start the app with --add-exports=java.desktop/com.sun.media.sound=ALL-UNNAMED, or install fluidsynth and ensure it's on PATH. Video saved without audio.";
                    String finalMessage = warning + " Saved video " + output.getFileName() + '.';
                    finalStatus.set(finalMessage);
                    updateProgress(1.0, 1.0);
                    updateMessage(finalMessage);
                    return null;
                } finally {
                    try {
                        Files.deleteIfExists(audioFile);
                    } catch (IOException ignored) {
                    }
                    try {
                        Files.deleteIfExists(videoFile);
                    } catch (IOException ignored) {
                    }
                    try {
                        Files.deleteIfExists(tempDir);
                    } catch (IOException ignored) {
                    }
                }
            }
        };
    }

    private double[] resolveCaptureSize() throws InterruptedException {
        AtomicReference<double[]> ref = new AtomicReference<>();
        runOnFxAndWait(() -> {
            double width = Math.max(1.0, captureNode.getLayoutBounds().getWidth());
            double height = Math.max(1.0, captureNode.getLayoutBounds().getHeight());
            if (captureNode instanceof Region region) {
                if (width <= 0 || height <= 0) {
                    region.applyCss();
                    region.layout();
                    width = Math.max(1.0, region.getLayoutBounds().getWidth());
                    height = Math.max(1.0, region.getLayoutBounds().getHeight());
                }
            }
            ref.set(new double[]{width, height});
        });
        return ref.get();
    }

    private Sequence clipSequence(Sequence source, long startMicros, long endMicros) throws Exception {
        long length = source.getMicrosecondLength();
        long start = Math.max(0L, Math.min(startMicros, length));
        long end = Math.max(start, Math.min(endMicros, length));
        if (start == 0 && end >= length) {
            return source;
        }
        long startTick = microsToTicks(source, start);
        long endTick = microsToTicks(source, end);
        Sequence clipped = new Sequence(source.getDivisionType(), source.getResolution());
        javax.sound.midi.Track[] srcTracks = source.getTracks();
        for (javax.sound.midi.Track src : srcTracks) {
            javax.sound.midi.Track dst = clipped.createTrack();
            MidiEvent tempoSnapshot = null;
            for (int i = 0; i < src.size(); i++) {
                MidiEvent event = src.get(i);
                MidiMessage message = event.getMessage();
                long tick = event.getTick();
                if (tick < startTick) {
                    if (message instanceof MetaMessage meta && meta.getType() == 0x51) {
                        tempoSnapshot = new MidiEvent((MidiMessage) meta.clone(), 0L);
                    }
                    continue;
                }
                if (tick > endTick) {
                    if (message instanceof ShortMessage shortMessage && isNoteOff(shortMessage)) {
                        MidiMessage clone = (MidiMessage) shortMessage.clone();
                        long adjusted = Math.max(0L, endTick - startTick);
                        dst.add(new MidiEvent(clone, adjusted));
                    }
                    continue;
                }
                MidiMessage clone = (MidiMessage) message.clone();
                long adjusted = Math.max(0L, tick - startTick);
                dst.add(new MidiEvent(clone, adjusted));
            }
            if (tempoSnapshot != null) {
                dst.add(tempoSnapshot);
            }
        }
        if (clipped.getTracks().length == 0) {
            clipped.createTrack();
        }
        return clipped;
    }

    private boolean isNoteOff(ShortMessage message) {
        int command = message.getCommand();
        return command == ShortMessage.NOTE_OFF
                || (command == ShortMessage.NOTE_ON && message.getData2() == 0);
    }

    private long microsToTicks(Sequence sequence, long micros) throws Exception {
        Sequencer converter = MidiSystem.getSequencer(false);
        converter.open();
        try {
            converter.setSequence(sequence);
            converter.setMicrosecondPosition(Math.max(0L, micros));
            return converter.getTickPosition();
        } finally {
            converter.close();
        }
    }

    private String formatMicrosAsTime(long micros) {
        long totalSeconds = Math.max(0L, Math.round(micros / 1_000_000.0));
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private long parseTimeToMicros(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Enter time as mm:ss");
        }
        String trimmed = text.trim();
        String[] parts = trimmed.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Enter time as mm:ss");
        }
        int minutes;
        double seconds;
        try {
            minutes = Integer.parseInt(parts[0]);
            seconds = Double.parseDouble(parts[1]);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Enter time as mm:ss");
        }
        if (minutes < 0 || seconds < 0) {
            throw new IllegalArgumentException("Time cannot be negative");
        }
        double totalSeconds = minutes * 60.0 + seconds;
        return (long) Math.round(totalSeconds * 1_000_000.0);
    }

    private record ExportOptions(int fps, int width, int height, long startMicros, long endMicros, Path output) {
    }

    private WritableImage captureFrameAtMicros(FrameRenderer renderer,
                                               long micros,
                                               int frameWidth,
                                               int frameHeight,
                                               double scale) throws InterruptedException {
        AtomicReference<WritableImage> frameRef = new AtomicReference<>();
        runOnFxAndWait(() -> {
            renderer.advanceTo(micros);
            keyFallCanvas.renderAtMicros(micros);
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.rgb(18, 18, 18));
            if (scale != 1.0) {
                params.setTransform(Transform.scale(scale, scale));
            }
            WritableImage target = new WritableImage(frameWidth, frameHeight);
            WritableImage snapshot = captureNode.snapshot(params, target);
            frameRef.set(snapshot);
        });
        return frameRef.get();
    }

    private void runOnFxAndWait(Runnable action) throws InterruptedException {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await();
    }

    private void releaseAllKeys() {
        for (int note = 0; note < 128; note++) {
            keyboardView.release(note);
        }
    }

    private List<MidiReplayer.VisualNote> buildVisualNotes(Sequence sequence, VelocityMap velocityMap) {
        try {
            List<MidiReplayer.VisualNote> raw = MidiReplayer.collectVisualNotes(sequence);
            if (raw.isEmpty()) {
                return raw;
            }
            List<MidiReplayer.VisualNote> mapped = new ArrayList<>(raw.size());
            for (MidiReplayer.VisualNote note : raw) {
                int midi = Math.max(0, Math.min(127, note.key()));
                int velocity = velocityMap != null ? velocityMap.map(note.velocity()) : note.velocity();
                long onMicros = Math.max(0L, note.onMicros());
                long releaseMicros = Math.max(onMicros, note.releaseMicros());
                mapped.add(new MidiReplayer.VisualNote(
                        midi,
                        note.channel(),
                        onMicros,
                        releaseMicros,
                        Math.max(0, Math.min(127, velocity))));
            }
            mapped.sort(Comparator.comparingLong(MidiReplayer.VisualNote::onMicros));
            return mapped;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to prepare visual notes", ex);
        }
    }

    private final class FrameRenderer {
        private final List<MidiReplayer.VisualNote> notes;
        private final long travelMicros;
        private int nextSpawn;
        private int nextImpact;

        FrameRenderer(List<MidiReplayer.VisualNote> notes, long travelMicros) {
            this.notes = notes;
            this.travelMicros = Math.max(1L, travelMicros);
        }

        void reset() {
            keyFallCanvas.clear();
            releaseAllKeys();
            nextSpawn = 0;
            nextImpact = 0;
        }

        void advanceTo(long micros) {
            while (nextSpawn < notes.size()) {
                MidiReplayer.VisualNote note = notes.get(nextSpawn);
                long spawnMicros = note.onMicros() - travelMicros;
                if (spawnMicros > micros) {
                    break;
                }
                nextSpawn++;
                keyFallCanvas.spawnScheduled(
                        note.key(),
                        note.velocity(),
                        spawnMicros,
                        note.onMicros(),
                        note.releaseMicros());
            }
            while (nextImpact < notes.size() && notes.get(nextImpact).onMicros() <= micros) {
                MidiReplayer.VisualNote note = notes.get(nextImpact);
                keyboardView.keyDownUntil(note.key(), note.releaseMicros());
                nextImpact++;
            }
            keyboardView.tickMicros(micros);
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
        if (midiReplayer != null) {
            midiReplayer.pumpVisuals(keyFallCanvas.getTravelMicros());
        }
        long nowMicros = ensureClock().nowMicros();
        keyFallCanvas.tickMicros(nowMicros);
        keyboardView.tickMicros(nowMicros);
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
                keyboardHeightRatio,
                visualOffsetMillis,
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
            if (updated.getOutputDirectory() != null) {
                Prefs.putLastExportDir(updated.getOutputDirectory().toString());
            }

            boolean soundSettingsChanged = false;
            boolean velocityChanged = false;
            boolean transposeChanged = false;
            boolean reverbChanged = false;
            boolean keyboardChanged = false;
            boolean offsetChanged = false;

            String newSoundFont = result.soundFontPath().orElse(null);
            String newInstrument = result.instrumentName().orElse(null);
            VelCurve newCurve = result.velocityCurve();
            int requestedTranspose = result.transposeSemis();
            ReverbPreset requestedPreset = result.reverbPreset();
            double requestedKeyboardRatio = result.keyboardHeightRatio();
            int requestedVisualOffset = result.visualOffsetMillis();
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
                    midiReplayer.setReverbPreset(preferredReverb);
                }
                midiService.setReverbPreset(preferredReverb);
                preferredReverb = midiService.getReverbPreset();
                Prefs.putReverb(preferredReverb.toString());
                updateSoundFontStatus();
                soundSettingsChanged = true;
            }

            if (newCurve != null && newCurve != velocityCurve) {
                velocityCurve = newCurve;
                keyFallCanvas.setVelocityCurve(newCurve);
                midiService.setVelocityCurve(newCurve);
                Prefs.putVelCurve(newCurve.name());
                velocityChanged = true;
            }

            if (requestedTranspose != previousTranspose) {
                midiService.setTranspose(requestedTranspose);
                Prefs.putTranspose(requestedTranspose);
                transposeChanged = true;
            }

            if (requestedPreset != null && requestedPreset != previousPreset) {
                midiService.setReverbPreset(requestedPreset);
                preferredReverb = midiService.getReverbPreset();
                if (midiReplayer != null) {
                    midiReplayer.setReverbPreset(preferredReverb);
                }
                Prefs.putReverb(preferredReverb.toString());
                reverbChanged = true;
            }

            double clampedRatio = clampKeyboardHeightRatio(requestedKeyboardRatio);
            if (Math.abs(clampedRatio - keyboardHeightRatio) > 1e-4) {
                keyboardHeightRatio = clampedRatio;
                Prefs.putKbRatio(keyboardHeightRatio);
                refreshKeyboardHeightBinding();
                keyboardChanged = true;
            }

            if (requestedVisualOffset != visualOffsetMillis) {
                visualOffsetMillis = requestedVisualOffset;
                keyFallCanvas.setVisualOffsetMillis(visualOffsetMillis);
                Prefs.putVisualOffsetMillis(visualOffsetMillis);
                offsetChanged = true;
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
            if (keyboardChanged) {
                if (feedback.length() > 0) {
                    feedback.append(" · ");
                }
                feedback.append(String.format("Keyboard %.0f%%", keyboardHeightRatio * 100.0));
            }
            if (offsetChanged) {
                if (feedback.length() > 0) {
                    feedback.append(" · ");
                }
                feedback.append(String.format("Visual offset: %+d ms", visualOffsetMillis));
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
