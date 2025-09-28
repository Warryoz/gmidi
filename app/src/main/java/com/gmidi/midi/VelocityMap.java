package com.gmidi.midi;

/**
 * Shared velocity mapper so audio, visuals, and exports stay perfectly aligned.
 */
public final class VelocityMap {

    private volatile VelCurve curve = VelCurve.LINEAR;

    public void setCurve(VelCurve c) {
        curve = c == null ? VelCurve.LINEAR : c;
    }

    public VelCurve getCurve() {
        return curve;
    }

    /**
     * Maps a NOTE_ON velocity in the range 1..127 using the configured curve. Zero is preserved.
     */
    public int map(int velocity) {
        if (velocity <= 0) {
            return 0;
        }
        double x = Math.min(127, velocity) / 127.0;
        double y = switch (curve) {
            case SOFT -> Math.sqrt(x);
            case HARD -> x * x;
            default -> x;
        };
        int out = (int) Math.round(1 + y * 126);
        return Math.max(1, Math.min(127, out));
    }
}
