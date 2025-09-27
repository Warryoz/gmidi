# gmidi

GMIDI is a Java 21 command line toolkit for experimenting with MIDI-driven piano visualizers. This initial iteration sets up the Gradle build and ships with a console-based recorder that captures input from any connected MIDI controller and writes a Standard MIDI File (`.mid`).

## Prerequisites
- Java 21 or newer
- Gradle 8.6+ (only required the first time to regenerate the wrapper JAR)

## Bootstrapping the Gradle wrapper
Binary assets such as `gradle-wrapper.jar` cannot be committed in this repository. Before using `./gradlew`, generate the wrapper JAR locally:

```bash
gradle wrapper
```

This downloads the wrapper artifacts into `gradle/wrapper/` so subsequent invocations of `./gradlew` work as expected.

## Running the recorder
Use the Gradle wrapper to launch the CLI once the wrapper files have been bootstrapped:

```bash
./gradlew :app:run
```

The program will list all available MIDI input devices, prompt you to choose one, and guide you through starting and stopping a recording. By default, recordings are written to a timestamped file such as `recording-20241231-235945.mid` in the current directory.

If Gradle downloads are blocked entirely, you can run the application directly with the JDK:

```bash
javac -d build/classes $(find app/src/main/java -name "*.java")
java -cp build/classes com.gmidi.App
```

## Testing
Unit tests focus on deterministic utilities that do not require MIDI hardware. Execute them with:

```bash
./gradlew test
```

If Gradle cannot download dependencies in your environment, run `gradle test` with a locally installed Gradle distribution after regenerating the wrapper.
