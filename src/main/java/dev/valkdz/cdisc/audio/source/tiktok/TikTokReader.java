package dev.valkdz.cdisc.audio.source.tiktok;

import dev.valkdz.cdisc.audio.player.Http;
import dev.valkdz.cdisc.util.Json;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TikTokReader {

    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";

    private static final String PLAYER_API = "https://www.tiktok.com/player/api/v1/items?item_ids=";

    private static final Pattern HOST = Pattern.compile(
            "^https?://(?:[\\w-]+\\.)*tiktok\\.com(?:/|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_ID = Pattern.compile(
            "/(?:video|photo|v|embed(?:/v2)?)/(\\d{8,})");
    private static final Pattern SHORT_LINK = Pattern.compile(
            "^https?://(?:(?:vm|vt)\\.tiktok\\.com/\\w+|(?:www\\.|m\\.)?tiktok\\.com/t/\\w+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_ID = Pattern.compile("^(?:tt:)?(\\d{15,21})$");
    private static final Pattern EMBED_STATE = Pattern.compile(
            "<script[^>]*id=\"__FRONTITY_CONNECT_STATE__\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern HASHTAG = Pattern.compile("(?:^|\\s)#[^\\s#]+");

    public boolean matches(String input) {
        String s = input.trim();
        if (BARE_ID.matcher(s).matches()) return true;
        if (!HOST.matcher(s).find()) return false;
        return ITEM_ID.matcher(s).find() || SHORT_LINK.matcher(s).find();
    }

    public TikTokItem read(String input) throws IOException {
        String id = idOf(input.trim());

        IOException apiFailure;
        try {
            TikTokItem item = fromPlayerApi(id);
            if (item != null) return item;
            apiFailure = new IOException("player API returned no playable media");
        } catch (TikTokException e) {
            throw e;
        } catch (IOException e) {
            apiFailure = e;
        }

        try {
            return fromEmbed(id);
        } catch (IOException e) {
            e.addSuppressed(apiFailure);
            throw e;
        }
    }

    public String idOf(String input) throws IOException {
        Matcher bare = BARE_ID.matcher(input);
        if (bare.matches()) return bare.group(1);

        String location = input;
        for (int hop = 0; hop < 5 && location != null; hop++) {
            Matcher m = ITEM_ID.matcher(location);
            if (m.find()) return m.group(1);
            if (!HOST.matcher(location).find()) break;
            // Short links answer 301/302; following them lands on the watch page, behind a WAF bot check.
            location = Http.redirectOf(location, "User-Agent", USER_AGENT);
        }
        throw new TikTokException("Not a TikTok video link: " + input);
    }

    private TikTokItem fromPlayerApi(String id) throws IOException {
        Json root = Json.parse(Http.text(PLAYER_API + id, "User-Agent", USER_AGENT));

        Json item = root.get("items").index(0);
        if (item.isNull()) {
            String code = root.get("results").index(0).get("code").text();
            if ("nil_core_data".equals(code)) {
                throw new TikTokException("TikTok has no video " + id + " (deleted or private)");
            }
            throw new IOException("player API: status " + root.get("status_code").text()
                    + " " + root.get("status_msg").safeText() + (code == null ? "" : " / " + code));
        }

        Json video = item.get("video_info");
        String media = pickProfile(video);
        if (media == null) media = video.get("url_list").index(0).text();
        if (media == null) return null;

        Json author = item.get("author_info");
        return new TikTokItem(
                id,
                titleOf(item.get("desc").text(), item.get("music_info").get("title").text(), id),
                author.get("nickname").text(),
                author.get("unique_id").text(),
                video.get("meta").get("duration").asLong(0),
                video.get("cover").get("url_list").index(0).text(),
                media,
                "video/mp4",
                "player-api");
    }

    private static String pickProfile(Json video) {
        return video.get("profiles").values().stream()
                .filter(p -> !p.get("play_addr").get("url_list").index(0).isNull())
                .min(Comparator.comparingLong(p -> p.get("bitrate").asLong(Long.MAX_VALUE)))
                .map(p -> p.get("play_addr").get("url_list").index(0).text())
                .orElse(null);
    }

    private TikTokItem fromEmbed(String id) throws IOException {
        String route = "/embed/v2/" + id;
        HttpResponse<byte[]> response = Http.get("https://www.tiktok.com" + route, "User-Agent", USER_AGENT);
        int status = response.statusCode();
        String html = new String(response.body(), StandardCharsets.UTF_8);

        // A missing video answers 400 with the reason in the page state, so the status alone says too little.
        Matcher m = EMBED_STATE.matcher(html);
        if (!m.find()) throw new IOException("TikTok embed page carries no state (HTTP " + status + ")");

        Json data = Json.parse(m.group(1)).get("source").get("data").get(route);
        if (data.get("isError").asBoolean(false)) {
            throw new TikTokException("TikTok refused video " + id + ": error " + data.get("errorCode").safeText());
        }

        Json info = data.get("videoData").get("itemInfos");
        Json music = data.get("videoData").get("musicInfos");
        Json author = data.get("videoData").get("authorInfos");

        String media = info.get("video").get("urls").index(0).text();
        String mime = "video/mp4";
        if (media == null || media.isEmpty()) {
            media = music.get("playUrl").index(0).text();
            mime = "audio/mpeg";
        }
        if (media == null || media.isEmpty()) throw new IOException("TikTok embed page has no media address");

        return new TikTokItem(
                id,
                titleOf(info.get("text").text(), music.get("musicName").text(), id),
                author.get("nickName").text(),
                author.get("uniqueId").text(),
                info.get("video").get("videoMeta").get("duration").asLong(0) * 1000,
                info.get("covers").index(0).text(),
                media,
                mime,
                "embed");
    }

    static String titleOf(String desc, String musicTitle, String id) {
        String clean = desc == null ? "" : HASHTAG.matcher(desc).replaceAll(" ").replaceAll("\\s+", " ").trim();
        if (clean.length() > 100) clean = clean.substring(0, 99).trim() + "…";
        if (!clean.isEmpty()) return clean;
        if (musicTitle != null && !musicTitle.isBlank()) return musicTitle;
        return "TikTok " + id;
    }

    public static final class TikTokException extends IOException {
        public TikTokException(String message) {
            super(message);
        }
    }
}
