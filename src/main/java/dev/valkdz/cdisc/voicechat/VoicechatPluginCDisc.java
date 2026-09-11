package dev.valkdz.cdisc.voicechat;

import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import dev.valkdz.cdisc.Main;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.Enumeration;

public class VoicechatPluginCDisc implements VoicechatPlugin {

    private final Main plugin;

    public VoicechatPluginCDisc(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getPluginId() {
        return "cdisc";
    }

    @Override
    public void initialize(VoicechatApi api) {}

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
            String id = plugin.cdiscConfig().getSvcCategoryId();
            String name = plugin.cdiscConfig().getSvcCategoryName();
            String desc = plugin.cdiscConfig().getSvcCategoryDescription();

            VolumeCategory category = event.getVoicechat().volumeCategoryBuilder()
                    .setId(id)
                    .setName(name)
                    .setDescription(desc)
                    .setIcon(getIcon())
                    .build();

            event.getVoicechat().registerVolumeCategory(category);
            plugin.onSimpleVoiceChatReady(event.getVoicechat());
        });
    }

    private int[] @Nullable [] getIcon() {
        try {
            Enumeration<URL> resources = VoicechatPluginCDisc.class.getClassLoader().getResources("cdisc_icon.png");
            while (resources.hasMoreElements()) {
                BufferedImage bufferedImage = ImageIO.read(resources.nextElement().openStream());
                if (bufferedImage.getWidth() != 16) {
                    continue;
                }
                if (bufferedImage.getHeight() != 16) {
                    continue;
                }
                int[][] image = new int[16][16];
                for (int x = 0; x < bufferedImage.getWidth(); x++) {
                    for (int y = 0; y < bufferedImage.getHeight(); y++) {
                        image[x][y] = bufferedImage.getRGB(x, y);
                    }
                }
                return image;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
