package dev.valkdz.cdisc.audio.hls;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HlsPlaylist {

    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Z0-9-]+)=(\"([^\"]*)\"|[^,]*)");

    public record Key(String uri, byte[] iv) {}

    public record Segment(String uri, long startMs, long durationMs, long sequence, Key key) {}

    private final List<Segment> segments;
    private final long durationMs;
    private final Map<String, byte[]> keys = new HashMap<>();

    private HlsPlaylist(List<Segment> segments, long durationMs) {
        this.segments = segments;
        this.durationMs = durationMs;
    }

    public static HlsPlaylist fetch(HttpInterface http, String url) throws IOException {
        String text = text(http, url);
        String variant = firstVariant(text, url);
        return parse(variant == null ? text : text(http, variant), variant == null ? url : variant);
    }

    static HlsPlaylist parse(String text, String baseUrl) throws IOException {
        URI base = URI.create(baseUrl);
        List<Segment> segments = new ArrayList<>();
        long sequence = 0;
        long at = 0;
        double pending = -1;
        Key key = null;

        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                sequence = Long.parseLong(line.substring(22).trim());
            } else if (line.startsWith("#EXTINF:")) {
                String value = line.substring(8);
                int comma = value.indexOf(',');
                pending = Double.parseDouble((comma < 0 ? value : value.substring(0, comma)).trim());
            } else if (line.startsWith("#EXT-X-KEY:")) {
                key = keyOf(attributes(line.substring(11)), base);
            } else if (!line.startsWith("#")) {
                long length = Math.round(Math.max(pending, 0) * 1000);
                segments.add(new Segment(base.resolve(line).toString(), at, length, sequence++, key));
                at += length;
                pending = -1;
            }
        }

        if (segments.isEmpty()) throw new IOException("The HLS playlist lists no segments");
        return new HlsPlaylist(List.copyOf(segments), at);
    }

    private static String firstVariant(String text, String url) {
        boolean next = false;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                next = true;
            } else if (next && !line.isEmpty() && !line.startsWith("#")) {
                return URI.create(url).resolve(line).toString();
            }
        }
        return null;
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

    private static Map<String, String> attributes(String list) {
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

    public int indexAt(long positionMs) {
        for (int i = segments.size() - 1; i > 0; i--) {
            if (segments.get(i).startMs() <= positionMs) return i;
        }
        return 0;
    }

    public byte[] audioOf(HttpInterface http, int index) throws IOException {
        Segment segment = segments.get(index);
        byte[] body = bytes(http, segment.uri());
        if (segment.key() != null) body = decrypt(http, segment, body);
        return MpegTsAudio.mp3Of(body);
    }

    private byte[] decrypt(HttpInterface http, Segment segment, byte[] body) throws IOException {
        byte[] key = keys.get(segment.key().uri());
        if (key == null) {
            key = bytes(http, segment.key().uri());
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

    private static String text(HttpInterface http, String url) throws IOException {
        return new String(bytes(http, url), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(HttpInterface http, String url) throws IOException {
        try (CloseableHttpResponse response = http.execute(new HttpGet(url))) {
            HttpClientTools.assertSuccessWithContent(response, "HLS resource");
            return EntityUtils.toByteArray(response.getEntity());
        }
    }
}
