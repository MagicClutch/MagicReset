package dev.magicclutch.magicreset.stats;

import dev.magicclutch.magicreset.goal.Goal;
import dev.magicclutch.magicreset.timer.TimerMode;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Best completion times per goal and timer mode, stored in leaderboard.yml.
 */
public final class Leaderboard {

    public record Entry(UUID player, String name, long millis, long achievedAt, long seed) {
    }

    private static final int MAX_ENTRIES = 10;

    private final File file;
    private final Logger logger;
    private final Map<String, List<Entry>> boards = new HashMap<>();

    public Leaderboard(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "leaderboard.yml");
        this.logger = logger;
    }

    /** One board per goal (type, target, amount) and timer mode, because RTA and IGT times are not comparable. */
    public static String category(Goal goal, TimerMode mode) {
        String target = goal.target() == null ? "-" : goal.target().asString();
        // '.' is the YAML path separator, so it never appears in a category
        return (goal.type().name() + "|" + target + "|" + goal.amount() + "|" + mode.name())
            .toLowerCase(java.util.Locale.ROOT).replace('.', '_');
    }

    public List<Entry> top(String category) {
        return List.copyOf(this.boards.getOrDefault(category, List.of()));
    }

    /**
     * Adds a time. A player keeps only their best time per board.
     *
     * @return the 1-based rank of the new time, or 0 if it did not make the board (or is not a personal best)
     */
    public int record(String category, Entry entry) {
        List<Entry> board = this.boards.computeIfAbsent(category, ignored -> new ArrayList<>());
        for (Entry existing : board) {
            if (existing.player().equals(entry.player()) && existing.millis() <= entry.millis()) {
                return 0;
            }
        }
        board.removeIf(existing -> existing.player().equals(entry.player()));
        board.add(entry);
        board.sort(Comparator.comparingLong(Entry::millis).thenComparingLong(Entry::achievedAt));
        int rank = board.indexOf(entry) + 1;
        while (board.size() > MAX_ENTRIES) {
            board.removeLast();
        }
        this.save();
        return rank <= MAX_ENTRIES ? rank : 0;
    }

    public void clear(String category) {
        if (this.boards.remove(category) != null) {
            this.save();
        }
    }

    public void load() {
        this.boards.clear();
        if (!this.file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(this.file);
        ConfigurationSection root = yaml.getConfigurationSection("boards");
        if (root == null) {
            return;
        }
        for (String category : root.getKeys(false)) {
            List<Entry> entries = new ArrayList<>();
            for (Map<?, ?> raw : root.getMapList(category)) {
                try {
                    entries.add(new Entry(
                        UUID.fromString(String.valueOf(raw.get("uuid"))),
                        String.valueOf(raw.get("name")),
                        ((Number) raw.get("millis")).longValue(),
                        ((Number) raw.get("at")).longValue(),
                        ((Number) raw.get("seed")).longValue()));
                } catch (RuntimeException e) {
                    this.logger.warning("Skipping a broken leaderboard entry in " + category);
                }
            }
            entries.sort(Comparator.comparingLong(Entry::millis));
            this.boards.put(category, entries);
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        this.boards.forEach((category, entries) -> {
            List<Map<String, Object>> raw = new ArrayList<>();
            for (Entry entry : entries) {
                Map<String, Object> map = new java.util.LinkedHashMap<>();
                map.put("uuid", entry.player().toString());
                map.put("name", entry.name());
                map.put("millis", entry.millis());
                map.put("at", entry.achievedAt());
                map.put("seed", entry.seed());
                raw.add(map);
            }
            yaml.set("boards." + category, raw);
        });
        try {
            yaml.save(this.file);
        } catch (IOException e) {
            this.logger.log(Level.SEVERE, "Could not save leaderboard.yml", e);
        }
    }
}
