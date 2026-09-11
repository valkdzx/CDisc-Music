package dev.valkdz.cdisc.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HeadUtils {

    private static final Pattern URL_PATTERN = Pattern.compile("\"url\"\\s*:\\s*\"(.*?)\"");

    private HeadUtils() {
    }

    public static ItemStack createHead(String base64Texture) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (base64Texture == null || base64Texture.isBlank()) return head;

        String url = extractSkinUrl(base64Texture);
        if (url == null) return head;

        ItemMeta meta = head.getItemMeta();
        if (!(meta instanceof SkullMeta skullMeta)) return head;

        try {
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(URI.create(url).toURL());
            profile.setTextures(textures);
            skullMeta.setOwnerProfile(profile);
            head.setItemMeta(skullMeta);
        } catch (Exception e) {
            Bukkit.getLogger().warning("[CDisc] Failed to apply custom head texture: " + e.getMessage());
        }

        return head;
    }

    private static String extractSkinUrl(String base64Texture) {
        try {
            String json = new String(Base64.getDecoder().decode(base64Texture), StandardCharsets.UTF_8);
            Matcher matcher = URL_PATTERN.matcher(json);
            return matcher.find() ? matcher.group(1) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
