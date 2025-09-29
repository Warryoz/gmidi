package com.gmidi.midi;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import javax.sound.midi.Transmitter;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

/** Utility class that renders MIDI sequences to audio files offline. */
public final class OfflineAudioRenderer {

    private OfflineAudioRenderer() {
    }

    public static Sequence prepareSequence(Sequence source,
                                           MidiService.MidiProgram program,
                                           MidiService.ReverbPreset reverbPreset,
                                           int transposeSemis) throws Exception {
        return cloneWithPatchAndTranspose(source, program, reverbPreset, transposeSemis);
    }

    public static Path renderWav(Sequence sequence,
                                 MidiService.MidiProgram program,
                                 MidiService.ReverbPreset reverbPreset,
                                 int transposeSemis,
                                 VelocityMap velocityMap,
                                 Soundbank customSoundbank,
                                 File outputFile) throws Exception {
        return renderWav(sequence,
                program,
                transposeSemis,
                velocityMap,
                reverbPreset,
                customSoundbank,
                null,
                outputFile,
                null,
                null);
    }

    public static Path renderWav(Sequence sequence,
                                 MidiService.MidiProgram program,
                                 int transposeSemis,
                                 VelocityMap velocityMap,
                                 MidiService.ReverbPreset reverbPreset,
                                 Soundbank customSoundbank,
                                 String soundFontPath,
                                 File outputFile,
                                 LongConsumer progressMicros,
                                 AtomicBoolean cancelFlag) throws Exception {
        Objects.requireNonNull(sequence, "sequence null");
        Objects.requireNonNull(velocityMap, "velocity map null");
        Objects.requireNonNull(outputFile, "output file null");

        File parent = outputFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        Sequence playable = bakePatchTransposeVelocity(sequence,
                program,
                reverbPreset,
                transposeSemis,
                velocityMap);

        AudioUnavailableException softFailure = null;
        try {
            if (renderWavWithSoftSynth(playable,
                    customSoundbank,
                    reverbPreset,
                    outputFile,
                    progressMicros,
                    cancelFlag)) {
                return outputFile.toPath();
            }
        } catch (AudioUnavailableException ex) {
            softFailure = ex;
        }

        if (cancelFlag != null && cancelFlag.get()) {
            throw new InterruptedException("Audio render cancelled");
        }

        File fluidsynthExe = locateFluidsynth();
        File soundFontFile = null;
        if (soundFontPath != null && !soundFontPath.isBlank()) {
            soundFontFile = new File(soundFontPath);
        }
        if (fluidsynthExe != null && soundFontFile != null && soundFontFile.isFile()) {
            if (renderWavWithFluidsynth(playable,
                    fluidsynthExe,
                    soundFontFile,
                    outputFile,
                    progressMicros,
                    cancelFlag)) {
                return outputFile.toPath();
            }
        }

        String baseMessage = "Audio export unavailable: Java SoftSynth couldn't be accessed. "
                + "Start the app with --add-exports=java.desktop/com.sun.media.sound=ALL-UNNAMED, "
                + "or install fluidsynth and ensure it's on PATH.";
        if (softFailure != null && softFailure.getMessage() != null) {
            baseMessage = softFailure.getMessage();
        }
        if (fluidsynthExe == null) {
            baseMessage += " (fluidsynth not found on PATH)";
        } else if (soundFontFile == null || !soundFontFile.isFile()) {
            baseMessage += " (SoundFont required for fluidsynth)";
        }
        throw new AudioUnavailableException(baseMessage, softFailure);
    }

    private static Sequence bakePatchTransposeVelocity(Sequence source,
                                                        MidiService.MidiProgram program,
                                                        MidiService.ReverbPreset reverbPreset,
                                                        int transposeSemis,
                                                        VelocityMap velocityMap) throws Exception {
        CloneResult clone = cloneWithTranspose(source, transposeSemis, velocityMap);
        Sequence prepared = clone.sequence();
        MidiReplayer.insertPatchAndReverbAtTick0(prepared, program, reverbPreset);
        addEndOfTrack(prepared, clone.maxTick());
        return prepared;
    }

    private static boolean renderWavWithSoftSynth(Sequence playable,
                                                  Soundbank customSoundbank,
                                                  MidiService.ReverbPreset reverbPreset,
                                                  File outputFile,
                                                  LongConsumer progressMicros,
                                                  AtomicBoolean cancelFlag) throws Exception {
        AudioFormat format = new AudioFormat(44_100f, 16, 2, true, false);
        SoftAudio soft = openSoftSynth(format);
        ExecutorService writerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "gmidi-audio-writer");
            t.setDaemon(true);
            return t;
        });
        Sequencer sequencer = null;
        Transmitter transmitter = null;
        Future<?> writerTask = null;
        try {
            if (customSoundbank != null) {
                Soundbank defaultBank = soft.synth.getDefaultSoundbank();
                if (defaultBank != null) {
                    soft.synth.unloadAllInstruments(defaultBank);
                }
                soft.synth.loadAllInstruments(customSoundbank);
            }
            applyReverb(soft.synth.getChannels(), reverbPreset);

            sequencer = MidiSystem.getSequencer(false);
            sequencer.open();
            transmitter = sequencer.getTransmitter();
            transmitter.setReceiver(soft.receiver);
            sequencer.setSequence(playable);
            sequencer.setTickPosition(0);

            writerTask = writerExecutor.submit(() -> {
                AudioSystem.write(soft.audio, AudioFileFormat.Type.WAVE, outputFile);
                return null;
            });

            sequencer.start();
            long totalMicros = Math.max(0L, playable.getMicrosecondLength());
            while (sequencer.isRunning()) {
                if (cancelFlag != null && cancelFlag.get()) {
                    sequencer.stop();
                    break;
                }
                if (progressMicros != null) {
                    progressMicros.accept(Math.max(0L, sequencer.getMicrosecondPosition()));
                }
                Thread.sleep(30L);
            }
            if (progressMicros != null) {
                progressMicros.accept(totalMicros);
            }
            if (cancelFlag != null && cancelFlag.get()) {
                throw new InterruptedException("Audio render cancelled");
            }

            sequencer.stop();
            soft.synth.close();
            if (writerTask != null) {
                writerTask.get();
            }
            writerExecutor.shutdown();
            return outputFile.isFile() && outputFile.length() > 0L;
        } finally {
            if (transmitter != null) {
                transmitter.close();
            }
            if (sequencer != null) {
                sequencer.close();
            }
            writerExecutor.shutdownNow();
            try {
                soft.audio.close();
            } catch (IOException ignored) {
            }
            try {
                soft.receiver.close();
            } catch (Exception ignored) {
            }
            try {
                soft.synth.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean renderWavWithFluidsynth(Sequence playable,
                                                   File fluidsynthExe,
                                                   File soundFont,
                                                   File outputFile,
                                                   LongConsumer progressMicros,
                                                   AtomicBoolean cancelFlag) throws Exception {
        if (fluidsynthExe == null || soundFont == null || !soundFont.isFile()) {
            return false;
        }
        Path tempMidi = Files.createTempFile("gmidi-render", ".mid");
        try {
            MidiSystem.write(playable, 1, tempMidi.toFile());
            List<String> command = new ArrayList<>();
            command.add(fluidsynthExe.getAbsolutePath());
            command.add("-ni");
            command.add(soundFont.getAbsolutePath());
            command.add(tempMidi.toAbsolutePath().toString());
            command.add("-F");
            command.add(outputFile.getAbsolutePath());
            command.add("-r");
            command.add("44100");
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            Thread drain = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    in.transferTo(OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                }
            }, "gmidi-fluidsynth-log");
            drain.setDaemon(true);
            drain.start();
            while (true) {
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    break;
                }
                if (cancelFlag != null && cancelFlag.get()) {
                    process.destroyForcibly();
                    drain.join();
                    throw new InterruptedException("Audio render cancelled");
                }
            }
            drain.join();
            int exit = process.exitValue();
            if (exit != 0) {
                return false;
            }
            if (progressMicros != null) {
                progressMicros.accept(Math.max(0L, playable.getMicrosecondLength()));
            }
            return outputFile.isFile() && outputFile.length() > 0L;
        } finally {
            Files.deleteIfExists(tempMidi);
        }
    }

    private static File locateFluidsynth() {
        String exe = isWindows() ? "fluidsynth.exe" : "fluidsynth";
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] entries = path.split(File.pathSeparator);
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            File candidate = new File(entry, exe);
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private static SoftAudio openSoftSynth(AudioFormat format) throws Exception {
        try {
            Class<?> softClass = Class.forName("com.sun.media.sound.SoftSynthesizer");
            Object soft = softClass.getConstructor().newInstance();
            Synthesizer synth = (Synthesizer) soft;
            Method openStream = softClass.getMethod("openStream", AudioFormat.class, java.util.Map.class);
            AudioInputStream stream = (AudioInputStream) openStream.invoke(soft, format, null);
            Receiver receiver = synth.getReceiver();
            return new SoftAudio(synth, receiver, stream);
        } catch (ReflectiveOperationException ex) {
            throw new AudioUnavailableException(
                    "Audio export unavailable: Java SoftSynth couldn't be accessed. "
                            + "Start the app with --add-exports=java.desktop/com.sun.media.sound=ALL-UNNAMED, "
                            + "or install fluidsynth and ensure it's on PATH.",
                    ex);
        }
    }

    private static Sequence cloneWithPatchAndTranspose(Sequence source,
                                                        MidiService.MidiProgram program,
                                                        MidiService.ReverbPreset reverbPreset,
                                                        int transposeSemis) throws Exception {
        CloneResult clone = cloneWithTranspose(source, transposeSemis);
        Sequence prepared = clone.sequence();
        MidiReplayer.insertPatchAndReverbAtTick0(prepared, program, reverbPreset);
        addEndOfTrack(prepared, clone.maxTick());
        return prepared;
    }

    private static CloneResult cloneWithTranspose(Sequence source, int semis) throws Exception {
        return cloneWithTranspose(source, semis, null);
    }

    private static CloneResult cloneWithTranspose(Sequence source,
                                                  int semis,
                                                  VelocityMap velocityMap) throws Exception {
        if (source == null) {
            throw new IllegalArgumentException("source sequence null");
        }
        Sequence clone = new Sequence(source.getDivisionType(), source.getResolution());
        javax.sound.midi.Track[] srcTracks = source.getTracks();
        List<javax.sound.midi.Track> dstTracks = new ArrayList<>(srcTracks.length);
        for (int i = 0; i < srcTracks.length; i++) {
            dstTracks.add(clone.createTrack());
        }
        long maxTick = 0L;
        for (int i = 0; i < srcTracks.length; i++) {
            javax.sound.midi.Track src = srcTracks[i];
            javax.sound.midi.Track dst = dstTracks.get(i);
            for (int j = 0; j < src.size(); j++) {
                MidiEvent event = src.get(j);
                MidiMessage copy = cloneForPlayback(event.getMessage(), semis, velocityMap);
                dst.add(new MidiEvent(copy, event.getTick()));
                maxTick = Math.max(maxTick, event.getTick());
            }
        }
        if (clone.getTracks().length == 0) {
            clone.createTrack();
        }
        return new CloneResult(clone, maxTick);
    }

    private static MidiMessage cloneForPlayback(MidiMessage message,
                                                int semis,
                                                VelocityMap velocityMap) {
        if (!(message instanceof ShortMessage shortMessage)) {
            return (MidiMessage) message.clone();
        }
        int command = shortMessage.getCommand();
        int channel = shortMessage.getChannel();
        int data1 = shortMessage.getData1();
        int data2 = shortMessage.getData2();
        int note = data1;
        if ((command == ShortMessage.NOTE_ON || command == ShortMessage.NOTE_OFF)
                && channel != 9 && semis != 0) {
            note = clamp7bit(data1 + semis);
        }
        int velocity = data2;
        if (velocityMap != null && command == ShortMessage.NOTE_ON && velocity > 0) {
            velocity = clamp7bit(velocityMap.map(velocity));
        }
        if (note == data1 && velocity == data2) {
            return (MidiMessage) shortMessage.clone();
        }
        try {
            ShortMessage clone = new ShortMessage();
            clone.setMessage(command, channel, note, velocity);
            return clone;
        } catch (InvalidMidiDataException ex) {
            return (MidiMessage) shortMessage.clone();
        }
    }

    private static void addEndOfTrack(Sequence sequence, long maxTick) throws InvalidMidiDataException {
        long tick = Math.max(maxTick + sequence.getResolution(), sequence.getResolution());
        MetaMessage end = new MetaMessage();
        end.setMessage(0x2F, new byte[0], 0);
        sequence.getTracks()[0].add(new MidiEvent(end, tick));
    }

    private static void applyReverb(MidiChannel[] channels, MidiService.ReverbPreset preset) {
        MidiService.ReverbPreset target = preset != null ? preset : MidiService.ReverbPreset.ROOM;
        if (channels == null) {
            return;
        }
        for (MidiChannel channel : channels) {
            if (channel != null) {
                channel.controlChange(91, clamp7bit(target.reverbCc()));
                channel.controlChange(93, clamp7bit(target.chorusCc()));
            }
        }
    }

    private static int clamp7bit(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 127) {
            return 127;
        }
        return value;
    }

    private record CloneResult(Sequence sequence, long maxTick) {
    }

    private static final class SoftAudio {
        final Synthesizer synth;
        final Receiver receiver;
        final AudioInputStream audio;

        SoftAudio(Synthesizer synth, Receiver receiver, AudioInputStream audio) {
            this.synth = synth;
            this.receiver = receiver;
            this.audio = audio;
        }
    }

    public static class AudioUnavailableException extends IOException {
        public AudioUnavailableException(String message) {
            super(message);
        }

        public AudioUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
