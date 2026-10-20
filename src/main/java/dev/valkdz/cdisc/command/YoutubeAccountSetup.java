package dev.valkdz.cdisc.command;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.audio.source.youtube.YoutubeAccount;
import dev.valkdz.cdisc.util.Chat;
import dev.valkdz.cdisc.util.Tasks;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.concurrent.atomic.AtomicReference;

public final class YoutubeAccountSetup {

    private static final YoutubeAccount.DeviceCode ASKING = new YoutubeAccount.DeviceCode("", "", 0);

    private final Main plugin;
    private final AtomicReference<YoutubeAccount.DeviceCode> pending = new AtomicReference<>();

    public YoutubeAccountSetup(Main plugin) {
        this.plugin = plugin;
    }

    public void start(CommandSender sender) {
        YoutubeAccount.DeviceCode waiting = pending.get();
        if (waiting != null || !pending.compareAndSet(null, ASKING)) {
            YoutubeAccount.DeviceCode shown = pending.get();
            sender.sendMessage("§e" + (shown == null || shown == ASKING
                    ? message(sender, "cdisc.oauth_starting")
                    : message(sender, "cdisc.oauth_busy", shown.userCode(), shown.verificationUrl())));
            return;
        }

        sender.sendMessage("§7" + message(sender, "cdisc.oauth_starting"));
        YoutubeAccount account = plugin.getAudioPlayerManager().getTrackLoader().youtubeAccount();

        Tasks.async(plugin, () -> {
            try {
                String token = account.link(code -> {
                    pending.set(code);
                    reply(sender, () -> showCode(sender, code));
                });
                plugin.cdiscConfig().setYoutubeRefreshToken(token);
                account.warmUp();
                reply(sender, () -> {
                    sender.sendMessage("§a" + message(sender, "cdisc.oauth_linked"));
                    if (!plugin.cdiscConfig().isYoutubeAccountEnabled()) {
                        sender.sendMessage("§e" + message(sender, "cdisc.oauth_switched_off"));
                    }
                });
            } catch (Exception e) {
                reply(sender, () -> sender.sendMessage("§c" + message(sender, "cdisc.oauth_failed",
                        String.valueOf(e.getMessage()))));
            } finally {
                pending.set(null);
            }
        });
    }

    private void showCode(CommandSender sender, YoutubeAccount.DeviceCode code) {
        String line = "§a" + message(sender, "cdisc.oauth_code",
                code.verificationUrl(), code.userCode(), Math.max(1, code.expiresInSeconds() / 60));
        if (sender instanceof Player player) {
            player.spigot().sendMessage(Chat.link(line, code.verificationUrl(), "§7" + code.verificationUrl()));
        } else {
            sender.sendMessage(line);
        }
        sender.sendMessage("§e" + message(sender, "cdisc.oauth_spare"));
    }

    private void reply(CommandSender sender, Runnable work) {
        if (sender instanceof Player player) {
            Tasks.entity(plugin, player, work);
        } else {
            Tasks.global(plugin, work);
        }
    }

    private String message(CommandSender sender, String path, Object... args) {
        return plugin.getMessageManager().get(sender instanceof Player p ? p : null, path, args);
    }
}
