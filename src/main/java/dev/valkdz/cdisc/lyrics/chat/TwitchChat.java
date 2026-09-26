package dev.valkdz.cdisc.lyrics.chat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

final class TwitchChat extends ChatFeed {

    private static final URI SERVER = URI.create("wss://irc-ws.chat.twitch.tv:443");

    private static final String[] DEFAULT_COLORS = {
            "#FF0000", "#0000FF", "#008000", "#B22222", "#FF7F50",
            "#9ACD32", "#FF4500", "#2E8B57", "#DAA520", "#D2691E",
            "#5F9EA0", "#1E90FF", "#FF69B4", "#8A2BE2", "#00FF7F"
    };

    private final String channel;
    private final HttpClient http;
    private final ScheduledExecutorService timer;
    private final IntSupplier maxLength;

    private volatile WebSocket socket;
    private int failures;

    TwitchChat(String channel, HttpClient http, ScheduledExecutorService timer, IntSupplier maxLength) {
        this.channel = channel;
        this.http = http;
        this.timer = timer;
        this.maxLength = maxLength;
    }

    @Override
    void open() {
        if (closed) return;

        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(SERVER, new Listener())
                .whenComplete((ws, error) -> {
                    if (error != null) retry();
                });
    }

    @Override
    void shut() {
        WebSocket open = socket;
        socket = null;
        if (open != null) open.abort();
    }

    private void retry() {
        socket = null;
        if (closed) return;
        timer.schedule(this::open, backoffMs(failures++), TimeUnit.MILLISECONDS);
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            if (closed) {
                ws.abort();
                return;
            }
            socket = ws;

            // Twitch reads one command per frame, and a sendText before the last one completes throws.
            ws.sendText("CAP REQ :twitch.tv/tags", true)
                    .thenCompose(w -> w.sendText("NICK justinfan"
                            + ThreadLocalRandom.current().nextInt(10000, 99999), true))
                    .thenCompose(w -> w.sendText("JOIN #" + channel, true));
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String frame = partial.toString();
                partial.setLength(0);
                for (String line : frame.split("\r\n")) {
                    if (!line.isEmpty()) handle(ws, line);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            if (socket == ws) retry();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (socket == ws) retry();
        }
    }

    private void handle(WebSocket ws, String line) {
        Map<String, String> tags = Map.of();
        String rest = line;

        if (rest.startsWith("@")) {
            int space = rest.indexOf(' ');
            if (space < 0) return;
            tags = tags(rest.substring(1, space));
            rest = rest.substring(space + 1);
        }

        String login = null;
        if (rest.startsWith(":")) {
            int space = rest.indexOf(' ');
            if (space < 0) return;
            String prefix = rest.substring(1, space);
            int bang = prefix.indexOf('!');
            login = bang > 0 ? prefix.substring(0, bang) : null;
            rest = rest.substring(space + 1);
        }

        if (rest.startsWith("PING")) {
            ws.sendText("PONG" + rest.substring(4), true);
            return;
        }
        if (rest.startsWith("RECONNECT")) {
            ws.abort();
            if (socket == ws) retry();
            return;
        }
        if (rest.startsWith("JOIN")) {
            failures = 0;
            return;
        }
        if (!rest.startsWith("PRIVMSG") || login == null) return;

        int text = rest.indexOf(" :");
        if (text < 0) return;
        String message = rest.substring(text + 2);

        if (message.startsWith("\u0001ACTION ")) {
            message = message.substring(8).replace("\u0001", "");
        }

        String name = tags.getOrDefault("display-name", "");
        if (name.isBlank()) name = login;

        add(ChatMessage.of(name, colorOf(tags.get("color"), login), message, maxLength.getAsInt()));
    }

    private static Map<String, String> tags(String raw) {
        Map<String, String> out = new HashMap<>();
        for (String pair : raw.split(";")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            out.put(pair.substring(0, eq), unescape(pair.substring(eq + 1)));
        }
        return out;
    }

    private static String unescape(String value) {
        if (value.indexOf('\\') < 0) return value;

        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                if (c != '\\') out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            out.append(switch (next) {
                case 's' -> ' ';
                case ':' -> ';';
                case 'r' -> '\r';
                case 'n' -> '\n';
                default -> next;
            });
        }
        return out.toString();
    }

    static String colorOf(String tag, String login) {
        String hex = tag != null && tag.matches("#[0-9A-Fa-f]{6}") ? tag : defaultColor(login);
        return legacy(hex.substring(1).toLowerCase(Locale.ROOT));
    }

    // Twitch's web chat colours a user who never picked one by this sum, so both sides agree.
    private static String defaultColor(String login) {
        if (login == null || login.isEmpty()) return DEFAULT_COLORS[0];
        int sum = login.charAt(0) + login.charAt(login.length() - 1);
        return DEFAULT_COLORS[sum % DEFAULT_COLORS.length];
    }

    private static String legacy(String digits) {
        StringBuilder out = new StringBuilder(14).append('§').append('x');
        for (int i = 0; i < 6; i++) {
            out.append('§').append(digits.charAt(i));
        }
        return out.toString();
    }
}
