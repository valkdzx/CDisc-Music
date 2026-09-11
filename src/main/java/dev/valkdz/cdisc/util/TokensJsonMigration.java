package dev.valkdz.cdisc.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.valkdz.cdisc.Main;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

final class TokensJsonMigration {

    static final String LEGACY_FILE_NAME = "tokens.json";
    private static final String BACKUP_NAME = "tokens-before-yml.json.bak";

    private static final String PSEUDO_KEY_PREFIX = "_";

    private TokensJsonMigration() {
    }

    static void run(Main plugin) {
        File json = new File(plugin.getDataFolder(), LEGACY_FILE_NAME);
        File yml = new File(plugin.getDataFolder(), Tokens.FILE_NAME);

        if (!json.exists() || yml.exists()) return;

        Logger log = plugin.getLogger();
        log.info("Converting " + LEGACY_FILE_NAME + " to " + Tokens.FILE_NAME + ".");

        Map<String, Object> root;
        try {
            root = new ObjectMapper().readValue(json,
                    new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            log.severe("Couldn't read " + LEGACY_FILE_NAME + ": " + e.getMessage()
                    + " — check it for a missing comma or quote. Nothing has been changed;"
                    + " CDisc will try again next start, and runs with no credentials"
                    + " until then.");
            return;
        }

        plugin.saveResource(Tokens.FILE_NAME, false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(yml);

        int carried = copyInto(root, cfg);

        try {
            cfg.save(yml);
        } catch (Exception e) {
            log.severe("Couldn't write " + Tokens.FILE_NAME + ": " + e.getMessage()
                    + " — " + LEGACY_FILE_NAME + " is untouched, so nothing is lost.");
            return;
        }

        if (!backUp(json, new File(plugin.getDataFolder(), BACKUP_NAME), log)) return;

        log.info("Moved " + carried + " credential(s) into " + Tokens.FILE_NAME
                + ". The old file is kept as " + BACKUP_NAME + " — it still holds those"
                + " credentials, so delete it once you've checked everything still works.");
    }

    private static int copyInto(Map<String, Object> root, YamlConfiguration cfg) {
        int carried = 0;

        for (Map.Entry<String, Object> entry : root.entrySet()) {
            String section = entry.getKey();
            if (isPseudoKey(section)) continue;

            if (!(entry.getValue() instanceof Map<?, ?> values)) {

                cfg.set(section, entry.getValue());
                if (isSet(entry.getValue())) carried++;
                continue;
            }

            for (Map.Entry<?, ?> value : values.entrySet()) {
                String key = String.valueOf(value.getKey());
                if (isPseudoKey(key)) continue;

                cfg.set(section + "." + key, value.getValue());
                if (isSet(value.getValue())) carried++;
            }
        }

        return carried;
    }

    private static boolean isPseudoKey(String key) {
        return key.startsWith(PSEUDO_KEY_PREFIX);
    }

    private static boolean isSet(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return false;
        String text = String.valueOf(value).trim();
        return !text.isEmpty() && !text.equalsIgnoreCase("null") && !text.equalsIgnoreCase("false");
    }

    private static boolean backUp(File json, File backup, Logger log) {
        try {
            Files.move(json.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {

            log.warning("Converted " + Tokens.FILE_NAME + " successfully, but couldn't rename "
                    + LEGACY_FILE_NAME + ": " + e.getMessage()
                    + " — delete it by hand; it is no longer read, and it still holds"
                    + " your credentials.");
            return false;
        }
    }
}
