package dev.magicclutch.magicreset.game;

import dev.magicclutch.magicreset.MagicReset;
import dev.magicclutch.magicreset.config.Messages;
import dev.magicclutch.magicreset.config.Settings;
import dev.magicclutch.magicreset.goal.GoalService;
import dev.magicclutch.magicreset.manhunt.ManhuntService;
import dev.magicclutch.magicreset.stats.Leaderboard;
import dev.magicclutch.magicreset.timer.RunTimer;
import dev.magicclutch.magicreset.timer.TimerScope;
import dev.magicclutch.magicreset.timer.TimerService;
import dev.magicclutch.magicreset.util.Seeds;
import dev.magicclutch.magicreset.util.TimeFormat;
import dev.magicclutch.magicreset.util.WorldFiles;
import dev.magicclutch.magicreset.world.GameWorlds;
import dev.magicclutch.magicreset.world.SpawnFinder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.jspecify.annotations.Nullable;

/**
 * Drives the life cycle of a run: generating worlds, moving players between limbo and the game, pausing,
 * completing goals and failing runs. All methods must be called on the main thread unless stated otherwise.
 */
public final class GameManager {

    public enum Phase {
        /** The run is being played. */
        RUNNING,
        /** A countdown before a reset or a pause is running; the game continues meanwhile. */
        COUNTDOWN,
        /** Players are in limbo while the worlds are regenerated. */
        RESETTING,
        /** The run is paused, players are in limbo with their state saved. */
        PAUSED,
        /** Countdown in limbo before players return from a pause. */
        RESUMING
    }

    public enum Outcome {
        NONE,
        COMPLETED,
        FAILED
    }

    private enum CountdownKind {
        RESET,
        PAUSE
    }

    private static final String DATA_FILE = "data.yml";
    private static final int LIMBO_VIEW_DISTANCE = 3;
    private static final Sound TICK_SOUND = Sound.sound(Key.key("minecraft:block.note_block.hat"), Sound.Source.MASTER, 1f, 1f);
    private static final Sound GO_SOUND = Sound.sound(Key.key("minecraft:block.note_block.pling"), Sound.Source.MASTER, 1f, 2f);
    private static final Sound COMPLETE_SOUND = Sound.sound(Key.key("minecraft:ui.toast.challenge_complete"), Sound.Source.MASTER, 1f, 1f);
    private static final Sound FAIL_SOUND = Sound.sound(Key.key("minecraft:entity.wither.death"), Sound.Source.MASTER, 0.7f, 1f);
    private static final Title.Times COUNTDOWN_TIMES = Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(250));

    private final MagicReset plugin;
    private final GameWorlds worlds;
    private final TimerService timers;
    private final GoalService goals;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MagicReset-IO");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Phase phase = Phase.RESETTING;
    private Phase phaseBeforeCountdown = Phase.RUNNING;
    private Outcome outcome = Outcome.NONE;
    private volatile int run;
    private long seed;
    private volatile @Nullable Location spawn;
    private volatile @Nullable Location limboSpawn;
    private int deaths;
    private final Map<UUID, Integer> participants = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<>();

    private @Nullable BukkitTask countdownTask;
    private @Nullable CountdownKind countdownKind;
    private final BossBar bossBar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
    private @Nullable ConfigurationSection loadedExtra;
    /** The last reset failed half way; the old run cannot be continued. */
    private boolean resetFailed;
    /** Dimensions the running reset deletes; all of them unless chosen in the menu. */
    private Set<GameWorlds.Dimension> resetSelection = EnumSet.allOf(GameWorlds.Dimension.class);
    private final Preloader preloader;
    /** Old worlds of an instant (preloaded) reset are still being backed up and deleted. */
    private boolean cleaningUp;
    private long runStartedNanos = System.nanoTime();
    /** The new world is generated, players are only waiting for the limbo countdown. */
    private boolean awaitingRelease;
    private Phase savedPhase = Phase.RESETTING;

    public GameManager(MagicReset plugin, GameWorlds worlds, TimerService timers, GoalService goals) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.timers = timers;
        this.goals = goals;
        this.preloader = new Preloader(plugin, worlds, this.io, this::nextSeed, this::canPreload);
    }

    /** Preloading waits a bit after a run started so it does not compete with the start of the run. */
    private boolean canPreload() {
        long sinceStart = System.nanoTime() - this.runStartedNanos;
        return this.phase == Phase.RUNNING && !this.cleaningUp
            && sinceStart >= TimeUnit.SECONDS.toNanos(this.settings().reset().preloadDelay());
    }

    /** Starts background preloading; called once after {@link #start()}. */
    public void startPreloader() {
        this.preloader.start();
    }

    private Settings settings() {
        return this.plugin.settings();
    }

    private Messages messages() {
        return this.plugin.messages();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Startup / shutdown
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Loads the saved state and the worlds. Runs on the first server tick after enabling.
     *
     * @return {@code false} if the plugin cannot work (the limbo world could not be created)
     */
    public boolean start() {
        World limbo = this.worlds.loadLimbo();
        if (limbo == null) {
            return false;
        }
        this.limboSpawn = this.worlds.limboSpawn(limbo);
        // keep the limbo loaded all the time so moving players there is instant
        int spawnChunkX = this.limboSpawn.getBlockX() >> 4;
        int spawnChunkZ = this.limboSpawn.getBlockZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                limbo.getChunkAt(spawnChunkX + dx, spawnChunkZ + dz).addPluginChunkTicket(this.plugin);
            }
        }
        // it is an empty void, a small view distance lets clients finish loading it almost immediately
        limbo.setViewDistance(LIMBO_VIEW_DISTANCE);
        limbo.setSimulationDistance(LIMBO_VIEW_DISTANCE);
        Phase previous = this.savedPhase;

        boolean worldsOnDisk = this.worlds.hasSavedData(GameWorlds.Dimension.OVERWORLD);
        boolean readyWorld = previous == Phase.RESETTING && this.awaitingRelease;
        if ((previous == Phase.RESETTING && !readyWorld) || this.resetFailed || !worldsOnDisk || this.run == 0) {
            this.plugin.getLogger().info(worldsOnDisk && this.run > 0
                ? "The last reset did not finish, generating a new world"
                : "No game world found, generating the first world");
            this.executeReset(null, this.settings().reset().limboCountdown());
            return true;
        }

        for (GameWorlds.Dimension dimension : GameWorlds.Dimension.values()) {
            if (this.worlds.isEnabled(dimension) && this.worlds.loadOrCreate(dimension, this.seed) == null && dimension == GameWorlds.Dimension.OVERWORLD) {
                this.plugin.getLogger().severe("Could not load the game overworld, generating a new one");
                this.executeReset(null, this.settings().reset().limboCountdown());
                return true;
            }
        }
        World overworld = this.worlds.overworld();
        if (this.spawn == null && overworld != null) {
            this.spawn = overworld.getSpawnLocation().toCenterLocation();
        }
        this.spawn = this.spawn(); // attach the loaded world for the async join check
        this.phase = switch (previous) {
            case PAUSED, RESUMING, RESETTING -> Phase.PAUSED;
            default -> Phase.RUNNING;
        };
        if (readyWorld) {
            this.plugin.getLogger().info("The server stopped during the limbo countdown, use /magicreset resume to start the run");
        }
        if (this.phase == Phase.PAUSED) {
            this.showBossBar(this.messages().get("bossbar.paused"), 1f, BossBar.Color.YELLOW);
        }
        this.plugin.getLogger().info("Loaded run #" + this.run + " (seed " + this.seed + ")");
        this.logGameWorlds();
        return true;
    }

    public void shutdown() {
        this.preloader.stop();
        this.cancelCountdownTask();
        this.saveNow();
        this.io.shutdown();
        try {
            if (!this.io.awaitTermination(10, TimeUnit.SECONDS)) {
                this.plugin.getLogger().warning("Timed out while writing " + DATA_FILE);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Reset
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Starts a reset, optionally after a countdown.
     *
     * @param seedInput      seed text, {@code null} to use the configured seed mode, "random" or "same"
     * @param countdown      seconds before the players are moved to limbo
     * @param limboCountdown seconds in limbo after the world is ready
     * @return message key describing why the reset could not start, or {@code null} on success
     */
    public @Nullable String requestReset(@Nullable String seedInput, int countdown, int limboCountdown) {
        return this.requestReset(seedInput, countdown, limboCountdown, EnumSet.allOf(GameWorlds.Dimension.class));
    }

    /**
     * Resets only some dimensions. With the overworld this is a normal new run that keeps the dimensions that
     * were not chosen; without it the run continues and only the chosen nether/end are regenerated.
     */
    public @Nullable String requestReset(@Nullable String seedInput, int countdown, int limboCountdown, Set<GameWorlds.Dimension> selection) {
        if (this.phase == Phase.RESETTING || this.cleaningUp) {
            return "reset.already-resetting";
        }
        if (selection.isEmpty()) {
            return "reset.nothing-selected";
        }
        if (!selection.contains(GameWorlds.Dimension.OVERWORLD)) {
            if (this.phase != Phase.RUNNING && this.phase != Phase.COUNTDOWN) {
                return "pause.not-running";
            }
            Long partialSeed = this.resolveSeed(seedInput);
            Set<GameWorlds.Dimension> chosen = EnumSet.copyOf(selection);
            if (countdown <= 0) {
                this.regenerate(chosen, partialSeed);
            } else {
                this.startCountdown(CountdownKind.RESET, countdown,
                    remaining -> this.countdownTick("countdown.reset", remaining),
                    () -> {
                        this.phase = this.phaseBeforeCountdown;
                        this.regenerate(chosen, partialSeed);
                    });
            }
            return null;
        }
        this.resetSelection = EnumSet.copyOf(selection);
        if (this.phase == Phase.COUNTDOWN || this.phase == Phase.RESUMING) {
            this.cancelCountdown(false);
        }
        Long chosenSeed = this.resolveSeed(seedInput);
        if (countdown <= 0) {
            this.executeReset(chosenSeed, limboCountdown);
            return null;
        }
        // while paused everybody is in limbo already, so the phase stays PAUSED during the countdown
        CountdownKind kind = this.phase == Phase.PAUSED ? null : CountdownKind.RESET;
        this.startCountdown(kind, countdown,
            remaining -> this.countdownTick("countdown.reset", remaining),
            () -> this.executeReset(chosenSeed, limboCountdown));
        return null;
    }

    private @Nullable Long resolveSeed(@Nullable String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        return switch (input.toLowerCase(java.util.Locale.ROOT)) {
            case "random" -> Seeds.random();
            case "same", "keep" -> this.seed;
            default -> Seeds.parse(input);
        };
    }

    private long nextSeed() {
        if (this.settings().seedMode() == SeedMode.FIXED && !this.settings().fixedSeed().isBlank()) {
            return Seeds.parse(this.settings().fixedSeed());
        }
        return Seeds.random();
    }

    private void executeReset(@Nullable Long requestedSeed, int limboCountdown) {
        this.cancelCountdown(false);
        // a preloaded overworld lets everybody go straight into the new run without the limbo
        boolean everything = this.resetSelection.containsAll(EnumSet.allOf(GameWorlds.Dimension.class));
        Preloader.Ready ready = !everything || this.phase == Phase.RESETTING || this.worlds.overworld() == null ? null : this.preloader.take(requestedSeed);
        long newSeed = ready != null ? ready.seed() : requestedSeed != null ? requestedSeed : this.nextSeed();
        this.phase = Phase.RESETTING;
        this.outcome = Outcome.NONE;
        this.deaths = 0;
        this.timers.prepareNewRun();
        this.goals.resetProgress();
        this.snapshots.clear();
        this.saveAsync();

        List<String> deleting = new ArrayList<>();
        for (GameWorlds.Dimension dimension : GameWorlds.Dimension.values()) {
            if (this.worlds.isEnabled(dimension) && this.resetSelection.contains(dimension)) {
                deleting.add(this.worlds.key(dimension).asString());
            }
        }
        this.messages().send(Bukkit.getServer(), "reset.started", Placeholder.unparsed("worlds", String.join(", ", deleting)));
        if (ready != null) {
            this.instantReset(ready);
            return;
        }
        this.showBossBar(this.messages().get("bossbar.moving"), 0f, BossBar.Color.PURPLE);
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.sendToLimbo(player);
        }
        if (this.settings().reset().clearScoreboardScores()) {
            Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
            new ArrayList<>(main.getEntries()).forEach(main::resetScores);
        }
        this.unloadWorlds(newSeed, limboCountdown, 0);
    }

    private void unloadWorlds(long newSeed, int limboCountdown, int attempt) {
        // players that were mid-teleport, dead on the respawn screen or joined meanwhile
        for (World world : this.worlds.loadedGameWorlds()) {
            for (Player player : world.getPlayers()) {
                this.sendToLimbo(player);
            }
        }

        Map<GameWorlds.Dimension, Path> folders = new EnumMap<>(GameWorlds.Dimension.class);
        boolean unloaded = true;
        try {
            for (GameWorlds.Dimension dimension : GameWorlds.Dimension.values()) {
                if (!this.worlds.isEnabled(dimension) || !this.resetSelection.contains(dimension)) {
                    continue; // only the worlds that are part of the game and chosen for this reset
                }
                World world = this.worlds.world(dimension);
                // the path of a loaded world comes straight from the server, the fallback is derived from the layout
                folders.put(dimension, world != null ? world.getWorldPath() : this.worlds.folder(dimension));
                if (!this.worlds.unload(dimension)) {
                    unloaded = false;
                }
            }
        } catch (IllegalStateException e) {
            this.abortReset(e.getMessage());
            return;
        }
        if (!unloaded) {
            if (attempt < 40) {
                Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.unloadWorlds(newSeed, limboCountdown, attempt + 1), 5L);
                return;
            }
            this.abortReset("Could not unload the game worlds (another plugin may be keeping them loaded)");
            return;
        }

        this.showBossBar(this.messages().get("bossbar.deleting"), 0.15f, BossBar.Color.PURPLE);
        List<Path> leftovers = this.worlds.leftoverFolders();
        Settings.Backups backups = this.settings().backups();
        Path backupRoot = backups.enabled() ? this.backupRoot() : null;
        Path backup = this.newBackupFolder(backupRoot, this.run);
        String backupInfo = "run: " + this.run + "\nseed: " + this.seed + "\nworlds: " + this.resetWorldList() + "\n";
        // give the chunk system a moment to release the region files before deleting them
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> CompletableFuture.runAsync(() -> {
            for (Map.Entry<GameWorlds.Dimension, Path> entry : folders.entrySet()) {
                Path target = backup == null ? null
                    : backup.resolve(this.worlds.key(entry.getKey()).asString().replace(':', '_'));
                this.wipeWithRetry(entry.getKey(), entry.getValue(), target);
            }
            if (backup != null && Files.isDirectory(backup)) {
                try {
                    Files.writeString(backup.resolve("info.yml"), backupInfo, StandardCharsets.UTF_8);
                    WorldFiles.prune(backupRoot, backups.keep());
                } catch (IOException e) {
                    this.plugin.getLogger().warning("Could not finish the backup " + backup + ": " + e.getMessage());
                }
            }
            for (Path leftover : leftovers) {
                try {
                    this.worlds.wipeLeftover(leftover);
                } catch (IOException e) {
                    this.plugin.getLogger().warning("Could not delete the unused folder " + leftover + ": " + e.getMessage());
                }
            }
        }, this.io).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (error != null) {
                this.plugin.getLogger().log(Level.SEVERE, "Failed to delete the old world", error);
                this.abortReset("Could not delete the old world files: " + rootMessage(error));
                return;
            }
            this.generate(newSeed, limboCountdown, 0);
        })), 10L);
    }

    private static @Nullable Path newBackupFolder(@Nullable Path backupRoot, int run) {
        return backupRoot == null ? null : backupRoot.resolve(
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(LocalDateTime.now()) + "_run-" + run);
    }

    /**
     * Reset with a preloaded overworld: everybody moves straight into it, the old worlds are backed up and
     * deleted afterwards and nether/end are regenerated in the background.
     */
    private void instantReset(Preloader.Ready ready) {
        int oldRun = this.run;
        long oldSeed = this.seed;
        String oldWorlds = this.resetWorldList();
        World oldOverworld = this.worlds.overworld();
        Path oldOverworldFolder = oldOverworld != null ? oldOverworld.getWorldPath() : this.worlds.folder(GameWorlds.Dimension.OVERWORLD);

        this.worlds.swapOverworldSlots();
        World overworld = this.worlds.overworld();
        if (overworld == null) {
            this.worlds.swapOverworldSlots();
            this.plugin.getLogger().warning("The preloaded overworld is gone, resetting the normal way");
            this.phase = Phase.RUNNING;
            this.executeReset(ready.seed(), this.settings().reset().limboCountdown());
            return;
        }
        Location newSpawn = ready.spawn();
        newSpawn.setWorld(overworld);
        overworld.setSpawnLocation(newSpawn);
        if (this.settings().worlds().borderSize() > 0) {
            overworld.getWorldBorder().setCenter(newSpawn.getBlockX() + 0.5, newSpawn.getBlockZ() + 0.5);
        }
        if (this.settings().reset().clearScoreboardScores()) {
            Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
            new ArrayList<>(main.getEntries()).forEach(main::resetScores);
        }
        this.spawn = newSpawn.clone();
        this.seed = ready.seed();
        this.run++;
        this.resetFailed = false;
        this.cleaningUp = true;
        this.plugin.getLogger().info("Run #" + this.run + " started with the preloaded overworld (seed " + this.seed + ")");
        this.messages().send(Bukkit.getServer(), this.settings().showSeed() ? "reset.ready" : "reset.ready-no-seed", this.seedPlaceholder());
        this.startRun();

        Path backupRoot = this.settings().backups().enabled() ? this.backupRoot() : null;
        Path backup = newBackupFolder(backupRoot, oldRun);
        String backupInfo = "run: " + oldRun + "\nseed: " + oldSeed + "\nworlds: " + oldWorlds + "\n";
        Bukkit.getScheduler().runTask(this.plugin, () -> this.cleanUpOldWorlds(oldOverworld, oldOverworldFolder, backupRoot, backup, backupInfo, 0));
    }

    private void cleanUpOldWorlds(@Nullable World oldOverworld, Path oldOverworldFolder, @Nullable Path backupRoot,
                                  @Nullable Path backup, String backupInfo, int attempt) {
        Location target = this.spawn();
        Map<GameWorlds.Dimension, Path> folders = new EnumMap<>(GameWorlds.Dimension.class);
        boolean unloaded = true;
        if (oldOverworld != null && Bukkit.getWorld(oldOverworld.getKey()) != null) {
            oldOverworld.getPlayers().forEach(player -> this.placeIntoRun(player));
            unloaded = Bukkit.unloadWorld(oldOverworld, false);
        }
        for (GameWorlds.Dimension dimension : List.of(GameWorlds.Dimension.NETHER, GameWorlds.Dimension.END)) {
            if (!this.worlds.isEnabled(dimension)) {
                continue;
            }
            World world = this.worlds.world(dimension);
            if (world != null && target != null) {
                world.getPlayers().forEach(player -> player.teleport(target));
            }
            folders.put(dimension, world != null ? world.getWorldPath() : this.worlds.folder(dimension));
            if (!this.worlds.unload(dimension)) {
                unloaded = false;
            }
        }
        if (!unloaded) {
            if (attempt < 40) {
                Bukkit.getScheduler().runTaskLater(this.plugin,
                    () -> this.cleanUpOldWorlds(oldOverworld, oldOverworldFolder, backupRoot, backup, backupInfo, attempt + 1), 5L);
                return;
            }
            this.plugin.getLogger().severe("Could not unload the old worlds, they stay on disk");
            this.cleaningUp = false;
            return;
        }
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> CompletableFuture.runAsync(() -> {
            try {
                this.worlds.wipeOverworldSlot(oldOverworldFolder,
                    backup == null ? null : backup.resolve(oldOverworldKeyName(oldOverworldFolder)));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            for (Map.Entry<GameWorlds.Dimension, Path> entry : folders.entrySet()) {
                Path folderBackup = backup == null ? null : backup.resolve(this.worlds.key(entry.getKey()).asString().replace(':', '_'));
                this.wipeWithRetry(entry.getKey(), entry.getValue(), folderBackup);
            }
            if (backup != null && Files.isDirectory(backup)) {
                try {
                    Files.writeString(backup.resolve("info.yml"), backupInfo, StandardCharsets.UTF_8);
                    WorldFiles.prune(backupRoot, this.settings().backups().keep());
                } catch (IOException e) {
                    this.plugin.getLogger().warning("Could not finish the backup " + backup + ": " + e.getMessage());
                }
            }
        }, this.io).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (error != null) {
                this.plugin.getLogger().log(Level.SEVERE, "Failed to delete the old worlds", error);
            }
            // nether and end of the new run, one per tick
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (this.worlds.isEnabled(GameWorlds.Dimension.NETHER)) {
                    this.worlds.loadOrCreate(GameWorlds.Dimension.NETHER, this.seed);
                }
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    if (this.worlds.isEnabled(GameWorlds.Dimension.END)) {
                        this.worlds.loadOrCreate(GameWorlds.Dimension.END, this.seed);
                    }
                    this.cleaningUp = false;
                    this.logGameWorlds();
                    this.saveAsync();
                });
            });
        })), 10L);
    }

    private String oldOverworldKeyName(Path folder) {
        return this.worlds.namespace() + "_" + folder.getFileName();
    }

    /** Where backups are stored: relative paths are inside the plugin folder. */
    public Path backupRoot() {
        Path configured = Path.of(this.settings().backups().folder());
        return configured.isAbsolute() ? configured : this.plugin.getDataFolder().toPath().resolve(configured);
    }

    private void wipeWithRetry(GameWorlds.Dimension dimension, Path folder, @Nullable Path backup) {
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                // a retry continues where the last attempt stopped: already moved files are gone from the source
                this.worlds.wipe(dimension, folder, backup);
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new java.io.UncheckedIOException(last);
    }

    private void generate(long newSeed, int limboCountdown, int index) {
        GameWorlds.Dimension[] dimensions = GameWorlds.Dimension.values();
        if (index >= dimensions.length) {
            this.findSpawn(newSeed, limboCountdown);
            return;
        }
        GameWorlds.Dimension dimension = dimensions[index];
        if (this.worlds.isEnabled(dimension) && this.resetSelection.contains(dimension)) {
            this.showBossBar(this.messages().get("bossbar.generating",
                Placeholder.component("dimension", this.messages().get("dimension." + dimension.path().replace('_', '-')))),
                0.25f + 0.5f * index / dimensions.length, BossBar.Color.BLUE);
            if (this.worlds.loadOrCreate(dimension, newSeed) == null && dimension == GameWorlds.Dimension.OVERWORLD) {
                this.abortReset("Could not create the overworld");
                return;
            }
        }
        // one dimension per tick keeps the server responsive
        Bukkit.getScheduler().runTask(this.plugin, () -> this.generate(newSeed, limboCountdown, index + 1));
    }

    private void findSpawn(long newSeed, int limboCountdown) {
        World overworld = this.worlds.overworld();
        if (overworld == null) {
            this.abortReset("The overworld disappeared during the reset");
            return;
        }
        this.showBossBar(this.messages().get("bossbar.spawn"), 0.8f, BossBar.Color.BLUE);
        SpawnFinder.find(overworld, this.settings().spawn().safeSearchRadius())
            .thenCompose(found -> SpawnFinder.preload(this.plugin, found, this.settings().spawn().preloadRadius()).thenApply(ignored -> found))
            .whenComplete((found, error) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                Location target = found;
                if (error != null || target == null) {
                    this.plugin.getLogger().log(Level.WARNING, "Spawn search failed, using the world spawn", error);
                    target = overworld.getSpawnLocation().toCenterLocation();
                }
                this.finishReset(newSeed, target, limboCountdown);
            }));
    }

    private void finishReset(long newSeed, Location newSpawn, int limboCountdown) {
        World overworld = newSpawn.getWorld();
        overworld.setSpawnLocation(newSpawn);
        if (this.settings().worlds().borderSize() > 0) {
            overworld.getWorldBorder().setCenter(newSpawn.getBlockX() + 0.5, newSpawn.getBlockZ() + 0.5);
        }
        this.spawn = newSpawn.clone();
        this.seed = newSeed;
        this.run++;
        this.resetFailed = false;
        this.awaitingRelease = true;
        this.saveAsync();
        this.plugin.getLogger().info("Run #" + this.run + " is ready (seed " + newSeed + ")");
        this.logGameWorlds();

        this.showBossBar(this.messages().get("bossbar.ready"), 1f, BossBar.Color.GREEN);
        this.messages().send(Bukkit.getServer(), this.settings().showSeed() ? "reset.ready" : "reset.ready-no-seed", this.seedPlaceholder());
        if (limboCountdown <= 0) {
            this.startRun();
            return;
        }
        this.startCountdown(null, limboCountdown,
            remaining -> this.countdownTick("countdown.start", remaining),
            this::startRun);
    }

    /**
     * Regenerates only the nether and/or end while the run goes on. Players inside are moved to the spawn,
     * the old files are backed up like on a normal reset.
     */
    private void regenerate(Set<GameWorlds.Dimension> dimensions, @Nullable Long requestedSeed) {
        long dimensionSeed = requestedSeed != null ? requestedSeed : this.seed;
        Location target = this.spawn();
        Map<GameWorlds.Dimension, Path> folders = new EnumMap<>(GameWorlds.Dimension.class);
        for (GameWorlds.Dimension dimension : dimensions) {
            if (!this.worlds.isEnabled(dimension)) {
                continue;
            }
            World world = this.worlds.world(dimension);
            if (world != null && target != null) {
                world.getPlayers().forEach(player -> player.teleport(target));
            }
            folders.put(dimension, world != null ? world.getWorldPath() : this.worlds.folder(dimension));
            if (!this.worlds.unload(dimension)) {
                this.messages().send(Bukkit.getServer(), "reset.failed", Placeholder.unparsed("reason", "could not unload " + this.worlds.key(dimension)));
                return;
            }
        }
        if (folders.isEmpty()) {
            return;
        }
        this.cleaningUp = true;
        List<String> names = folders.keySet().stream().map(dimension -> this.worlds.key(dimension).asString()).toList();
        this.messages().send(Bukkit.getServer(), "reset.partial-started", Placeholder.unparsed("worlds", String.join(", ", names)));
        Path backupRoot = this.settings().backups().enabled() ? this.backupRoot() : null;
        Path backup = newBackupFolder(backupRoot, this.run);
        String backupInfo = "run: " + this.run + "\nseed: " + this.seed + "\nworlds: " + String.join(", ", names) + "\n";
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> CompletableFuture.runAsync(() -> {
            for (Map.Entry<GameWorlds.Dimension, Path> entry : folders.entrySet()) {
                Path folderBackup = backup == null ? null : backup.resolve(this.worlds.key(entry.getKey()).asString().replace(':', '_'));
                this.wipeWithRetry(entry.getKey(), entry.getValue(), folderBackup);
            }
            if (backup != null && Files.isDirectory(backup)) {
                try {
                    Files.writeString(backup.resolve("info.yml"), backupInfo, StandardCharsets.UTF_8);
                    WorldFiles.prune(backupRoot, this.settings().backups().keep());
                } catch (IOException e) {
                    this.plugin.getLogger().warning("Could not finish the backup " + backup + ": " + e.getMessage());
                }
            }
        }, this.io).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (error != null) {
                this.plugin.getLogger().log(Level.SEVERE, "Failed to delete " + names, error);
            }
            for (GameWorlds.Dimension dimension : folders.keySet()) {
                this.worlds.loadOrCreate(dimension, dimensionSeed);
            }
            this.cleaningUp = false;
            this.logGameWorlds();
            this.messages().send(Bukkit.getServer(), "reset.partial-done", Placeholder.unparsed("worlds", String.join(", ", names)));
            this.saveAsync();
        })), 10L);
    }

    private void startRun() {
        this.resetSelection = EnumSet.allOf(GameWorlds.Dimension.class);
        this.phase = Phase.RUNNING;
        this.runStartedNanos = System.nanoTime();
        this.awaitingRelease = false;
        World overworld = this.worlds.overworld();
        if (overworld != null) {
            overworld.removePluginChunkTickets(this.plugin);
        }
        this.hideBossBar();
        List<Player> participantsNow = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.placeIntoRun(player);
            participantsNow.add(player);
            // without a goal (free play) there is nothing to announce below "GO!"
            Component subtitle = this.goals.goal().isNone() ? Component.empty() : this.messages().get("title.go-subtitle", this.goalPlaceholder());
            player.showTitle(Title.title(this.messages().get("title.go"), subtitle, COUNTDOWN_TIMES));
            player.playSound(GO_SOUND);
        }
        this.timers.begin(participantsNow);
        this.plugin.manhunt().onRunStart();
        this.saveAsync();
    }

    private void abortReset(String reason) {
        this.plugin.getLogger().severe("Reset failed: " + reason);
        this.messages().send(Bukkit.getServer(), "reset.failed", Placeholder.unparsed("reason", reason));
        // keep everyone safe in limbo; an admin can retry with /magicreset reset
        this.phase = Phase.PAUSED;
        this.resetFailed = true;
        this.showBossBar(this.messages().get("bossbar.failed"), 1f, BossBar.Color.RED);
        this.saveAsync();
    }

    /** Fresh participant of the current run: reset and teleport to spawn. */
    private void placeIntoRun(Player player) {
        Location target = this.spawn();
        if (target == null) {
            return;
        }
        if (player.isDead()) {
            return; // handled by the respawn listener
        }
        PlayerPreparer.resetForNewRun(player, this.settings().reset().player());
        player.teleport(target);
        this.participants.put(player.getUniqueId(), this.run);
        this.timers.onParticipantJoin(player);
        this.plugin.manhunt().prepare(player);
    }

    private void sendToLimbo(Player player) {
        World limbo = this.worlds.limbo();
        if (limbo == null) {
            return;
        }
        if (player.isDead()) {
            // the respawn listener sends them to limbo
            player.spigot().respawn();
            return;
        }
        PlayerPreparer.prepareForLimbo(player, this.settings().limbo().gameMode());
        if (!this.worlds.isLimbo(player.getWorld())) {
            player.teleport(this.worlds.limboSpawn(limbo));
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Pause / resume (limbo pause)
    // ------------------------------------------------------------------------------------------------------------

    public @Nullable String requestPause(int countdown) {
        if (this.phase != Phase.RUNNING) {
            return this.phase == Phase.PAUSED || this.phase == Phase.RESUMING ? "pause.already-paused" : "pause.not-running";
        }
        if (countdown <= 0) {
            this.pause();
            return null;
        }
        this.startCountdown(CountdownKind.PAUSE, countdown,
            remaining -> this.countdownTick("countdown.pause", remaining),
            this::pause);
        return null;
    }

    private void pause() {
        this.phase = Phase.PAUSED;
        this.timers.pauseAll();
        List<Player> captured = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (this.capture(player)) {
                captured.add(player);
            }
        }
        // persist before touching any inventory so nothing is lost on a crash
        this.saveNow();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (captured.contains(player)) {
                player.getInventory().clear();
                player.setItemOnCursor(null);
            }
            this.sendToLimbo(player);
        }
        this.showBossBar(this.messages().get("bossbar.paused"), 1f, BossBar.Color.YELLOW);
        this.messages().send(Bukkit.getServer(), "pause.paused");
    }

    /** Saves the player's state (if they are in the game) and moves them to limbo. */
    private void parkInLimbo(Player player) {
        if (this.capture(player)) {
            this.saveNow();
            player.getInventory().clear();
            player.setItemOnCursor(null);
        }
        this.sendToLimbo(player);
    }

    private boolean capture(Player player) {
        if (!this.worlds.isGameWorld(player.getWorld()) || this.snapshots.containsKey(player.getUniqueId()) || player.isDead()) {
            return false;
        }
        Integer playerRun = this.participants.get(player.getUniqueId());
        if (playerRun == null || playerRun != this.run) {
            return false; // gear from an older run is never carried into this one
        }
        // puts cursor and crafting-grid items back into the inventory so the snapshot contains them
        player.closeInventory();
        this.snapshots.put(player.getUniqueId(), PlayerSnapshot.capture(player));
        return true;
    }

    public @Nullable String requestResume(int countdown) {
        if (this.phase != Phase.PAUSED) {
            return "pause.not-paused";
        }
        if (this.resetFailed || this.worlds.overworld() == null || this.spawn() == null) {
            return "pause.no-world";
        }
        if (countdown <= 0) {
            this.resume();
            return null;
        }
        if (this.cancelCountdown(false)) {
            this.messages().send(Bukkit.getServer(), "countdown.cancelled");
        }
        this.phase = Phase.RESUMING;
        this.startCountdown(null, countdown,
            remaining -> this.countdownTick("countdown.resume", remaining),
            this::resume);
        return null;
    }

    private void resume() {
        this.phase = Phase.RUNNING;
        this.hideBossBar();
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.returnFromLimbo(player);
            player.showTitle(Title.title(this.messages().get("title.resumed"), Component.empty(), COUNTDOWN_TIMES));
            player.playSound(GO_SOUND);
        }
        if (!this.timers.isArmed()) {
            // the run was never released (server restarted during the limbo countdown)
            this.timers.begin(List.copyOf(Bukkit.getOnlinePlayers()));
        } else {
            this.timers.resumeAll();
        }
        this.saveAsync();
        this.messages().send(Bukkit.getServer(), "pause.resumed");
    }

    private void returnFromLimbo(Player player) {
        PlayerSnapshot snapshot = this.snapshots.remove(player.getUniqueId());
        if (snapshot != null) {
            Location location = snapshot.location();
            if (location == null || !this.worlds.isGameWorld(location.getWorld())) {
                location = this.spawn();
            }
            if (location != null) {
                player.teleport(location);
            }
            snapshot.apply(player);
            this.timers.onParticipantJoin(player);
            this.saveAsync();
            return;
        }
        Integer playerRun = this.participants.get(player.getUniqueId());
        if (playerRun == null || playerRun != this.run) {
            this.placeIntoRun(player);
        } else if (!this.worlds.isGameWorld(player.getWorld())) {
            Location target = this.spawn();
            if (target != null) {
                player.setGameMode(this.settings().reset().player().gameMode());
                player.teleport(target);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Countdowns
    // ------------------------------------------------------------------------------------------------------------

    private void startCountdown(@Nullable CountdownKind kind, int seconds, IntConsumer onSecond, Runnable onDone) {
        this.cancelCountdown(false);
        if (kind != null) {
            this.phaseBeforeCountdown = this.phase;
            this.phase = Phase.COUNTDOWN;
        }
        this.countdownKind = kind;
        int[] remaining = {seconds};
        this.countdownTask = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            if (remaining[0] <= 0) {
                this.cancelCountdownTask();
                this.countdownKind = null;
                onDone.run();
                return;
            }
            onSecond.accept(remaining[0]);
            remaining[0]--;
        }, 0L, 20L);
    }

    private void countdownTick(String key, int remaining) {
        TagResolver seconds = Placeholder.unparsed("seconds", Integer.toString(remaining));
        Title title = Title.title(this.messages().get(key + ".title", seconds), this.messages().get(key + ".subtitle", seconds), COUNTDOWN_TIMES);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(title);
            player.playSound(TICK_SOUND);
        }
        if (remaining <= 5 || remaining % 10 == 0) {
            this.messages().send(Bukkit.getServer(), key + ".chat", seconds);
        }
    }

    /**
     * Cancels a pending reset/pause countdown or a resume countdown.
     *
     * @return {@code true} if something was cancelled
     */
    public boolean cancelCountdown(boolean announce) {
        if (this.countdownTask == null) {
            return false;
        }
        this.cancelCountdownTask();
        if (this.phase == Phase.COUNTDOWN) {
            this.phase = this.phaseBeforeCountdown;
        } else if (this.phase == Phase.RESUMING) {
            this.phase = Phase.PAUSED;
        } else if (this.phase == Phase.RESETTING) {
            // the countdown in limbo after a reset cannot be cancelled, it would strand the players
            this.startRun();
            return true;
        }
        this.countdownKind = null;
        if (announce) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.clearTitle();
            }
            this.messages().send(Bukkit.getServer(), "countdown.cancelled");
        }
        return true;
    }

    private void cancelCountdownTask() {
        if (this.countdownTask != null) {
            this.countdownTask.cancel();
            this.countdownTask = null;
        }
    }

    public boolean hasCountdown() {
        return this.countdownTask != null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Goal and deaths
    // ------------------------------------------------------------------------------------------------------------

    /** Whether the player currently counts as an active runner (goal progress, deaths). */
    public boolean isActiveRunner(Player player) {
        if ((this.phase != Phase.RUNNING && this.phase != Phase.COUNTDOWN) || this.outcome != Outcome.NONE) {
            return false;
        }
        if (!this.worlds.isGameWorld(player.getWorld()) || player.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        if (this.timers.scope() == TimerScope.INDIVIDUAL) {
            return this.timers.timer(player.getUniqueId()).state() != RunTimer.State.FINISHED;
        }
        return true;
    }

    public void completeGoal(Player player) {
        if (!this.isActiveRunner(player)) {
            return;
        }
        if (this.plugin.manhunt().isEnabled()) {
            this.endManhunt(ManhuntService.Winner.TARGETS, player);
            return;
        }
        Settings.GoalSettings config = this.settings().goal();
        if (this.timers.scope() == TimerScope.GLOBAL) {
            if (this.timers.global().state() == RunTimer.State.IDLE) {
                this.timers.global().start(); // MANUAL trigger never started: finish at 0 instead of staying idle
            }
            this.timers.global().finish();
            this.timers.endRun();
            this.outcome = Outcome.COMPLETED;
            long millis = this.timers.global().millis(this.timers.mode());
            String time = this.formatPrecise(millis);
            this.recordTime(player, millis);
            TagResolver resolvers = TagResolver.resolver(Placeholder.unparsed("player", player.getName()), Placeholder.unparsed("time", time), this.goalPlaceholder());
            this.messages().send(Bukkit.getServer(), "goal.completed.global", resolvers);
            Title title = Title.title(this.messages().get("title.completed", resolvers), this.messages().get("title.completed-subtitle", resolvers));
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.showTitle(title);
                online.playSound(COMPLETE_SOUND);
                if (config.spectatorOnFinish() && this.worlds.isGameWorld(online.getWorld())) {
                    online.setGameMode(GameMode.SPECTATOR);
                }
            }
            this.scheduleFollowUpReset(config.resetAfter());
        } else {
            RunTimer timer = this.timers.timer(player.getUniqueId());
            if (timer.state() == RunTimer.State.IDLE) {
                timer.start();
            }
            timer.finish();
            long millis = timer.millis(this.timers.mode());
            String time = this.formatPrecise(millis);
            this.recordTime(player, millis);
            TagResolver resolvers = TagResolver.resolver(Placeholder.unparsed("player", player.getName()), Placeholder.unparsed("time", time), this.goalPlaceholder());
            this.messages().send(Bukkit.getServer(), "goal.completed.individual", resolvers);
            player.showTitle(Title.title(this.messages().get("title.completed", resolvers), this.messages().get("title.completed-subtitle", resolvers)));
            player.playSound(COMPLETE_SOUND);
            if (config.spectatorOnFinish()) {
                player.setGameMode(GameMode.SPECTATOR);
            }
            boolean everyoneDone = Bukkit.getOnlinePlayers().stream()
                .filter(online -> this.worlds.isGameWorld(online.getWorld()))
                .allMatch(online -> this.timers.timer(online.getUniqueId()).state() == RunTimer.State.FINISHED);
            if (everyoneDone) {
                this.outcome = Outcome.COMPLETED;
                this.timers.endRun();
                this.messages().send(Bukkit.getServer(), "goal.completed.all");
                this.scheduleFollowUpReset(config.resetAfter());
            }
        }
        this.saveAsync();
    }

    /**
     * Ends a manhunt with a big title for everybody.
     *
     * @param decisive the target that reached the goal, or the hunter that got the last kill
     */
    private void endManhunt(ManhuntService.Winner winner, Player decisive) {
        this.outcome = Outcome.COMPLETED;
        this.timers.finishAll();
        long millis = this.timers.displayed(decisive).millis(this.timers.mode());
        this.plugin.manhunt().onRunEnd();
        String time = this.formatPrecise(millis);
        if (winner == ManhuntService.Winner.TARGETS) {
            this.recordTime(decisive, millis);
        }
        String key = winner == ManhuntService.Winner.TARGETS ? "manhunt.win-targets" : "manhunt.win-hunters";
        TagResolver resolvers = TagResolver.resolver(
            Placeholder.unparsed("player", decisive.getName()),
            Placeholder.unparsed("targets", this.plugin.manhunt().targetNames()),
            Placeholder.unparsed("time", time),
            this.goalPlaceholder());
        this.messages().send(Bukkit.getServer(), key + ".chat", resolvers);
        Title title = Title.title(this.messages().get(key + ".title", resolvers), this.messages().get(key + ".subtitle", resolvers),
            Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(6), Duration.ofSeconds(1)));
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.showTitle(title);
            online.playSound(winner == ManhuntService.Winner.TARGETS ? COMPLETE_SOUND : FAIL_SOUND);
        }
        this.scheduleFollowUpReset(this.settings().manhunt().resetAfter());
        this.saveAsync();
    }

    /** Puts a completion time on the leaderboard of the current goal and announces records. */
    private void recordTime(Player player, long millis) {
        if (!this.settings().timer().enabled()) {
            return; // nothing to rank without a timer
        }
        String category = Leaderboard.category(this.goals.goal(), this.timers.mode(), this.plugin.manhunt().isEnabled());
        int rank = this.plugin.leaderboard().record(category,
            new Leaderboard.Entry(player.getUniqueId(), player.getName(), millis, System.currentTimeMillis(), this.seed));
        if (rank > 0) {
            this.messages().send(Bukkit.getServer(), rank == 1 ? "leaderboard.new-record" : "leaderboard.new-entry",
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("rank", Integer.toString(rank)),
                Placeholder.unparsed("time", this.formatPrecise(millis)));
        }
    }

    public void onDeath(Player player) {
        if (!this.isActiveRunner(player)) {
            return;
        }
        ManhuntService manhunt = this.plugin.manhunt();
        if (manhunt.isEnabled()) {
            // in a manhunt only the targets' lives matter, the normal death rules are not used
            this.deaths++;
            if (manhunt.role(player) == ManhuntService.Role.TARGET && manhunt.onTargetDeath(player)) {
                Player killer = player.getKiller();
                this.endManhunt(ManhuntService.Winner.HUNTERS, killer != null ? killer : player);
            }
            this.saveAsync();
            return;
        }
        Settings.Deaths config = this.settings().deaths();
        this.deaths++;
        switch (config.mode()) {
            case OFF -> {
            }
            case HARDCORE -> this.fail(player);
            case SHARED_LIVES -> {
                int left = config.sharedLives() - this.deaths;
                if (left <= 0) {
                    this.fail(player);
                } else {
                    this.messages().send(Bukkit.getServer(), "deaths.life-lost",
                        Placeholder.unparsed("player", player.getName()),
                        Placeholder.unparsed("lives", Integer.toString(left)));
                }
            }
        }
        this.saveAsync();
    }

    private void fail(Player cause) {
        this.outcome = Outcome.FAILED;
        this.timers.finishAll();
        String time = this.formatPrecise(this.timers.global().millis(this.timers.mode()));
        TagResolver resolvers = TagResolver.resolver(Placeholder.unparsed("player", cause.getName()), Placeholder.unparsed("time", time));
        this.messages().send(Bukkit.getServer(), "deaths.failed", resolvers);
        Title title = Title.title(this.messages().get("title.failed", resolvers), this.messages().get("title.failed-subtitle", resolvers));
        boolean spectate = this.settings().deaths().spectatorOnFail();
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.showTitle(title);
            online.playSound(FAIL_SOUND);
            if (spectate && !online.isDead() && this.worlds.isGameWorld(online.getWorld())) {
                online.setGameMode(GameMode.SPECTATOR);
            }
        }
        this.scheduleFollowUpReset(this.settings().deaths().resetAfter());
    }

    private void scheduleFollowUpReset(int seconds) {
        if (seconds < 0) {
            return;
        }
        // one tick later: this can be called from inside PlayerDeathEvent
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (this.phase == Phase.RUNNING && this.outcome != Outcome.NONE) {
                this.requestReset(null, seconds, this.settings().reset().limboCountdown());
            }
        });
    }

    // ------------------------------------------------------------------------------------------------------------
    // Player placement
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Decides where a joining player spawns. Called asynchronously, only reads thread-safe state.
     */
    public Location joinLocation(UUID uuid, Location current) {
        Phase now = this.phase;
        Location limbo = this.limboSpawn;
        Location gameSpawn = this.spawn;
        if (now == Phase.RESETTING || ((now == Phase.PAUSED || now == Phase.RESUMING) && this.snapshots.containsKey(uuid))) {
            return limbo == null ? current : limbo.clone();
        }
        if ((now == Phase.RUNNING || now == Phase.COUNTDOWN) && gameSpawn != null && !this.snapshots.containsKey(uuid)) {
            Integer playerRun = this.participants.get(uuid);
            if (playerRun == null || playerRun != this.run || !this.worlds.isGameWorld(current.getWorld())) {
                Location target = gameSpawn.clone();
                if (target.getWorld() == null) {
                    return current;
                }
                return target;
            }
        }
        return current;
    }

    /** Puts a player that just joined where they belong. */
    public void onJoin(Player player) {
        switch (this.phase) {
            case RESETTING -> {
                this.sendToLimbo(player);
                player.showBossBar(this.bossBar);
            }
            case PAUSED, RESUMING -> {
                this.parkInLimbo(player);
                player.showBossBar(this.bossBar);
            }
            case RUNNING, COUNTDOWN -> {
                player.hideBossBar(this.bossBar);
                if (this.snapshots.containsKey(player.getUniqueId())) {
                    this.returnFromLimbo(player);
                    return;
                }
                Integer playerRun = this.participants.get(player.getUniqueId());
                if (playerRun == null || playerRun != this.run) {
                    this.placeIntoRun(player);
                } else if (!this.worlds.isGameWorld(player.getWorld())) {
                    Location target = this.spawn();
                    if (target != null) {
                        player.setGameMode(this.settings().reset().player().gameMode());
                        player.teleport(target);
                    }
                    this.timers.onParticipantJoin(player);
                } else {
                    this.timers.onParticipantJoin(player);
                }
                boolean spectate = (this.outcome == Outcome.FAILED && this.settings().deaths().spectatorOnFail())
                    || (this.outcome == Outcome.COMPLETED && this.settings().goal().spectatorOnFinish());
                if (spectate && this.worlds.isGameWorld(player.getWorld())) {
                    player.setGameMode(GameMode.SPECTATOR);
                }
            }
        }
    }

    /**
     * @return where the player should respawn, or {@code null} to keep the vanilla location
     */
    public @Nullable Location respawnLocation(Player player, Location vanilla, boolean blockSpawn) {
        if (this.phase == Phase.RESETTING || this.phase == Phase.PAUSED || this.phase == Phase.RESUMING) {
            World limbo = this.worlds.limbo();
            return limbo == null ? null : this.worlds.limboSpawn(limbo);
        }
        if (blockSpawn && this.worlds.isGameWorld(vanilla.getWorld())) {
            return null;
        }
        Location target = this.spawn();
        if (target == null) {
            return null;
        }
        // a player that died in an older run (e.g. during the reset) starts fresh
        Integer playerRun = this.participants.get(player.getUniqueId());
        if (playerRun == null || playerRun != this.run) {
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (player.isOnline()) {
                    this.placeIntoRun(player);
                }
            });
        }
        return target;
    }

    /** Applied one tick after a respawn. */
    public void afterRespawn(Player player) {
        if (this.phase == Phase.RESETTING || this.phase == Phase.PAUSED || this.phase == Phase.RESUMING) {
            PlayerPreparer.prepareForLimbo(player, this.settings().limbo().gameMode());
            return;
        }
        if (this.outcome == Outcome.FAILED && this.settings().deaths().spectatorOnFail() && this.worlds.isGameWorld(player.getWorld())) {
            player.setGameMode(GameMode.SPECTATOR);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // HUD helpers
    // ------------------------------------------------------------------------------------------------------------

    private void showBossBar(Component name, float progress, BossBar.Color color) {
        this.bossBar.name(name);
        this.bossBar.progress(Math.clamp(progress, 0f, 1f));
        this.bossBar.color(color);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showBossBar(this.bossBar);
        }
    }

    private void hideBossBar() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(this.bossBar);
        }
    }

    /** One console line per game dimension, so a missing world is easy to spot. */
    private void logGameWorlds() {
        for (GameWorlds.Dimension dimension : GameWorlds.Dimension.values()) {
            if (!this.worlds.isEnabled(dimension)) {
                continue;
            }
            boolean loaded = this.worlds.world(dimension) != null;
            String line = "Game " + dimension.path() + ": " + this.worlds.key(dimension) + (loaded ? " (loaded)" : " (NOT LOADED)");
            if (loaded) {
                this.plugin.getLogger().info(line);
            } else {
                this.plugin.getLogger().warning(line);
            }
        }
    }

    /** "magicreset:overworld, minecraft:the_nether, minecraft:the_end" – the worlds a reset deletes. */
    public String resetWorldList() {
        List<String> keys = new ArrayList<>();
        for (GameWorlds.Dimension dimension : GameWorlds.Dimension.values()) {
            if (this.worlds.isEnabled(dimension)) {
                keys.add(this.worlds.key(dimension).asString());
            }
        }
        return String.join(", ", keys);
    }

    public String formatPrecise(long millis) {
        // without a timer there is no time to show in titles and messages
        return this.settings().timer().enabled() ? TimeFormat.format(millis, 3, this.settings().timer().style()) : "-";
    }

    /** The seed, or "hidden" when seed.show is off. */
    public TagResolver seedPlaceholder() {
        return Placeholder.unparsed("seed", this.settings().showSeed() ? Long.toString(this.seed) : this.messages().raw("seed.hidden"));
    }

    public TagResolver goalPlaceholder() {
        return Placeholder.component("goal", this.goals.describe(this.goals.goal()));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------------------------------------------------

    public Phase phase() {
        return this.phase;
    }

    public Outcome outcome() {
        return this.outcome;
    }

    public int run() {
        return this.run;
    }

    public long seed() {
        return this.seed;
    }

    public int deaths() {
        return this.deaths;
    }

    public int livesLeft() {
        return Math.max(0, this.settings().deaths().sharedLives() - this.deaths);
    }

    public boolean isPauseCountdown() {
        return this.countdownKind == CountdownKind.PAUSE;
    }

    public @Nullable Location spawn() {
        Location current = this.spawn;
        if (current == null) {
            return null;
        }
        World overworld = this.worlds.overworld();
        if (overworld == null) {
            return null;
        }
        // always resolve against the loaded world object of the current run
        Location resolved = current.clone();
        resolved.setWorld(overworld);
        return resolved;
    }

    public int pausedPlayers() {
        return this.snapshots.size();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------------------------------------------------

    private File dataFile() {
        return new File(this.plugin.getDataFolder(), DATA_FILE);
    }

    /**
     * Reads data.yml. Must be called once before {@link #start()}.
     */
    public void load() {
        this.savedPhase = this.loadData();
    }

    private Phase loadData() {
        File file = this.dataFile();
        if (!file.exists()) {
            return Phase.RESETTING;
        }
        YamlConfiguration data = new YamlConfiguration();
        try {
            data.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            File backup = new File(file.getParentFile(), DATA_FILE + ".corrupt-" + System.currentTimeMillis());
            boolean moved = file.renameTo(backup);
            this.plugin.getLogger().log(Level.SEVERE, "Could not read " + DATA_FILE + ", starting with a fresh state"
                + (moved ? " (the old file was kept as " + backup.getName() + ")" : ""), e);
            return Phase.RESETTING;
        }
        this.run = data.getInt("run", 0);
        this.awaitingRelease = data.getBoolean("awaiting-release");
        this.resetFailed = data.getBoolean("reset-failed");
        this.seed = data.getLong("seed");
        this.deaths = data.getInt("deaths");
        try {
            this.outcome = Outcome.valueOf(data.getString("outcome", Outcome.NONE.name()));
        } catch (IllegalArgumentException e) {
            this.outcome = Outcome.NONE;
        }
        ConfigurationSection spawnSection = data.getConfigurationSection("spawn");
        if (spawnSection != null) {
            this.spawn = new Location(null, spawnSection.getDouble("x"), spawnSection.getDouble("y"), spawnSection.getDouble("z"),
                (float) spawnSection.getDouble("yaw"), (float) spawnSection.getDouble("pitch"));
        }
        ConfigurationSection participantSection = data.getConfigurationSection("participants");
        if (participantSection != null) {
            for (String key : participantSection.getKeys(false)) {
                try {
                    this.participants.put(UUID.fromString(key), participantSection.getInt(key));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed entries
                }
            }
        }
        ConfigurationSection pausedSection = data.getConfigurationSection("paused-players");
        if (pausedSection != null) {
            for (String key : pausedSection.getKeys(false)) {
                try {
                    PlayerSnapshot snapshot = PlayerSnapshot.load(pausedSection.getConfigurationSection(key));
                    if (snapshot != null) {
                        this.snapshots.put(UUID.fromString(key), snapshot);
                    } else {
                        this.plugin.getLogger().warning("Could not restore the paused state of " + key);
                    }
                } catch (IllegalArgumentException ignored) {
                    // skip malformed entries
                }
            }
        }
        this.timers.load(data.getConfigurationSection("timer"));
        this.goals.load(data.getConfigurationSection("goal-progress"));
        this.worlds.setOverworldSlot(data.getString("overworld-slot", GameWorlds.OVERWORLD_SLOTS.getFirst()));
        this.preloader.load(data.getConfigurationSection("preload"));
        this.loadedExtra = data.getConfigurationSection("extra");
        try {
            return Phase.valueOf(data.getString("phase", Phase.RESETTING.name()));
        } catch (IllegalArgumentException e) {
            return Phase.RESETTING;
        }
    }

    /** Extra sections stored by other components (auto reset, scoreboard preferences). */
    public @Nullable ConfigurationSection loadedExtra() {
        return this.loadedExtra;
    }

    private String serialize() {
        YamlConfiguration data = new YamlConfiguration();
        data.options().setHeader(List.of("MagicReset runtime state. Do not edit while the server is running."));
        Phase persisted = this.phase == Phase.COUNTDOWN ? this.phaseBeforeCountdown : this.phase;
        data.set("phase", persisted.name());
        data.set("outcome", this.outcome.name());
        data.set("awaiting-release", this.awaitingRelease);
        data.set("reset-failed", this.resetFailed);
        data.set("run", this.run);
        data.set("seed", this.seed);
        data.set("deaths", this.deaths);
        Location currentSpawn = this.spawn;
        if (currentSpawn != null) {
            ConfigurationSection spawnSection = data.createSection("spawn");
            spawnSection.set("x", currentSpawn.getX());
            spawnSection.set("y", currentSpawn.getY());
            spawnSection.set("z", currentSpawn.getZ());
            spawnSection.set("yaw", currentSpawn.getYaw());
            spawnSection.set("pitch", currentSpawn.getPitch());
        }
        ConfigurationSection participantSection = data.createSection("participants");
        this.participants.forEach((uuid, playerRun) -> participantSection.set(uuid.toString(), playerRun));
        ConfigurationSection pausedSection = data.createSection("paused-players");
        this.snapshots.forEach((uuid, snapshot) -> snapshot.save(pausedSection.createSection(uuid.toString())));
        this.timers.save(data.createSection("timer"));
        this.goals.save(data.createSection("goal-progress"));
        data.set("overworld-slot", this.worlds.overworldSlot());
        this.preloader.save(data.createSection("preload"));
        this.plugin.saveExtra(data.createSection("extra"));
        return data.saveToString();
    }

    /** Serializes on the calling (main) thread and writes the file in the background. */
    public void saveAsync() {
        String content = this.serialize();
        if (this.io.isShutdown()) {
            this.write(content);
            return;
        }
        this.io.execute(() -> this.write(content));
    }

    /** Serializes and writes synchronously. */
    public void saveNow() {
        String content = this.serialize();
        if (this.io.isShutdown()) {
            this.write(content);
            return;
        }
        try {
            // keep the order with pending background writes
            this.io.submit(() -> this.write(content)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // the write stays queued behind the backlog, writing here could be overwritten by older content
            this.plugin.getLogger().warning("Saving " + DATA_FILE + " is slow, it continues in the background");
        }
    }

    private void write(String content) {
        Path target = this.dataFile().toPath();
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), DATA_FILE, ".tmp");
            try {
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not save " + DATA_FILE, e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
