# gmidi

GMIDI is a Java 21 toolkit for capturing MIDI performances that will evolve into a visual piano experience. The first milestone is a console recorder that writes Standard MIDI Files (`.mid`). This iteration focuses on readability and scalability so the same core logic can power future graphical interfaces.

## Architecture at a glance

- **Core MIDI services (`com.gmidi.midi`, `com.gmidi.recorder`)** – Pure Java classes that discover devices, manage recording sessions, and expose UI-agnostic hooks.
- **Console front-end (`com.gmidi.cli`)** – A thin command line layer that formats prompts, resolves output paths, and delegates the actual recording to the core services.
- **Desktop front-end (`com.gmidi.ui`)** – A JavaFX scene graph that lists MIDI inputs, renders an 88-key piano, and mirrors incoming notes while the shared session persists the MIDI file.
- **Interaction abstraction** – `RecordingInteraction` defines the lifecycle callbacks and (optionally) receiver decoration that a UI must provide so the core session can remain oblivious to presentation concerns.

## Prerequisites

- Java 21 or newer
- Gradle 8.6+ (only required the first time to regenerate the wrapper JAR)
- The build pulls JavaFX modules directly from Maven Central and selects the right native artifacts for your OS when you run `./gradlew`

## Bootstrapping the Gradle wrapper

Binary assets such as `gradle-wrapper.jar` cannot be committed in this repository. Before using `./gradlew`, generate the wrapper JAR locally:

```bash
gradle wrapper
```

This downloads the wrapper artifacts into `gradle/wrapper/` so subsequent invocations of `./gradlew` work as expected.

## Running the console recorder

Once the wrapper files exist, launch the CLI with:

```bash
./gradlew :app:run
```

The program lists available MIDI input devices, helps you pick one, and records until you press Enter again. Recordings default to timestamped filenames such as `recording-20241231-235945.mid` in the current directory.

If Gradle downloads are blocked entirely, you can run the application directly with the JDK.
Because the project is modular and depends on JavaFX, point both compilation and execution at
the JavaFX SDK that ships with OpenJFX (the Gradle build downloads it into your user cache on
first use):

```bash
PATH_TO_FX="$HOME/.gradle/caches/modules-2/files-2.1/org.openjfx" # adjust for your platform
javac --module-path "$PATH_TO_FX" -d build/classes $(find app/src/main/java -name "*.java")
java --module-path "build/classes:$PATH_TO_FX" --add-modules javafx.controls,javafx.graphics \
  --enable-native-access=ALL-UNNAMED com.gmidi/com.gmidi.App
```

## Launching the graphical recorder

The JavaFX interface shows a scrolling keyboard and flashes keys as you play. Run it with the `--gui` switch:

```bash
./gradlew :app:run --args="--gui"
```

You can place `--gui` anywhere in the argument list—the launcher scans all arguments and opens the
desktop experience when it finds the flag.

Launch directly via the JDK (again pointing at the JavaFX SDK and enabling native access):

```bash
PATH_TO_FX="$HOME/.gradle/caches/modules-2/files-2.1/org.openjfx"
javac --module-path "$PATH_TO_FX" -d build/classes $(find app/src/main/java -name "*.java")
java --module-path "build/classes:$PATH_TO_FX" --add-modules javafx.controls,javafx.graphics \
  --enable-native-access=ALL-UNNAMED com.gmidi/com.gmidi.App --gui
```

The Gradle tasks already pass `--enable-native-access=ALL-UNNAMED`, so the JavaFX runtime avoids
the `sun.misc.Unsafe` warnings seen on newer JDKs.

From the window you can:

- Refresh and choose any available MIDI input device.
- Pick a target `.mid` file via the platform file chooser (defaults to the timestamped suggestion).
- Watch highlighted keys mirror the notes captured during the session.

## Testing

Most tests focus on deterministic utilities that do not require MIDI hardware. Execute them with:

```bash
./gradlew test
```

If Gradle cannot download dependencies in your environment, run `gradle test` with a locally installed Gradle distribution after regenerating the wrapper.

## Roadmap

- Add richer transport controls (metronome, configurable count-in, punch in/out) on top of the existing interaction contract.
- Provide velocity-aware colouring and sustain-pedal overlays on the keyboard visualiser.
- Record and surface session metadata to feed future playback and visualization features.
