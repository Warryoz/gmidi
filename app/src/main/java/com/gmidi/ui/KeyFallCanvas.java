package com.gmidi.ui;

import javafx.beans.InvalidationListener;
import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Piano-roll style visualiser that renders falling rectangles for sustained notes. The canvas keeps
 * a pool of {@link NoteSprite} objects to avoid allocation pressure while the animation runs. The
 * drawing window is virtualised: only the most recent {@code windowSeconds} worth of notes is drawn
 * regardless of how long the performance has been running.
 */
public class KeyFallCanvas extends Canvas {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final double DEFAULT_WINDOW_SECONDS = 8.0;
    private static final double MIN_WINDOW_SECONDS = 1.0;
    private static final double FADE_SECONDS = 0.6;
    private static final double MIN_NOTE_HEIGHT = 6.0;
    private static final double NOTE_MARGIN = 32.0;
    private static final Color BACKGROUND = Color.web("#101010");
    private static final double MAX_DIMENSION = 8192.0;
    private static final Color NOTE_COLOR = Color.web("#2CE4D0");

    private final List<NoteSprite> sprites = new ArrayList<>();
    private final Deque<NoteSprite> pool = new ArrayDeque<>();
    @SuppressWarnings("unchecked")
    private final Deque<NoteSprite>[] activePerNote = new Deque[128];

    private final InvalidationListener viewportBoundsListener = obs -> applyViewportBounds();

    private Region boundViewport;
    private boolean sustainPedal;
    private double windowSeconds = DEFAULT_WINDOW_SECONDS;
    private long lastTickNanos;

    public KeyFallCanvas() {
        super(1, 1);
        setFocusTraversable(false);
        setMouseTransparent(true);
        for (int i = 0; i < activePerNote.length; i++) {
            activePerNote[i] = new ArrayDeque<>();
        }
    }

    /**
     * Binds the canvas size to the provided {@code viewport}. The canvas clamps both dimensions to
     * a minimum of one pixel so JavaFX never attempts to create zero-sized backing textures.
     *
     * @param viewport region that drives this canvas' size
     */
    public void bindTo(Region viewport) {
        Objects.requireNonNull(viewport, "viewport");
        if (viewport == boundViewport) {
            applyViewportBounds();
            return;
        }
        unbindViewport();
        boundViewport = viewport;
        boundViewport.layoutBoundsProperty().addListener(viewportBoundsListener);
        applyViewportBounds();
    }

    public void unbindViewport() {
        if (boundViewport != null) {
            boundViewport.layoutBoundsProperty().removeListener(viewportBoundsListener);
            boundViewport = null;
        }
    }

    private void applyViewportBounds() {
        if (boundViewport == null) {
            return;
        }
        // Only observe layoutBoundsProperty to avoid resize feedback loops between the canvas
        // and its parent region. Clamp to a minimum size so the renderer always has a surface.
        Bounds bounds = boundViewport.getLayoutBounds();
        if (bounds == null) {
            return;
        }
        double width = clampDimension(bounds.getWidth());
        double height = clampDimension(bounds.getHeight());
        setWidth(width);
        setHeight(height);
        drawLastFrame();
    }

    private double clampDimension(double value) {
        if (!Double.isFinite(value)) {
            return 1.0;
        }
        return Math.max(1.0, Math.min(MAX_DIMENSION, value));
    }

    /**
     * Sets how many seconds of note history should be visible on screen. Larger windows make notes
     * appear to fall more slowly; smaller windows accelerate the animation.
     */
    public void setWindowSeconds(double seconds) {
        if (seconds < MIN_WINDOW_SECONDS) {
            seconds = MIN_WINDOW_SECONDS;
        }
        if (this.windowSeconds != seconds) {
            this.windowSeconds = seconds;
            drawLastFrame();
        }
    }

    /**
     * Returns the number of seconds represented by the viewport.
     */
    public double getWindowSeconds() {
        return windowSeconds;
    }

    /**
     * Registers a new note onset. Notes outside the MIDI range 0–127 are ignored.
     */
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

    /**
     * Marks a note as released. If the sustain pedal is held the note will continue to render until
     * {@link #onSustain(boolean, long)} reports that the pedal was lifted.
     */
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

    /**
     * Updates the sustain pedal state. When the pedal is released any notes that were waiting for
     * sustain are marked as finished so they can fade out of the viewport.
     */
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

    /**
     * Clears all sprites and returns them to the pool. Invoked when a new recording session starts.
     */
    public void clear() {
        for (NoteSprite sprite : sprites) {
            sprite.reset(0, 0, 0);
            pool.addLast(sprite);
        }
        sprites.clear();
        for (Deque<NoteSprite> stack : activePerNote) {
            stack.clear();
        }
        drawLastFrame();
    }

    /**
     * Advances the animation to {@code nowNanos}. The canvas keeps track of the last timestamp so it
     * can redraw immediately in response to resizes without allocating new backing textures.
     */
    public void tick(long nowNanos) {
        if (nowNanos <= 0) {
            nowNanos = System.nanoTime();
        }
        lastTickNanos = nowNanos;
        draw(nowNanos);
    }

    private void drawLastFrame() {
        if (lastTickNanos == 0) {
            lastTickNanos = System.nanoTime();
        }
        draw(lastTickNanos);
    }

    private void draw(long nowNanos) {
        double width = Math.max(1.0, getWidth());
        double height = Math.max(1.0, getHeight());
        GraphicsContext gc = getGraphicsContext2D();
        gc.clearRect(0, 0, width, height);
        gc.setFill(BACKGROUND);
        gc.fillRect(0, 0, width, height);

        long windowNanos = (long) (windowSeconds * NANOS_PER_SECOND);
        if (windowNanos <= 0) {
            windowNanos = (long) (MIN_WINDOW_SECONDS * NANOS_PER_SECOND);
        }
        long viewEnd = nowNanos;
        long viewStart = viewEnd - windowNanos;
        long fadeCutoff = viewStart - (long) (FADE_SECONDS * NANOS_PER_SECOND);
        double pixelsPerNano = height / (double) windowNanos;
        if (!Double.isFinite(pixelsPerNano) || pixelsPerNano <= 0) {
            pixelsPerNano = 1.0 / NANOS_PER_SECOND;
        }

        Iterator<NoteSprite> iterator = sprites.iterator();
        while (iterator.hasNext()) {
            NoteSprite sprite = iterator.next();
            if (sprite.onTimeNanos > viewEnd) {
                continue;
            }

            long releaseTime = sprite.tailEndNanos >= 0 ? sprite.tailEndNanos : viewEnd;
            if (sprite.tailEndNanos >= 0 && sprite.tailEndNanos < fadeCutoff) {
                recycle(iterator, sprite);
                continue;
            }

            long visibleEnd = Math.min(releaseTime, viewEnd);
            long visibleStart = Math.max(sprite.onTimeNanos, viewStart);
            if (visibleEnd < viewStart) {
                recycle(iterator, sprite);
                continue;
            }

            double bottom = height - ((viewEnd - visibleEnd) * pixelsPerNano);
            double top = height - ((viewEnd - visibleStart) * pixelsPerNano);
            double noteHeight = bottom - top;
            if (!Double.isFinite(noteHeight)) {
                continue;
            }
            if (noteHeight < MIN_NOTE_HEIGHT) {
                noteHeight = MIN_NOTE_HEIGHT;
                top = bottom - noteHeight;
            }
            if (bottom < -NOTE_MARGIN) {
                recycle(iterator, sprite);
                continue;
            }
            if (top > height + NOTE_MARGIN) {
                continue;
            }

            double keyLeft = PianoKeyLayout.keyLeft(sprite.note, width);
            double keyWidth = Math.max(6.0, PianoKeyLayout.keyWidth(sprite.note, width) * 0.82);

            double velocityAlpha = 0.3 + (sprite.velocity / 127.0) * 0.7;
            double fade = 1.0;
            if (sprite.tailEndNanos >= 0) {
                double fadeSeconds = (viewEnd - sprite.tailEndNanos) / (double) NANOS_PER_SECOND;
                fade = Math.max(0.0, 1.0 - (fadeSeconds / FADE_SECONDS));
                if (fade <= 0.01) {
                    recycle(iterator, sprite);
                    continue;
                }
            }
            double alpha = Math.max(0.0, Math.min(1.0, velocityAlpha * fade));
            gc.setFill(NOTE_COLOR.deriveColor(0, 1, 1, alpha));
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
