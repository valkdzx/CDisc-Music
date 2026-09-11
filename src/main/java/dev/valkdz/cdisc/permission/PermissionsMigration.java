package dev.valkdz.cdisc.permission;

import dev.valkdz.cdisc.Main;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.Map;

public final class PermissionsMigration {

    private static final String OLD_SECTION = "defaults";

    private PermissionsMigration() {
    }

    public static void run(Main plugin) {
        File file = new File(plugin.getDataFolder(), PermissionsConfig.FILE_NAME);
        if (!file.exists()) return;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection old = cfg.getConfigurationSection(OLD_SECTION);
        if (old == null) return;

        int moved = 0;
        for (Map.Entry<String, List<Action>> legacy : PermissionsConfig.legacyNodes().entrySet()) {
            String value = old.getString(legacy.getKey());
            if (value == null || value.isBlank()) continue;

            for (Action action : legacy.getValue()) {
                cfg.set("actions." + action.path(), value.trim());
            }
            moved++;
        }

        cfg.set(OLD_SECTION, null);
        try {
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not rewrite " + PermissionsConfig.FILE_NAME
                    + " onto the per-action rules: " + e.getMessage());
            return;
        }

        plugin.getLogger().info("permissions.yml now holds a rule per action; " + moved
                + " setting(s) from the old defaults block were carried onto them.");
    }
}
