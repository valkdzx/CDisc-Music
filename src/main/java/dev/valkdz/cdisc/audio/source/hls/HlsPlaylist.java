package dev.valkdz.cdisc.audio.source.hls;

import dev.valkdz.cdisc.audio.player.Http;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HlsPlaylist {

    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Z0-9-]+)=(\"([^\"]*)\"|[^,]*)");

    public record Key(String uri, byte[] iv) {}

    public record Segment(String uri, long startMs, long durationMs, long sequence, Key key, String title) {}

    public record Variant(String uri, Map<String, String> attributes) {}

    private final List<Segment> segments;
    private final long durationMs;
    private final String mapUri;
    private final boolean ended;
    private final long targetDurationMs;
    private final Map<String, byte[]> keys;
    private final Http.Guard guard;
    private final String url;
    private final String[] headers;

    private HlsPlaylist(List<Segment> segments, long durationMs, String mapUri, boolean ended,
                        long targetDurationMs, Map<String, byte[]> keys, Http.Guard guard, String url,
                        String[] headers) {
        this.segments = segments;
        this.durationMs = durationMs;
        this.mapUri = mapUri;
        this.ended = ended;
        this.targetDurationMs = targetDurationMs;
        this.keys = keys;
        this.guard = guard;
        this.url = url;
        this.headers = headers;
    }

    public static HlsPlaylist fetch(String url, String... headers) throws IOException {
        return fetch(url, null, headers);
    }

    public static HlsPlaylist fetch(String url, Http.Guard guard, String... headers) throws IOException {
        String text = new String(load(url, guard, headers), StandardCharsets.UTF_8);
        List<Variant> variants = variants(text, url);
        String chosen = variants.isEmpty() ? url : variants.get(0).uri();
        if (!variants.isEmpty()) text = new String(load(chosen, guard, headers), StandardCharsets.UTF_8);
        return parse(text, chosen, new ConcurrentHashMap<>(), guard, headers);
    }

    public static HlsPlaylist fetchMedia(String url, Http.Guard guard, String... headers) throws IOException {
        return parse(new String(load(url, guard, headers), StandardCharsets.UTF_8), url, new ConcurrentHashMap<>(),
                guard, headers);
    }

    HlsPlaylist refetch(String url) throws IOException {
        return parse(new String(load(url, guard, headers), StandardCharsets.UTF_8), url, keys, guard, headers);
    }

    private static byte[] load(String url, Http.Guard guard, String[] headers) throws IOException {
        if (guard == null) return Http.bytes(url, headers);
        HttpResponse<InputStream> response = Http.open(url, 0, -1, guard, headers);
        try (InputStream body = response.body()) {
            Http.expectOk(response, url);
            return body.readAllBytes();
        }
    }

    public String url() {
        return url;
    }

    public static List<Variant> variants(String text, String url) {
        List<Variant> found = new ArrayList<>();
        Map<String, String> pending = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pending = attributes(line.substring(18));
            } else if (pending != null && !line.isEmpty() && !line.startsWith("#")) {
                found.add(new Variant(URI.create(url).resolve(line).toString(), pending));
                pending = null;
            }
        }
        return found;
    }

    static HlsPlaylist parse(String text, String baseUrl, Map<String, byte[]> keys, Http.Guard guard,
                             String[] headers) throws IOException {
        URI base = URI.create(baseUrl);
        List<Segment> segments = new ArrayList<>();
        long sequence = 0;
        long at = 0;
        double pending = -1;
        String title = null;
        Key key = null;
        String map = null;
        boolean ended = false;
        long target = 0;

        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                sequence = Long.parseLong(line.substring(22).trim());
            } else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                target = Math.round(Double.parseDouble(line.substring(22).trim()) * 1000);
            } else if (line.startsWith("#EXTINF:")) {
                String value = line.substring(8);
                int comma = value.indexOf(',');
                pending = Double.parseDouble((comma < 0 ? value : value.substring(0, comma)).trim());
                title = comma < 0 ? null : value.substring(comma + 1).trim();
            } else if (line.startsWith("#EXT-X-KEY:")) {
                key = keyOf(attributes(line.substring(11)), base);
            } else if (line.startsWith("#EXT-X-MAP:")) {
                String uri = attributes(line.substring(11)).get("URI");
                if (uri != null && map == null) map = base.resolve(uri).toString();
            } else if (line.startsWith("#EXT-X-ENDLIST")) {
                ended = true;
            } else if (!line.startsWith("#")) {
                long length = Math.round(Math.max(pending, 0) * 1000);
                segments.add(new Segment(base.resolve(line).toString(), at, length, sequence++, key, title));
                at += length;
                pending = -1;
                title = null;
            }
        }

        if (segments.isEmpty() && ended) throw new IOException("The HLS playlist lists no segments");
        return new HlsPlaylist(List.copyOf(segments), at, map, ended, target > 0 ? target : 2000, keys, guard,
                baseUrl, headers);
    }

    private static Key keyOf(Map<String, String> attributes, URI base) throws IOException {
        String method = attributes.getOrDefault("METHOD", "NONE");
        if ("NONE".equals(method)) return null;
        if (!"AES-128".equals(method)) {
            throw new IOException("HLS encryption " + method + " is not supported");
        }

        String iv = attributes.get("IV");
        return new Key(base.resolve(attributes.get("URI")).toString(), iv == null ? null : hex(iv));
    }

    public static Map<String, String> attributes(String list) {
        Map<String, String> found = new HashMap<>();
        Matcher m = ATTRIBUTE.matcher(list);
        while (m.find()) found.put(m.group(1), m.group(3) != null ? m.group(3) : m.group(2));
        return found;
    }

    private static byte[] hex(String value) {
        String digits = value.toLowerCase(Locale.ROOT).startsWith("0x") ? value.substring(2) : value;
        digits = "0".repeat(Math.max(0, 32 - digits.length())) + digits;
        digits = digits.substring(digits.length() - 32);

        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) Integer.parseInt(digits.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public List<Segment> segments() {
        return segments;
    }

    public long durationMs() {
        return durationMs;
    }

    public boolean ended() {
        return ended;
    }

    public long targetDurationMs() {
        return targetDurationMs;
    }

    public int indexAt(long positionMs) {
        for (int i = segments.size() - 1; i > 0; i--) {
            if (segments.get(i).startMs() <= positionMs) return i;
        }
        return 0;
    }

    // What must come before segment `from` for a decoder to start there: the EXT-X-MAP
    // init section, or an Ogg stream's header pages, which only the first segment carries.
    public byte[] prefixFor(int from) throws IOException {
        if (mapUri != null) return load(mapUri, guard, headers);
        if (from == 0 || segments.isEmpty()) return new byte[0];
        byte[] first = audioOf(0);
        return OggPages.isOgg(first) ? OggPages.headers(first) : new byte[0];
    }

    public byte[] audioOf(int index) throws IOException {
        Segment segment = segments.get(index);
        byte[] body = load(segment.uri(), guard, headers);
        if (segment.key() != null) body = decrypt(segment, body);
        return MpegTsAudio.isTransportStream(body) ? MpegTsAudio.audioOf(body) : body;
    }

    private byte[] decrypt(Segment segment, byte[] body) throws IOException {
        byte[] key = keys.get(segment.key().uri());
        if (key == null) {
            key = load(segment.key().uri(), guard, headers);
            keys.put(segment.key().uri(), key);
        }

        // Without an IV attribute the segment's media sequence number is the IV (RFC 8216 5.2).
        byte[] iv = segment.key().iv() != null ? segment.key().iv()
                : ByteBuffer.allocate(16).putLong(8, segment.sequence()).array();
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not decrypt HLS segment " + segment.sequence(), e);
        }
    }

    static final class OggPages {
        private OggPages() {
        }

        static boolean isOgg(byte[] data) {
            return data.length >= 4 && data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S';
        }

        static byte[] headers(byte[] data) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int at = 0;
            while (at + 27 <= data.length && data[at] == 'O' && data[at + 1] == 'g') {
                long granule = ByteBuffer.wrap(data, at + 6, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong();
                int segments = data[at + 26] & 0xFF;
                if (at + 27 + segments > data.length) break;
                int size = 27 + segments;
                for (int i = 0; i < segments; i++) size += data[at + 27 + i] & 0xFF;
                if (granule != 0 || at + size > data.length) break;
                out.write(data, at, size);
                at += size;
            }
            return out.toByteArray();
        }
    }
}
