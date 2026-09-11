package dev.valkdz.cdisc.gui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.valkdz.cdisc.Main;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ScreenPreferences {

    public static final String FILE_NAME = "screens.dat";

    private static final byte[] MAGIC = {'C', 'D', 'S', 'C', 1};

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final String KEY_SOURCE = "dev.valkdz.cdisc/screen-preferences/v1";

    private final Main plugin;
    private final File file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    private final Map<UUID, Boolean> chosen = new ConcurrentHashMap<>();

    private final Object saveLock = new Object();

    public ScreenPreferences(Main plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        load();
    }

    public boolean has(UUID player) {
        return chosen.containsKey(player);
    }

    public boolean wantsDialog(UUID player, boolean fallback) {
        Boolean said = chosen.get(player);
        return said == null ? fallback : said;
    }

    public void set(UUID player, boolean dialog) {
        Boolean before = chosen.put(player, dialog);
        if (before == null || before != dialog) saveLater();
    }

    public void clear(UUID player) {
        if (chosen.remove(player) != null) saveLater();
    }

    public void load() {
        chosen.clear();
        if (!file.isFile()) return;

        try {
            byte[] json = decrypt(Files.readAllBytes(file.toPath()));
            if (json == null) {
                plugin.getLogger().warning(FILE_NAME + " could not be read; screen choices "
                        + "start over and the file is rewritten on the next one.");
                return;
            }

            JsonNode all = mapper.readTree(json).path("screens");
            if (!all.isObject()) return;

            for (Iterator<Map.Entry<String, JsonNode>> it = all.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                try {
                    chosen.put(UUID.fromString(entry.getKey()), entry.getValue().asBoolean());
                } catch (IllegalArgumentException ignored) {

                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't read " + FILE_NAME + ": " + e.getMessage());
        }
    }

    // Off the main thread: this is a disk write and a click is what triggers it.
    private void saveLater() {
        if (!plugin.isEnabled()) {
            saveNow();
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::saveNow);
    }

    public void saveNow() {
        synchronized (saveLock) {
            ObjectNode root = mapper.createObjectNode();
            ObjectNode all = root.putObject("screens");
            for (Map.Entry<UUID, Boolean> entry : chosen.entrySet()) {
                all.put(entry.getKey().toString(), entry.getValue());
            }

            try {
                File folder = file.getParentFile();
                if (folder != null && !folder.isDirectory() && !folder.mkdirs()) {
                    plugin.getLogger().warning("Couldn't create " + folder + " for " + FILE_NAME);
                    return;
                }

                byte[] sealed = encrypt(mapper.writeValueAsBytes(root));

                // Written beside the real file and moved into place, so a crash halfway
                // through leaves the previous choices rather than half of the new ones.
                Path target = file.toPath();
                Path temp = target.resolveSibling(FILE_NAME + ".tmp");
                Files.write(temp, sealed);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                plugin.getLogger().warning("Couldn't write " + FILE_NAME + ": " + e.getMessage());
            }
        }
    }

    private SecretKeySpec key() throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(KEY_SOURCE.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IOException("no AES here", e);
        }
    }

    private byte[] encrypt(byte[] plain) throws IOException {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = cipher.doFinal(plain);

            byte[] out = new byte[MAGIC.length + iv.length + body.length];
            System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
            System.arraycopy(iv, 0, out, MAGIC.length, iv.length);
            System.arraycopy(body, 0, out, MAGIC.length + iv.length, body.length);
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("could not seal the file", e);
        }
    }

    private byte[] decrypt(byte[] raw) {
        if (raw.length < MAGIC.length + IV_BYTES) return null;
        for (int i = 0; i < MAGIC.length; i++) {
            if (raw[i] != MAGIC[i]) return null;
        }

        try {
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(raw, MAGIC.length, iv, 0, IV_BYTES);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));

            int at = MAGIC.length + IV_BYTES;
            return cipher.doFinal(raw, at, raw.length - at);
        } catch (Exception e) {

            return null;
        }
    }
}
