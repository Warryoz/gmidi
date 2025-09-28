package com.gmidi.ui;

import javafx.animation.AnimationTimer;
import javafx.beans.InvalidationListener;
import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.DoubleConsumer;

/**
 * Piano-roll style visualiser that renders Synthesia-like falling trails. Notes spawn slightly above
 * the viewport, fall at a constant velocity, impact the keyboard line, and fade away after the
 * impact flash has been triggered.
 */
public class KeyFallCanvas extends Canvas {

    private static final long MICROS_PER_SECOND = 1_000_000L;
    private static final double SPAWN_PAD_PX = 24.0;
    private static final double IMPACT_FADE_MS = 160.0;
    private static final double IMPACT_THRESHOLD_PX = 0.0;
    private static final Color NOTE_COLOR = Color.web("#2CE4D0");
    // Tweak these to adjust how velocity influences the visual trails.
    private static final double MIN_TRAIL_THICKNESS = 3.0;
    private static final double MAX_TRAIL_THICKNESS = 12.0;
    private static final double MIN_TRAIL_ALPHA = 0.25;
    private static final double MAX_TRAIL_ALPHA = 1.0;

    private static final int MIN_W = 640;
    private static final int MIN_H = 360;
    private static final int MAX_W = 3072;
    private static final int MAX_H = 2048;
    private static final int STEP_W = 256;
    private static final int STEP_H = 144;

    private final List<FallingNote> active = new ArrayList<>(256);
    private final Deque<FallingNote> pool = new ArrayDeque<>(256);

    private Region boundViewport;
    private final InvalidationListener viewportBoundsListener = obs -> {
        if (boundViewport != null) {
            scheduleViewportApply(boundViewport.getLayoutBounds());
        }
    };

    private boolean renderRequested = true;

    /**
     * The canvas keeps a fixed backing size inside the safe [MIN, MAX] range and maps the viewport
     * into it using these scale factors. This avoids constantly reallocating large RT textures while
     * still matching the visible region exactly.
     */
    private double renderScaleX = 1.0;
    private double renderScaleY = 1.0;
    private int canvasW = 1280;
    private int canvasH = 720;
    private double viewportWidth = MIN_W;
    private double viewportHeight = MIN_H;

    private double fallDurationSeconds = 10.0;
    private double fallSpeedPxPerSec = 600.0;

    private double pendingViewportWidth = MIN_W;
    private double pendingViewportHeight = MIN_H;
    private boolean pendingApply;
    private AnimationTimer resizeCoalescer;

    private BiConsumer<Integer, Double> onImpact;
    private VelCurve velCurve = VelCurve.LINEAR;

    private long lastTickMicros;

    public KeyFallCanvas() {
        super(1, 1);
        setFocusTraversable(false);
        setMouseTransparent(true);
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
            scheduleViewportApply(boundViewport.getLayoutBounds());
            return;
        }
        unbindViewport();
        boundViewport = viewport;
        boundViewport.layoutBoundsProperty().addListener(viewportBoundsListener);
        scheduleViewportApply(boundViewport.getLayoutBounds());
    }

    public double getFallDurationSeconds() {
        return fallDurationSeconds;
    }

    public void setFallDurationSeconds(double seconds) {
        fallDurationSeconds = Math.max(1.0, seconds);
        recomputeFallSpeed();
    }

    public void unbindViewport() {
        if (boundViewport != null) {
            boundViewport.layoutBoundsProperty().removeListener(viewportBoundsListener);
            boundViewport = null;
        }
        if (resizeCoalescer != null) {
            resizeCoalescer.stop();
        }
        pendingApply = false;
    }

    /**
     * Coalesces resize events so the canvas is only resized once per JavaFX pulse. Directly
     * resizing the canvas for every layout invalidation tends to thrash backing textures and can
     * trigger RT texture allocation failures on some GPUs.
     */
    private void scheduleViewportApply(Bounds bounds) {
        if (bounds == null) {
            return;
        }
        double width = Math.max(1.0, bounds.getWidth());
        double height = Math.max(1.0, bounds.getHeight());
        if (!Double.isFinite(width) || !Double.isFinite(height)) {
            return;
        }
        pendingViewportWidth = width;
        pendingViewportHeight = height;
        if (pendingApply) {
            return;
        }
        pendingApply = true;
        if (resizeCoalescer == null) {
            resizeCoalescer = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    pendingApply = false;
                    stop();
                    applyBoundsOnce(pendingViewportWidth, pendingViewportHeight);
                }
            };
        }
        resizeCoalescer.stop();
        resizeCoalescer.start();
    }

    private void applyBoundsOnce(double viewportW, double viewportH) {
        double vw = clamp(viewportW, 1.0, MAX_W * 1.25);
        double vh = clamp(viewportH, 1.0, MAX_H * 1.25);
        if (!Double.isFinite(vw) || !Double.isFinite(vh)) {
            return;
        }

        viewportWidth = vw;
        viewportHeight = vh;
        recomputeFallSpeed();

        int targetW = bucket((int) Math.round(vw), STEP_W, MIN_W, MAX_W);
        int targetH = bucket((int) Math.round(vh), STEP_H, MIN_H, MAX_H);
        if (targetW != canvasW || targetH != canvasH) {
            canvasW = targetW;
            canvasH = targetH;
            setWidth(canvasW);
            setHeight(canvasH);
        }

        // Rendering happens in viewport coordinates; scaling maps that logical space to the
        // allocated backing pixels so the visual content adapts without thrashing RT textures.
        renderScaleX = canvasW / viewportWidth;
        renderScaleY = canvasH / viewportHeight;

        for (FallingNote note : active) {
            positionForMidi(note.midi, x -> note.x = x, w -> note.w = w);
        }

        requestRender();
    }

    private void recomputeFallSpeed() {
        double travelDistance = viewportHeight + SPAWN_PAD_PX;
        if (fallDurationSeconds <= 0) {
            fallSpeedPxPerSec = 600.0;
            return;
        }
        fallSpeedPxPerSec = travelDistance / fallDurationSeconds;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Snaps the requested backing dimension to a multiple of {@code step}. The clamp keeps the
     * texture size inside a conservative range so we only pay for reallocations when the viewport
     * crosses a coarse bucket.
     */
    private static int bucket(int value, int step, int min, int max) {
        int snapped = ((value + step / 2) / step) * step;
        if (snapped < min) {
            return min;
        }
        if (snapped > max) {
            return max;
        }
        return snapped;
    }

    /**
     * Registers a new note onset. Notes outside the MIDI range 0–127 are ignored.
     */
    public void onNoteOn(int midi, int velocity, long tNanos) {
        if (midi < 0 || midi >= 128) {
            return;
        }
        FallingNote note = pool.pollFirst();
        if (note == null) {
            note = new FallingNote();
        }
        note.midi = midi;
        FallingNote finalNote = note;
        FallingNote finalNote1 = note;
        positionForMidi(midi, x -> finalNote.x = x, w -> finalNote1.w = w);
        note.spawnMicros = resolveTimestampMicros(tNanos);
        note.impacted = false;
        note.impactMicros = 0;
        int clampedVelocity = Math.max(1, Math.min(127, velocity));
        double mapped = mapVelocity(clampedVelocity);
        note.trailThickness = lerp(MIN_TRAIL_THICKNESS, MAX_TRAIL_THICKNESS, mapped);
        note.baseAlpha = lerp(MIN_TRAIL_ALPHA, MAX_TRAIL_ALPHA, mapped);
        note.intensity = mapped;
        active.add(note);
        requestRender();
    }

    public void onNoteOff(int midi, long tNanos) {
        // The falling trail decouples visual state from note-off messages.
    }

    public void onSustain(boolean down, long tNanos) {
        // The visualiser ignores sustain; audio and keyboard state still honour it elsewhere.
    }

    public void clear() {
        for (FallingNote note : active) {
            pool.addLast(note);
        }
        active.clear();
        requestRender();
    }

    public void setOnImpact(BiConsumer<Integer, Double> onImpact) {
        this.onImpact = onImpact;
    }

    public void setVelocityCurve(VelCurve curve) {
        velCurve = curve == null ? VelCurve.LINEAR : curve;
    }

    public VelCurve getVelocityCurve() {
        return velCurve;
    }

    public void tickMicros(long nowMicros) {
        long resolvedMicros = resolveTickMicros(nowMicros);
        if (viewportHeight <= 0 || viewportWidth <= 0) {
            return;
        }

        boolean hasVisible = updateActiveNotes(resolvedMicros);
        if (!renderRequested && !hasVisible) {
            return;
        }
        renderRequested = false;
        draw(resolvedMicros);
    }

    public void renderAtMicros(long micros) {
        long resolvedMicros = resolveTickMicros(micros);
        if (viewportHeight <= 0 || viewportWidth <= 0) {
            return;
        }
        updateActiveNotes(resolvedMicros);
        renderRequested = false;
        draw(resolvedMicros);
    }

    private boolean updateActiveNotes(long nowMicros) {
        final double dtScale = MICROS_PER_SECOND;
        final double keyboardLine = viewportHeight;
        boolean hasVisible = false;

        for (int i = active.size() - 1; i >= 0; i--) {
            FallingNote note = active.get(i);
            double elapsed = Math.max(0, (nowMicros - note.spawnMicros) / dtScale);
            double y = -SPAWN_PAD_PX + fallSpeedPxPerSec * elapsed;

            if (!note.impacted && y >= keyboardLine - IMPACT_THRESHOLD_PX) {
                note.impacted = true;
                note.impactMicros = nowMicros;
                if (onImpact != null) {
                    onImpact.accept(note.midi, note.intensity);
                }
                y = keyboardLine;
            }

            if (note.impacted) {
                double fadeMs = (nowMicros - note.impactMicros) / 1_000.0;
                if (fadeMs >= IMPACT_FADE_MS) {
                    active.remove(i);
                    pool.addLast(note);
                    continue;
                }
            } else if (y > keyboardLine + SPAWN_PAD_PX) {
                active.remove(i);
                pool.addLast(note);
                continue;
            }
            hasVisible = true;
        }

        return hasVisible;
    }

    private void draw(long nowMicros) {
        GraphicsContext g = getGraphicsContext2D();
        g.setTransform(1, 0, 0, 1, 0, 0);
        g.clearRect(0, 0, canvasW, canvasH);

        if (viewportWidth <= 0 || viewportHeight <= 0) {
            return;
        }

        g.save();
        g.scale(renderScaleX, renderScaleY);
        g.beginPath();
        g.rect(0, 0, viewportWidth, viewportHeight);
        g.clip();
        g.setFill(NOTE_COLOR);

        final double keyboardLine = viewportHeight;
        for (FallingNote note : active) {
            double elapsed = Math.max(0, (nowMicros - note.spawnMicros) / (double) MICROS_PER_SECOND);
            double y = -SPAWN_PAD_PX + fallSpeedPxPerSec * elapsed;
            double alpha = 1.0;

            if (note.impacted) {
                double fadeMs = (nowMicros - note.impactMicros) / 1_000.0;
                alpha = Math.max(0.0, 1.0 - (fadeMs / IMPACT_FADE_MS));
                y = keyboardLine;
            } else {
                y = Math.min(y, keyboardLine);
            }

            if (alpha <= 0.0) {
                continue;
            }

            double appliedAlpha = alpha * note.baseAlpha;
            if (appliedAlpha <= 0.0) {
                continue;
            }
            g.setGlobalAlpha(appliedAlpha);
            double thickness = note.trailThickness;
            g.fillRoundRect(note.x, y - thickness, note.w, thickness, 6, 6);
        }
        g.setGlobalAlpha(1.0);
        g.restore();
    }

    private void positionForMidi(int midi, DoubleConsumer setX, DoubleConsumer setW) {
        double width = viewportWidth;
        if (width <= 0) {
            setX.accept(0);
            setW.accept(0);
            return;
        }
        if (midi >= PianoKeyLayout.FIRST_MIDI_NOTE && midi <= PianoKeyLayout.LAST_MIDI_NOTE) {
            setX.accept(PianoKeyLayout.keyLeft(midi, width));
            setW.accept(Math.max(6.0, PianoKeyLayout.keyWidth(midi, width)));
            return;
        }
        double whiteWidth = width / PianoKeyLayout.whiteKeyCount();
        double clampedWidth = Math.max(6.0, whiteWidth);
        if (midi < PianoKeyLayout.FIRST_MIDI_NOTE) {
            setX.accept(0.0);
            setW.accept(clampedWidth);
        } else {
            setX.accept(Math.max(0.0, width - clampedWidth));
            setW.accept(clampedWidth);
        }
    }

    private void requestRender() {
        renderRequested = true;
    }

    private long resolveTimestampMicros(long nanos) {
        if (nanos > 0) {
            return nanos / 1_000L;
        }
        if (lastTickMicros > 0) {
            return lastTickMicros;
        }
        return 0L;
    }

    private long resolveTickMicros(long candidate) {
        long resolved = candidate > 0 ? candidate : lastTickMicros;
        if (resolved < 0) {
            resolved = 0;
        }
        lastTickMicros = resolved;
        return resolved;
    }

    private static final class FallingNote {
        int midi;
        double x;
        double w;
        long spawnMicros;
        boolean impacted;
        long impactMicros;
        double trailThickness;
        double baseAlpha;
        double intensity;
    }

    public enum VelCurve {
        LINEAR,
        SOFT,
        HARD // Add more entries here to experiment with custom curves.
    }

    private double mapVelocity(int velocity) {
        double x = clamp(velocity, 1, 127) / 127.0;
        return switch (velCurve) {
            case SOFT -> Math.sqrt(x);
            case HARD -> x * x;
            default -> x;
        };
    }

    private double lerp(double min, double max, double t) {
        return min + (max - min) * clamp(t, 0.0, 1.0);
    }
}
