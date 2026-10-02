package dev.valkdz.cdisc.audio.media.container;

import dev.valkdz.cdisc.audio.media.MediaReader;
import dev.valkdz.cdisc.audio.media.Tags;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

final class Id3 {

    private Id3() {
    }

    static boolean startsTag(byte[] head) {
        return head.length >= 10 && head[0] == 'I' && head[1] == 'D' && head[2] == '3'
                && (head[3] & 0xFF) < 0xFF && (head[6] & 0x80) == 0 && (head[7] & 0x80) == 0
                && (head[8] & 0x80) == 0 && (head[9] & 0x80) == 0;
    }

    static int tagLength(byte[] head) {
        int size = ((head[6] & 0x7F) << 21) | ((head[7] & 0x7F) << 14) | ((head[8] & 0x7F) << 7) | (head[9] & 0x7F);
        boolean footer = (head[5] & 0x10) != 0;
        return 10 + size + (footer ? 10 : 0);
    }

    static Tags readV2(MediaReader in) throws IOException {
        byte[] head = in.bytes(10);
        int version = head[3] & 0xFF;
        int flags = head[5] & 0xFF;
        int size = tagLength(head) - 10 - ((flags & 0x10) != 0 ? 10 : 0);
        byte[] body = in.bytes(size);
        if ((flags & 0x10) != 0) in.skip(10);
        if ((flags & 0x80) != 0 && version < 4) body = unsynchronise(body);

        int at = 0;
        if ((flags & 0x40) != 0 && version >= 3 && body.length >= 4) {
            int extended = version == 4 ? syncsafe(body, 0) : (int) be32(body, 0) + 4;
            at = Math.max(0, Math.min(body.length, extended));
        }

        String title = null;
        String artist = null;
        int idLength = version == 2 ? 3 : 4;
        int headerLength = version == 2 ? 6 : 10;
        while (at + headerLength <= body.length) {
            String id = new String(body, at, idLength, StandardCharsets.ISO_8859_1);
            if (id.charAt(0) == 0) break;
            int frameSize = version == 2 ? ((body[at + 3] & 0xFF) << 16) | ((body[at + 4] & 0xFF) << 8) | (body[at + 5] & 0xFF)
                    : version == 4 ? syncsafe(body, at + 4) : (int) be32(body, at + 4);
            int dataAt = at + headerLength;
            if (frameSize <= 0 || dataAt + frameSize > body.length) break;

            if (id.equals("TIT2") || id.equals("TT2")) {
                title = text(body, dataAt, frameSize);
            } else if (id.equals("TPE1") || id.equals("TP1")) {
                artist = text(body, dataAt, frameSize);
            }
            at = dataAt + frameSize;
        }
        return new Tags(Tags.clean(title), Tags.clean(artist));
    }

    static Tags readV1(byte[] tail) {
        if (tail.length < 128 || tail[0] != 'T' || tail[1] != 'A' || tail[2] != 'G') return Tags.NONE;
        return new Tags(Tags.clean(new String(tail, 3, 30, StandardCharsets.ISO_8859_1)),
                Tags.clean(new String(tail, 33, 30, StandardCharsets.ISO_8859_1)));
    }

    private static String text(byte[] data, int at, int length) {
        if (length < 1) return null;
        int encoding = data[at] & 0xFF;
        Charset charset = switch (encoding) {
            case 1 -> StandardCharsets.UTF_16;
            case 2 -> StandardCharsets.UTF_16BE;
            case 3 -> StandardCharsets.UTF_8;
            default -> StandardCharsets.ISO_8859_1;
        };
        String value = new String(data, at + 1, length - 1, charset);
        int nul = value.indexOf('\u0000');
        return nul >= 0 ? value.substring(0, nul) : value;
    }

    private static byte[] unsynchronise(byte[] data) {
        byte[] out = new byte[data.length];
        int n = 0;
        for (int i = 0; i < data.length; i++) {
            out[n++] = data[i];
            if ((data[i] & 0xFF) == 0xFF && i + 1 < data.length && data[i + 1] == 0) i++;
        }
        byte[] trimmed = new byte[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    private static int syncsafe(byte[] data, int at) {
        return ((data[at] & 0x7F) << 21) | ((data[at + 1] & 0x7F) << 14) | ((data[at + 2] & 0x7F) << 7) | (data[at + 3] & 0x7F);
    }

    private static long be32(byte[] data, int at) {
        return ((long) (data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16) | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }
}
