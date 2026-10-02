package dev.valkdz.cdisc.audio.player;

public final class AudioFrame {

    public static final int SAMPLES = 960;
    public static final int CHANNELS = 2;
    public static final int BYTES = SAMPLES * CHANNELS * 2;
    public static final int SAMPLE_RATE = 48000;

    private final byte[] data;
    private final long timecode;

    AudioFrame(byte[] data, long timecode) {
        this.data = data;
        this.timecode = timecode;
    }

    public byte[] getData() {
        return data;
    }

    public int getDataLength() {
        return data.length;
    }

    public long getTimecode() {
        return timecode;
    }

    public boolean isTerminator() {
        return false;
    }
}
