package com.gmidi;

import java.util.prefs.Preferences;

public final class Prefs {
    private static final Preferences P = Preferences.userNodeForPackage(Prefs.class);

    public static void putSoundFontPath(String s) {
        P.put("sf2", s == null ? "" : s);
    }

    public static String getSoundFontPath() {
        return P.get("sf2", "");
    }

    public static void putProgram(int msb, int lsb, int prog, String name) {
        P.putInt("bankMsb", msb);
        P.putInt("bankLsb", lsb);
        P.putInt("program", prog);
        P.put("progName", name == null ? "" : name);
    }

    public static int bankMsb() {
        return P.getInt("bankMsb", 0);
    }

    public static int bankLsb() {
        return P.getInt("bankLsb", 0);
    }

    public static int program() {
        return P.getInt("program", 0);
    }

    public static String progName() {
        return P.get("progName", "GM Program 0");
    }

    public static void putTranspose(int v) {
        P.putInt("transpose", v);
    }

    public static int getTranspose() {
        return P.getInt("transpose", 0);
    }

    public static void putReverb(String label) {
        P.put("reverb", label);
    }

    public static String getReverb() {
        return P.get("reverb", "Room");
    }

    public static void putVelCurve(String v) {
        P.put("velCurve", v);
    }

    public static String getVelCurve() {
        return P.get("velCurve", "LINEAR");
    }

    public static void putFallSeconds(double s) {
        P.putDouble("fallSeconds", s);
    }

    public static double getFallSeconds() {
        return P.getDouble("fallSeconds", 10.0);
    }

    public static void putKbRatio(double r) {
        P.putDouble("kbRatio", r);
    }

    public static double getKbRatio() {
        return P.getDouble("kbRatio", 0.24);
    }

    public static void putLastExportDir(String d) {
        P.put("lastExportDir", d == null ? "" : d);
    }

    public static String getLastExportDir() {
        return P.get("lastExportDir", "");
    }

    public static void putVisualOffsetMillis(int ms) {
        P.putInt("visualOffsetMs", ms);
    }

    public static int getVisualOffsetMillis() {
        return P.getInt("visualOffsetMs", 0);
    }

    private Prefs() {
    }
}

