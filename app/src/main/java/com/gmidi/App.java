package com.gmidi;

import com.gmidi.cli.MidiRecorderCli;

/**
 * Entry point for the GMIDI command line tools.
 */
public final class App {

    private App() {
        // Utility class
    }

    public static void main(String[] args) {
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
