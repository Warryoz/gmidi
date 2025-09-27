# GMIDI Recorder

GMIDI Recorder is a JavaFX 21 desktop application for capturing live MIDI performances while
visualising the keyboard and optionally encoding a 60 FPS MP4 “key-fall” video with ffmpeg.

## Requirements

- Java 21 (the Gradle build uses the toolchain API to download it automatically when needed)
- Gradle 8.6+ (only for regenerating the wrapper JAR if you do not already have it)
- [FFmpeg](https://ffmpeg.org/) accessible on your `PATH` when recording video

## Project layout

```
app/
  build.gradle     # JavaFX + modular configuration
  src/main/java    # Application sources
  src/main/resources/com/gmidi/dark.css
```

## Building & running

1. (Optional) Generate the Gradle wrapper JAR if it is missing:

   ```bash
   gradle wrapper
   ```

2. Launch the JavaFX application via Gradle:

   ```bash
   ./gradlew :app:run
   ```

   Gradle enables native access, configures the module path, and runs
   `com.gmidi/com.gmidi.MainApp` directly.

3. Alternatively, run with a locally installed JDK:

   ```bash
   PATH_TO_FX="$HOME/.gradle/caches/modules-2/files-2.1/org.openjfx"
   javac --module-path "$PATH_TO_FX" \
         -d app/build/classes $(find app/src/main/java -name "*.java")
   java --module-path "app/build/classes:$PATH_TO_FX" \
        --add-modules javafx.controls,javafx.graphics \
        --enable-native-access=ALL-UNNAMED \
        --sun-misc-unsafe-memory-access=allow \
        com.gmidi/com.gmidi.MainApp
   ```

## Using ffmpeg for video capture

1. Install ffmpeg from your platform’s package manager or the [official builds](https://ffmpeg.org/download.html).
2. Add the `ffmpeg` executable to your `PATH` (e.g. place `ffmpeg.exe` next to the GMIDI Recorder
   executable on Windows or symlink it into `/usr/local/bin` on macOS/Linux).
3. Inside GMIDI Recorder, enable **Record Video** after starting a MIDI capture. Frames are piped to
   ffmpeg as PNG images and encoded to H.264 with the settings from the **Settings** dialog.

If ffmpeg cannot be located the UI will disable video capture and show an error in the status bar.

## Recording workflow

1. Open the app and pick a MIDI input from the toolbar (use **Refresh** if you plug a controller in
   while the app is running).
2. Toggle **Record MIDI**. A timestamped `.mid` file is created under `recordings/` and all note
   events, sustain pedal data, and tempo meta events are stored with microsecond timing.
3. (Optional) Toggle **Record Video** to capture the key-fall canvas at the configured resolution and
   FPS. The MP4 is written alongside the MIDI file.
4. Watch the keyboard highlight keys in real time while the notes fall in the visualiser. Velocity
   maps to brightness/opacity and sustain holds notes until the pedal is released.
5. Stop recording. Files are saved as `yyyyMMdd-HHmmss.mid` / `.mp4` in the `recordings/` directory
   (override the location via the Settings dialog).

## UI highlights

- Modern dark theme with rounded panels, accent toggles, and keyboard tooltips.
- Key-fall visualiser renders at 60 FPS without per-frame allocations.
- Sustain pedal support keeps note tails visible until the pedal is released.
- Status bar tracks elapsed time, frames submitted to ffmpeg, and dropped frames.
- Settings dialog lets you adjust video FPS, resolution, x264 preset, CRF, and the output folder.

## Troubleshooting

- **No MIDI devices listed:** Ensure your controller is connected and recognised by the OS, then
  click **Refresh**. Some devices require installing vendor drivers on Windows.
- **Video recording disabled:** Verify `ffmpeg --version` works in a terminal. The app pipes PNG
  frames to ffmpeg; missing executables or incompatible builds will trigger an error message.
- **Performance hiccups:** Close other applications that may compete for GPU resources. The visualiser
  redraws via a single `Canvas` and uses a bounded queue to back-pressure ffmpeg when it falls behind.

## License

GMIDI Recorder is released under the MIT License. See [LICENSE](LICENSE) for details.
