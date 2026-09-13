package dev.valkdz.cdisc.update;

import dev.valkdz.cdisc.Main;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

public final class PluginUpdater {

    private static final String HOOK_PROPERTY = "cdisc.updater.exit-hook";
    private static final String DISABLED_PROPERTY = "cdisc.updater.disabled";

    record Download(String url, String fileName, String sha512) {}

    private final Main plugin;
    private final HttpClient http;
    private final Path pluginsDir;
    private final Path stagingDir;

    public PluginUpdater(Main plugin, HttpClient http) {
        this.plugin = plugin;
        this.http = http;
        this.stagingDir = plugin.getDataFolder().toPath().toAbsolutePath().resolve("update");
        this.pluginsDir = stagingDir.getParent().getParent();
        System.clearProperty(DISABLED_PROPERTY);
    }

    public String stagedVersion() {
        Path jar = stagedJar(stagingDir);
        if (jar == null) return null;

        String version = field(jar, "version");
        if (plugin.getName().equals(field(jar, "name")) && version != null
                && UpdateChecker.compareVersions(version, plugin.getDescription().getVersion()) > 0) {
            armExitHook();
            return version;
        }
        deleteQuietly(jar);
        return null;
    }

    void stage(String version, Download download) throws Exception {
        String name = download.fileName();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".jar")
                || name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            throw new IOException("unexpected file name " + name);
        }
        if (download.url() == null || !download.url().startsWith("https://")) {
            throw new IOException("unexpected download URL " + download.url());
        }
        if (download.sha512() == null) throw new IOException("Modrinth gave no SHA-512 for " + name);

        Files.createDirectories(stagingDir);
        clearStaging();
        Path part = stagingDir.resolve(name + ".part");

        HttpRequest request = HttpRequest.newBuilder(URI.create(download.url()))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "valkdz/CDisc/" + plugin.getDescription().getVersion()
                        + " (modrinth auto-update)")
                .GET()
                .build();
        try {
            HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(part));
            if (response.statusCode() != 200) {
                throw new IOException("download returned HTTP " + response.statusCode());
            }
            String hash = sha512(part);
            if (!hash.equalsIgnoreCase(download.sha512())) {
                throw new IOException("SHA-512 mismatch for " + name);
            }
            String foundName = field(part, "name");
            String foundVersion = field(part, "version");
            if (!plugin.getName().equals(foundName) || foundVersion == null
                    || UpdateChecker.compareVersions(foundVersion, plugin.getDescription().getVersion()) <= 0) {
                throw new IOException(name + " is " + foundName + " " + foundVersion
                        + ", not a newer " + plugin.getName());
            }
            Files.move(part, stagingDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteQuietly(part);
        }
        armExitHook();
    }

    public void onDisable(boolean autoUpdate) {
        try {
            String version = stagedVersion();
            if (version == null) return;

            if (!autoUpdate) {
                clearStaging();
                return;
            }
            if (install(pluginsDir, stagingDir, plugin.getName())) {
                plugin.getLogger().info("Installed " + plugin.getName() + " " + version
                        + ", it loads on the next start.");
            } else {
                plugin.getLogger().info(plugin.getName() + " " + version
                        + " replaces this jar once the server has shut down.");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Auto-update could not install the downloaded jar: " + e);
        } finally {
            // Set only after the attempt above, so a running exit hook never swaps alongside it.
            System.setProperty(DISABLED_PROPERTY, "true");
        }
    }

    private void armExitHook() {
        if (System.getProperty(HOOK_PROPERTY) != null) return;
        Path plugins = pluginsDir;
        Path staging = stagingDir;
        String name = plugin.getName();
        try {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(() -> installOnExit(plugins, staging, name), "CDisc auto-update"));
            System.setProperty(HOOK_PROPERTY, "armed");
        } catch (IllegalStateException ignored) {
        }
    }

    // Runs after the server closed this plugin's class loader, so it may touch JDK classes only.
    // On Windows the old jar stays locked until then, which is why the swap waits for it here.
    private static void installOnExit(Path plugins, Path staging, String name) {
        try {
            for (int i = 0; i < 150 && System.getProperty(DISABLED_PROPERTY) == null
                    && stagedJar(staging) != null; i++) {
                Thread.sleep(200);
            }
            for (int i = 0; i < 25; i++) {
                if (install(plugins, staging, name)) return;
                Thread.sleep(200);
            }
        } catch (InterruptedException ignored) {
        }
    }

    private static boolean install(Path plugins, Path staging, String name) {
        Path staged = stagedJar(staging);
        if (staged == null) return true;

        Path target = plugins.resolve(staged.getFileName().toString());
        Path part = plugins.resolve(staged.getFileName() + ".part");
        try {
            Files.copy(staged, part, StandardCopyOption.REPLACE_EXISTING);
            try (DirectoryStream<Path> jars = Files.newDirectoryStream(plugins, "*.jar")) {
                for (Path jar : jars) {
                    if (name.equals(field(jar, "name"))) Files.delete(jar);
                }
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(staged);
            return true;
        } catch (IOException e) {
            deleteQuietly(part);
            return false;
        }
    }

    private static Path stagedJar(Path staging) {
        if (!Files.isDirectory(staging)) return null;
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(staging, "*.jar")) {
            for (Path jar : jars) return jar;
        } catch (IOException ignored) {
        }
        return null;
    }

    private void clearStaging() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(stagingDir)) {
            for (Path file : files) {
                if (Files.isRegularFile(file)) Files.delete(file);
            }
        }
    }

    private static String field(Path jar, String key) {
        try (JarFile file = new JarFile(jar.toFile())) {
            ZipEntry entry = file.getEntry("plugin.yml");
            if (entry == null) return null;
            String yml;
            try (InputStream in = file.getInputStream(entry)) {
                yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Matcher matcher = Pattern.compile("(?m)^" + key + ":[ \\t]*['\"]?([^'\"\\s#]+)").matcher(yml);
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String sha512(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                .append(Character.forDigit(b & 0xF, 16));
        return hex.toString();
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }
}
