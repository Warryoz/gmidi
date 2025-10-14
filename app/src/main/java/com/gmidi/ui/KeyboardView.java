package com.gmidi.ui;

import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Stylised piano keyboard that renders the 88-key range (A0–C8). The view draws into an internal
 * {@link Canvas} so both hover effects and key presses remain smooth on resizes.
 */
public class KeyboardView extends Region {

    private static final double FLASH_DURATION_MS = 120.0;
    private static final long FLASH_DURATION_MICROS = (long) (FLASH_DURATION_MS * 1_000.0);
    private static final Color FLASH_COLOR = Color.web("#2CE4D0");
    private static final double MIN_HEIGHT = 140.0;
    private static final double MAX_HEIGHT = 260.0;
    private static final double DEFAULT_HEIGHT_RATIO = PianoKeyLayout.KEYBOARD_HEIGHT_RATIO;

    private final Canvas canvas = new Canvas(800, 160);
    private final boolean[] pressed = new boolean[128];
    private final long[] flashStartMicros = new long[128];
    private final long[] flashEndMicros = new long[128];
    private final double[] flashIntensity = new double[128];
    private boolean flashActive;
    private long lastNowMicros = -1L;

    private final Map<Integer, Tooltip> tooltips = new HashMap<>();
    private Tooltip activeTooltip;
    private final Map<Integer, Long> keyReleaseMicros = new HashMap<>();

    public KeyboardView() {
        getStyleClass().add("keyboard-view");
        getChildren().add(canvas);
        setPadding(new Insets(12, 16, 12, 16));
        setMaxWidth(Double.MAX_VALUE);
        setMinHeight(MIN_HEIGHT);
        setPrefHeight(clampHeight(800 * DEFAULT_HEIGHT_RATIO));
        setMaxHeight(MAX_HEIGHT);
        widthProperty().addListener((obs, oldV, newV) -> redraw());
        heightProperty().addListener((obs, oldV, newV) -> redraw());
        canvas.addEventHandler(MouseEvent.MOUSE_MOVED, e -> handleHover(e, true));
        canvas.addEventHandler(MouseEvent.MOUSE_EXITED, e -> handleHover(e, false));
    }

    public void press(int midiNote) {
        if (midiNote < 0 || midiNote >= pressed.length) {
            return;
        }
        keyReleaseMicros.remove(midiNote);
        if (markDown(midiNote)) {
            redraw();
        }
    }

    public void release(int midiNote) {
        if (midiNote < 0 || midiNote >= pressed.length) {
            return;
        }
        keyReleaseMicros.remove(midiNote);
        if (markUp(midiNote)) {
            redraw();
        }
    }

    public void keyDownUntil(int midiNote, long releaseMicros) {
        if (midiNote < 0 || midiNote >= pressed.length) {
            return;
        }
        markDown(midiNote);
        if (releaseMicros > 0) {
            keyReleaseMicros.merge(midiNote, releaseMicros, Math::max);
        } else {
            keyReleaseMicros.remove(midiNote);
        }
        redraw();
    }

    public void noteOnFallback(int midiNote) {
        if (midiNote < 0 || midiNote >= pressed.length) {
            return;
        }
        markDown(midiNote);
        keyReleaseMicros.remove(midiNote);
        redraw();
    }

    public void noteOffSchedule(int midiNote, long releaseMicros) {
        if (midiNote < 0 || midiNote >= pressed.length) {
            return;
        }
        if (releaseMicros <= 0L) {
            keyReleaseMicros.remove(midiNote);
            if (markUp(midiNote)) {
                redraw();
            }
            return;
        }
        markDown(midiNote);
        keyReleaseMicros.merge(midiNote, releaseMicros, Math::max);
        redraw();
    }

    public void tickMicros(long nowMicros) {
        long resolvedNow = resolveNowMicros(nowMicros);
        boolean changed = false;
        if (!keyReleaseMicros.isEmpty()) {
            Iterator<Map.Entry<Integer, Long>> it = keyReleaseMicros.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, Long> entry = it.next();
                if (resolvedNow >= entry.getValue()) {
                    it.remove();
                    if (markUp(entry.getKey())) {
                        changed = true;
                    }
                }
            }
        }
        boolean flashNeedsRedraw = updateFlash(resolvedNow);
        if (changed || flashNeedsRedraw) {
            redraw(resolvedNow);
        }
    }

    private boolean updateFlash(long nowMicros) {
        if (!flashActive) {
            return false;
        }
        boolean active = false;
        boolean changed = false;
        for (int i = 0; i < flashEndMicros.length; i++) {
            long end = flashEndMicros[i];
            if (end == 0L) {
                continue;
            }
            if (nowMicros >= end) {
                flashStartMicros[i] = 0L;
                flashEndMicros[i] = 0L;
                flashIntensity[i] = 0.0;
                changed = true;
            } else {
                active = true;
            }
        }
        flashActive = active;
        return changed || active;
    }

    public void flash(int midiNote, double intensity) {
        flash(midiNote, intensity, currentMicros());
    }

    public void flash(int midiNote, double intensity, long nowMicros) {
        if (midiNote < 0 || midiNote >= flashStartMicros.length) {
            return;
        }
        double clamped = Math.max(0.0, Math.min(1.0, intensity));
        if (clamped <= 0.0) {
            return;
        }
        long resolvedNow = resolveNowMicros(nowMicros);
        flashStartMicros[midiNote] = resolvedNow;
        flashEndMicros[midiNote] = resolvedNow + FLASH_DURATION_MICROS;
        flashIntensity[midiNote] = clamped;
        flashActive = true;
        redraw(resolvedNow);
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

    private long currentMicros() {
        return System.nanoTime() / 1_000L;
    }

    private long resolveNowMicros(long candidate) {
        if (candidate >= 0L) {
            lastNowMicros = candidate;
            return candidate;
        }
        if (lastNowMicros >= 0L) {
            return lastNowMicros;
        }
        long fallback = currentMicros();
        lastNowMicros = fallback;
        return fallback;
    }

    private void redraw() {
        redraw(lastNowMicros >= 0 ? lastNowMicros : currentMicros());
    }

    private void redraw(long nowMicros) {
        long resolvedNow = resolveNowMicros(nowMicros);
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, width, height);

        drawWhiteKeys(gc, width, height, resolvedNow);
        drawBlackKeys(gc, width, height, resolvedNow);
    }

    private void drawWhiteKeys(GraphicsContext gc, double width, double height, long nowMicros) {
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
            double flashAlpha = computeFlashAlpha(note, nowMicros);
            if (flashAlpha > 0) {
                gc.setFill(FLASH_COLOR.deriveColor(0, 1, 1, flashAlpha));
                gc.fillRoundRect(keyLeft + 2, 2, keyWidth - 4, whiteKeyHeight - 4, 10, 10);
            }
            gc.setStroke(border);
            gc.setLineWidth(1.25);
            gc.strokeRoundRect(keyLeft, 0, keyWidth, whiteKeyHeight, 12, 12);
        }
    }

    private void drawBlackKeys(GraphicsContext gc, double width, double height, long nowMicros) {
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
            double flashAlpha = computeFlashAlpha(note, nowMicros);
            if (flashAlpha > 0) {
                gc.setFill(FLASH_COLOR.deriveColor(0, 1, 1, flashAlpha));
                gc.fillRoundRect(keyLeft, 0, keyWidth, blackKeyHeight, 8, 8);
            }
            gc.setStroke(Color.web("#101010"));
            gc.setLineWidth(1);
            gc.strokeRoundRect(keyLeft, 0, keyWidth, blackKeyHeight, 8, 8);
        }
    }

    private double computeFlashAlpha(int midiNote, long nowMicros) {
        long start = flashStartMicros[midiNote];
        long end = flashEndMicros[midiNote];
        if (start == 0L || end <= start || nowMicros >= end) {
            return 0.0;
        }
        double base = flashIntensity[midiNote];
        if (base <= 0.0) {
            return 0.0;
        }
        double duration = end - start;
        double elapsed = nowMicros - start;
        double remaining = 1.0 - (elapsed / duration);
        if (remaining <= 0.0) {
            return 0.0;
        }
        return base * remaining;
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

    private boolean markDown(int midiNote) {
        if (!pressed[midiNote]) {
            pressed[midiNote] = true;
            return true;
        }
        return false;
    }

    private boolean markUp(int midiNote) {
        if (pressed[midiNote]) {
            pressed[midiNote] = false;
            return true;
        }
        return false;
    }
}
