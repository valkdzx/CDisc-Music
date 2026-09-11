package dev.valkdz.cdisc.youtube;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.LavaPlayerManager;
import dev.valkdz.cdisc.util.MessageManager;
import dev.valkdz.cdisc.util.Chat;
import net.md_5.bungee.api.chat.TextComponent;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.LogEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YouTubeOAuthSetup implements Listener {

    private final Main plugin;
    private final MessageManager msg;
    private final Map<UUID, String> setupPlayers = new ConcurrentHashMap<>();
    private AbstractAppender logAppender;

    private static final String DEVICE_URL = "https://www.google.com/device";

    private static final Pattern CODE_PATTERN = Pattern.compile("enter code ([A-Z0-9\\-]+)");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\((1//[a-zA-Z0-9\\-_]+)\\)");

    public YouTubeOAuthSetup(Main plugin) {
        this.plugin = plugin;
        this.msg = plugin.getMessageManager();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        setupLog4jAppender();
    }

    private void setupLog4jAppender() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        LoggerConfig loggerConfig = context.getConfiguration()
                .getLoggerConfig("dev.lavalink.youtube.http.YoutubeOauth2Handler");

        logAppender = new AbstractAppender("YouTubeOAuthAppender", null, null, true, null) {
            @Override
            public void append(LogEvent event) {
                if (setupPlayers.isEmpty()) return;

                String message = event.getMessage().getFormattedMessage();

                Matcher codeMatcher = CODE_PATTERN.matcher(message);
                if (codeMatcher.find()) {
                    String code = codeMatcher.group(1);
                    setupPlayers.keySet().forEach(uuid ->
                            Bukkit.getScheduler().runTask(plugin, () -> sendInstructions(uuid, code))
                    );
                    return;
                }

                Matcher tokenMatcher = TOKEN_PATTERN.matcher(message);
                if (tokenMatcher.find()) {
                    String token = tokenMatcher.group(1);
                    setupPlayers.keySet().forEach(uuid ->
                            Bukkit.getScheduler().runTask(plugin, () -> finishSetup(uuid, token))
                    );
                }
            }
        };

        logAppender.start();
        loggerConfig.addAppender(logAppender, null, null);
        context.updateLoggers();
    }

    public void startSetup(Player player) {
        dev.valkdz.cdisc.util.Config config = plugin.cdiscConfig();

        String status = config.getYoutubeOauthSetupDone();
        if (!status.equals("false")) {
            if (status.equals("in-process")) {
                player.sendMessage("§c" + msg.get(player, "youtube.oauth.in_process"));
            } else {
                player.sendMessage("§c" + msg.get(player, "youtube.oauth.already_done"));
            }
            return;
        }

        LavaPlayerManager lpm = plugin.getAudioPlayerManager();
        YoutubeAudioSourceManager ytManager = lpm.getYoutubeSourceManager();

        if (ytManager == null) {
            player.sendMessage("§c" + msg.get(player, "youtube.oauth.not_ready"));
            return;
        }

        config.setYoutubeOauthSetupDone("in-process");
        config.save();

        setupPlayers.put(player.getUniqueId(), "");

        ytManager.useOauth2(null, false);

        player.sendMessage("§e" + msg.get(player, "youtube.oauth.starting"));
        player.sendMessage("§e" + msg.get(player, "youtube.oauth.initiated"));
    }

    private void sendInstructions(UUID playerId, String code) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return;

        TextComponent message = new TextComponent("");
        message.addExtra(Chat.block(text(player, "header")));
        message.addExtra("\n");
        message.addExtra(Chat.block(text(player, "step_link")));
        message.addExtra(Chat.link(text(player, "link"), DEVICE_URL, text(player, "link_hover")));
        message.addExtra("\n");
        message.addExtra(Chat.block(text(player, "step_code")));
        message.addExtra(Chat.copyable(text(player, "code", code), code, text(player, "code_hover")));
        message.addExtra(Chat.block(text(player, "code_hint")));
        message.addExtra("\n");
        message.addExtra(Chat.block(text(player, "step_warning")));
        message.addExtra("\n\n");
        message.addExtra(Chat.block(text(player, "waiting")));
        message.addExtra("\n");
        message.addExtra(Chat.block(text(player, "footer")));

        Chat.send(player, message);
    }

    private String text(Player player, String key, Object... args) {
        return msg.get(player, "youtube.oauth.instructions." + key, args);
    }

    private void finishSetup(UUID playerId, String token) {
        Player player = Bukkit.getPlayer(playerId);
        dev.valkdz.cdisc.util.Config config = plugin.cdiscConfig();

        config.setYoutubeOauthRefreshToken(token);
        config.setYoutubeOauthSetupDone("true");
        config.setYoutubeOauthEnabled(true);
        config.save();

        if (player != null && player.isOnline()) {
            player.sendMessage("§a" + msg.get(player, "youtube.oauth.success"));
        }

        setupPlayers.remove(playerId);

        YoutubeAudioSourceManager ytManager = plugin.getAudioPlayerManager().getYoutubeSourceManager();
        if (ytManager != null) {
            ytManager.useOauth2(token, true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        if (setupPlayers.containsKey(uuid)) {
            dev.valkdz.cdisc.util.Config config = plugin.cdiscConfig();
            config.setYoutubeOauthSetupDone("false");
            config.save();
            setupPlayers.remove(uuid);
        }
    }

    public void shutdown() {
        if (logAppender != null) {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            LoggerConfig loggerConfig = context.getConfiguration()
                    .getLoggerConfig("dev.lavalink.youtube.http.YoutubeOauth2Handler");
            loggerConfig.removeAppender("YouTubeOAuthAppender");
            logAppender.stop();
            context.updateLoggers();
        }
    }
}
