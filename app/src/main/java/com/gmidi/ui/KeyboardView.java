package com.gmidi.ui;

import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.Map;

/**
 * Stylised piano keyboard that renders the 88-key range (A0–C8). The view draws into an internal
 * {@link Canvas} so both hover effects and key presses remain smooth on resizes.
 */
public class KeyboardView extends Region {

    private static final double FLASH_DURATION_MS = 120.0;
    private static final Color FLASH_COLOR = Color.web("#2CE4D0");
    private static final double MIN_HEIGHT = 140.0;
    private static final double MAX_HEIGHT = 260.0;
    private static final double DEFAULT_HEIGHT_RATIO = PianoKeyLayout.KEYBOARD_HEIGHT_RATIO;

    private final Canvas canvas = new Canvas(800, 160);
    private final boolean[] pressed = new boolean[128];
    private final long[] flashStartNanos = new long[128];
    private final double[] flashIntensity = new double[128];
    private boolean flashTimerActive;
    private final AnimationTimer flashTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (!redraw(now)) {
                stop();
                flashTimerActive = false;
            }
        }
    };

    private final Map<Integer, Tooltip> tooltips = new HashMap<>();
    private Tooltip activeTooltip;
    private boolean flashActiveDuringDraw;

    public KeyboardView() {
        getStyleClass().add("keyboard-view");
        getChildren().add(canvas);
        setPadding(new Insets(12, 16, 12, 16));
        setMaxWidth(Double.MAX_VALUE);
        setMinHeight(MIN_HEIGHT);
        setPrefHeight(clampHeight(800 * DEFAULT_HEIGHT_RATIO));
        setMaxHeight(MAX_HEIGHT);
        canvas.addEventHandler(MouseEvent.MOUSE_MOVED, e -> handleHover(e, true));
        canvas.addEventHandler(MouseEvent.MOUSE_EXITED, e -> handleHover(e, false));
    }

    public void press(int midiNote) {
        if (midiNote < 0 || midiNote >= pressed.length) {
            return;
        }
        pressed[midiNote] = true;
        redraw();
    }

    public void release(int midiNote) {
        if (midiNote < 0 || midiNote >= pressed.length) {
            return;
        }
        pressed[midiNote] = false;
        redraw();
    }

    public void flash(int midiNote, double intensity) {
        if (midiNote < 0 || midiNote >= flashStartNanos.length) {
            return;
        }
        double clamped = Math.max(0.0, Math.min(1.0, intensity));
        if (clamped <= 0.0) {
            return;
        }
        flashStartNanos[midiNote] = System.nanoTime();
        flashIntensity[midiNote] = clamped;
        ensureFlashTimer();
        redraw();
    }

    private void ensureFlashTimer() {
        if (!flashTimerActive) {
            flashTimer.start();
            flashTimerActive = true;
        }
    }

    private void handleHover(MouseEvent event, boolean inside) {
        if (!inside) {
            if (activeTooltip != null) {
                Tooltip.uninstall(canvas, activeTooltip);
                activeTooltip = null;
            }
            return;
        }
        double width = canvas.getWidth();
        double mouseX = event.getX();
        int hoveredNote = PianoKeyLayout.FIRST_MIDI_NOTE;
        double minDistance = Double.MAX_VALUE;
        for (int note : PianoKeyLayout.allNotes()) {
            double center = PianoKeyLayout.keyCenter(note, width);
            double distance = Math.abs(center - mouseX);
            if (distance < minDistance) {
                minDistance = distance;
                hoveredNote = note;
            }
        }
        Tooltip tooltip = tooltips.computeIfAbsent(hoveredNote, n -> {
            Tooltip tip = new Tooltip(PianoKeyLayout.noteName(n));
            tip.setShowDelay(Duration.millis(150));
            return tip;
        });
        if (activeTooltip != tooltip) {
            if (activeTooltip != null) {
                Tooltip.uninstall(canvas, activeTooltip);
            }
            Tooltip.install(canvas, tooltip);
            activeTooltip = tooltip;
        }
    }

    @Override
    protected void layoutChildren() {
        Insets padding = getPadding();
        double contentWidth = Math.max(1, getWidth() - padding.getLeft() - padding.getRight());
        double contentHeight = Math.max(1, getHeight() - padding.getTop() - padding.getBottom());
        canvas.setWidth(contentWidth);
        canvas.setHeight(contentHeight);
        canvas.relocate(padding.getLeft(), padding.getTop());
        redraw();
    }

    private void redraw() {
        redraw(System.nanoTime());
    }

    private boolean redraw(long nowNanos) {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, width, height);

        flashActiveDuringDraw = false;
        drawWhiteKeys(gc, width, height, nowNanos);
        drawBlackKeys(gc, width, height, nowNanos);

        if (!flashActiveDuringDraw && flashTimerActive) {
            flashTimer.stop();
            flashTimerActive = false;
        }
        return flashActiveDuringDraw;
    }

    private void drawWhiteKeys(GraphicsContext gc, double width, double height, long nowNanos) {
        double whiteKeyHeight = height;
        Color base = Color.web("#F0F0F0");
        Color pressedColor = Color.web("#2CE4D0", 0.55);
        Color border = Color.web("#272727");

        for (int note : PianoKeyLayout.allNotes()) {
            if (!PianoKeyLayout.isWhiteKey(note)) {
                continue;
            }
            double keyLeft = PianoKeyLayout.keyLeft(note, width);
            double keyWidth = PianoKeyLayout.keyWidth(note, width);
            gc.setFill(base.deriveColor(0, 1, pressed[note] ? 0.85 : 1.0, 1));
            gc.fillRoundRect(keyLeft, 0, keyWidth, whiteKeyHeight, 12, 12);
            if (pressed[note]) {
                gc.setFill(pressedColor);
                gc.fillRoundRect(keyLeft + 2, 2, keyWidth - 4, whiteKeyHeight - 4, 10, 10);
            }
            double flashAlpha = computeFlashAlpha(note, nowNanos);
            if (flashAlpha > 0) {
                gc.setFill(FLASH_COLOR.deriveColor(0, 1, 1, flashAlpha));
                gc.fillRoundRect(keyLeft + 2, 2, keyWidth - 4, whiteKeyHeight - 4, 10, 10);
            }
            gc.setStroke(border);
            gc.setLineWidth(1.25);
            gc.strokeRoundRect(keyLeft, 0, keyWidth, whiteKeyHeight, 12, 12);
        }
    }

    private void drawBlackKeys(GraphicsContext gc, double width, double height, long nowNanos) {
        double blackKeyHeight = PianoKeyLayout.blackKeyHeight(height);
        Color base = Color.web("#1E1E1E");
        Color highlight = Color.web("#2CE4D0");

        for (int note : PianoKeyLayout.allNotes()) {
            if (PianoKeyLayout.isWhiteKey(note)) {
                continue;
            }
            double keyLeft = PianoKeyLayout.keyLeft(note, width);
            double keyWidth = PianoKeyLayout.keyWidth(note, width);
            boolean isPressed = pressed[note];
            gc.setFill(isPressed ? highlight : base);
            gc.fillRoundRect(keyLeft, 0, keyWidth, blackKeyHeight, 8, 8);
            double flashAlpha = computeFlashAlpha(note, nowNanos);
            if (flashAlpha > 0) {
                gc.setFill(FLASH_COLOR.deriveColor(0, 1, 1, flashAlpha));
                gc.fillRoundRect(keyLeft, 0, keyWidth, blackKeyHeight, 8, 8);
            }
            gc.setStroke(Color.web("#101010"));
            gc.setLineWidth(1);
            gc.strokeRoundRect(keyLeft, 0, keyWidth, blackKeyHeight, 8, 8);
        }
    }

    private double computeFlashAlpha(int midiNote, long nowNanos) {
        long start = flashStartNanos[midiNote];
        if (start == 0) {
            return 0;
        }
        double elapsedMs = (nowNanos - start) / 1_000_000.0;
        if (elapsedMs >= FLASH_DURATION_MS) {
            flashStartNanos[midiNote] = 0;
            flashIntensity[midiNote] = 0;
            return 0;
        }
        flashActiveDuringDraw = true;
        double base = flashIntensity[midiNote];
        if (base <= 0) {
            return 0;
        }
        return base * (1.0 - (elapsedMs / FLASH_DURATION_MS));
    }

    private double clampHeight(double desired) {
        if (desired < MIN_HEIGHT) {
            return MIN_HEIGHT;
        }
        if (desired > MAX_HEIGHT) {
            return MAX_HEIGHT;
        }
        return desired;
    }
}
