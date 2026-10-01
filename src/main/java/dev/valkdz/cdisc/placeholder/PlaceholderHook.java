package dev.valkdz.cdisc.placeholder;

import dev.valkdz.cdisc.Main;

public final class PlaceholderHook {

    private PlaceholderHook() {
    }

    // CDiscPlaceholders extends a PlaceholderAPI class, so it must not load unless the plugin is there.
    public static void register(Main plugin) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
        try {
            if (new CDiscPlaceholders(plugin).register()) {
                plugin.getLogger().info("PlaceholderAPI found: %cdisc_...% placeholders registered.");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[CDisc] Could not register PlaceholderAPI placeholders: " + t);
        }
    }
}
