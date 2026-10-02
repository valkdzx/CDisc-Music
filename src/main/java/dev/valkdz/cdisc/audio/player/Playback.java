package dev.valkdz.cdisc.audio.player;

import dev.valkdz.cdisc.audio.media.AudioDecoder;
import dev.valkdz.cdisc.audio.media.Demuxer;
import dev.valkdz.cdisc.audio.media.Media;
import dev.valkdz.cdisc.audio.media.MediaInput;
import dev.valkdz.cdisc.audio.media.Packet;
import dev.valkdz.cdisc.audio.media.Resampler;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Playback {

    private static final int MAX_BAD_PACKETS = 50;
    private static final int MAX_PENDING_SECONDS = 5;

    private final TrackExecutor executor;
    private final long startMs;
    private long positionMs;

    Playback(TrackExecutor executor, long startMs) {
        this.executor = executor;
        this.startMs = startMs;
        this.positionMs = startMs;
    }

    public long startMs() {
        return startMs;
    }

    long positionMs() {
        return positionMs;
    }

    public boolean stopped() {
        return executor.stopped();
    }

    // Closed when playback stops or seeks, which also unblocks a read waiting on the network.
    public <T extends Closeable> T register(T resource) {
        executor.register(resource);
        return resource;
    }

    public void checkpoint() {
        executor.checkpoint();
    }

    public void idle() throws InterruptedException {
        while (true) {
            if (executor.stopped()) throw new TrackExecutor.Stopped();
            executor.takeSeek();
            Thread.sleep(200);
        }
    }

    public void decode(MediaInput input, String mimeType) throws Exception {
        decode(input, mimeType, 0);
    }

    // For input that cannot seek but was opened at a known point, such as an HLS segment.
    public void decode(MediaInput input, String mimeType, long openedAtMs) throws Exception {
        register(input);
        try (Demuxer demuxer = Media.open(input, mimeType)) {
            decode(demuxer, openedAtMs);
        }
    }

    public void decode(Demuxer demuxer) throws Exception {
        decode(demuxer, 0);
    }

    public void decode(Demuxer demuxer, long openedAtMs) throws Exception {
        register(demuxer);
        AudioDecoder decoder = Media.decoder(demuxer.format());
        Pipeline pipeline = new Pipeline(decoder.sampleRate(), decoder.channels());
        long preroll = demuxer.format().seekPrerollMs();
        if (startMs > 0 && demuxer.canSeek()) {
            long landed = demuxer.seek(Math.max(0, startMs - preroll));
            pipeline.restart(landed, startMs, 0, false);
        } else if (openedAtMs > 0) {
            pipeline.restart(openedAtMs, startMs, 0, true);
        } else {
            pipeline.restart(0, startMs, demuxer.format().skipSamples(), true);
        }

        float[][] buffer = new float[decoder.channels()][decoder.maxSamples()];
        int bad = 0;
        while (true) {
            if (executor.peekSeek() >= 0 && demuxer.canSeek() && !executor.stopped()) {
                long target = executor.takeSeek();
                long landed = demuxer.seek(Math.max(0, target - preroll));
                decoder.reset();
                pipeline.restart(landed, target, 0, false);
            }
            executor.checkpoint();
            Packet packet = demuxer.next();
            if (packet == null) break;
            int n;
            try {
                n = decoder.decode(packet, buffer);
                bad = 0;
            } catch (IOException e) {
                if (++bad > MAX_BAD_PACKETS) throw e;
                continue;
            }
            try {
                pipeline.write(buffer, n, packet.timeUs());
            } catch (TrackExecutor.SeekRequested seek) {
                if (!demuxer.canSeek()) throw seek;
            }
        }
        pipeline.finish();
    }

    // Takes interleaved 16-bit stereo PCM at 48 kHz from a source that makes its own audio.
    public void writePcm(byte[] frame, long timecode) throws InterruptedException {
        executor.offer(frame, timecode);
        positionMs = timecode;
    }

    private final class Pipeline {
        private final int rate;
        private final int channels;
        private final Resampler[] resamplers;
        private float[] resampledLeft = new float[0];
        private float[] resampledRight = new float[0];
        private final short[] frame = new short[AudioFrame.SAMPLES * 2];
        private int frameFill;
        private long outSample;
        private long dropUntil;
        private int skipInput;
        private boolean timeKnown;
        private long fedSamples;
        private long resampledSamples;
        private final List<float[][]> pending = new ArrayList<>();
        private long pendingSamples;

        Pipeline(int rate, int channels) {
            this.rate = rate;
            this.channels = channels;
            if (rate != AudioFrame.SAMPLE_RATE) {
                resamplers = new Resampler[channels];
                for (int c = 0; c < channels; c++) resamplers[c] = new Resampler(rate, AudioFrame.SAMPLE_RATE);
            } else {
                resamplers = null;
            }
        }

        void restart(long landedMs, long targetMs, int skip, boolean exact) {
            outSample = landedMs * 48;
            dropUntil = targetMs * 48;
            skipInput = skip;
            frameFill = 0;
            fedSamples = 0;
            resampledSamples = 0;
            pending.clear();
            pendingSamples = 0;
            timeKnown = exact || skip > 0;
            if (resamplers != null) for (Resampler r : resamplers) r.reset();
        }

        void write(float[][] pcm, int n, long timeUs) throws InterruptedException {
            if (n <= 0) return;
            int from = 0;
            if (skipInput > 0) {
                from = Math.min(n, skipInput);
                skipInput -= from;
                if (from == n) return;
            }
            int count = n - from;
            if (!timeKnown) {
                if (timeUs == Packet.UNKNOWN_TIME && pendingSamples + count <= rate * MAX_PENDING_SECONDS) {
                    float[][] copy = new float[channels][];
                    for (int c = 0; c < channels; c++) copy[c] = Arrays.copyOfRange(pcm[c], from, n);
                    pending.add(copy);
                    pendingSamples += count;
                    return;
                }
                timeKnown = true;
                if (timeUs != Packet.UNKNOWN_TIME) {
                    outSample = timeUs * 48 / 1000 - pendingSamples * AudioFrame.SAMPLE_RATE / rate;
                }
                for (float[][] block : pending) feed(block, 0, block[0].length);
                pending.clear();
                pendingSamples = 0;
            }
            feed(pcm, from, count);
        }

        private void feed(float[][] pcm, int from, int count) throws InterruptedException {
            if (resamplers == null) {
                emit(pcm[0], channels > 1 ? pcm[1] : pcm[0], from, count);
                return;
            }
            fedSamples += count;
            resample(pcm, from, count, Long.MAX_VALUE);
        }

        private void resample(float[][] pcm, int from, int count, long limit) throws InterruptedException {
            int capacity = resamplers[0].maxOutput(count);
            if (resampledLeft.length < capacity) {
                resampledLeft = new float[capacity];
                resampledRight = new float[capacity];
            }
            int produced = resamplers[0].process(pcm[0], from, count, resampledLeft, 0);
            if (channels > 1) resamplers[1].process(pcm[1], from, count, resampledRight, 0);
            produced = (int) Math.min(produced, limit - resampledSamples);
            resampledSamples += produced;
            emit(resampledLeft, channels > 1 ? resampledRight : resampledLeft, 0, produced);
        }

        private void emit(float[] left, float[] right, int from, int count) throws InterruptedException {
            for (int i = 0; i < count; i++) {
                if (outSample < dropUntil) {
                    outSample++;
                    continue;
                }
                frame[frameFill * 2] = toShort(left[from + i]);
                frame[frameFill * 2 + 1] = toShort(right[from + i]);
                outSample++;
                if (++frameFill == AudioFrame.SAMPLES) flushFrame();
            }
        }

        private void flushFrame() throws InterruptedException {
            byte[] data = new byte[AudioFrame.BYTES];
            for (int i = 0; i < AudioFrame.SAMPLES * 2; i++) {
                short s = frame[i];
                data[2 * i] = (byte) s;
                data[2 * i + 1] = (byte) (s >> 8);
            }
            long timecode = (outSample - AudioFrame.SAMPLES) / 48;
            frameFill = 0;
            positionMs = timecode;
            executor.offer(data, timecode);
        }

        void finish() throws InterruptedException {
            if (!timeKnown) {
                timeKnown = true;
                for (float[][] block : pending) feed(block, 0, block[0].length);
                pending.clear();
            }
            if (resamplers != null && fedSamples > 0) {
                float[][] silence = new float[channels][resamplers[0].latency() + 2];
                resample(silence, 0, silence[0].length, fedSamples * AudioFrame.SAMPLE_RATE / rate);
            }
            if (frameFill == 0) return;
            for (int i = frameFill * 2; i < frame.length; i++) frame[i] = 0;
            outSample += AudioFrame.SAMPLES - frameFill;
            frameFill = AudioFrame.SAMPLES;
            flushFrame();
        }

        private short toShort(float v) {
            float s = v * 32768f;
            if (s >= 32767f) return Short.MAX_VALUE;
            if (s <= -32768f) return Short.MIN_VALUE;
            return (short) Math.round(s);
        }
    }
}
