package dev.valkdz.cdisc.permission;

import java.util.Locale;

public enum Action {

    DISC_CREATE("disc.create", "true"),
    DISC_PLAYLIST("disc.playlist", "true"),
    DISC_CLEAR("disc.clear", "true"),
    DISC_CONVERT("disc.convert", "true"),
    DISC_DOWNLOAD("disc.download", "op"),

    PLAYER_GUI("player.gui", "true"),
    PLAYER_PLAY("player.play", "true"),
    PLAYER_PAUSE("player.pause", "true"),
    PLAYER_NEXT("player.next", "true"),
    PLAYER_PREVIOUS("player.previous", "true"),
    PLAYER_SEEK("player.seek", "true"),
    PLAYER_REPEAT("player.repeat", "true"),
    PLAYER_SHUFFLE("player.shuffle", "true"),
    PLAYER_VOLUME("player.volume", "true"),
    PLAYER_LOCAL_VOLUME("player.local-volume", "true"),
    PLAYER_BEACON("player.beacon", "true"),
    PLAYER_PORTABLE("player.portable", "true"),
    PLAYER_CHANNELS("player.channels", "true"),
    PLAYER_MESSAGES("player.messages", "true"),
    PLAYER_SCREEN("player.screen", "true"),
    PLAYER_INFO("player.info", "true"),

    QUEUE_OPEN("queue.open", "true"),
    QUEUE_ADD("queue.add", "true"),
    QUEUE_REMOVE("queue.remove", "true"),
    QUEUE_PLAY("queue.play", "true"),
    QUEUE_POLICY("queue.policy", "true"),

    LYRICS_TOGGLE("lyrics.toggle", "true"),
    LYRICS_LOOK("lyrics.look", "true"),
    LYRICS_PRESET("lyrics.preset", "true"),
    LYRICS_SHARE("lyrics.share", "true"),
    LYRICS_SCOREBOARD("lyrics.scoreboard", "true"),

    PAIR_CREATE("pair.create", "true"),
    PAIR_MANAGE("pair.manage", "true"),
    PAIR_SETTINGS("pair.settings", "true"),
    PAIR_DISSOLVE("pair.dissolve", "true"),
    PAIR_LIST("pair.list", "true"),

    ADMIN_RELOAD("admin.reload", "op"),
    ADMIN_DOCTOR("admin.doctor", "op"),
    ADMIN_YTSETUP("admin.ytsetup", "op");

    private final String path;
    private final String fallback;

    Action(String path, String fallback) {
        this.path = path;
        this.fallback = fallback;
    }

    public String path() {
        return path;
    }

    public String group() {
        return path.substring(0, path.indexOf('.'));
    }

    public String node() {
        return "cdisc." + path;
    }

    public String fallback() {
        return fallback;
    }

    public static Action byPath(String path) {
        String wanted = path == null ? "" : path.trim().toLowerCase(Locale.ROOT);
        for (Action action : values()) {
            if (action.path.equals(wanted)) return action;
        }
        return null;
    }
}
