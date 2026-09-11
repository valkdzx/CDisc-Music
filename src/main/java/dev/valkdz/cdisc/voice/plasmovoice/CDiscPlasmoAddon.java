package dev.valkdz.cdisc.voice.plasmovoice;

import dev.valkdz.cdisc.Main;
import dev.valkdz.cdisc.util.Config;
import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.InjectPlasmoVoice;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.line.ServerSourceLine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

@Addon(
        id = "cdisc-music",
        name = "CDisc Music",
        version = "1.0.0",
        authors = {"valkdz"}
)
public final class CDiscPlasmoAddon implements AddonInitializer {

    @InjectPlasmoVoice
    private PlasmoVoiceServer voiceServer;
    private final Main plugin;

    private ServerSourceLine sourceLine;
    private final String lineName;

    public CDiscPlasmoAddon(String lineName, Main plugin) {
        this.lineName = lineName;
        this.plugin = plugin;
    }

    @Override
    public void onAddonInitialize() {
        Config config = plugin.cdiscConfig();
        try (InputStream icon = getClass().getClassLoader().getResourceAsStream("cdisc_icon.png")) {
            if (icon != null) {
                this.sourceLine = voiceServer.getSourceLineManager().createBuilder(
                        this,
                        lineName,
                        config.getPlasmoSourcelineName(),
                        icon,
                        10
                ).build();
            } else {
                Logger.getLogger("CDisc").warning(
                        "cdisc_icon.png not found on classpath, falling back to vanilla music disc icon");
                this.sourceLine = voiceServer.getSourceLineManager().createBuilder(
                        this,
                        lineName,
                        "cdisc.music.sourceline",
                        "minecraft:textures/item/music_disc_13.png",
                        10
                ).build();
            }
        } catch (IOException e) {
            Logger.getLogger("CDisc").log(Level.WARNING,
                    "Failed to load cdisc_icon.png for the Plasmo Voice source line", e);
        }
    }

    public PlasmoVoiceServer getVoiceServer() {return voiceServer;}

    public ServerSourceLine getSourceLine() {return sourceLine;}
}
