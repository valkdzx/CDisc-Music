package dev.valkdz.cdisc.lyrics.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

final class YouTubeChat extends ChatFeed {

    private static final String NEXT_URL = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false";
    private static final String CHAT_URL =
            "https://www.youtube.com/youtubei/v1/live_chat/get_live_chat?prettyPrint=false";

    private static final long MIN_POLL_MS = 1_000;
    private static final long MAX_POLL_MS = 3_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String videoId;
    private final HttpClient http;
    private final ScheduledExecutorService timer;
    private final Supplier<String> clientVersion;
    private final IntSupplier maxLength;

    private volatile String continuation;
    private int failures;

    YouTubeChat(String videoId, HttpClient http, ScheduledExecutorService timer,
                Supplier<String> clientVersion, IntSupplier maxLength) {
        this.videoId = videoId;
        this.http = http;
        this.timer = timer;
        this.clientVersion = clientVersion;
        this.maxLength = maxLength;
    }

    @Override
    void open() {
        timer.execute(this::poll);
    }

    @Override
    void shut() {
    }

    private void poll() {
        if (closed) return;

        ObjectNode body = MAPPER.createObjectNode();
        ObjectNode client = body.putObject("context").putObject("client");
        client.put("clientName", "WEB");
        client.put("clientVersion", clientVersion.get());
        client.put("hl", "en");

        String from = continuation;
        if (from == null) body.put("videoId", videoId);
        else body.put("continuation", from);

        HttpRequest request = HttpRequest.newBuilder(URI.create(from == null ? NEXT_URL : CHAT_URL))
                .timeout(Duration.ofSeconds(15))
                .header("content-type", "application/json")
                .header("origin", "https://www.youtube.com")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    long wait;
                    try {
                        if (error != null || response.statusCode() != 200) throw new IllegalStateException();
                        JsonNode root = MAPPER.readTree(response.body());
                        wait = from == null ? start(root) : read(root);
                        failures = 0;
                    } catch (Exception e) {
                        continuation = null;
                        wait = backoffMs(failures++);
                    }
                    if (!closed) timer.schedule(this::poll, wait, TimeUnit.MILLISECONDS);
                });
    }

    private long start(JsonNode root) {
        JsonNode chat = root.path("contents").path("twoColumnWatchNextResults")
                .path("conversationBar").path("liveChatRenderer");
        String first = chat.path("continuations").path(0)
                .path("reloadContinuationData").path("continuation").asText(null);

        // No chat: switched off, members only, or the stream is over. It may come back.
        if (first == null) throw new IllegalStateException();
        continuation = first;
        return 0;
    }

    private long read(JsonNode root) {
        JsonNode live = root.path("continuationContents").path("liveChatContinuation");

        for (JsonNode action : live.path("actions")) {
            JsonNode item = action.path("addChatItemAction").path("item");
            JsonNode message = item.has("liveChatTextMessageRenderer")
                    ? item.path("liveChatTextMessageRenderer")
                    : item.path("liveChatPaidMessageRenderer");
            if (message.isMissingNode()) continue;

            add(ChatMessage.of(message.path("authorName").path("simpleText").asText(""),
                    null, text(message.path("message").path("runs")), maxLength.getAsInt()));
        }

        JsonNode next = live.path("continuations").path(0);
        JsonNode data = next.has("invalidationContinuationData") ? next.path("invalidationContinuationData")
                : next.has("timedContinuationData") ? next.path("timedContinuationData")
                : next.path("reloadContinuationData");

        String following = data.path("continuation").asText(null);
        if (following == null) throw new IllegalStateException();
        continuation = following;

        long timeout = data.path("timeoutMs").asLong(MAX_POLL_MS);
        return Math.max(MIN_POLL_MS, Math.min(MAX_POLL_MS, timeout));
    }

    private static String text(JsonNode runs) {
        StringBuilder out = new StringBuilder();
        for (JsonNode run : runs) {
            if (run.has("text")) {
                out.append(run.path("text").asText(""));
                continue;
            }
            JsonNode emoji = run.path("emoji");
            // The font has no colour emoji, so the shortcut reads better than an empty box.
            String shortcut = emoji.path("shortcuts").path(0).asText("");
            out.append(shortcut.isEmpty() ? emoji.path("emojiId").asText("") : shortcut);
        }
        return out.toString();
    }
}
