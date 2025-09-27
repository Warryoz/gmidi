package com.gmidi;

import com.gmidi.cli.MidiRecorderCli;
import com.gmidi.ui.MidiRecorderApp;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Locale;

/**
 * Entry point for the GMIDI command line tools.
 */
public final class App {

    private App() {
        // Utility class
    }

    public static void main(String[] args) {
        if (wantsGui(args)) {
            MidiRecorderApp.launchApp();
            return;
        }

        int exitCode = run(System.in, System.out);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static boolean wantsGui(String[] args) {
        return Arrays.stream(args)
                .map(arg -> arg.toLowerCase(Locale.ROOT))
                .anyMatch("--gui"::equals);
    }

    static int run(InputStream in, PrintStream out) {
        MidiRecorderCli cli = new MidiRecorderCli(in, out);
        return cli.run();
    }
}
