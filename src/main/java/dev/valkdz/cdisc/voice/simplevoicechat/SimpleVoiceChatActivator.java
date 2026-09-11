package dev.valkdz.cdisc.voice.simplevoicechat;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.voice.VoiceBackend;
import org.bukkit.Bukkit;

public final class SimpleVoiceChatActivator {

    private SimpleVoiceChatActivator() {
    }

    public static void register(Main plugin) {
        de.maxhenkel.voicechat.api.BukkitVoicechatService service =
                Bukkit.getServicesManager().load(de.maxhenkel.voicechat.api.BukkitVoicechatService.class);
        if (service == null) {
            plugin.getLogger().warning("Simple Voice Chat is installed, but no service was found!");
            return;
        }
        service.registerPlugin(new dev.valkdz.cdisc.voicechat.VoicechatPluginCDisc(plugin));
    }

    public static VoiceBackend createBackend(Object api, String categoryId) {
        return new SimpleVoiceChatBackend((de.maxhenkel.voicechat.api.VoicechatServerApi) api, categoryId);
    }
}
