package dev.valkdz.cdisc.voice.plasmovoice;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.voice.VoiceBackend;

public final class PlasmoVoiceActivator {

    private final Main plugin;

    private PlasmoVoiceActivator(Main plugin) {
        this.plugin = plugin;
    }

    public static Object tryLoad(Main plugin) {
        try {
            Class.forName("su.plo.voice.api.server.PlasmoVoiceServer", false,
                    PlasmoVoiceActivator.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }

        CDiscPlasmoAddon addon = new CDiscPlasmoAddon("cdisc_music", plugin);
        su.plo.voice.api.server.PlasmoVoiceServer.getAddonsLoader().load(addon);
        return addon;
    }

    public static VoiceBackend createBackend(Main plugin) {
        CDiscPlasmoAddon addon = (CDiscPlasmoAddon) plugin.getPlasmoAddon();
        return new PlasmoVoiceBackend(addon);
    }
}
