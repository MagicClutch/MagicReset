package dev.magicclutch.magicreset.config;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads messages.yml (MiniMessage format). Keys missing from the user's file fall back to the bundled defaults.
 */
public final class Messages {

    private static final String FILE_NAME = "messages.yml";

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final YamlConfiguration messages;
    private final TagResolver prefix;

    public Messages(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        this.messages = YamlConfiguration.loadConfiguration(file);
        try (InputStream stream = plugin.getResource(FILE_NAME)) {
            if (stream != null) {
                this.messages.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read bundled " + FILE_NAME + ": " + e.getMessage());
        }
        this.prefix = Placeholder.parsed("prefix", this.messages.getString("prefix", ""));
    }

    public String raw(String key) {
        return this.messages.getString(key, key);
    }

    public Component get(String key, TagResolver... resolvers) {
        return this.parse(this.raw(key), resolvers);
    }

    public List<Component> list(String key, TagResolver... resolvers) {
        return this.messages.getStringList(key).stream().map(line -> this.parse(line, resolvers)).toList();
    }

    public Component parse(String miniMessage, TagResolver... resolvers) {
        return this.miniMessage.deserialize(miniMessage, TagResolver.resolver(this.prefix, TagResolver.resolver(resolvers)));
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        String raw = this.raw(key);
        if (raw.isEmpty()) {
            return;
        }
        audience.sendMessage(this.parse(raw, resolvers));
    }

    public boolean isEmpty(String key) {
        return this.raw(key).isEmpty();
    }
}
