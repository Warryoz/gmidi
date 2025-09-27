package com.gmidi.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.application.Platform;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * JavaFX component that renders a full 88-key piano keyboard and highlights
 * pressed keys.
 */
final class PianoKeyboardView extends Pane {

    private static final int FIRST_NOTE = 21; // A0
    private static final int LAST_NOTE = 108; // C8
    private static final double WHITE_KEY_WIDTH = 22;
    private static final double WHITE_KEY_HEIGHT = 120;
    private static final double BLACK_KEY_WIDTH = 14;
    private static final double BLACK_KEY_HEIGHT = 80;

    private static final Color WHITE_KEY_COLOR = Color.WHITE;
    private static final Color WHITE_KEY_PRESSED_COLOR = Color.rgb(173, 216, 230);
    private static final Color BLACK_KEY_COLOR = Color.BLACK;
    private static final Color BLACK_KEY_PRESSED_COLOR = Color.rgb(30, 144, 255);

    private final Map<Integer, Key> keysByNote = new HashMap<>();
    private final List<Key> whiteKeys = new ArrayList<>();
    private final List<Key> blackKeys = new ArrayList<>();
    private final Set<Integer> pressedNotes = new HashSet<>();

    PianoKeyboardView() {
        buildKeys();
        layoutKeys();
        double totalWidth = whiteKeys.size() * WHITE_KEY_WIDTH;
        setPrefSize(totalWidth, WHITE_KEY_HEIGHT);
        setMinSize(totalWidth, WHITE_KEY_HEIGHT);
        setMaxSize(totalWidth, WHITE_KEY_HEIGHT);
    }

    void noteOn(int note) {
        if (!isPlayable(note)) {
            return;
        }
        runOnFxThread(() -> {
            pressedNotes.add(note);
            updateKeyFill(note);
        });
    }

    void noteOff(int note) {
        if (!isPlayable(note)) {
            return;
        }
        runOnFxThread(() -> {
            pressedNotes.remove(note);
            updateKeyFill(note);
        });
    }

    void clearPressedNotes() {
        runOnFxThread(() -> {
            pressedNotes.clear();
            keysByNote.values().forEach(key -> key.applyFill(false));
        });
    }

    private void runOnFxThread(Runnable task) {
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    private boolean isPlayable(int note) {
        return note >= FIRST_NOTE && note <= LAST_NOTE;
    }

    private void updateKeyFill(int note) {
        Key key = keysByNote.get(note);
        if (key != null) {
            key.applyFill(pressedNotes.contains(note));
        }
    }

    private void buildKeys() {
        int whiteIndex = 0;
        for (int note = FIRST_NOTE; note <= LAST_NOTE; note++) {
            if (isWhite(note)) {
                double x = whiteIndex * WHITE_KEY_WIDTH;
                Rectangle shape = createRectangle(x, 0, WHITE_KEY_WIDTH, WHITE_KEY_HEIGHT);
                Key key = new Key(note, true, shape);
                shape.setFill(WHITE_KEY_COLOR);
                shape.setStroke(Color.DARKGRAY);
                keysByNote.put(note, key);
                whiteKeys.add(key);
                whiteIndex++;
            } else {
                double x = whiteIndex * WHITE_KEY_WIDTH - BLACK_KEY_WIDTH / 2.0;
                Rectangle shape = createRectangle(x, 0, BLACK_KEY_WIDTH, BLACK_KEY_HEIGHT);
                Key key = new Key(note, false, shape);
                shape.setFill(BLACK_KEY_COLOR);
                keysByNote.put(note, key);
                blackKeys.add(key);
            }
        }
    }

    private Rectangle createRectangle(double x, double y, double width, double height) {
        Rectangle rectangle = new Rectangle(width, height);
        rectangle.setX(x);
        rectangle.setY(y);
        rectangle.setManaged(false);
        return rectangle;
    }

    private void layoutKeys() {
        getChildren().clear();
        for (Key key : whiteKeys) {
            getChildren().add(key.shape);
        }
        for (Key key : blackKeys) {
            getChildren().add(key.shape);
        }
    }

    private boolean isWhite(int note) {
        return switch (Math.floorMod(note, 12)) {
            case 0, 2, 4, 5, 7, 9, 11 -> true;
            default -> false;
        };
    }

    private static final class Key {
        final int note;
        final boolean white;
        final Rectangle shape;

        Key(int note, boolean white, Rectangle shape) {
            this.note = note;
            this.white = white;
            this.shape = shape;
        }

        void applyFill(boolean pressed) {
            if (white) {
                shape.setFill(pressed ? WHITE_KEY_PRESSED_COLOR : WHITE_KEY_COLOR);
            } else {
                shape.setFill(pressed ? BLACK_KEY_PRESSED_COLOR : BLACK_KEY_COLOR);
            }
        }
    }
}
