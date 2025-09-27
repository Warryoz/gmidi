package com.gmidi;

import com.gmidi.cli.MidiRecorderCli;
import java.io.InputStream;
import java.io.PrintStream;

/**
 * Entry point for the GMIDI command line tools.
 */
public final class App {

    private App() {
        // Utility class
    }

    public static void main(String[] args) {
        if (args.length > 0 && "--gui".equalsIgnoreCase(args[0])) {
            MidiRecorderApp.launchApp();
            return;
        }
        int exitCode = run();

        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run() {
        MidiRecorderCli cli = new MidiRecorderCli(System.in, System.out);
        return cli.run();
    }
}
