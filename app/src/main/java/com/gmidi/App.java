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
        int exitCode = run(System.in, System.out);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(InputStream in, PrintStream out) {
        MidiRecorderCli cli = new MidiRecorderCli(in, out);
        return cli.run();
    }
}
