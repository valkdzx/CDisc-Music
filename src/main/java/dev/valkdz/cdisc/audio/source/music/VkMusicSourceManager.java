package dev.valkdz.cdisc.audio.source.music;

import dev.valkdz.cdisc.audio.player.AudioItem;
import dev.valkdz.cdisc.audio.player.AudioPlaylist;
import dev.valkdz.cdisc.audio.player.AudioSourceManager;
import dev.valkdz.cdisc.audio.player.AudioTrack;
import dev.valkdz.cdisc.audio.player.AudioTrackInfo;
import dev.valkdz.cdisc.audio.player.Http;
import dev.valkdz.cdisc.audio.player.HttpAudioTrack;
import dev.valkdz.cdisc.audio.player.LoadException;
import dev.valkdz.cdisc.audio.player.Playback;
import dev.valkdz.cdisc.audio.source.hls.HlsVodAudioTrack;
import dev.valkdz.cdisc.util.Json;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VkMusicSourceManager implements AudioSourceManager {

    private static final String API = "https://api.vk.com/method/";
    private static final String VERSION = "5.199";
    private static final int SEARCH_LIMIT = 10;
    private static final int LIST_LIMIT = 300;

    private static final Pattern TRACK = Pattern.compile("^(?:https?://)?(?:www\\.|m\\.)?vk\\.(?:com|ru)/"
            + "audio(-?\\d+)_(-?\\d+)(?:_([^/?#]*))?(?:[/?#].*)?$");
    private static final Pattern PLAYLIST = Pattern.compile("^(?:https?://)?(?:www\\.|m\\.)?vk\\.(?:com|ru)/"
            + "music/(?:playlist|album)/(-?[A-Za-z\\d]+)_(-?[A-Za-z\\d]+)(?:_([^/?#]*))?(?:[/?#].*)?$");
    private static final Pattern AUDIOS_PLAYLIST = Pattern.compile("^(?:https?://)?(?:www\\.|m\\.)?vk\\.(?:com|ru)/"
            + "audios-?\\d+\\?.*z=audio_playlist(-?[A-Za-z\\d]+)_(-?[A-Za-z\\d]+)(?:_([^/?#&]*))?.*$");
    private static final Pattern ARTIST = Pattern.compile("^(?:https?://)?(?:www\\.|m\\.)?vk\\.(?:com|ru)/"
            + "artist/([^/?#]+)/?(?:[?#].*)?$");

    private final String token;

    public VkMusicSourceManager(String token) {
        this.token = token;
    }

    @Override
    public String getSourceName() {
        return "vkmusic";
    }

    @Override
    public AudioItem loadItem(String identifier) {
        try {
            if (identifier.startsWith("vksearch:")) return search(identifier.substring(9).trim());

            Matcher track = TRACK.matcher(identifier);
            if (track.matches()) return track(track.group(1) + "_" + track.group(2)
                    + (track.group(3) == null ? "" : "_" + track.group(3)));

            Matcher playlist = PLAYLIST.matcher(identifier);
            if (!playlist.matches()) playlist = AUDIOS_PLAYLIST.matcher(identifier);
            if (playlist.matches()) return playlist(playlist.group(1), playlist.group(2), playlist.group(3));

            Matcher artist = ARTIST.matcher(identifier);
            if (artist.matches()) return artist(artist.group(1));
            return null;
        } catch (IOException e) {
            throw new LoadException("Loading from VK Music failed: " + e.getMessage(), e);
        }
    }

    private AudioItem search(String query) throws IOException {
        if (query.isEmpty()) return AudioItem.NONE;
        List<AudioTrack> tracks = tracksOf(api("audio.search", "&q=" + encode(query) + "&count=" + SEARCH_LIMIT)
                .path("items"));
        return tracks.isEmpty() ? AudioItem.NONE : new AudioPlaylist("Vk Music Search: " + query, tracks, null, true);
    }

    private AudioItem track(String audios) throws IOException {
        Json found = api("audio.getById", "&audios=" + encode(audios));
        AudioTrack track = found.isArray() && !found.isEmpty() ? trackOf(found.path(0)) : null;
        return track == null ? AudioItem.NONE : track;
    }

    private AudioItem playlist(String owner, String id, String accessKey) throws IOException {
        String key = accessKey == null || accessKey.isEmpty() ? "" : "&access_key=" + encode(accessKey);
        List<AudioTrack> tracks = tracksOf(api("audio.get", "&owner_id=" + owner + "&album_id=" + id
                + "&count=" + LIST_LIMIT + key).path("items"));
        String title = api("audio.getPlaylistById", "&owner_id=" + owner + "&playlist_id=" + id + key)
                .path("title").asText("VK Music playlist");
        return tracks.isEmpty() ? AudioItem.NONE : new AudioPlaylist(title, tracks, null, false);
    }

    private AudioItem artist(String id) throws IOException {
        List<AudioTrack> tracks = tracksOf(api("audio.getAudiosByArtist", "&artist_id=" + encode(id) + "&count=10")
                .path("items"));
        String name = api("audio.getArtistById", "&artist_id=" + encode(id)).path("name").asText("Artist");
        return tracks.isEmpty() ? AudioItem.NONE : new AudioPlaylist(name + "'s Top Tracks", tracks, null, false);
    }

    private List<AudioTrack> tracksOf(Json items) {
        List<AudioTrack> tracks = new ArrayList<>();
        for (Json item : items) {
            AudioTrack track = trackOf(item);
            if (track != null) tracks.add(track);
        }
        return tracks;
    }

    private AudioTrack trackOf(Json item) {
        String owner = item.path("owner_id").asText(null);
        String id = item.path("id").asText(null);
        if (owner == null || id == null) return null;
        String accessKey = item.path("access_key").asText(null);
        String audios = owner + "_" + id + (accessKey == null || accessKey.isEmpty() ? "" : "_" + accessKey);

        String artwork = item.path("album").path("thumb").path("photo_600").asText(null);
        return new VkTrack(new AudioTrackInfo(
                item.path("title").asText("Unknown title"),
                item.path("artist").asText("Unknown artist"),
                item.path("duration").asLong(0) * 1000,
                audios, false, "https://vk.com/audio" + owner + "_" + id, artwork, null), this);
    }

    String streamUrl(String audios) throws IOException {
        Json found = api("audio.getById", "&audios=" + encode(audios));
        String url = found.path(0).path("url").asText("");
        if (url.isEmpty()) throw new LoadException("No download url found for track " + audios);
        return url;
    }

    private Json api(String method, String parameters) throws IOException {
        Json json = Http.json(API + method + "?v=" + VERSION + parameters + "&access_token=" + token);
        Json error = json.path("error");
        if (!error.isMissing() && !error.isNull()) {
            throw new LoadException("VK refused " + method + ": " + error.path("error_msg").asText("unknown error"));
        }
        return json.path("response");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class VkTrack extends AudioTrack {
        private final VkMusicSourceManager source;

        VkTrack(AudioTrackInfo info, VkMusicSourceManager source) {
            super(info);
            this.source = source;
        }

        // VK answers with either a plain MP3 or an HLS playlist of encrypted MPEG-TS.
        @Override
        public void process(Playback playback) throws Exception {
            String url = source.streamUrl(trackInfo.identifier);
            AudioTrack actual = url.contains(".m3u8")
                    ? new HlsVodAudioTrack(trackInfo, source, url, "audio/mpeg")
                    : new HttpAudioTrack(trackInfo, source, url, "audio/mpeg");
            actual.process(playback);
        }

        @Override
        public AudioSourceManager getSourceManager() {
            return source;
        }

        @Override
        protected AudioTrack makeShallowClone() {
            return new VkTrack(trackInfo, source);
        }
    }
}
