package dev.valkdz.cdisc.audio.media;

import java.util.List;

public record TrackFormat(Codec codec, int sampleRate, int channels, byte[] config,
                          List<byte[]> headers, int bitsPerSample, boolean bigEndian, boolean floating,
                          int skipSamples, long seekPrerollMs) {

    public static TrackFormat of(Codec codec, int sampleRate, int channels, byte[] config) {
        return new TrackFormat(codec, sampleRate, channels, config, List.of(), 0, false, false, 0, 0);
    }

    public static TrackFormat pcm(int sampleRate, int channels, int bitsPerSample,
                                  boolean bigEndian, boolean floating) {
        return new TrackFormat(Codec.PCM, sampleRate, channels, null, List.of(),
                bitsPerSample, bigEndian, floating, 0, 0);
    }

    public TrackFormat withHeaders(List<byte[]> packets) {
        return new TrackFormat(codec, sampleRate, channels, config, List.copyOf(packets), bitsPerSample,
                bigEndian, floating, skipSamples, seekPrerollMs);
    }

    public TrackFormat withSkip(int samples, long prerollMs) {
        return new TrackFormat(codec, sampleRate, channels, config, headers, bitsPerSample,
                bigEndian, floating, samples, prerollMs);
    }
}
