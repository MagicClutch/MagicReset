package dev.magicclutch.magicreset.config;

import dev.magicclutch.magicreset.game.AutoResetOn;
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
        boolean locatorBar,
        Difficulty difficulty,
        double borderSize,
        Map<String, String> gameRules
    ) {
    }

    public record Spawn(int safeSearchRadius, int preloadRadius) {
    }

    public record Backups(boolean enabled, String folder, int keep) {
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
        boolean preload,
        int preloadDelay,
        AutoResetOn auto,
        int autoDelay,
        boolean confirm,
        PlayerReset player
    ) {
    }

    public record Limbo(Material platformBlock, int platformRadius, int platformHeight, GameMode gameMode) {
    }

    public record Timer(
        boolean enabled,
        TimerMode mode,
        TimerScope scope,
        StartTrigger start,
        boolean igtPauseWhenEmpty,
        boolean stopWhenEmpty,
        boolean pauseWhileDead,
        int decimals,
        TimeFormat.Style style,
        String format,
        boolean actionBar,
        boolean showWhenIdle,
        int updateInterval
    ) {
        /** Formats a time for the action bar, sidebar and leaderboard. */
        public String display(long millis) {
            return this.format.equalsIgnoreCase("auto") ? TimeFormat.format(millis, this.decimals, this.style) : TimeFormat.pattern(millis, this.format);
        }
    }

    public record GoalSettings(Goal goal, boolean spectatorOnFinish) {
    }

    public record Deaths(DeathMode mode, int sharedLives, boolean spectatorOnFail) {
    }

    public record AutoReset(List<Integer> warnings) {
    }

    public record Manhunt(boolean enabled, int targetLives, int headStart, boolean hunterCompass) {
    }

    public record Scoreboard(boolean enabled, boolean enabledByDefault, int updateInterval) {
    }


    private final Worlds worlds;
    private final SeedMode seedMode;
    private final String fixedSeed;
    private final boolean showSeed;
    private final Backups backups;
    private final Spawn spawn;
    private final Reset reset;
    private final Limbo limbo;
    private final Timer timer;
    private final GoalSettings goal;
    private final Deaths deaths;
    private final AutoReset autoReset;
    private final Scoreboard scoreboard;
    private final Manhunt manhunt;

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
            config.getBoolean("worlds.locator-bar", true),
            this.enumValue(Difficulty.class, config.getString("worlds.difficulty"), Difficulty.NORMAL, "worlds.difficulty"),
            Math.max(0d, config.getDouble("worlds.world-border", 0d)),
            Collections.unmodifiableMap(rules)
        );

        this.seedMode = this.enumValue(SeedMode.class, config.getString("seed.mode"), SeedMode.RANDOM, "seed.mode");
        this.fixedSeed = config.getString("seed.fixed", "");
        this.showSeed = config.getBoolean("seed.show", true);
        this.backups = new Backups(
            config.getBoolean("backups.enabled", true),
            String.valueOf(config.getString("backups.folder", "backups")),
            Math.max(0, config.getInt("backups.keep", 5))
        );

        this.spawn = new Spawn(
            Math.clamp(config.getInt("spawn.safe-search-radius", 96), 0, 512),
            Math.clamp(config.getInt("spawn.preload-radius", 3), 0, 12)
        );

        this.reset = new Reset(
            Math.max(0, config.getInt("reset.countdown", 5)),
            Math.max(0, config.getInt("reset.limbo-countdown", 5)),
            config.getBoolean("reset.shortcut-command", true),
            config.getBoolean("reset.clear-scoreboard-scores", false),
            config.getBoolean("reset.preload", false),
            Math.max(0, config.getInt("reset.preload-delay", 15)),
            this.enumValue(AutoResetOn.class, config.getString("reset.auto"), AutoResetOn.OFF, "reset.auto"),
            Math.clamp(config.getInt("reset.auto-delay", 10), 0, 600),
            config.getBoolean("reset.confirm", false),
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
            config.getBoolean("timer.enabled", true),
            this.enumValue(TimerMode.class, config.getString("timer.mode"), TimerMode.RTA, "timer.mode"),
            this.enumValue(TimerScope.class, config.getString("timer.scope"), TimerScope.GLOBAL, "timer.scope"),
            this.enumValue(StartTrigger.class, config.getString("timer.start"), StartTrigger.AUTO, "timer.start"),
            config.getBoolean("timer.igt-pause-when-empty", true),
            config.getBoolean("timer.stop-when-empty", false),
            config.getBoolean("timer.pause-while-dead", false),
            Math.clamp(config.getInt("timer.decimals", 2), 0, 3),
            this.enumValue(TimeFormat.Style.class, config.getString("timer.style"), TimeFormat.Style.DIGITAL, "timer.style"),
            this.timeFormat(config.getString("timer.format", "auto")),
            config.getBoolean("timer.action-bar", true),
            config.getBoolean("timer.show-when-idle", true),
            Math.clamp(config.getInt("timer.update-interval", 1), 1, 20)
        );

        this.goal = new GoalSettings(
            this.readGoal(config),
            config.getBoolean("goal.spectator-on-finish", false)
        );

        this.deaths = new Deaths(
            this.enumValue(DeathMode.class, config.getString("deaths.mode"), DeathMode.OFF, "deaths.mode"),
            Math.max(1, config.getInt("deaths.shared-lives", 3)),
            config.getBoolean("deaths.spectator-on-fail", true)
        );

        List<Integer> warnings = new ArrayList<>();
        List<Integer> configured = config.contains("auto-reset.warnings")
            ? config.getIntegerList("auto-reset.warnings") : List.of(3600, 1800, 600, 300, 60, 30, 10, 5, 4, 3, 2, 1);
        for (Integer seconds : configured) {
            if (seconds != null && seconds > 0) {
                warnings.add(seconds);
            }
        }
        this.autoReset = new AutoReset(List.copyOf(warnings));

        this.manhunt = new Manhunt(
            config.getBoolean("manhunt.enabled", false),
            Math.clamp(config.getInt("manhunt.target-lives", 1), 1, 100),
            Math.clamp(config.getInt("manhunt.head-start", 30), 0, 600),
            config.getBoolean("manhunt.hunter-compass", true)
        );
        this.scoreboard = new Scoreboard(
            config.getBoolean("sidebar.enabled", true),
            config.getBoolean("scoreboard.enabled-by-default", true),
            Math.clamp(config.getInt("scoreboard.update-interval", 10), 1, 100)
        );


    }

    private Goal readGoal(FileConfiguration config) {
        GoalType type = this.enumValue(GoalType.class, config.getString("goal.type"), GoalType.BEAT_GAME, "goal.type");
        if (!type.needsTarget()) {
            return type == GoalType.NONE ? Goal.NONE : new Goal(type, null, 1);
        }
        String rawTarget = config.getString("goal.target", "");
        NamespacedKey target = rawTarget == null ? null : NamespacedKey.fromString(rawTarget.toLowerCase(Locale.ROOT));
        if (target == null) {
            this.logger.warning("goal.target '" + rawTarget + "' is not a valid key, the goal is disabled");
            return Goal.NONE;
        }
        return new Goal(type, target, Math.clamp(config.getInt("goal.amount", 1), 1, 100_000));
    }

    private String timeFormat(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("auto") || TimeFormat.isValidPattern(raw)) {
            return raw == null || raw.isBlank() ? "auto" : raw;
        }
        this.logger.warning("timer.format '" + raw + "' needs H, m or s, using auto");
        return "auto";
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

    public boolean showSeed() {
        return this.showSeed;
    }

    public Backups backups() {
        return this.backups;
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

    public Manhunt manhunt() {
        return this.manhunt;
    }

    public Scoreboard scoreboard() {
        return this.scoreboard;
    }


}
