package dev.valkdz.cdisc.audio.media.container;

import dev.valkdz.cdisc.audio.media.Tags;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class VorbisComments {

    private VorbisComments() {
    }

    static Tags parse(byte[] data, int offset) {
        try {
            int at = offset;
            long vendor = le32(data, at);
            at += 4 + (int) vendor;
            long count = le32(data, at);
            at += 4;
            String title = null;
            String artist = null;
            for (long i = 0; i < count && at + 4 <= data.length; i++) {
                int length = (int) le32(data, at);
                at += 4;
                if (length < 0 || at + length > data.length) break;
                String entry = new String(data, at, length, StandardCharsets.UTF_8);
                at += length;
                int eq = entry.indexOf('=');
                if (eq <= 0) continue;
                String key = entry.substring(0, eq).toUpperCase(Locale.ROOT);
                String value = entry.substring(eq + 1);
                if (key.equals("TITLE") && title == null) title = value;
                if (key.equals("ARTIST") && artist == null) artist = value;
            }
            return new Tags(Tags.clean(title), Tags.clean(artist));
        } catch (RuntimeException e) {
            return Tags.NONE;
        }
    }

    private static long le32(byte[] data, int at) {
        return (data[at] & 0xFFL) | ((data[at + 1] & 0xFFL) << 8) | ((data[at + 2] & 0xFFL) << 16) | ((data[at + 3] & 0xFFL) << 24);
    }
}
