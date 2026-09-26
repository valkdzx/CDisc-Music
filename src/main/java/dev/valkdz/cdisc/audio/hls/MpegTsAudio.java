package dev.valkdz.cdisc.audio.hls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class MpegTsAudio {

    private static final int PACKET = 188;

    private MpegTsAudio() {
    }

    static byte[] mp3Of(byte[] ts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(ts.length);
        int pmtPid = -1;
        int audioPid = -1;
        int otherType = -1;

        for (int at = 0; at + PACKET <= ts.length; at += PACKET) {
            if (ts[at] != 0x47) throw new IOException("Not an MPEG-TS segment");

            boolean unitStart = (ts[at + 1] & 0x40) != 0;
            int pid = ((ts[at + 1] & 0x1F) << 8) | (ts[at + 2] & 0xFF);
            int adaptation = (ts[at + 3] >> 4) & 0x3;
            if ((adaptation & 0x1) == 0) continue;

            int payload = at + 4 + (adaptation == 3 ? 1 + (ts[at + 4] & 0xFF) : 0);
            int end = at + PACKET;
            if (payload >= end) continue;

            if (pid == 0 && unitStart) {
                pmtPid = programMapOf(ts, payload, end);
            } else if (pid == pmtPid && unitStart && audioPid < 0) {
                int[] found = audioStreamOf(ts, payload, end);
                audioPid = found[0];
                otherType = found[1];
            } else if (pid == audioPid) {
                if (unitStart) {
                    if (payload + 9 > end) continue;
                    payload += 9 + (ts[payload + 8] & 0xFF);
                }
                if (payload < end) out.write(ts, payload, end - payload);
            }
        }

        if (audioPid < 0 && otherType >= 0) {
            throw new IOException(String.format("HLS audio of stream type 0x%02x is not supported", otherType));
        }
        return out.toByteArray();
    }

    private static int programMapOf(byte[] ts, int payload, int end) {
        int table = payload + 1 + (ts[payload] & 0xFF);
        int sectionEnd = Math.min(end, table + 3 + sectionLength(ts, table) - 4);

        for (int at = table + 8; at + 4 <= sectionEnd; at += 4) {
            int program = ((ts[at] & 0xFF) << 8) | (ts[at + 1] & 0xFF);
            if (program != 0) return ((ts[at + 2] & 0x1F) << 8) | (ts[at + 3] & 0xFF);
        }
        return -1;
    }

    private static int[] audioStreamOf(byte[] ts, int payload, int end) {
        int table = payload + 1 + (ts[payload] & 0xFF);
        int sectionEnd = Math.min(end, table + 3 + sectionLength(ts, table) - 4);
        int infoLength = ((ts[table + 10] & 0x0F) << 8) | (ts[table + 11] & 0xFF);
        int other = -1;

        for (int at = table + 12 + infoLength; at + 5 <= sectionEnd; ) {
            int type = ts[at] & 0xFF;
            int pid = ((ts[at + 1] & 0x1F) << 8) | (ts[at + 2] & 0xFF);
            if (type == 0x03 || type == 0x04) return new int[]{pid, type};
            if (other < 0) other = type;
            at += 5 + (((ts[at + 3] & 0x0F) << 8) | (ts[at + 4] & 0xFF));
        }
        return new int[]{-1, other};
    }

    private static int sectionLength(byte[] ts, int table) {
        return ((ts[table + 1] & 0x0F) << 8) | (ts[table + 2] & 0xFF);
    }
}
