package dev.valkdz.cdisc.audio.engine;

import dev.valkdz.cdisc.audio.hls.HlsLiveAudioTrack;
import dev.valkdz.cdisc.audio.hls.HlsPlaylist;
import dev.valkdz.cdisc.audio.hls.HlsVodAudioTrack;
import dev.valkdz.cdisc.audio.media.Demuxer;
import dev.valkdz.cdisc.audio.media.Media;
import dev.valkdz.cdisc.audio.media.Tags;
import dev.valkdz.cdisc.audio.player.AudioItem;
import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.Http;
import dev.valkdz.cdisc.audio.player.HttpAudioTrack;
import dev.valkdz.cdisc.audio.player.HttpStream;
import dev.valkdz.cdisc.audio.player.LoadException;
import dev.valkdz.cdisc.util.SafeUrl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class ScopedHttpAudioSourceManager implements AudioSourceManager {

    static final String REFUSED = "Links into this server's own network are refused.";

    private static final int LIST_PROBE = 64 * 1024;

    // Runs before every connection, redirects and later reconnects of a playing track included.
    static final Http.Guard PUBLIC_ONLY = target -> {
        String host = target.getHost();
        if (host != null && SafeUrl.judgeHost(host) == SafeUrl.Verdict.PRIVATE_ADDRESS) {
            throw new IOException(REFUSED + " (" + host + ")");
        }
    };

    private final String[] allowedPrefixes;

    ScopedHttpAudioSourceManager(String... allowedPrefixes) {
        this.allowedPrefixes = new String[allowedPrefixes.length];
        for (int i = 0; i < allowedPrefixes.length; i++) {
            this.allowedPrefixes[i] = allowedPrefixes[i].toLowerCase(Locale.ROOT);
        }
    }

    @Override
    public String getSourceName() {
        return "http";
    }

    @Override
    public AudioItem loadItem(String identifier) {
        if (identifier == null || !allowed(identifier.toLowerCase(Locale.ROOT))) return null;

        if (SafeUrl.judge(identifier) == SafeUrl.Verdict.PRIVATE_ADDRESS) throw new LoadException(REFUSED);
        try {
            return probe(identifier, true);
        } catch (IOException e) {
            throw new LoadException("Could not read " + identifier + ": " + e.getMessage(), e);
        }
    }

    private AudioItem probe(String url, boolean mayFollowList) throws IOException {
        String contentType;
        byte[] head;
        try (HttpStream stream = new HttpStream(url, -1).guard(PUBLIC_ONLY)) {
            contentType = stream.contentType();
            head = stream.readNBytes(LIST_PROBE);
        }
        String text = new String(head, StandardCharsets.UTF_8).trim();
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);

        if (text.startsWith("#EXTM3U") && text.contains("#EXT-X-")) return hls(url);
        if (mayFollowList && (type.contains("mpegurl") || type.contains("scpls") || text.startsWith("[playlist]")
                || text.startsWith("#EXTM3U"))) {
            String entry = firstEntry(text);
            if (entry == null) return AudioItem.NONE;
            PUBLIC_ONLY.check(java.net.URI.create(entry));
            return probe(entry, false);
        }

        try (HttpStream stream = new HttpStream(url, -1).guard(PUBLIC_ONLY);
             Demuxer demuxer = Media.open(stream, contentType)) {
            long duration = demuxer.durationMs();
            boolean live = duration <= 0;
            Tags tags = demuxer.tags();
            AudioTrackInfo info = new AudioTrackInfo(
                    tags.title() != null ? tags.title() : AudioTrackInfo.UNKNOWN_TITLE,
                    tags.artist() != null ? tags.artist() : AudioTrackInfo.UNKNOWN_ARTIST,
                    live ? AudioTrackInfo.UNKNOWN_LENGTH : duration, url, live, url);
            return new HttpAudioTrack(info, this, url, contentType, -1, null, PUBLIC_ONLY);
        }
    }

    private AudioItem hls(String url) throws IOException {
        HlsPlaylist playlist = HlsPlaylist.fetch(url, PUBLIC_ONLY);
        if (!playlist.ended()) {
            return new HlsLiveAudioTrack(new AudioTrackInfo(AudioTrackInfo.UNKNOWN_TITLE, AudioTrackInfo.UNKNOWN_ARTIST,
                    AudioTrackInfo.UNKNOWN_LENGTH, url, true, url), this, url, PUBLIC_ONLY);
        }
        return new HlsVodAudioTrack(new AudioTrackInfo(AudioTrackInfo.UNKNOWN_TITLE, AudioTrackInfo.UNKNOWN_ARTIST,
                playlist.durationMs(), url, false, url), this, url, null, PUBLIC_ONLY);
    }

    private static String firstEntry(String text) {
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.regionMatches(true, 0, "File", 0, 4) && line.contains("=")) {
                line = line.substring(line.indexOf('=') + 1).trim();
            }
            if (line.startsWith("http://") || line.startsWith("https://")) return line;
        }
        return null;
    }

    private boolean allowed(String lower) {
        if (allowedPrefixes.length == 0) return lower.startsWith("http://") || lower.startsWith("https://");
        for (String prefix : allowedPrefixes) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }
}
