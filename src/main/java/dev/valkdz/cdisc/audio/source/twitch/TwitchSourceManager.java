package dev.valkdz.cdisc.audio.source.twitch;

import dev.valkdz.cdisc.audio.player.AudioItem;
import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.Http;
import dev.valkdz.cdisc.audio.player.LoadException;
import dev.valkdz.cdisc.audio.source.hls.HlsPlaylist;
import dev.valkdz.cdisc.util.Json;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TwitchSourceManager implements AudioSourceManager {

    private static final String GQL = "https://gql.twitch.tv/gql";
    private static final String CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";
    private static final String ARTWORK = "https://static-cdn.jtvnw.net/previews-ttv/live_user_%s-440x248.jpg";
    private static final Pattern CHANNEL = Pattern.compile("^https://(?:www\\.|go\\.|m\\.)?twitch\\.tv/([^/]+)$");

    private static final String METADATA = "{\"operationName\":\"StreamMetadata\",\"variables\":{\"channelLogin\":"
            + "\"%s\"},\"extensions\":{\"persistedQuery\":{\"version\":1,\"sha256Hash\":"
            + "\"ad022ca32220d5523d03a23cbcb5beaa1e0999889c1f8f78f9f2520dafb5cae6\"}}}";
    private static final String ACCESS_TOKEN = "{\"operationName\":\"PlaybackAccessToken_Template\",\"query\":"
            + "\"query PlaybackAccessToken_Template($login: String!,$isLive:Boolean!,$vodID:ID!,$isVod:Boolean!,"
            + "$playerType:String!){streamPlaybackAccessToken(channelName:$login,params:{platform:\\\"web\\\","
            + "playerBackend:\\\"mediaplayer\\\",playerType:$playerType})@include(if:$isLive){value signature "
            + "__typename}videoPlaybackAccessToken(id:$vodID,params:{platform:\\\"web\\\",playerBackend:"
            + "\\\"mediaplayer\\\",playerType:$playerType})@include(if:$isVod){value signature __typename}}\","
            + "\"variables\":{\"isLive\":true,\"login\":\"%s\",\"isVod\":false,\"vodID\":\"\",\"playerType\":\"site\"}}";

    private final String deviceId = UUID.randomUUID().toString().replace("-", "");

    @Override
    public String getSourceName() {
        return "twitch";
    }

    @Override
    public AudioItem loadItem(String identifier) {
        Matcher matcher = CHANNEL.matcher(identifier);
        if (!matcher.matches()) return null;
        String channel = matcher.group(1);

        Json user;
        try {
            user = gql(String.format(METADATA, channel)).path("data").path("user");
        } catch (IOException e) {
            throw new LoadException("Loading Twitch channel information failed.", e);
        }
        Json stream = user.path("stream");
        if (user.isNull() || user.isMissing() || stream.isNull() || stream.isMissing()
                || !"live".equals(stream.path("type").asText(""))) {
            return AudioItem.NONE;
        }

        String title = user.path("lastBroadcast").path("title").asText(channel);
        return new TwitchStreamTrack(new AudioTrackInfo(title, channel, AudioTrackInfo.UNKNOWN_LENGTH, identifier,
                true, identifier, String.format(ARTWORK, channel.toLowerCase(java.util.Locale.ROOT)), null),
                this, channel);
    }

    String mediaPlaylist(String channel) throws IOException {
        Json token = gql(String.format(ACCESS_TOKEN, channel)).path("data").path("streamPlaybackAccessToken");
        String value = token.path("value").asText(null);
        String signature = token.path("signature").asText(null);
        if (value == null || signature == null) throw new IOException("Twitch gave no playback token for " + channel);

        String master = "https://usher.ttvnw.net/api/channel/hls/" + channel + ".m3u8?token="
                + URLEncoder.encode(value, StandardCharsets.UTF_8) + "&sig=" + signature
                + "&allow_source=true&allow_spectre=true&allow_audio_only=true&player_backend=html5"
                + "&expgroup=regular&p=" + (int) (Math.random() * 1_000_000);
        List<HlsPlaylist.Variant> variants = HlsPlaylist.variants(Http.text(master), master);
        if (variants.isEmpty()) throw new IOException("No streams available on channel " + channel);

        return variants.stream()
                .filter(v -> "audio_only".equals(v.attributes().get("VIDEO")))
                .findFirst()
                .orElseGet(() -> variants.stream()
                        .min(Comparator.comparingLong(v -> bandwidth(v.attributes().get("BANDWIDTH"))))
                        .orElseThrow())
                .uri();
    }

    private static long bandwidth(String value) {
        try {
            return value == null ? Long.MAX_VALUE : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private Json gql(String body) throws IOException {
        HttpResponse<String> response = Http.post(GQL, body, "Client-ID", CLIENT_ID, "X-Device-ID", deviceId,
                "Content-Type", "text/plain;charset=UTF-8");
        if (response.statusCode() != 200) throw new IOException("Twitch answered HTTP " + response.statusCode());
        return Json.parse(response.body());
    }

    static String channelOf(String url) {
        Matcher matcher = CHANNEL.matcher(url);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
