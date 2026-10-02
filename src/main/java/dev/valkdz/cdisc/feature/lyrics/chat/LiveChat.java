package dev.valkdz.cdisc.feature.lyrics.chat;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.NetProxy;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LiveChat {

    private static final long IDLE_MS = 30_000;

    private static final Pattern TWITCH = Pattern.compile(
            "^https?://(?:www\\.|go\\.|m\\.)?twitch\\.tv/([A-Za-z0-9_]{2,25})/?(?:\\?.*)?$");

    private static final Pattern YOUTUBE = Pattern.compile(
            "^https?://(?:(?:www\\.|m\\.|music\\.)?youtube\\.com/(?:watch\\?(?:.*&)?v=|live/)|youtu\\.be/)"
                    + "([A-Za-z0-9_-]{11})");

    private final Main plugin;
    private final HttpClient http;
    private final ScheduledExecutorService timer;
    private final Map<String, ChatFeed> feeds = new ConcurrentHashMap<>();

    public LiveChat(Main plugin) {
        this.plugin = plugin;
        this.http = NetProxy.apply(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))).build();
        this.timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CDisc-LiveChat");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(this::closeIdle, 10, 10, TimeUnit.SECONDS);
    }

    public boolean supports(String uri) {
        return plugin.cdiscConfig().isLiveChatEnabled() && key(uri) != null;
    }

    public ChatFeed.Snapshot read(String uri) {
        if (!plugin.cdiscConfig().isLiveChatEnabled()) return null;

        String key = key(uri);
        if (key == null) return null;

        ChatFeed feed = feeds.computeIfAbsent(key, this::open);
        feed.wantedAt = System.currentTimeMillis();
        return feed.snapshot();
    }

    private ChatFeed open(String key) {
        IntSupplier maxLength = () -> plugin.cdiscConfig().getLiveChatMaxLength();
        String id = key.substring(key.indexOf(':') + 1);

        ChatFeed feed = key.startsWith("twitch:")
                ? new TwitchChat(id, http, timer, maxLength)
                : new YouTubeChat(id, http, timer,
                        () -> plugin.cdiscConfig().getYoutubeWebClientVersion(), maxLength);
        feed.open();
        return feed;
    }

    static String key(String uri) {
        if (uri == null) return null;

        Matcher twitch = TWITCH.matcher(uri.trim());
        if (twitch.find()) return "twitch:" + twitch.group(1).toLowerCase(Locale.ROOT);

        Matcher youtube = YOUTUBE.matcher(uri.trim());
        return youtube.find() ? "youtube:" + youtube.group(1) : null;
    }

    private void closeIdle() {
        long stale = System.currentTimeMillis() - IDLE_MS;
        feeds.entrySet().removeIf(entry -> {
            if (entry.getValue().wantedAt > stale) return false;
            entry.getValue().close();
            return true;
        });
    }

    public void shutdown() {
        feeds.values().forEach(ChatFeed::close);
        feeds.clear();
        timer.shutdownNow();
    }
}
