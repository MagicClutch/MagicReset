package dev.magicclutch.magicreset.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Replaces a config.yml from an older version with the current layout. The values of every setting that is
 * still in the file are taken over; the old file is kept as config-old.yml.
 */
public final class ConfigUpdater {

    private static final String FILE_NAME = "config.yml";
    private static final String VERSION_KEY = "config-version";
    /** Settings that moved: old path -> new path. */
    private static final Map<String, String> MOVED = Map.of("scoreboard.enabled", "sidebar.enabled");

    private ConfigUpdater() {
    }

    public static void update(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            return;
        }
        YamlConfiguration old = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration current = new YamlConfiguration();
        try (InputStream stream = plugin.getResource(FILE_NAME)) {
            if (stream == null) {
                return;
            }
            current.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().warning("Could not read the bundled " + FILE_NAME + ": " + e.getMessage());
            return;
        }
        if (old.getInt(VERSION_KEY, 0) >= current.getInt(VERSION_KEY)) {
            return;
        }
        for (String key : current.getKeys(true)) {
            if (key.equals(VERSION_KEY)) {
                continue;
            }
            if (current.isConfigurationSection(key) && !key.equals("worlds.game-rules")) {
                continue;
            }
            String source = MOVED.entrySet().stream().filter(entry -> entry.getValue().equals(key))
                .map(Map.Entry::getKey).findFirst().orElse(key);
            if (!old.contains(source)) {
                continue;
            }
            Object value = old.get(source);
            current.set(key, value instanceof ConfigurationSection section ? section.getValues(true) : value);
        }
        try {
            Files.copy(file.toPath(), new File(plugin.getDataFolder(), "config-old.yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
            current.save(file);
            plugin.getLogger().info("config.yml was updated to the new, shorter layout (your values were kept, the old file is config-old.yml)");
        } catch (IOException e) {
            plugin.getLogger().warning("Could not update " + FILE_NAME + ": " + e.getMessage());
        }
    }
}
