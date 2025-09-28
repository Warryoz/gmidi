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

    /** Ratio between the keyboard viewport height and the stage height. */
    public static final double KEYBOARD_HEIGHT_RATIO = 0.24;
    /** Width of a black key as a fraction of the white key width. */
    public static final double BLACK_KEY_WIDTH_RATIO = 0.60;
    /** Height of a black key as a fraction of the white key height. */
    public static final double BLACK_KEY_HEIGHT_RATIO = 0.68;

    private static final boolean[] WHITE_KEY = new boolean[128];
    private static final int[] WHITE_KEY_INDEX = new int[128];
    private static final int[] PRECEDING_WHITE_INDEX = new int[128];
    private static final int[] FOLLOWING_WHITE_INDEX = new int[128];
    private static final double[] BLACK_CENTER_RATIOS = new double[12];
    private static final int WHITE_KEY_COUNT;
    private static final int[] ALL_NOTES;

    static {
        initialiseBlackCenterRatios();

        int whiteCounter = 0;
        ALL_NOTES = new int[KEY_COUNT];
        int arrayIndex = 0;
        for (int note = FIRST_MIDI_NOTE; note <= LAST_MIDI_NOTE; note++) {
            boolean isWhite = isNatural(note);
            WHITE_KEY[note] = isWhite;
            if (isWhite) {
                PRECEDING_WHITE_INDEX[note] = Math.max(0, whiteCounter - 1);
                FOLLOWING_WHITE_INDEX[note] = whiteCounter;
                WHITE_KEY_INDEX[note] = whiteCounter;
                whiteCounter++;
            } else {
                PRECEDING_WHITE_INDEX[note] = Math.max(0, whiteCounter - 1);
                FOLLOWING_WHITE_INDEX[note] = whiteCounter;
                WHITE_KEY_INDEX[note] = whiteCounter;
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
        int preceding = PRECEDING_WHITE_INDEX[midiNote];
        int following = FOLLOWING_WHITE_INDEX[midiNote];
        double leftEdge = preceding * whiteWidth;
        double rightEdge = following * whiteWidth;
        double gap = rightEdge - leftEdge;
        double ratio = BLACK_CENTER_RATIOS[Math.floorMod(midiNote, 12)];
        double centre = leftEdge + gap * ratio;
        double halfWidth = (whiteWidth * BLACK_KEY_WIDTH_RATIO) / 2.0;
        return centre - halfWidth;
    }

    public static double keyCenter(int midiNote, double totalWidth) {
        return keyLeft(midiNote, totalWidth) + keyWidth(midiNote, totalWidth) / 2.0;
    }

    public static double blackKeyHeight(double keyboardHeight) {
        return keyboardHeight * BLACK_KEY_HEIGHT_RATIO;
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

    private static void initialiseBlackCenterRatios() {
        // Values tuned to align sharp/flat keys roughly above the gaps of the naturals.
        BLACK_CENTER_RATIOS[1] = 0.63;  // C#
        BLACK_CENTER_RATIOS[3] = 0.37;  // D#
        BLACK_CENTER_RATIOS[6] = 0.63;  // F#
        BLACK_CENTER_RATIOS[8] = 0.46;  // G#
        BLACK_CENTER_RATIOS[10] = 0.36; // A#
        for (int i = 0; i < BLACK_CENTER_RATIOS.length; i++) {
            if (BLACK_CENTER_RATIOS[i] == 0.0) {
                BLACK_CENTER_RATIOS[i] = 0.5;
            }
        }
    }
}
