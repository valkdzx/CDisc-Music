package dev.valkdz.cdisc.voice;

import dev.valkdz.cdisc.speaker.SpeakerSettings;

public final class PcmShaper {

    private PcmShaper() {
    }

    public static short[] toSamples(byte[] littleEndian, SpeakerSettings.Channel channel, int volume) {
        int total = littleEndian.length / 2;
        double gain = volume / 100.0D;

        if (channel == SpeakerSettings.Channel.STEREO) {
            short[] samples = new short[total];
            for (int i = 0; i < total; i++) {
                samples[i] = scale(read(littleEndian, i), gain);
            }
            return samples;
        }

        short[] mono = new short[total / 2];
        for (int i = 0; i < mono.length; i++) {
            int left = read(littleEndian, i * 2);
            int right = read(littleEndian, i * 2 + 1);
            int value = switch (channel) {
                case LEFT -> left;
                case RIGHT -> right;
                default -> (left + right) / 2;
            };
            mono[i] = scale(value, gain);
        }
        return mono;
    }

    private static int read(byte[] littleEndian, int index) {
        return (littleEndian[index * 2] & 0xFF) | (littleEndian[index * 2 + 1] << 8);
    }

    private static short scale(int sample, double gain) {
        if (gain >= 1.0D) return (short) sample;
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * gain)));
    }
}
