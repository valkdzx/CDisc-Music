package dev.valkdz.cdisc.voice;

import dev.valkdz.cdisc.Main;
import org.bukkit.Bukkit;

public class VoiceBackendManager {

    private static final String SVC_PLUGIN_NAME = "voicechat";

    private final Main plugin;
    private VoiceBackend backend;

    private MultiVoiceBackend multi;

    private String mode = "none";

    public VoiceBackendManager(Main plugin) {
        this.plugin = plugin;
    }

    public boolean setup() {
        String configured = plugin.cdiscConfig().getVoiceBackend();

        boolean svcInstalled = isPluginPresent(SVC_PLUGIN_NAME);
        boolean plasmoInstalled = plugin.getPlasmoAddon() != null;

        if (!svcInstalled && !plasmoInstalled) {
            plugin.getLogger().severe("Neither Simple Voice Chat nor Plasmo Voice is installed.");
            plugin.getLogger().severe("CDisc Music requires one of them to broadcast jukebox audio. Disabling.");
            plugin.disablePlugin();
            return false;
        }

        String chosen = configured;
        if (chosen.equals("auto")) {

            if (svcInstalled && plasmoInstalled) {
                chosen = "multi";
            } else {
                chosen = svcInstalled ? "simplevoicechat" : "plasmovoice";
            }
        }

        switch (chosen) {
            case "simplevoicechat" -> {
                if (!svcInstalled) {
                    plugin.getLogger().severe("config.yml requests 'simplevoicechat', but it is not installed. Disabling.");
                    plugin.disablePlugin();
                    return false;
                }
                mode = "simplevoicechat";

                dev.valkdz.cdisc.voice.simplevoicechat.SimpleVoiceChatActivator.register(plugin);
                return true;
            }
            case "plasmovoice" -> {
                if (!plasmoInstalled) {
                    plugin.getLogger().severe("config.yml requests 'plasmovoice', but it is not installed. Disabling.");
                    plugin.disablePlugin();
                    return false;
                }
                VoiceBackend plasmo = createPlasmo();
                if (plasmo == null) {
                    plugin.disablePlugin();
                    return false;
                }
                mode = "plasmovoice";
                this.backend = plasmo;
                plugin.getLogger().info("CDisc Music is broadcasting through Plasmo Voice.");
                return true;
            }
            case "multi", "all", "both" -> {
                if (!svcInstalled && !plasmoInstalled) {
                    plugin.getLogger().severe("config.yml requests 'multi', but no voice plugin is installed. Disabling.");
                    plugin.disablePlugin();
                    return false;
                }

                MultiVoiceBackend composite = new MultiVoiceBackend();
                this.multi = composite;
                this.backend = composite;
                mode = "multi";

                if (plasmoInstalled) {
                    VoiceBackend plasmo = createPlasmo();

                    if (plasmo == null && !svcInstalled) {
                        plugin.disablePlugin();
                        return false;
                    }
                    composite.addBackend(plasmo);
                }

                if (svcInstalled) {

                    dev.valkdz.cdisc.voice.simplevoicechat.SimpleVoiceChatActivator.register(plugin);
                }

                composite.startListenerAssignment(plugin);

                plugin.getLogger().info("CDisc Music is broadcasting through every installed voice plugin, "
                        + "so clients of either mod can hear it. Set 'voice-backend' in config.yml to "
                        + "'simplevoicechat' or 'plasmovoice' to use just one.");
                return true;
            }
            default -> {
                plugin.getLogger().severe("Unknown voice-backend '" + configured
                        + "' in config.yml. Expected 'auto', 'multi', 'simplevoicechat', or 'plasmovoice'. Disabling.");
                plugin.disablePlugin();
                return false;
            }
        }
    }

    private VoiceBackend createPlasmo() {
        try {
            return dev.valkdz.cdisc.voice.plasmovoice.PlasmoVoiceActivator.createBackend(plugin);
        } catch (Throwable t) {
            plugin.getLogger().severe("Plasmo Voice is installed, but failed to initialize properly "
                    + "(it may not support this server build). CDisc Music cannot broadcast audio "
                    + "through it. Error: " + t);
            return null;
        }
    }

    public void activateSimpleVoiceChat(Object api, String categoryId) {
        VoiceBackend svc =
                dev.valkdz.cdisc.voice.simplevoicechat.SimpleVoiceChatActivator.createBackend(api, categoryId);

        if (multi != null) {

            multi.addBackend(svc);
            plugin.getLogger().info("Simple Voice Chat has joined the broadcast.");
            return;
        }

        this.backend = svc;
        plugin.getLogger().info("CDisc Music is broadcasting through Simple Voice Chat.");
    }

    public VoiceBackend getBackend() {
        return backend;
    }

    public String getMode() {
        return mode;
    }

    private boolean isPluginPresent(String name) {
        org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin(name);
        return p != null && p.isEnabled();
    }
}
