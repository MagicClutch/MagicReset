package dev.magicclutch.magicreset.menu;

import dev.magicclutch.magicreset.goal.GoalService;
import dev.magicclutch.magicreset.goal.GoalType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Biome;
import org.bukkit.entity.EntityType;

/**
 * What can be picked as the target of a goal, with a search over the registry keys and a short list of
 * popular choices shown before anything is typed.
 */
final class GoalTargets {

    static final int MAX_RESULTS = 24;

    private static final List<String> POPULAR_ENTITIES = List.of(
        "ender_dragon", "wither", "warden", "elder_guardian", "blaze", "enderman",
        "piglin_brute", "evoker", "ravager", "iron_golem", "creeper", "zombie");
    private static final List<String> POPULAR_BLOCKS = List.of(
        "diamond_ore", "deepslate_diamond_ore", "ancient_debris", "obsidian", "crying_obsidian", "emerald_ore",
        "spawner", "gold_ore", "iron_ore", "sculk_shrieker", "reinforced_deepslate", "dragon_egg");
    private static final List<String> POPULAR_ITEMS = List.of(
        "diamond", "ender_pearl", "blaze_rod", "ender_eye", "netherite_ingot", "elytra", "totem_of_undying",
        "dragon_egg", "nether_star", "enchanted_golden_apple", "trident", "heart_of_the_sea", "echo_shard",
        "heavy_core", "music_disc_pigstep", "golden_apple");
    private static final List<String> POPULAR_ADVANCEMENTS = List.of(
        "story/mine_stone", "story/smelt_iron", "story/mine_diamond", "story/enter_the_nether",
        "nether/find_fortress", "nether/obtain_blaze_rod", "story/follow_ender_eye", "story/enter_the_end",
        "end/kill_dragon", "end/elytra", "nether/obtain_ancient_debris", "adventure/kill_a_mob",
        "adventure/sleep_in_bed", "husbandry/balanced_diet", "adventure/adventuring_time", "nether/all_effects");

    private static final List<String> POPULAR_BIOMES = List.of(
        "mushroom_fields", "badlands", "ice_spikes", "cherry_grove", "jungle", "deep_dark", "pale_garden",
        "warped_forest", "crimson_forest", "soul_sand_valley", "basalt_deltas", "end_highlands");
    private static final List<String> POPULAR_STRUCTURES = List.of(
        "village_plains", "stronghold", "fortress", "bastion_remnant", "end_city", "ancient_city",
        "woodland_mansion", "monument", "trial_chambers", "desert_pyramid", "ruined_portal", "trail_ruins");

    private GoalTargets() {
    }

    /** Every valid target of a goal type. */
    static List<NamespacedKey> all(GoalType type) {
        List<NamespacedKey> keys = new ArrayList<>();
        switch (type) {
            case KILL_ENTITY -> Registry.ENTITY_TYPE.stream().filter(EntityType::isAlive).forEach(entity -> keys.add(entity.getKey()));
            case BREAK_BLOCK -> Registry.MATERIAL.stream().filter(material -> material.isBlock() && !material.isAir()).forEach(m -> keys.add(m.getKey()));
            case OBTAIN_ITEM -> Registry.MATERIAL.stream().filter(material -> material.isItem() && !material.isAir()).forEach(m -> keys.add(m.getKey()));
            case FIND_BIOME -> Registry.BIOME.keyStream().forEach(keys::add);
            case FIND_STRUCTURE -> Registry.STRUCTURE.keyStream().forEach(keys::add);
            case ADVANCEMENT -> {
                Iterator<Advancement> iterator = Bukkit.advancementIterator();
                while (iterator.hasNext()) {
                    Advancement advancement = iterator.next();
                    if (advancement.getDisplay() != null) {
                        keys.add(advancement.getKey());
                    }
                }
            }
            default -> {
            }
        }
        return keys;
    }

    /** Shown before the player searched; entries that do not exist in this version are skipped. */
    static List<NamespacedKey> popular(GoalType type) {
        List<String> names = switch (type) {
            case KILL_ENTITY -> POPULAR_ENTITIES;
            case BREAK_BLOCK -> POPULAR_BLOCKS;
            case OBTAIN_ITEM -> POPULAR_ITEMS;
            case ADVANCEMENT -> POPULAR_ADVANCEMENTS;
            case FIND_BIOME -> POPULAR_BIOMES;
            case FIND_STRUCTURE -> POPULAR_STRUCTURES;
            default -> List.of();
        };
        List<NamespacedKey> available = all(type);
        return names.stream().map(NamespacedKey::minecraft).filter(available::contains).toList();
    }

    /**
     * Finds keys whose path contains every word of the query, best matches first: exact, then prefix, then
     * matches at the start of a word, then anywhere. Pure function so it can be tested without a server.
     */
    static List<NamespacedKey> search(List<NamespacedKey> keys, String query, int limit) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) {
            return List.of();
        }
        String[] words = normalized.split(" ");
        return keys.stream()
            .filter(key -> {
                String text = normalize(key.getKey());
                for (String word : words) {
                    if (!text.contains(word)) {
                        return false;
                    }
                }
                return true;
            })
            .sorted(Comparator.<NamespacedKey>comparingInt(key -> score(normalize(key.getKey()), normalized))
                .thenComparingInt(key -> key.getKey().length())
                .thenComparing(NamespacedKey::asString))
            .limit(limit)
            .toList();
    }

    private static int score(String text, String query) {
        if (text.equals(query)) {
            return 0;
        }
        // whole words beat parts of words: "end" ranks "end kill dragon" above "ender pearl"
        if (text.startsWith(query + " ")) {
            return 1;
        }
        if (text.contains(" " + query + " ") || text.endsWith(" " + query)) {
            return 2;
        }
        if (text.startsWith(query)) {
            return 3;
        }
        return text.contains(" " + query) ? 4 : 5;
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT)
            .replace("minecraft:", "")
            .replace('_', ' ')
            .replace('/', ' ')
            .replaceAll("\\s+", " ")
            .trim();
    }

    /** Name shown on the button, translated by the client into the player's language. */
    static Component label(GoalType type, NamespacedKey key) {
        return switch (type) {
            case KILL_ENTITY -> {
                EntityType entity = Registry.ENTITY_TYPE.get(key);
                yield entity == null ? Component.text(key.getKey()) : Component.translatable(entity.translationKey());
            }
            case BREAK_BLOCK, OBTAIN_ITEM -> {
                Material material = Registry.MATERIAL.get(key);
                yield material == null ? Component.text(key.getKey()) : Component.translatable(material.translationKey());
            }
            case ADVANCEMENT -> {
                Advancement advancement = Bukkit.getAdvancement(key);
                yield advancement == null || advancement.getDisplay() == null ? Component.text(key.getKey()) : advancement.getDisplay().title();
            }
            case FIND_BIOME -> {
                Biome biome = Registry.BIOME.get(key);
                yield biome == null ? Component.text(key.getKey()) : Component.translatable(biome.translationKey());
            }
            case FIND_STRUCTURE -> Component.text(GoalService.readable(key));
            default -> Component.text(key.asString());
        };
    }
}
