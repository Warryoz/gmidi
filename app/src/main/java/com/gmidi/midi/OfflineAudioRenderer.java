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
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

        Sequence playable = cloneWithPatchAndTranspose(sequence, program, reverbPreset, transposeSemis);
        AudioFormat format = new AudioFormat(44_100f, 16, 2, true, false);
        SoftAudio soft = openSoftSynth(format);
        ExecutorService writerExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "gmidi-audio-writer");
            t.setDaemon(true);
            return t;
        });
        Sequencer sequencer = null;
        Transmitter transmitter = null;
        VelocityReceiver velocityReceiver = null;
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
            velocityReceiver = new VelocityReceiver(soft.receiver, velocityMap);
            transmitter.setReceiver(velocityReceiver);
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
                Thread.sleep(40L);
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
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        } finally {
            if (velocityReceiver != null) {
                velocityReceiver.close();
            } else {
                try {
                    soft.receiver.close();
                } catch (Exception ignored) {
                }
            }
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
                soft.synth.close();
            } catch (Exception ignored) {
            }
        }
        return outputFile.toPath();
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
            throw new IOException("Java SoftSynth not accessible. Run with --add-exports=java.desktop/com.sun.media.sound=ALL-UNNAMED or install an external renderer.", ex);
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
                MidiMessage copy = cloneWithTranspose(event.getMessage(), semis);
                dst.add(new MidiEvent(copy, event.getTick()));
                maxTick = Math.max(maxTick, event.getTick());
            }
        }
        if (clone.getTracks().length == 0) {
            clone.createTrack();
        }
        return new CloneResult(clone, maxTick);
    }

    private static MidiMessage cloneWithTranspose(MidiMessage message, int semis) {
        if (!(message instanceof ShortMessage shortMessage)) {
            return (MidiMessage) message.clone();
        }
        int command = shortMessage.getCommand();
        if ((command == ShortMessage.NOTE_ON || command == ShortMessage.NOTE_OFF)
                && shortMessage.getChannel() != 9 && semis != 0) {
            int note = clamp7bit(shortMessage.getData1() + semis);
            int velocity = shortMessage.getData2();
            try {
                ShortMessage shifted = new ShortMessage();
                shifted.setMessage(command, shortMessage.getChannel(), note, velocity);
                return shifted;
            } catch (InvalidMidiDataException ignored) {
            }
        }
        return (MidiMessage) message.clone();
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

    private static final class VelocityReceiver implements Receiver {
        private final Receiver out;
        private final VelocityMap velocityMap;

        private VelocityReceiver(Receiver out, VelocityMap velocityMap) {
            this.out = out;
            this.velocityMap = velocityMap;
        }

        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (message instanceof ShortMessage shortMessage) {
                int command = shortMessage.getCommand();
                if (command == ShortMessage.NOTE_ON) {
                    int velocity = shortMessage.getData2();
                    if (velocity > 0) {
                        int mapped = velocityMap.map(velocity);
                        if (mapped != velocity) {
                            try {
                                ShortMessage remapped = new ShortMessage();
                                remapped.setMessage(ShortMessage.NOTE_ON,
                                        shortMessage.getChannel(),
                                        shortMessage.getData1(),
                                        mapped);
                                out.send(remapped, timeStamp);
                                return;
                            } catch (InvalidMidiDataException ignored) {
                            }
                        }
                    }
                }
            }
            out.send(message, timeStamp);
        }

        @Override
        public void close() {
            out.close();
        }
    }
}
