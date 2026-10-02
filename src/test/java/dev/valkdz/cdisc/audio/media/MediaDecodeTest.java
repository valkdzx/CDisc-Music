package dev.valkdz.cdisc.audio.media;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaDecodeTest {

    static float[][] decode(String name) throws IOException {
        byte[] data;
        try (InputStream in = MediaDecodeTest.class.getResourceAsStream("/media/" + name)) {
            assertNotNull(in, name);
            data = in.readAllBytes();
        }
        try (Demuxer demuxer = Media.open(MediaInput.of(data), null)) {
            AudioDecoder decoder = Media.decoder(demuxer.format());
            float[][] buffer = new float[decoder.channels()][decoder.maxSamples()];
            float[][] all = new float[decoder.channels()][decoder.sampleRate() * 2];
            int skip = demuxer.format().skipSamples();
            int total = 0;
            Packet packet;
            while ((packet = demuxer.next()) != null) {
                int n = decoder.decode(packet, buffer);
                int from = Math.min(n, skip);
                skip -= from;
                for (int c = 0; c < all.length; c++) {
                    int count = Math.min(n - from, all[c].length - total);
                    if (count > 0) System.arraycopy(buffer[c], from, all[c], total, count);
                }
                total = Math.min(all[0].length, total + n - from);
            }
            float[][] trimmed = new float[all.length + 1][];
            for (int c = 0; c < all.length; c++) trimmed[c] = Arrays.copyOf(all[c], total);
            trimmed[all.length] = new float[]{decoder.sampleRate()};
            return trimmed;
        }
    }

    // Fits a sine of the given frequency to the middle of the signal and returns {amplitude, snr dB}.
    static double[] fit(float[] x, double frequency, int rate) {
        int from = x.length / 4;
        int to = x.length * 3 / 4;
        double w = 2 * Math.PI * frequency / rate;
        double s = 0;
        double c = 0;
        for (int i = from; i < to; i++) {
            s += x[i] * Math.sin(w * i);
            c += x[i] * Math.cos(w * i);
        }
        s *= 2.0 / (to - from);
        c *= 2.0 / (to - from);
        double signal = 0;
        double noise = 0;
        for (int i = from; i < to; i++) {
            double model = s * Math.sin(w * i) + c * Math.cos(w * i);
            signal += model * model;
            noise += (x[i] - model) * (x[i] - model);
        }
        return new double[]{Math.sqrt(s * s + c * c), 10 * Math.log10(signal / Math.max(noise, 1e-20))};
    }

    @ParameterizedTest
    @CsvSource({
            "tone.mp3, 1.0, 35, 0.05",
            "tone.m4a, 1.0, 35, 0.05",
            "tone.aac, 1.0, 35, 0.05",
            "tone.mka, 1.0, 35, 0.05",
            "tone.flac, 1.0, 70, 0.05",
            "tone.ogg, 1.0, 70, 0.05",
            "tone-vorbis.ogg, 1.0, 35, 0.05",
            "tone-vorbis.webm, 1.0, 35, 0.05",
            "tone-opus.ogg, 1.0, 35, 0.05",
            "tone-opus.webm, 1.0, 35, 0.05",
            "tone-hybrid.opus, 1.0, 5, 0.15",
            "tone.wav, 0.25, 70, 0.05"
    })
    void decodesTheTone(String name, double seconds, double minSnr, double amplitudeTolerance) throws IOException {
        float[][] pcm = decode(name);
        assertEquals(3, pcm.length, name);
        int rate = (int) pcm[2][0];
        assertTrue(Math.abs(pcm[0].length - rate * seconds) < rate / 20.0, name + " length " + pcm[0].length);
        double[] left = fit(pcm[0], 440, rate);
        double[] right = fit(pcm[1], 1000, rate);
        assertEquals(0.5, left[0], amplitudeTolerance, name + " left amplitude");
        assertEquals(0.5, right[0], amplitudeTolerance, name + " right amplitude");
        assertTrue(left[1] > minSnr, name + " left snr " + left[1]);
        assertTrue(right[1] > minSnr, name + " right snr " + right[1]);
    }
}
