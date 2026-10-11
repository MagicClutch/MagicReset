package dev.magicclutch.magicreset.config;

import dev.magicclutch.magicreset.game.DeathMode;
import dev.magicclutch.magicreset.game.SeedMode;
import dev.magicclutch.magicreset.goal.Goal;
import dev.magicclutch.magicreset.goal.GoalType;
import dev.magicclutch.magicreset.timer.StartTrigger;
import dev.magicclutch.magicreset.timer.TimerMode;
import dev.magicclutch.magicreset.timer.TimerScope;
import dev.magicclutch.magicreset.util.TimeFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.WorldType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Typed, validated view of config.yml. A new instance is created on every (re)load.
 */
public final class Settings {

    public record Worlds(
        String namespace,
        boolean nether,
        boolean end,
        WorldType type,
        boolean generateStructures,
        boolean bonusChest,
        boolean hardcoreHearts,
        Difficulty difficulty,
        double borderSize,
        Map<String, String> gameRules
    ) {
    }

    public record Spawn(int safeSearchRadius, int preloadRadius) {
    }

    public record PlayerReset(
        boolean clearInventory,
        boolean clearEnderChest,
        boolean resetHealth,
        boolean resetHunger,
        boolean resetExperience,
        boolean clearEffects,
        boolean revokeAdvancements,
        boolean revokeRecipes,
        boolean resetRespawnPoint,
        GameMode gameMode
    ) {
    }

    public record Reset(
        int countdown,
        int limboCountdown,
        boolean shortcutCommand,
        boolean clearScoreboardScores,
        PlayerReset player
    ) {
    }

    public record Limbo(Material platformBlock, int platformRadius, int platformHeight, GameMode gameMode) {
    }

    public record Timer(
        TimerMode mode,
        TimerScope scope,
        StartTrigger start,
        boolean igtPauseWhenEmpty,
        int decimals,
        TimeFormat.Style style,
        boolean actionBar,
        boolean showWhenIdle,
        int updateInterval
    ) {
    }

    public record GoalSettings(Goal goal, boolean spectatorOnFinish, int resetAfter) {
    }

    public record Deaths(DeathMode mode, int sharedLives, boolean spectatorOnFail, int resetAfter) {
    }

    public record AutoReset(List<Integer> warnings) {
    }

    public record Scoreboard(boolean enabledByDefault, int updateInterval) {
    }

    public record Radar(boolean enabled, boolean requireCompass, int maxDistance) {
    }

    private final Worlds worlds;
    private final SeedMode seedMode;
    private final String fixedSeed;
    private final Spawn spawn;
    private final Reset reset;
    private final Limbo limbo;
    private final Timer timer;
    private final GoalSettings goal;
    private final Deaths deaths;
    private final AutoReset autoReset;
    private final Scoreboard scoreboard;
    private final Radar radar;

    private final Logger logger;

    public Settings(FileConfiguration config, Logger logger) {
        this.logger = logger;

        String namespace = String.valueOf(config.getString("worlds.namespace", "magicreset")).toLowerCase(Locale.ROOT);
        if (!namespace.matches("[a-z0-9._-]+") || namespace.equals(NamespacedKey.MINECRAFT)) {
            logger.warning("worlds.namespace '" + namespace + "' is invalid, using 'magicreset'");
            namespace = "magicreset";
        }
        Map<String, String> rules = new LinkedHashMap<>();
        ConfigurationSection rulesSection = config.getConfigurationSection("worlds.game-rules");
        if (rulesSection != null) {
            for (String key : rulesSection.getKeys(false)) {
                rules.put(key, String.valueOf(rulesSection.get(key)));
            }
        }
        this.worlds = new Worlds(
            namespace,
            config.getBoolean("worlds.nether", true),
            config.getBoolean("worlds.end", true),
            this.worldType(config.getString("worlds.type", "NORMAL")),
            config.getBoolean("worlds.generate-structures", true),
            config.getBoolean("worlds.bonus-chest", false),
            config.getBoolean("worlds.hardcore-hearts", false),
            this.enumValue(Difficulty.class, config.getString("worlds.difficulty"), Difficulty.NORMAL, "worlds.difficulty"),
            Math.max(0d, config.getDouble("worlds.world-border", 0d)),
            Collections.unmodifiableMap(rules)
        );

        this.seedMode = this.enumValue(SeedMode.class, config.getString("seed.mode"), SeedMode.RANDOM, "seed.mode");
        this.fixedSeed = config.getString("seed.fixed", "");

        this.spawn = new Spawn(
            Math.clamp(config.getInt("spawn.safe-search-radius", 96), 0, 512),
            Math.clamp(config.getInt("spawn.preload-radius", 3), 0, 12)
        );

        this.reset = new Reset(
            Math.max(0, config.getInt("reset.countdown", 5)),
            Math.max(0, config.getInt("reset.limbo-countdown", 5)),
            config.getBoolean("reset.shortcut-command", true),
            config.getBoolean("reset.clear-scoreboard-scores", false),
            new PlayerReset(
                config.getBoolean("reset.player.clear-inventory", true),
                config.getBoolean("reset.player.clear-ender-chest", true),
                config.getBoolean("reset.player.reset-health", true),
                config.getBoolean("reset.player.reset-hunger", true),
                config.getBoolean("reset.player.reset-experience", true),
                config.getBoolean("reset.player.clear-effects", true),
                config.getBoolean("reset.player.revoke-advancements", true),
                config.getBoolean("reset.player.revoke-recipes", false),
                config.getBoolean("reset.player.reset-respawn-point", true),
                this.enumValue(GameMode.class, config.getString("reset.player.game-mode"), GameMode.SURVIVAL, "reset.player.game-mode")
            )
        );

        Material platform = Material.matchMaterial(config.getString("limbo.platform-block", "BLACK_STAINED_GLASS"));
        if (platform == null || !platform.isBlock() || platform.isAir()) {
            logger.warning("limbo.platform-block is not a valid block, using BLACK_STAINED_GLASS");
            platform = Material.BLACK_STAINED_GLASS;
        }
        this.limbo = new Limbo(
            platform,
            Math.clamp(config.getInt("limbo.platform-radius", 4), 1, 32),
            Math.clamp(config.getInt("limbo.platform-height", 100), -32, 300),
            this.enumValue(GameMode.class, config.getString("limbo.game-mode"), GameMode.ADVENTURE, "limbo.game-mode")
        );

        this.timer = new Timer(
            this.enumValue(TimerMode.class, config.getString("timer.mode"), TimerMode.RTA, "timer.mode"),
            this.enumValue(TimerScope.class, config.getString("timer.scope"), TimerScope.GLOBAL, "timer.scope"),
            this.enumValue(StartTrigger.class, config.getString("timer.start"), StartTrigger.AUTO, "timer.start"),
            config.getBoolean("timer.igt-pause-when-empty", true),
            Math.clamp(config.getInt("timer.decimals", 2), 0, 3),
            this.enumValue(TimeFormat.Style.class, config.getString("timer.style"), TimeFormat.Style.DIGITAL, "timer.style"),
            config.getBoolean("timer.action-bar", true),
            config.getBoolean("timer.show-when-idle", true),
            Math.clamp(config.getInt("timer.update-interval", 1), 1, 20)
        );

        this.goal = new GoalSettings(
            this.readGoal(config),
            config.getBoolean("goal.spectator-on-finish", false),
            config.getInt("goal.reset-after", -1)
        );

        this.deaths = new Deaths(
            this.enumValue(DeathMode.class, config.getString("deaths.mode"), DeathMode.OFF, "deaths.mode"),
            Math.max(1, config.getInt("deaths.shared-lives", 3)),
            config.getBoolean("deaths.spectator-on-fail", true),
            config.getInt("deaths.reset-after", 10)
        );

        List<Integer> warnings = new ArrayList<>();
        for (Integer seconds : config.getIntegerList("auto-reset.warnings")) {
            if (seconds != null && seconds > 0) {
                warnings.add(seconds);
            }
        }
        this.autoReset = new AutoReset(List.copyOf(warnings));

        this.scoreboard = new Scoreboard(
            config.getBoolean("scoreboard.enabled-by-default", true),
            Math.clamp(config.getInt("scoreboard.update-interval", 10), 1, 100)
        );

        this.radar = new Radar(
            config.getBoolean("radar.enabled", false),
            config.getBoolean("radar.require-compass", true),
            Math.max(0, config.getInt("radar.max-distance", 0))
        );
    }

    private Goal readGoal(FileConfiguration config) {
        GoalType type = this.enumValue(GoalType.class, config.getString("goal.type"), GoalType.KILL_ENTITY, "goal.type");
        if (!type.needsTarget()) {
            return Goal.NONE;
        }
        String rawTarget = config.getString("goal.target", "");
        NamespacedKey target = rawTarget == null ? null : NamespacedKey.fromString(rawTarget.toLowerCase(Locale.ROOT));
        if (target == null) {
            this.logger.warning("goal.target '" + rawTarget + "' is not a valid key, the goal is disabled");
            return Goal.NONE;
        }
        return new Goal(type, target, config.getInt("goal.amount", 1));
    }

    private WorldType worldType(String raw) {
        WorldType type = raw == null ? null : WorldType.getByName(raw.toUpperCase(Locale.ROOT));
        if (type == null) {
            this.logger.warning("worlds.type '" + raw + "' is invalid, using NORMAL");
            return WorldType.NORMAL;
        }
        return type;
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String raw, E fallback, String path) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            this.logger.warning(path + " '" + raw + "' is invalid, using " + fallback.name());
            return fallback;
        }
    }

    public Worlds worlds() {
        return this.worlds;
    }

    public SeedMode seedMode() {
        return this.seedMode;
    }

    public String fixedSeed() {
        return this.fixedSeed;
    }

    public Spawn spawn() {
        return this.spawn;
    }

    public Reset reset() {
        return this.reset;
    }

    public Limbo limbo() {
        return this.limbo;
    }

    public Timer timer() {
        return this.timer;
    }

    public GoalSettings goal() {
        return this.goal;
    }

    public Deaths deaths() {
        return this.deaths;
    }

    public AutoReset autoReset() {
        return this.autoReset;
    }

    public Scoreboard scoreboard() {
        return this.scoreboard;
    }

    public Radar radar() {
        return this.radar;
    }
}
