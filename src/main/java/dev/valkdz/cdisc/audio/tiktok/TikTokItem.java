package dev.valkdz.cdisc.audio.tiktok;

public record TikTokItem(
        String id,
        String title,
        String author,
        String handle,
        long durationMs,
        String artworkUrl,
        String mediaUrl,
        String mimeType,
        String via) {

    public String pageUrl() {
        return "https://www.tiktok.com/@" + (handle == null ? "" : handle) + "/video/" + id;
    }

    public boolean isMp3() {
        return mimeType != null && mimeType.startsWith("audio/mpeg");
    }
}
