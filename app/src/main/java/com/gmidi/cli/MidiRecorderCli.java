package com.gmidi.cli;

import com.gmidi.cli.interaction.ConsoleRecordingInteraction;
import com.gmidi.midi.MidiDeviceUtils;
import com.gmidi.midi.MidiRecordingSession;
import com.gmidi.midi.RecordingFileNamer;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiDevice.Info;
import javax.sound.midi.MidiUnavailableException;

/**
 * Minimal interactive CLI that records MIDI input to a .mid file.
 */
public final class MidiRecorderCli {

    private final InputStream in;
    private final PrintStream out;

    public MidiRecorderCli(InputStream in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    public int run() {
        try (Scanner scanner = new Scanner(in, java.nio.charset.StandardCharsets.UTF_8.name())) {
            List<MidiDevice.Info> inputs = MidiDeviceUtils.listInputDevices();
            if (inputs.isEmpty()) {
                out.println("No MIDI input devices were found. Connect a controller and try again.");
                return 1;
            }

            printDeviceList(inputs);
            MidiDevice.Info selectedDevice = selectDevice(scanner, inputs);
            Path outputPath = requestOutputPath(scanner);
            if (outputPath == null) {
                out.println("Recording cancelled.");
                return 1;
            }

            MidiRecordingSession session = new MidiRecordingSession();
            ConsoleRecordingInteraction interaction = new ConsoleRecordingInteraction(out, scanner);
            session.record(selectedDevice, outputPath, interaction);
            return 0;
        } catch (MidiUnavailableException ex) {
            out.printf("Failed to access MIDI device: %s%n", ex.getMessage());
            return 2;
        } catch (Exception ex) {
            out.printf("Unexpected error: %s%n", ex.getMessage());
            return 2;
        }
    }

    private void printDeviceList(List<MidiDevice.Info> inputs) {
        out.println("Available MIDI input devices:");
        for (int i = 0; i < inputs.size(); i++) {
            Info info = inputs.get(i);
            out.printf("[%d] %s — %s (%s)%n", i, info.getName(), info.getDescription(), info.getVendor());
        }
    }

    private MidiDevice.Info selectDevice(Scanner scanner, List<MidiDevice.Info> inputs) {
        out.printf("Select a device [0-%d] (press Enter for 0):%n", inputs.size() - 1);
        int selection = readDeviceSelection(scanner, inputs.size());
        MidiDevice.Info selectedDevice = inputs.get(selection);
        out.printf("Selected '%s'.%n", selectedDevice.getName());
        return selectedDevice;
    }

    private int readDeviceSelection(Scanner scanner, int deviceCount) {
        while (true) {
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                return 0;
            }
            try {
                int value = Integer.parseInt(line);
                if (value >= 0 && value < deviceCount) {
                    return value;
                }
                out.printf("Please enter a value between 0 and %d.%n", deviceCount - 1);
            } catch (NumberFormatException ex) {
                out.println("Enter a numeric index or press Enter for the default.");
            }
        }
    }

    private Path requestOutputPath(Scanner scanner) {
        String defaultFileName = RecordingFileNamer.defaultFileName();
        out.printf("Enter output file path (press Enter for %s):%n", defaultFileName);
        while (true) {
            String line = scanner.nextLine().trim();
            Path path = line.isEmpty() ? Paths.get(defaultFileName) : Paths.get(line);
            try {
                Path absolute = path.toAbsolutePath();
                Path parent = absolute.getParent();
                if (parent != null && Files.notExists(parent)) {
                    Files.createDirectories(parent);
                }
                if (Files.exists(absolute)) {
                    out.printf("File %s already exists. Overwrite? [y/N]%n", absolute);
                    String confirmation = scanner.nextLine().trim().toLowerCase(Locale.ROOT);
                    if (!confirmation.equals("y") && !confirmation.equals("yes")) {
                        out.println("Choose a different file name:");
                        continue;
                    }
                }
                return absolute;
            } catch (Exception ex) {
                out.printf("Invalid path: %s%n", ex.getMessage());
                out.println("Please enter a valid path:");
            }
        }
    }
}
