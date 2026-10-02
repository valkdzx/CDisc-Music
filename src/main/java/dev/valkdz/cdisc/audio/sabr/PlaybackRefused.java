package dev.valkdz.cdisc.audio.sabr;

import java.io.IOException;
import java.util.Locale;

public final class PlaybackRefused extends IOException {

    private final String videoId;
    private final String status;
    private final String reason;
    private final String subreason;
    private final String title;
    private final String author;

    public PlaybackRefused(String videoId, InnerTubePlayer.PlayerResponse response) {
        super(describe(videoId, response));
        this.videoId = videoId;
        this.status = response.playabilityStatus();
        this.reason = response.playabilityReason();
        this.subreason = response.playabilitySubreason();
        this.title = response.title();
        this.author = response.author();
    }

    private static String describe(String videoId, InnerTubePlayer.PlayerResponse response) {
        StringBuilder said = new StringBuilder("YouTube will not play ")
                .append(videoId).append(": ").append(response.playabilityStatus());

        if (notBlank(response.playabilityReason())) {
            said.append(" — ").append(response.playabilityReason());
        }
        if (notBlank(response.playabilitySubreason())) {
            said.append(" (").append(response.playabilitySubreason()).append(')');
        }
        return said.toString();
    }

    public String videoId() {
        return videoId;
    }

    public String status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String author() {
        return author;
    }

    public boolean botCheck() {
        return mentionsBot(reason) || mentionsBot(subreason);
    }

    private static boolean mentionsBot(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains("not a bot");
    }

    public boolean regional() {
        return mentionsRegion(reason) || mentionsRegion(subreason);
    }

    private static boolean mentionsRegion(String text) {
        if (text == null) return false;

        String said = text.toLowerCase(Locale.ROOT);
        return said.contains("in your country")
                || said.contains("in your location")
                || said.contains("in your region")
                || said.contains("not available in the country")
                || said.contains("country you are in")
                || (said.contains("blocked it") && said.contains("copyright"));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
