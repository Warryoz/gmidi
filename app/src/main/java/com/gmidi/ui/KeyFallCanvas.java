package com.gmidi.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Piano-roll style visualiser that renders falling rectangles for sustained notes. The canvas keeps
 * a pool of {@link NoteSprite} objects to avoid allocation pressure while the animation runs.
 */
public class KeyFallCanvas extends Canvas {

    private static final double SECONDS_VISIBLE = 8.0;
    private static final double FADE_SECONDS = 0.6;

    private final List<NoteSprite> sprites = new ArrayList<>();
    private final Deque<NoteSprite> pool = new ArrayDeque<>();
    @SuppressWarnings("unchecked")
    private final Deque<NoteSprite>[] activePerNote = new Deque[128];

    private boolean sustainPedal;

    public KeyFallCanvas() {
        super(800, 480);
        setFocusTraversable(false);
        for (int i = 0; i < activePerNote.length; i++) {
            activePerNote[i] = new ArrayDeque<>();
        }
    }

    public void onNoteOn(int note, int velocity, long timestampNanos) {
        if (note < 0 || note >= activePerNote.length) {
            return;
        }
        NoteSprite sprite = pool.pollFirst();
        if (sprite == null) {
            sprite = new NoteSprite();
        }
        sprite.reset(note, velocity, timestampNanos);
        sprites.add(sprite);
        activePerNote[note].addLast(sprite);
    }

    public void onNoteOff(int note, long timestampNanos) {
        if (note < 0 || note >= activePerNote.length) {
            return;
        }
        Deque<NoteSprite> stack = activePerNote[note];
        NoteSprite sprite = stack.peekLast();
        if (sprite == null) {
            return;
        }
        sprite.markReleased(sustainPedal, timestampNanos);
        if (!sustainPedal) {
            stack.removeLast();
        }
    }

    public void onSustain(boolean pressed, long timestampNanos) {
        sustainPedal = pressed;
        if (!pressed) {
            for (Deque<NoteSprite> stack : activePerNote) {
                Iterator<NoteSprite> iterator = stack.iterator();
                while (iterator.hasNext()) {
                    NoteSprite sprite = iterator.next();
                    if (sprite.isAwaitingSustainRelease()) {
                        sprite.sustainReleased(timestampNanos);
                        iterator.remove();
                    }
                }
            }
        }
    }

    public void clear() {
        for (NoteSprite sprite : sprites) {
            sprite.reset(0, 0, 0);
            pool.addLast(sprite);
        }
        sprites.clear();
        for (Deque<NoteSprite> stack : activePerNote) {
            stack.clear();
        }
    }

    public void tick(long nowNanos) {
        double width = getWidth();
        double height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        GraphicsContext gc = getGraphicsContext2D();
        gc.setFill(Color.web("#101010"));
        gc.fillRect(0, 0, width, height);

        double secondsPerPixel = SECONDS_VISIBLE / height;

        Iterator<NoteSprite> iterator = sprites.iterator();
        while (iterator.hasNext()) {
            NoteSprite sprite = iterator.next();
            double elapsedSinceStart = (nowNanos - sprite.onTimeNanos) / 1_000_000_000.0;
            if (elapsedSinceStart < 0) {
                continue;
            }
            double bottom = elapsedSinceStart / secondsPerPixel;
            if (bottom < 0) {
                continue;
            }
            if (bottom > height + 40) {
                recycle(iterator, sprite);
                continue;
            }
            long endTime = sprite.tailEndNanos >= 0 ? sprite.tailEndNanos : nowNanos;
            double duration = (endTime - sprite.onTimeNanos) / 1_000_000_000.0;
            if (duration < 0) {
                duration = 0;
            }
            double noteHeight = duration / secondsPerPixel;
            if (noteHeight < 6) {
                noteHeight = 6;
            }
            double top = bottom - noteHeight;
            if (top > height + 20) {
                recycle(iterator, sprite);
                continue;
            }

            double keyLeft = PianoKeyLayout.keyLeft(sprite.note, width);
            double keyWidth = Math.max(6, PianoKeyLayout.keyWidth(sprite.note, width) * 0.8);

            double velocityAlpha = 0.3 + (sprite.velocity / 127.0) * 0.7;
            double fade = 1.0;
            if (sprite.tailEndNanos >= 0) {
                double fadeSeconds = (nowNanos - sprite.tailEndNanos) / 1_000_000_000.0;
                fade = Math.max(0, 1.0 - fadeSeconds / FADE_SECONDS);
                if (fade <= 0.01) {
                    recycle(iterator, sprite);
                    continue;
                }
            }
            double alpha = velocityAlpha * fade;
            Color fill = Color.web("#2CE4D0", alpha);
            gc.setFill(fill);
            gc.fillRoundRect(keyLeft, top, keyWidth, noteHeight, 12, 12);
        }
    }

    private void recycle(Iterator<NoteSprite> iterator, NoteSprite sprite) {
        iterator.remove();
        activePerNote[sprite.note].remove(sprite);
        sprite.reset(0, 0, 0);
        pool.addLast(sprite);
    }

    private static final class NoteSprite {
        int note;
        int velocity;
        long onTimeNanos;
        long tailEndNanos = -1;
        boolean awaitingSustain;

        void reset(int note, int velocity, long start) {
            this.note = note;
            this.velocity = velocity;
            this.onTimeNanos = start;
            this.tailEndNanos = -1;
            this.awaitingSustain = false;
        }

        void markReleased(boolean sustainActive, long time) {
            if (sustainActive) {
                awaitingSustain = true;
            } else {
                tailEndNanos = time;
            }
        }

        void sustainReleased(long time) {
            tailEndNanos = time;
            awaitingSustain = false;
        }

        boolean isAwaitingSustainRelease() {
            return awaitingSustain;
        }
    }
}
