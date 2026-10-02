package dev.valkdz.cdisc.config;

import dev.valkdz.cdisc.Main;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class MessageManager {

    private static final String[] BUNDLED =
            {"ar_SA", "de_DE", "en_US", "es_ES", "he_IL", "ru_RU", "uk_UA"};

    private static final String FALLBACK = "en_US";

    private static final String NO_AUTHOR = "\u0000";

    private static final Pattern AFTER_NO_AUTHOR = Pattern.compile("\u0000\\s*(?:\u00a7.)*\\s*[-\u2013\u2014]\\s*");

    private static final Pattern BEFORE_NO_AUTHOR = Pattern.compile("\\s*(?:\u00a7.)*\\s*[-\u2013\u2014]\\s*(?:\u00a7.)*\u0000");

    private static final String AUTO = "auto";

    private static final String NAME_PATTERN = "[A-Za-z0-9_-]{2,32}";

    private final Main plugin;
    private final Map<String, FileConfiguration> locales = new ConcurrentHashMap<>();

    private String forced;

    public MessageManager(Main plugin) {
        this.plugin = plugin;
        load();
    }

    private void load() {
        for (String locale : BUNDLED) {
            String resourcePath = "languages/" + locale + ".yml";
            File file = new File(plugin.getDataFolder(), resourcePath);

            if (!file.exists()) {
                plugin.saveResource(resourcePath, false);
            }

            FileConfiguration config = YamlConfiguration.loadConfiguration(file);

            InputStream defaultStream = plugin.getResource(resourcePath);
            if (defaultStream != null) {
                Reader reader = new InputStreamReader(defaultStream, StandardCharsets.UTF_8);
                config.setDefaults(YamlConfiguration.loadConfiguration(reader));
            }

            locales.put(locale, config);
        }

        forced = resolveForced();
    }

    public void reload() {
        locales.clear();
        load();
    }

    private String resolveForced() {
        String configured = plugin.cdiscConfig().getLanguage();
        if (configured.isEmpty() || configured.equalsIgnoreCase(AUTO)) return null;

        String bundled = matchBundled(configured);
        if (bundled != null) return bundled;

        FileConfiguration own = loadOwn(configured);
        if (own != null) {
            locales.put(configured, own);
            return configured;
        }

        plugin.getLogger().warning("language: '" + configured + "' is none of "
                + String.join(", ", BUNDLED) + ", and languages/" + configured
                + ".yml isn't there either."
                + " Falling back to each player's own client language.");
        return null;
    }

    private static String matchBundled(String name) {
        String clean = name.toLowerCase(Locale.ROOT).replace('-', '_');
        for (String locale : BUNDLED) {
            if (clean.equals(locale.toLowerCase(Locale.ROOT))) return locale;
        }

        // es_MX and es_ES are the same file here, so a country nobody shipped a
        // translation for still reads its own language rather than English.
        int underscore = clean.indexOf('_');
        String language = underscore < 0 ? clean : clean.substring(0, underscore);
        for (String locale : BUNDLED) {
            if (locale.regionMatches(true, 0, language, 0, 2) && language.length() == 2) {
                return locale;
            }
        }
        return null;
    }

    private FileConfiguration loadOwn(String name) {
        if (!name.matches(NAME_PATTERN)) return null;

        File file = new File(plugin.getDataFolder(), "languages/" + name + ".yml");
        if (!file.isFile()) return null;

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

        InputStream defaultStream = plugin.getResource("languages/" + FALLBACK + ".yml");
        if (defaultStream != null) {
            Reader reader = new InputStreamReader(defaultStream, StandardCharsets.UTF_8);
            config.setDefaults(YamlConfiguration.loadConfiguration(reader));
        }
        return config;
    }

    public String localeFor(Player player) {
        if (forced != null) return forced;
        if (player == null) return FALLBACK;
        String matched = matchBundled(player.getLocale());
        return matched == null ? FALLBACK : matched;
    }

    public boolean isForced() {
        return forced != null;
    }

    public String track(Player player, String path, int authorAt, Object... args) {
        Object author = args[authorAt];
        if (author != null && !String.valueOf(author).isBlank()) return get(player, path, args);

        // Every template sets author and title apart with a dash; a one-line track drops the author and its dash.
        Object[] marked = args.clone();
        marked[authorAt] = NO_AUTHOR;
        return dropAuthor(get(player, path, marked));
    }

    static String dropAuthor(String text) {
        String cut = AFTER_NO_AUTHOR.matcher(text).replaceFirst("");
        if (cut.equals(text)) cut = BEFORE_NO_AUTHOR.matcher(text).replaceFirst("");
        return cut.replace(NO_AUTHOR, "");
    }

    public String get(Player player, String path, Object... args) {
        String locale = localeFor(player);

        FileConfiguration config = locales.get(locale);
        if (config == null) {
            return "§c[Missing locale: " + locale + "]";
        }

        String message = config.getString(path);
        if (message == null) {
            return "§c[Missing: " + locale + "." + path + "]";
        }

        message = message.replace("&", "§");
        String messageN = Normalizer.normalize(message, Normalizer.Form.NFC);
        return args.length > 0 ? String.format(messageN, args) : messageN;
    }
}
