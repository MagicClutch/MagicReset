package dev.magicclutch.magicreset.game;

import dev.magicclutch.magicreset.MagicReset;
import dev.magicclutch.magicreset.config.Messages;
import dev.magicclutch.magicreset.config.Settings;
import dev.magicclutch.magicreset.goal.GoalService;
import dev.magicclutch.magicreset.timer.RunTimer;
import dev.magicclutch.magicreset.timer.TimerScope;
import dev.magicclutch.magicreset.timer.TimerService;
import dev.magicclutch.magicreset.util.Seeds;
import dev.magicclutch.magicreset.util.TimeFormat;
import dev.magicclutch.magicreset.world.GameWorlds;
import dev.magicclutch.magicreset.world.SpawnFinder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
    private Phase savedPhase = Phase.RESETTING;

    public GameManager(MagicReset plugin, GameWorlds worlds, TimerService timers, GoalService goals) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.timers = timers;
        this.goals = goals;
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
        Phase previous = this.savedPhase;

        boolean worldsOnDisk = this.worlds.hasSavedData(GameWorlds.Dimension.OVERWORLD);
        if (previous == Phase.RESETTING || !worldsOnDisk || this.run == 0) {
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
        this.phase = switch (previous) {
            case PAUSED, RESUMING -> Phase.PAUSED;
            default -> Phase.RUNNING;
        };
        if (this.phase == Phase.PAUSED) {
            this.showBossBar(this.messages().get("bossbar.paused"), 1f, BossBar.Color.YELLOW);
        }
        this.plugin.getLogger().info("Loaded run #" + this.run + " (seed " + this.seed + ")");
        return true;
    }

    public void shutdown() {
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
        if (this.phase == Phase.RESETTING) {
            return "reset.already-resetting";
        }
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
        long newSeed = requestedSeed != null ? requestedSeed : this.nextSeed();
        this.phase = Phase.RESETTING;
        this.outcome = Outcome.NONE;
        this.deaths = 0;
        this.timers.prepareNewRun();
        this.goals.resetProgress();
        this.snapshots.clear();
        this.saveAsync();

        this.messages().send(Bukkit.getServer(), "reset.started");
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
        // give the chunk system a moment to release the region files before deleting them
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> CompletableFuture.runAsync(() -> {
            for (Map.Entry<GameWorlds.Dimension, Path> entry : folders.entrySet()) {
                this.wipeWithRetry(entry.getKey(), entry.getValue());
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

    private void wipeWithRetry(GameWorlds.Dimension dimension, Path folder) {
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                this.worlds.wipe(dimension, folder);
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
        if (this.worlds.isEnabled(dimension)) {
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
            .thenCompose(found -> SpawnFinder.preload(found, this.settings().spawn().preloadRadius()).thenApply(ignored -> found))
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
        this.spawn = newSpawn.clone();
        this.seed = newSeed;
        this.run++;
        this.saveAsync();
        this.plugin.getLogger().info("Run #" + this.run + " is ready (seed " + newSeed + ")");

        this.showBossBar(this.messages().get("bossbar.ready"), 1f, BossBar.Color.GREEN);
        this.messages().send(Bukkit.getServer(), "reset.ready", this.seedPlaceholder());
        if (limboCountdown <= 0) {
            this.startRun();
            return;
        }
        this.startCountdown(null, limboCountdown,
            remaining -> this.countdownTick("countdown.start", remaining),
            this::startRun);
    }

    private void startRun() {
        this.phase = Phase.RUNNING;
        this.hideBossBar();
        List<Player> participantsNow = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.placeIntoRun(player);
            participantsNow.add(player);
            player.showTitle(Title.title(this.messages().get("title.go"), this.messages().get("title.go-subtitle", this.goalPlaceholder()), COUNTDOWN_TIMES));
            player.playSound(GO_SOUND);
        }
        this.timers.begin(participantsNow);
        this.saveAsync();
    }

    private void abortReset(String reason) {
        this.plugin.getLogger().severe("Reset failed: " + reason);
        this.messages().send(Bukkit.getServer(), "reset.failed", Placeholder.unparsed("reason", reason));
        // keep everyone safe in limbo; an admin can retry with /magicreset reset
        this.phase = Phase.PAUSED;
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
        this.snapshots.put(player.getUniqueId(), PlayerSnapshot.capture(player));
        return true;
    }

    public @Nullable String requestResume(int countdown) {
        if (this.phase != Phase.PAUSED) {
            return "pause.not-paused";
        }
        if (this.worlds.overworld() == null || this.spawn() == null) {
            return "pause.no-world";
        }
        if (countdown <= 0) {
            this.resume();
            return null;
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
        this.timers.resumeAll();
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
        Settings.GoalSettings config = this.settings().goal();
        if (this.timers.scope() == TimerScope.GLOBAL) {
            this.timers.global().finish();
            this.outcome = Outcome.COMPLETED;
            String time = this.formatPrecise(this.timers.global().millis(this.timers.mode()));
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
            String time = this.formatPrecise(timer.millis(this.timers.mode()));
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
                this.messages().send(Bukkit.getServer(), "goal.completed.all");
                this.scheduleFollowUpReset(config.resetAfter());
            }
        }
        this.saveAsync();
    }

    public void onDeath(Player player) {
        if (!this.isActiveRunner(player)) {
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
        this.requestReset(null, seconds, this.settings().reset().limboCountdown());
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
                if (this.outcome == Outcome.FAILED && this.settings().deaths().spectatorOnFail() && this.worlds.isGameWorld(player.getWorld())) {
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

    public String formatPrecise(long millis) {
        return TimeFormat.format(millis, 3, this.settings().timer().style());
    }

    public TagResolver seedPlaceholder() {
        return Placeholder.unparsed("seed", Long.toString(this.seed));
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
            this.plugin.getLogger().log(Level.SEVERE, "Could not read " + DATA_FILE + ", starting with a fresh state", e);
            return Phase.RESETTING;
        }
        this.run = data.getInt("run", 0);
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
        } catch (Exception e) {
            this.write(content);
        }
    }

    private void write(String content) {
        Path target = this.dataFile().toPath();
        try {
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(DATA_FILE + ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
