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
import java.util.Map;

/**
 * Stylised piano keyboard that renders the 88-key range (A0–C8). The view draws into an internal
 * {@link Canvas} so both hover effects and key presses remain smooth on resizes.
 */
public class KeyboardView extends Region {

    private final Canvas canvas = new Canvas(800, 120);
    private final boolean[] pressed = new boolean[128];
    private final Map<Integer, Tooltip> tooltips = new HashMap<>();
    private Tooltip activeTooltip;

    public KeyboardView() {
        getStyleClass().add("keyboard-view");
        getChildren().add(canvas);
        setPadding(new Insets(12, 16, 12, 16));
        setMinHeight(100);
        setPrefHeight(140);
        canvas.addEventHandler(MouseEvent.MOUSE_MOVED, e -> handleHover(e, true));
        canvas.addEventHandler(MouseEvent.MOUSE_EXITED, e -> handleHover(e, false));
        widthProperty().addListener((obs, oldV, newV) -> redraw());
        heightProperty().addListener((obs, oldV, newV) -> redraw());
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
        double w = getWidth();
        double h = getHeight();
        canvas.setWidth(Math.max(200, w - getPadding().getLeft() - getPadding().getRight()));
        canvas.setHeight(Math.max(60, h - getPadding().getTop() - getPadding().getBottom()));
        canvas.relocate(getPadding().getLeft(), getPadding().getTop());
        redraw();
    }

    private void redraw() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, width, height);

        drawWhiteKeys(gc, width, height);
        drawBlackKeys(gc, width, height);
    }

    private void drawWhiteKeys(GraphicsContext gc, double width, double height) {
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
            gc.setStroke(border);
            gc.setLineWidth(1.25);
            gc.strokeRoundRect(keyLeft, 0, keyWidth, whiteKeyHeight, 12, 12);
        }
    }

    private void drawBlackKeys(GraphicsContext gc, double width, double height) {
        double blackKeyHeight = height * 0.62;
        Color base = Color.web("#1E1E1E");
        Color highlight = Color.web("#2CE4D0");

        for (int note : PianoKeyLayout.allNotes()) {
            if (PianoKeyLayout.isWhiteKey(note)) {
                continue;
            }
            double keyLeft = PianoKeyLayout.keyLeft(note, width);
            double keyWidth = PianoKeyLayout.keyWidth(note, width);
            gc.setFill(pressed[note] ? highlight : base);
            gc.fillRoundRect(keyLeft, 0, keyWidth, blackKeyHeight, 8, 8);
            gc.setStroke(Color.web("#101010"));
            gc.setLineWidth(1);
            gc.strokeRoundRect(keyLeft, 0, keyWidth, blackKeyHeight, 8, 8);
        }
    }
}
