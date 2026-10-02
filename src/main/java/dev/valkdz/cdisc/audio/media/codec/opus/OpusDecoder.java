package dev.valkdz.cdisc.audio.media.codec.opus;

import dev.valkdz.cdisc.audio.media.AudioDecoder;
import dev.valkdz.cdisc.audio.media.Packet;

import java.io.IOException;
import java.util.Arrays;

// A port of the libopus 1.5.2 decoder (BSD, see THIRD-PARTY-NOTICES), always decoding at 48 kHz.
public final class OpusDecoder implements AudioDecoder {

    private static final int FS = 48000;
    static final int MODE_SILK_ONLY = 1000;
    static final int MODE_HYBRID = 1001;
    static final int MODE_CELT_ONLY = 1002;
    static final int BANDWIDTH_NARROWBAND = 1101;
    static final int BANDWIDTH_MEDIUMBAND = 1102;
    static final int BANDWIDTH_WIDEBAND = 1103;
    static final int BANDWIDTH_SUPERWIDEBAND = 1104;
    static final int BANDWIDTH_FULLBAND = 1105;
    private static final int MAX_FRAME = 5760;

    private final int channels;
    private final CeltDecoder celt;
    private final SilkDecoder silk;
    private final SilkDecoder.Control control = new SilkDecoder.Control();
    private final int decodeGain;
    private final RangeDecoder dec = new RangeDecoder();

    private int streamChannels;
    private int bandwidth;
    private int mode;
    private int prevMode;
    private int frameSize;
    private boolean prevRedundancy;
    private final float[] pcm = new float[MAX_FRAME * 2];
    private final short[] size = new short[48];

    public OpusDecoder(byte[] head) throws IOException {
        if (head == null || head.length < 19) throw new IOException("Opus needs an OpusHead");
        int count = head[9] & 0xFF;
        int family = head[18] & 0xFF;
        if (family != 0 && !(family == 1 && count <= 2 && head.length >= 21 && (head[19] & 0xFF) == 1)) {
            throw new IOException("Multistream Opus (mapping family " + family + ", " + count + " channels) is not supported");
        }
        if (count < 1 || count > 2) throw new IOException("Opus with " + count + " channels is not supported");
        this.channels = count;
        this.decodeGain = (short) ((head[16] & 0xFF) | (head[17] << 8));
        this.celt = new CeltDecoder(count);
        this.silk = new SilkDecoder();
        control.apiSampleRate = FS;
        control.nChannelsApi = count;
        reset();
    }

    @Override
    public int sampleRate() {
        return FS;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public int maxSamples() {
        return MAX_FRAME;
    }

    @Override
    public void reset() {
        streamChannels = channels;
        bandwidth = 0;
        mode = 0;
        prevMode = 0;
        prevRedundancy = false;
        frameSize = FS / 400;
        silk.reset();
        celt.reset();
    }

    @Override
    public int decode(Packet packet, float[][] out) throws IOException {
        int samples = decodeNative(packet.data(), packet.offset(), packet.length(), pcm, MAX_FRAME);
        if (samples < 0) throw new IOException("Corrupt Opus packet (" + samples + ")");
        for (int c = 0; c < out.length; c++) {
            int from = Math.min(c, channels - 1);
            float[] row = out[c];
            for (int i = 0; i < samples; i++) row[i] = pcm[i * channels + from];
        }
        return samples;
    }

    private static int packetMode(int toc) {
        if ((toc & 0x80) != 0) return MODE_CELT_ONLY;
        if ((toc & 0x60) == 0x60) return MODE_HYBRID;
        return MODE_SILK_ONLY;
    }

    private static int packetBandwidth(int toc) {
        if ((toc & 0x80) != 0) {
            int bw = BANDWIDTH_MEDIUMBAND + ((toc >> 5) & 0x3);
            return bw == BANDWIDTH_MEDIUMBAND ? BANDWIDTH_NARROWBAND : bw;
        }
        if ((toc & 0x60) == 0x60) return (toc & 0x10) != 0 ? BANDWIDTH_FULLBAND : BANDWIDTH_SUPERWIDEBAND;
        return BANDWIDTH_NARROWBAND + ((toc >> 5) & 0x3);
    }

    static int samplesPerFrame(int toc, int fs) {
        if ((toc & 0x80) != 0) {
            int a = (toc >> 3) & 0x3;
            return (fs << a) / 400;
        }
        if ((toc & 0x60) == 0x60) return (toc & 0x08) != 0 ? fs / 50 : fs / 100;
        int a = (toc >> 3) & 0x3;
        return a == 3 ? fs * 60 / 1000 : (fs << a) / 100;
    }

    private static int parseSize(byte[] data, int at, int len, short[] size, int index) {
        if (len < 1) {
            size[index] = -1;
            return -1;
        }
        int b0 = data[at] & 0xFF;
        if (b0 < 252) {
            size[index] = (short) b0;
            return 1;
        }
        if (len < 2) {
            size[index] = -1;
            return -1;
        }
        size[index] = (short) (4 * (data[at + 1] & 0xFF) + b0);
        return 2;
    }

    // Returns the frame count and stores the payload offset in offsetOut[0], or a negative error.
    private int parsePacket(byte[] data, int at, int len, int[] offsetOut) {
        if (len == 0) return -4;
        int framesize = samplesPerFrame(data[at] & 0xFF, 48000);
        int toc = data[at++] & 0xFF;
        len--;
        int lastSize = len;
        int count;
        switch (toc & 0x3) {
            case 0 -> count = 1;
            case 1 -> {
                count = 2;
                if ((len & 1) != 0) return -4;
                lastSize = len / 2;
                size[0] = (short) lastSize;
            }
            case 2 -> {
                count = 2;
                int bytes = parseSize(data, at, len, size, 0);
                len -= bytes;
                if (size[0] < 0 || size[0] > len) return -4;
                at += bytes;
                lastSize = len - size[0];
            }
            default -> {
                if (len < 1) return -4;
                int ch = data[at++] & 0xFF;
                count = ch & 0x3F;
                if (count <= 0 || framesize * count > 5760) return -4;
                len--;
                if ((ch & 0x40) != 0) {
                    int p;
                    do {
                        if (len <= 0) return -4;
                        p = data[at++] & 0xFF;
                        len--;
                        int tmp = p == 255 ? 254 : p;
                        len -= tmp;
                    } while (p == 255);
                }
                if (len < 0) return -4;
                boolean cbr = (ch & 0x80) == 0;
                if (!cbr) {
                    lastSize = len;
                    for (int i = 0; i < count - 1; i++) {
                        int bytes = parseSize(data, at, len, size, i);
                        len -= bytes;
                        if (size[i] < 0 || size[i] > len) return -4;
                        at += bytes;
                        lastSize -= bytes + size[i];
                    }
                    if (lastSize < 0) return -4;
                } else {
                    lastSize = len / count;
                    if (lastSize * count != len) return -4;
                    for (int i = 0; i < count - 1; i++) size[i] = (short) lastSize;
                }
            }
        }
        if (lastSize > 1275) return -4;
        size[count - 1] = (short) lastSize;
        offsetOut[0] = at;
        return count;
    }

    private int decodeNative(byte[] data, int offset, int len, float[] out, int frameSizeLimit) {
        if (len == 0 || data == null) {
            int count = 0;
            do {
                int ret = decodeFrame(null, 0, 0, out, count * channels, frameSizeLimit - count);
                if (ret < 0) return ret;
                count += ret;
            } while (count < frameSizeLimit);
            return count;
        }
        int toc = data[offset] & 0xFF;
        int packetMode = packetMode(toc);
        int packetBandwidth = packetBandwidth(toc);
        int packetFrameSize = samplesPerFrame(toc, FS);
        int packetStreamChannels = (toc & 0x4) != 0 ? 2 : 1;

        int[] payload = new int[1];
        int count = parsePacket(data, offset, len, payload);
        if (count < 0) return count;
        if (count * packetFrameSize > frameSizeLimit) return -2;

        mode = packetMode;
        bandwidth = packetBandwidth;
        frameSize = packetFrameSize;
        streamChannels = packetStreamChannels;

        int at = payload[0];
        int samples = 0;
        for (int i = 0; i < count; i++) {
            int ret = decodeFrame(data, at, size[i], out, samples * channels, frameSizeLimit - samples);
            if (ret < 0) return ret;
            at += size[i];
            samples += ret;
        }
        return samples;
    }

    private static void smoothFade(float[] in1, int i1, float[] in2, int i2, float[] out, int o, int overlap,
                                   int channels, float[] window) {
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < overlap; i++) {
                float w = window[i] * window[i];
                out[o + i * channels + c] = w * in2[i2 + i * channels + c] + (1f - w) * in1[i1 + i * channels + c];
            }
        }
    }

    private int decodeFrame(byte[] data, int at, int len, float[] out, int outOff, int frameSizeArg) {
        int f20 = FS / 50;
        int f10 = f20 >> 1;
        int f5 = f10 >> 1;
        int f25 = f5 >> 1;
        if (frameSizeArg < f25) return -2;
        int frameSizeLocal = Math.min(frameSizeArg, FS / 25 * 3);
        if (len <= 1) {
            data = null;
            frameSizeLocal = Math.min(frameSizeLocal, frameSize);
        }
        int audiosize;
        int modeLocal;
        int bandwidthLocal;
        if (data != null) {
            audiosize = frameSize;
            modeLocal = mode;
            bandwidthLocal = bandwidth;
            dec.init(data, at, len);
        } else {
            audiosize = frameSizeLocal;
            modeLocal = prevRedundancy ? MODE_CELT_ONLY : prevMode;
            bandwidthLocal = 0;
            if (modeLocal == 0) {
                Arrays.fill(out, outOff, outOff + audiosize * channels, 0);
                return audiosize;
            }
            if (audiosize > f20) {
                int done = 0;
                do {
                    int ret = decodeFrame(null, 0, 0, out, outOff + done * channels, Math.min(audiosize, f20));
                    if (ret < 0) return ret;
                    done += ret;
                    audiosize -= ret;
                } while (audiosize > 0);
                return frameSizeLocal;
            } else if (audiosize < f20) {
                if (audiosize > f10) audiosize = f10;
                else if (modeLocal != MODE_SILK_ONLY && audiosize > f5 && audiosize < f10) audiosize = f5;
            }
        }

        boolean transition = false;
        float[] pcmTransition = null;
        if (data != null && prevMode > 0 && ((modeLocal == MODE_CELT_ONLY && prevMode != MODE_CELT_ONLY && !prevRedundancy)
                || (modeLocal != MODE_CELT_ONLY && prevMode == MODE_CELT_ONLY))) {
            transition = true;
        }
        if (transition && modeLocal == MODE_CELT_ONLY) {
            pcmTransition = new float[f5 * channels];
            decodeFrame(null, 0, 0, pcmTransition, 0, Math.min(f5, audiosize));
        }
        if (audiosize > frameSizeLocal) return -1;
        frameSizeLocal = audiosize;

        short[] pcmSilk = null;
        if (modeLocal != MODE_CELT_ONLY) {
            pcmSilk = new short[Math.max(f10, frameSizeLocal) * channels];
            if (prevMode == MODE_CELT_ONLY) silk.reset();
            control.payloadSizeMs = Math.max(10, 1000 * audiosize / FS);
            if (data != null) {
                control.nChannelsInternal = streamChannels;
                if (modeLocal == MODE_SILK_ONLY) {
                    if (bandwidthLocal == BANDWIDTH_NARROWBAND) control.internalSampleRate = 8000;
                    else if (bandwidthLocal == BANDWIDTH_MEDIUMBAND) control.internalSampleRate = 12000;
                    else control.internalSampleRate = 16000;
                } else {
                    control.internalSampleRate = 16000;
                }
            }
            int lostFlag = data == null ? 1 : 0;
            int decoded = 0;
            int[] silkFrameSize = new int[1];
            do {
                boolean firstFrame = decoded == 0;
                int ret = silk.decode(control, lostFlag, firstFrame, dec, pcmSilk, decoded * channels, silkFrameSize);
                if (ret != 0) {
                    if (lostFlag != 0) {
                        silkFrameSize[0] = frameSizeLocal;
                        Arrays.fill(pcmSilk, decoded * channels, decoded * channels + frameSizeLocal * channels, (short) 0);
                    } else {
                        return -3;
                    }
                }
                decoded += silkFrameSize[0];
            } while (decoded < frameSizeLocal);
        }

        int startBand = 0;
        boolean redundancy = false;
        int redundancyBytes = 0;
        boolean celtToSilk = false;
        if (modeLocal != MODE_CELT_ONLY && data != null
                && dec.tell() + 17 + 20 * (modeLocal == MODE_HYBRID ? 1 : 0) <= 8 * len) {
            redundancy = modeLocal != MODE_HYBRID || dec.bitLogp(12);
            if (redundancy) {
                celtToSilk = dec.bitLogp(1);
                redundancyBytes = modeLocal == MODE_HYBRID ? (int) dec.uint(256) + 2 : len - ((dec.tell() + 7) >> 3);
                len -= redundancyBytes;
                if (len * 8 < dec.tell()) {
                    len = 0;
                    redundancyBytes = 0;
                    redundancy = false;
                }
                dec.storage -= redundancyBytes;
            }
        }
        if (modeLocal != MODE_CELT_ONLY) startBand = 17;
        if (redundancy) transition = false;
        if (transition && modeLocal != MODE_CELT_ONLY) {
            pcmTransition = new float[f5 * channels];
            decodeFrame(null, 0, 0, pcmTransition, 0, Math.min(f5, audiosize));
        }

        if (bandwidthLocal != 0) {
            int endband = switch (bandwidthLocal) {
                case BANDWIDTH_NARROWBAND -> 13;
                case BANDWIDTH_MEDIUMBAND, BANDWIDTH_WIDEBAND -> 17;
                case BANDWIDTH_SUPERWIDEBAND -> 19;
                default -> 21;
            };
            celt.end = endband;
        }
        celt.streamChannels = streamChannels;

        float[] redundantAudio = redundancy ? new float[f5 * channels] : null;
        int redundantRng = 0;
        if (redundancy && celtToSilk) {
            celt.start = 0;
            celt.decode(data, at + len, redundancyBytes, redundantAudio, 0, f5, null);
            redundantRng = celt.rng;
        }
        celt.start = startBand;

        int celtRet = 0;
        if (modeLocal != MODE_SILK_ONLY) {
            int celtFrameSize = Math.min(f20, frameSizeLocal);
            if (modeLocal != prevMode && prevMode > 0 && !prevRedundancy) celt.reset();
            celtRet = celt.decode(data, at, len, out, outOff, celtFrameSize, dec);
        } else {
            Arrays.fill(out, outOff, outOff + frameSizeLocal * channels, 0);
            if (prevMode == MODE_HYBRID && !(redundancy && celtToSilk && prevRedundancy)) {
                celt.start = 0;
                byte[] silence = {(byte) 0xFF, (byte) 0xFF};
                celt.decode(silence, 0, 2, out, outOff, f25, null);
            }
        }

        if (modeLocal != MODE_CELT_ONLY) {
            for (int i = 0; i < frameSizeLocal * channels; i++) {
                out[outOff + i] = out[outOff + i] + (1.f / 32768.f) * pcmSilk[i];
            }
        }

        float[] window = CeltMode.MODE.window;
        if (redundancy && !celtToSilk) {
            celt.reset();
            celt.start = 0;
            celt.decode(data, at + len, redundancyBytes, redundantAudio, 0, f5, null);
            redundantRng = celt.rng;
            smoothFade(out, outOff + channels * (frameSizeLocal - f25), redundantAudio, channels * f25,
                    out, outOff + channels * (frameSizeLocal - f25), f25, channels, window);
        }
        if (redundancy && celtToSilk && (prevMode != MODE_SILK_ONLY || prevRedundancy)) {
            for (int c = 0; c < channels; c++) {
                for (int i = 0; i < f25; i++) out[outOff + channels * i + c] = redundantAudio[channels * i + c];
            }
            smoothFade(redundantAudio, channels * f25, out, outOff + channels * f25,
                    out, outOff + channels * f25, f25, channels, window);
        }
        if (transition) {
            if (audiosize >= f5) {
                System.arraycopy(pcmTransition, 0, out, outOff, channels * f25);
                smoothFade(pcmTransition, channels * f25, out, outOff + channels * f25,
                        out, outOff + channels * f25, f25, channels, window);
            } else {
                smoothFade(pcmTransition, 0, out, outOff, out, outOff, f25, channels, window);
            }
        }

        if (decodeGain != 0) {
            float gain = (float) Math.exp(0.6931471805599453094 * (6.48814081e-4f * decodeGain));
            for (int i = 0; i < frameSizeLocal * channels; i++) out[outOff + i] *= gain;
        }

        prevMode = modeLocal;
        prevRedundancy = redundancy && !celtToSilk;
        return celtRet < 0 ? celtRet : audiosize;
    }
}
