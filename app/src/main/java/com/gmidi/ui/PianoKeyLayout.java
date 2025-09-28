package com.gmidi.ui;

/**
 * Utility math that maps MIDI note numbers (A0–C8) to horizontal positions on a piano keyboard.
 * The same geometry is shared by the keyboard widget and the key-fall visualiser to keep the
 * rendered keys perfectly aligned.
 */
public final class PianoKeyLayout {

    public static final int FIRST_MIDI_NOTE = 21; // A0
    public static final int LAST_MIDI_NOTE = 108; // C8
    public static final int KEY_COUNT = LAST_MIDI_NOTE - FIRST_MIDI_NOTE + 1;

    private static final boolean[] WHITE_KEY = new boolean[128];
    private static final int[] WHITE_KEY_INDEX = new int[128];
    private static final double BLACK_KEY_WIDTH_RATIO = 0.62;
    private static final int WHITE_KEY_COUNT;
    private static final int[] ALL_NOTES;

    static {
        int whiteCounter = 0;
        ALL_NOTES = new int[KEY_COUNT];
        int arrayIndex = 0;
        for (int note = FIRST_MIDI_NOTE; note <= LAST_MIDI_NOTE; note++) {
            boolean isWhite = isNatural(note);
            WHITE_KEY[note] = isWhite;
            WHITE_KEY_INDEX[note] = isWhite ? whiteCounter : Math.max(0, whiteCounter);
            if (isWhite) {
                whiteCounter++;
            }
            ALL_NOTES[arrayIndex++] = note;
        }
        WHITE_KEY_COUNT = whiteCounter;
    }

    private PianoKeyLayout() {
    }

    private static boolean isNatural(int midiNote) {
        return switch (Math.floorMod(midiNote, 12)) {
            case 0, 2, 4, 5, 7, 9, 11 -> true; // C, D, E, F, G, A, B
            default -> false;
        };
    }

    public static boolean isWhiteKey(int midiNote) {
        return WHITE_KEY[midiNote];
    }

    public static int whiteKeyCount() {
        return WHITE_KEY_COUNT;
    }

    public static double keyWidth(int midiNote, double totalWidth) {
        double whiteWidth = totalWidth / whiteKeyCount();
        return isWhiteKey(midiNote) ? whiteWidth : whiteWidth * BLACK_KEY_WIDTH_RATIO;
    }

    public static double keyLeft(int midiNote, double totalWidth) {
        double whiteWidth = totalWidth / whiteKeyCount();
        if (isWhiteKey(midiNote)) {
            return WHITE_KEY_INDEX[midiNote] * whiteWidth;
        }
        int precedingWhiteIndex = Math.max(0, WHITE_KEY_INDEX[midiNote] - 1);
        double base = precedingWhiteIndex * whiteWidth;
        double blackWidth = whiteWidth * BLACK_KEY_WIDTH_RATIO;
        // Black keys hover near the boundary between the preceding and following white keys.
        // Bias slightly towards the following white key to mimic an acoustic piano.
        double offset = whiteWidth * 0.9;
        return base + offset - blackWidth / 2.0;
    }

    public static double keyCenter(int midiNote, double totalWidth) {
        return keyLeft(midiNote, totalWidth) + keyWidth(midiNote, totalWidth) / 2.0;
    }

    public static double[] buildWhiteKeyBoundaries(double totalWidth) {
        double whiteWidth = totalWidth / whiteKeyCount();
        double[] positions = new double[whiteKeyCount() + 1];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = i * whiteWidth;
        }
        return positions;
    }

    public static String noteName(int midiNote) {
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        int octave = (midiNote / 12) - 1;
        return names[Math.floorMod(midiNote, 12)] + octave;
    }

    public static int[] allNotes() {
        return ALL_NOTES;
    }
}
