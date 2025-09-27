package com.gmidi.cli.interaction;

import com.gmidi.recorder.RecordingInteraction;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Scanner;

/**
 * Console implementation of {@link RecordingInteraction} used by the CLI.
 */
public final class ConsoleRecordingInteraction implements RecordingInteraction {

    private final PrintStream out;
    private final Scanner scanner;

    public ConsoleRecordingInteraction(PrintStream out, Scanner scanner) {
        this.out = Objects.requireNonNull(out, "out");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
    }

    @Override
    public void onReadyToRecord() {
        out.println();
        out.println("Press Enter when you are ready to start recording.");
    }

    @Override
    public void awaitStart() {
        scanner.nextLine();
    }

    @Override
    public void onRecordingStarted() {
        out.println("Recording... Press Enter to stop.");
    }

    @Override
    public void awaitStop() {
        scanner.nextLine();
    }

    @Override
    public void onRecordingFinished(Path outputPath) {
        out.printf(Locale.ROOT, "Saved recording to %s%n", outputPath);
    }
}
