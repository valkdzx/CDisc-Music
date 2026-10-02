package dev.valkdz.cdisc.audio.backend;

import dev.valkdz.cdisc.audio.player.AudioItem;
import dev.valkdz.cdisc.audio.player.AudioPlaylist;
import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.Http;
import dev.valkdz.cdisc.audio.player.HttpAudioTrack;
import dev.valkdz.cdisc.audio.player.LoadException;
import dev.valkdz.cdisc.audio.player.Playback;
import dev.valkdz.cdisc.util.Json;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YandexMusicSourceManager implements AudioSourceManager {

    private static final String API = "https://api.music.yandex.net";
    private static final String SIGN_SALT = "XGRlBW9FXlekgbPrRHuSiA";
    private static final int LIST_LIMIT = 300;
    private static final int SEARCH_LIMIT = 10;

    private static final Pattern ITEM = Pattern.compile("^(?:https?://)?music\\.yandex\\.(ru|com|kz|by)/"
            + "(artist|album|track)/([0-9]+)(?:/(track)/([0-9]+))?/?(?:[?#].*)?$");
    private static final Pattern USER_PLAYLIST = Pattern.compile("^(?:https?://)?music\\.yandex\\.(ru|com|kz|by)/"
            + "users/([0-9A-Za-z@.-]+)/playlists/([0-9]+)/?(?:[?#].*)?$");
    private static final Pattern PLAYLIST = Pattern.compile("^(?:https?://)?music\\.yandex\\.(ru|com|kz|by)/"
            + "playlists/([0-9A-Za-z\\-.]+)/?(?:[?#].*)?$");
    private static final Pattern DOWNLOAD_FIELD = Pattern.compile("<(host|path|ts|s)>([^<]*)</\\1>");

    private final String token;

    public YandexMusicSourceManager(String token) {
        this.token = token;
    }

    @Override
    public String getSourceName() {
        return "yandexmusic";
    }

    @Override
    public AudioItem loadItem(String identifier) {
        try {
            if (identifier.startsWith("ymsearch:")) return search(identifier.substring(9).trim());

            Matcher item = ITEM.matcher(identifier);
            if (item.matches()) {
                if (item.group(4) != null) return track(item.group(5));
                return switch (item.group(2)) {
                    case "track" -> track(item.group(3));
                    case "album" -> album(item.group(3));
                    default -> artist(item.group(3));
                };
            }
            Matcher user = USER_PLAYLIST.matcher(identifier);
            if (user.matches()) {
                return playlist(api("/users/" + user.group(2) + "/playlists/" + user.group(3)
                        + "?page-size=" + LIST_LIMIT + "&rich-tracks=true"));
            }
            Matcher shared = PLAYLIST.matcher(identifier);
            if (shared.matches()) {
                return playlist(api("/playlist/" + shared.group(2) + "?page-size=" + LIST_LIMIT + "&rich-tracks=true"));
            }
            return null;
        } catch (IOException e) {
            throw new LoadException("Loading from Yandex Music failed: " + e.getMessage(), e);
        }
    }

    private AudioItem search(String query) throws IOException {
        if (query.isEmpty()) return AudioItem.NONE;
        Json result = api("/search?text=" + encode(query) + "&type=track&page=0");
        List<AudioTrack> tracks = tracksOf(result.path("tracks").path("results"), SEARCH_LIMIT);
        return tracks.isEmpty() ? AudioItem.NONE
                : new AudioPlaylist("Yandex Music Search: " + query, tracks, null, true);
    }

    private AudioItem track(String id) throws IOException {
        Json result = api("/tracks/" + id);
        Json track = result.isArray() ? result.path(0) : result;
        AudioTrack built = trackOf(track);
        return built == null ? AudioItem.NONE : built;
    }

    private AudioItem album(String id) throws IOException {
        Json album = api("/albums/" + id + "/with-tracks?page-size=" + LIST_LIMIT);
        List<AudioTrack> tracks = new ArrayList<>();
        for (Json volume : album.path("volumes")) tracks.addAll(tracksOf(volume, LIST_LIMIT));
        return tracks.isEmpty() ? AudioItem.NONE
                : new AudioPlaylist(album.path("title").asText("Yandex Music album"), tracks, null, false);
    }

    private AudioItem artist(String id) throws IOException {
        Json tracks = api("/artists/" + id + "/tracks?page-size=10");
        String name = api("/artists/" + id).path("artist").path("name").asText("Artist");
        List<AudioTrack> list = tracksOf(tracks.path("tracks"), LIST_LIMIT);
        return list.isEmpty() ? AudioItem.NONE : new AudioPlaylist(name + "'s Top Tracks", list, null, false);
    }

    private AudioItem playlist(Json playlist) {
        List<AudioTrack> tracks = new ArrayList<>();
        for (Json entry : playlist.path("tracks")) {
            AudioTrack track = trackOf(entry.has("track") ? entry.path("track") : entry);
            if (track != null) tracks.add(track);
        }
        String title = playlist.path("kind").asInt(0) == 3
                ? playlist.path("owner").path("login").asText("") + "'s liked songs"
                : playlist.path("title").asText("Yandex Music playlist");
        return tracks.isEmpty() ? AudioItem.NONE : new AudioPlaylist(title, tracks, null, false);
    }

    private List<AudioTrack> tracksOf(Json list, int limit) {
        List<AudioTrack> tracks = new ArrayList<>();
        for (Json entry : list) {
            if (tracks.size() >= limit) break;
            AudioTrack track = trackOf(entry);
            if (track != null) tracks.add(track);
        }
        return tracks;
    }

    private AudioTrack trackOf(Json track) {
        String id = track.path("id").asText(null);
        if (id == null || !track.path("available").asBoolean(true)) return null;

        List<String> artists = new ArrayList<>();
        for (Json artist : track.path("artists")) artists.add(artist.path("name").asText(""));
        String cover = track.path("coverUri").asText(null);
        if (cover == null) cover = track.path("ogImage").asText(null);
        return new YandexTrack(new AudioTrackInfo(
                track.path("title").asText("Unknown title"),
                artists.isEmpty() ? "Unknown artist" : String.join(", ", artists),
                track.path("durationMs").asLong(AudioTrackInfo.UNKNOWN_LENGTH),
                id, false, "https://music.yandex.com/track/" + id,
                cover == null ? null : "https://" + cover.replace("%%", "400x400"), null), this);
    }

    String downloadUrl(String id) throws IOException {
        Json best = null;
        for (Json option : api("/tracks/" + id + "/download-info")) {
            if (!"mp3".equals(option.path("codec").asText(""))) continue;
            if (best == null || option.path("bitrateInKbps").asInt(0) > best.path("bitrateInKbps").asInt(0)) {
                best = option;
            }
        }
        if (best == null) throw new IOException("No downloadInfo found for track " + id);

        Matcher field = DOWNLOAD_FIELD.matcher(Http.text(best.path("downloadInfoUrl").asText(), headers()));
        String host = null, path = null, ts = null, s = null;
        while (field.find()) {
            switch (field.group(1)) {
                case "host" -> host = field.group(2);
                case "path" -> path = field.group(2);
                case "ts" -> ts = field.group(2);
                default -> s = field.group(2);
            }
        }
        if (host == null || path == null || ts == null || s == null) {
            throw new IOException("No download Mp3 item URL found for track " + id);
        }
        return "https://" + host + "/get-mp3/" + md5(SIGN_SALT + path.substring(1) + s) + "/" + ts + path;
    }

    private Json api(String path) throws IOException {
        HttpResponse<byte[]> response = Http.get(API + path, headers());
        if (response.statusCode() == 404) throw new LoadException("Yandex Music has no such item");
        Http.expectOk(response, null);
        return Json.parse(new String(response.body(), StandardCharsets.UTF_8)).path("result");
    }

    private String[] headers() {
        return new String[]{"Accept", "application/json", "Authorization", "OAuth " + token,
                "User-Agent", "Yandex-Music-API", "X-Yandex-Music-Client", "YandexMusicAndroid/24023621"};
    }

    private static String md5(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class YandexTrack extends AudioTrack {
        private final YandexMusicSourceManager source;

        YandexTrack(AudioTrackInfo info, YandexMusicSourceManager source) {
            super(info);
            this.source = source;
        }

        @Override
        public void process(Playback playback) throws Exception {
            String url = source.downloadUrl(trackInfo.identifier);
            new HttpAudioTrack(trackInfo, source, url, "audio/mpeg", -1,
                    () -> source.downloadUrl(trackInfo.identifier), null).process(playback);
        }

        @Override
        public AudioSourceManager getSourceManager() {
            return source;
        }

        @Override
        protected AudioTrack makeShallowClone() {
            return new YandexTrack(trackInfo, source);
        }
    }
}
