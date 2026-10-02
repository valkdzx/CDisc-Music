package dev.valkdz.cdisc.audio.media;

import java.io.IOException;

public interface AudioDecoder {

    int sampleRate();

    int channels();

    int maxSamples();

    // Returns samples per channel written into out, which holds channels() rows of maxSamples().
    int decode(Packet packet, float[][] out) throws IOException;

    void reset();
}
