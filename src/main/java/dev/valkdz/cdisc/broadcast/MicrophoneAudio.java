package dev.valkdz.cdisc.broadcast;

public final class MicrophoneAudio {

    public static final int OUTPUT_RATE = 48_000;

    private MicrophoneAudio() {
    }

    public static byte[] toOutput(short[] samples, boolean stereo, int rate) {
        int channels = stereo ? 2 : 1;
        int frames = samples.length / channels;
        if (frames == 0) return new byte[0];
        int outFrames = rate == OUTPUT_RATE ? frames : Math.max(1, (int) ((long) frames * OUTPUT_RATE / rate));

        byte[] out = new byte[outFrames * 4];
        for (int i = 0; i < outFrames; i++) {
            double at = outFrames == 1 ? 0 : (double) i * (frames - 1) / (outFrames - 1);
            int left = sampleAt(samples, at, channels, 0, frames);
            int right = stereo ? sampleAt(samples, at, channels, 1, frames) : left;
            out[i * 4] = (byte) left;
            out[i * 4 + 1] = (byte) (left >> 8);
            out[i * 4 + 2] = (byte) right;
            out[i * 4 + 3] = (byte) (right >> 8);
        }
        return out;
    }

    private static int sampleAt(short[] samples, double at, int channels, int channel, int frames) {
        int low = (int) at;
        int high = Math.min(frames - 1, low + 1);
        double fraction = at - low;
        return (int) Math.round(samples[low * channels + channel] * (1 - fraction)
                + samples[high * channels + channel] * fraction);
    }
}
