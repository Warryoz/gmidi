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
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Utility class that renders MIDI sequences to audio files offline. */
public final class OfflineAudioRenderer {

    private OfflineAudioRenderer() {
    }

    public static Sequence prepareSequence(Sequence source,
                                           MidiService.MidiProgram program,
                                           int transposeSemis) throws InvalidMidiDataException {
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
        insertProgramChange(prepared.getTracks()[0], program);
        addEndOfTrack(prepared, maxTick);
        return prepared;
    }

    public static void renderWav(Sequence sequence,
                                 MidiService.MidiProgram program,
                                 int transposeSemis,
                                 VelocityMap velocityMap,
                                 MidiService.ReverbPreset reverbPreset,
                                 Soundbank customSoundbank,
                                 File outputFile) throws Exception {
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
        Sequence playable = prepareSequence(sequence, program, transposeSemis);
        AudioSynth synth = AudioSynth.create();
        AudioFormat format = new AudioFormat(44_100f, 16, 2, true, false);
        try (AudioSynth ignored = synth;
             AudioInputStream stream = synth.openStream(format);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outputFile))) {
            if (customSoundbank != null) {
                synth.loadSoundbank(customSoundbank);
            }
            applyReverb(synth.getChannels(), reverbPreset);
            Sequencer sequencer = MidiSystem.getSequencer(false);
            sequencer.open();
            try {
                try (TransmitterLink link = new TransmitterLink(sequencer.getTransmitter(),
                        new VelocityReceiver(synth.getReceiver(), velocityMap))) {
                    sequencer.setSequence(playable);
                    sequencer.setTickPosition(0);
                    sequencer.start();
                    AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out);
                    sequencer.stop();
                }
            } finally {
                sequencer.close();
            }
        }
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

    private static void insertProgramChange(javax.sound.midi.Track track,
                                            MidiService.MidiProgram program) throws InvalidMidiDataException {
        MidiService.MidiProgram patch = program != null ? program : new MidiService.MidiProgram(0, 0, 0, "GM Program 0");
        ShortMessage msb = new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 0, clamp7bit(patch.bankMsb()));
        ShortMessage lsb = new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 32, clamp7bit(patch.bankLsb()));
        ShortMessage pc = new ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, clamp7bit(patch.program()), 0);
        track.add(new MidiEvent(msb, 0));
        track.add(new MidiEvent(lsb, 0));
        track.add(new MidiEvent(pc, 0));
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

    private static final class TransmitterLink implements AutoCloseable {
        private final javax.sound.midi.Transmitter transmitter;
        private final Receiver receiver;

        private TransmitterLink(javax.sound.midi.Transmitter transmitter, Receiver receiver) {
            this.transmitter = transmitter;
            this.receiver = receiver;
            this.transmitter.setReceiver(receiver);
        }

        @Override
        public void close() {
            transmitter.close();
            receiver.close();
        }
    }

    private interface AudioSynth extends AutoCloseable {
        AudioInputStream openStream(AudioFormat format) throws Exception;

        Receiver getReceiver() throws Exception;

        MidiChannel[] getChannels();

        void loadSoundbank(Soundbank bank) throws Exception;

        @Override
        void close() throws Exception;

        static AudioSynth create() throws Exception {
            Class<?> clazz = Class.forName("com.sun.media.sound.SoftSynthesizer");
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object instance = ctor.newInstance();
            return new ReflectionAudioSynth(instance);
        }
    }

    private static final class ReflectionAudioSynth implements AudioSynth {
        private final Object synth;
        private final javax.sound.midi.Synthesizer asSynth;
        private final Map<String, Object> openParams = new HashMap<>();

        private ReflectionAudioSynth(Object synth) {
            this.synth = synth;
            this.asSynth = (javax.sound.midi.Synthesizer) synth;
        }

        @Override
        public AudioInputStream openStream(AudioFormat format) throws Exception {
            return (AudioInputStream) synth.getClass()
                    .getMethod("openStream", AudioFormat.class, Map.class)
                    .invoke(synth, format, openParams);
        }

        @Override
        public Receiver getReceiver() throws Exception {
            return asSynth.getReceiver();
        }

        @Override
        public MidiChannel[] getChannels() {
            return asSynth.getChannels();
        }

        @Override
        public void loadSoundbank(Soundbank bank) throws Exception {
            if (bank != null) {
                Soundbank defaultBank = asSynth.getDefaultSoundbank();
                if (defaultBank != null) {
                    asSynth.unloadAllInstruments(defaultBank);
                }
                asSynth.loadAllInstruments(bank);
            }
        }

        @Override
        public void close() throws Exception {
            asSynth.close();
        }
    }
}
