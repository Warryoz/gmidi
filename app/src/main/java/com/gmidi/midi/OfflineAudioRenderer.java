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
import javax.sound.midi.Transmitter;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        if (source == null) {
            throw new IllegalArgumentException("source sequence null");
        }
        Sequence prepared = new Sequence(source.getDivisionType(), source.getResolution());
        javax.sound.midi.Track[] srcTracks = source.getTracks();
        List<javax.sound.midi.Track> dstTracks = new ArrayList<>(srcTracks.length);
        for (int i = 0; i < srcTracks.length; i++) {
            dstTracks.add(prepared.createTrack());
        }
        long maxTick = 0;
        for (int i = 0; i < srcTracks.length; i++) {
            javax.sound.midi.Track src = srcTracks[i];
            javax.sound.midi.Track dst = dstTracks.get(i);
            for (int j = 0; j < src.size(); j++) {
                MidiEvent event = src.get(j);
                MidiMessage message = event.getMessage();
                MidiMessage copy = cloneWithTranspose(message, transposeSemis);
                dst.add(new MidiEvent(copy, event.getTick()));
                if (event.getTick() > maxTick) {
                    maxTick = event.getTick();
                }
            }
        }
        if (prepared.getTracks().length == 0) {
            prepared.createTrack();
        }
        MidiReplayer.insertPatchAndReverbAtTick0(prepared, program, reverbPreset);
        addEndOfTrack(prepared, maxTick);
        return prepared;
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
        if (sequence == null) {
            throw new IllegalArgumentException("sequence null");
        }
        if (velocityMap == null) {
            throw new IllegalArgumentException("velocity map null");
        }
        if (outputFile == null) {
            throw new IllegalArgumentException("output file null");
        }
        File parent = outputFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Sequence playable = prepareSequence(sequence, program, reverbPreset, transposeSemis);
        AudioFormat format = new AudioFormat(44_100f, 16, 2, true, false);
        SoftSynthesizerFacade synth = SoftSynthesizerFacade.create();
        try {
            AudioInputStream stream = synth.openStream(format);
            try (AudioInputStream ignored = stream;
                 BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outputFile))) {
                if (customSoundbank != null) {
                    Soundbank defaultBank = synth.getDefaultSoundbank();
                    if (defaultBank != null) {
                        synth.unloadAllInstruments(defaultBank);
                    }
                    synth.loadAllInstruments(customSoundbank);
                }
                applyReverb(synth.getChannels(), reverbPreset);
                Sequencer sequencer = MidiSystem.getSequencer(false);
                sequencer.open();
                try {
                    Transmitter transmitter = sequencer.getTransmitter();
                    Receiver synthReceiver = synth.getReceiver();
                    Receiver velocityReceiver = new VelocityReceiver(synthReceiver, velocityMap);
                    transmitter.setReceiver(velocityReceiver);
                    try {
                        sequencer.setSequence(playable);
                        sequencer.setTickPosition(0);
                        AtomicBoolean running = new AtomicBoolean(true);
                        Thread monitor = null;
                        if (progressMicros != null || cancelFlag != null) {
                            monitor = new Thread(() -> {
                                try {
                                    while (running.get()) {
                                        if (cancelFlag != null && cancelFlag.get()) {
                                            sequencer.stop();
                                            break;
                                        }
                                        if (progressMicros != null) {
                                            progressMicros.accept(Math.max(0L, sequencer.getMicrosecondPosition()));
                                        }
                                        Thread.sleep(100);
                                    }
                                } catch (InterruptedException s) {
                                    Thread.currentThread().interrupt();
                                }
                            }, "gmidi-audio-progress");
                            monitor.setDaemon(true);
                            monitor.start();
                        }
                        try {
                            sequencer.start();
                            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out);
                            sequencer.stop();
                        } finally {
                            running.set(false);
                            if (monitor != null) {
                                try {
                                    monitor.join();
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                        if (progressMicros != null) {
                            progressMicros.accept(sequence.getMicrosecondLength());
                        }
                        if (cancelFlag != null && cancelFlag.get()) {
                            throw new InterruptedException("Audio render cancelled");
                        }
                    } finally {
                        velocityReceiver.close();
                        transmitter.close();
                    }
                } finally {
                    sequencer.close();
                }
            }
        } finally {
            synth.close();
        }
        return outputFile.toPath();
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
                // fall back to clone
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

    private static final class SoftSynthesizerFacade implements AutoCloseable {
        private final Object synth;
        private final Method openStream;
        private final Method getDefaultSoundbank;
        private final Method unloadAllInstruments;
        private final Method loadAllInstruments;
        private final Method getChannels;
        private final Method getReceiver;
        private final Method close;

        private SoftSynthesizerFacade(Object synth,
                                      Method openStream,
                                      Method getDefaultSoundbank,
                                      Method unloadAllInstruments,
                                      Method loadAllInstruments,
                                      Method getChannels,
                                      Method getReceiver,
                                      Method close) {
            this.synth = synth;
            this.openStream = openStream;
            this.getDefaultSoundbank = getDefaultSoundbank;
            this.unloadAllInstruments = unloadAllInstruments;
            this.loadAllInstruments = loadAllInstruments;
            this.getChannels = getChannels;
            this.getReceiver = getReceiver;
            this.close = close;
        }

        static SoftSynthesizerFacade create() {
            try {
                Class<?> synthClass = Class.forName("com.sun.media.sound.SoftSynthesizer");
                Object synth = synthClass.getConstructor().newInstance();
                Method openStream = synthClass.getMethod("openStream", AudioFormat.class, Map.class);
                Method getDefaultSoundbank = synthClass.getMethod("getDefaultSoundbank");
                Method unloadAllInstruments = synthClass.getMethod("unloadAllInstruments", Soundbank.class);
                Method loadAllInstruments = synthClass.getMethod("loadAllInstruments", Soundbank.class);
                Method getChannels = synthClass.getMethod("getChannels");
                Method getReceiver = synthClass.getMethod("getReceiver");
                Method close = synthClass.getMethod("close");
                return new SoftSynthesizerFacade(synth,
                        openStream,
                        getDefaultSoundbank,
                        unloadAllInstruments,
                        loadAllInstruments,
                        getChannels,
                        getReceiver,
                        close);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("SoftSynthesizer is not available in this runtime", ex);
            }
        }

        AudioInputStream openStream(AudioFormat format) {
            try {
                return (AudioInputStream) openStream.invoke(synth, format, null);
            } catch (IllegalAccessException | InvocationTargetException ex) {
                throw new IllegalStateException("Failed to open SoftSynthesizer stream", ex);
            }
        }

        Soundbank getDefaultSoundbank() {
            try {
                return (Soundbank) getDefaultSoundbank.invoke(synth);
            } catch (IllegalAccessException | InvocationTargetException ex) {
                throw new IllegalStateException("Failed to query SoftSynthesizer soundbank", ex);
            }
        }

        void unloadAllInstruments(Soundbank soundbank) {
            try {
                unloadAllInstruments.invoke(synth, soundbank);
            } catch (IllegalAccessException | InvocationTargetException ex) {
                throw new IllegalStateException("Failed to unload SoftSynthesizer instruments", ex);
            }
        }

        void loadAllInstruments(Soundbank soundbank) {
            try {
                loadAllInstruments.invoke(synth, soundbank);
            } catch (IllegalAccessException | InvocationTargetException ex) {
                throw new IllegalStateException("Failed to load SoftSynthesizer instruments", ex);
            }
        }

        MidiChannel[] getChannels() {
            try {
                return (MidiChannel[]) getChannels.invoke(synth);
            } catch (IllegalAccessException | InvocationTargetException ex) {
                throw new IllegalStateException("Failed to query SoftSynthesizer channels", ex);
            }
        }

        Receiver getReceiver() {
            try {
                return (Receiver) getReceiver.invoke(synth);
            } catch (IllegalAccessException | InvocationTargetException ex) {
                throw new IllegalStateException("Failed to acquire SoftSynthesizer receiver", ex);
            }
        }

        @Override
        public void close() {
            try {
                close.invoke(synth);
            } catch (IllegalAccessException | InvocationTargetException ex) {
                throw new IllegalStateException("Failed to close SoftSynthesizer", ex);
            }
        }
    }


}
