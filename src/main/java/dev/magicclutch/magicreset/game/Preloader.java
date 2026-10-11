package dev.magicclutch.magicreset.game;

import dev.magicclutch.magicreset.MagicReset;
import dev.magicclutch.magicreset.config.Settings;
import dev.magicclutch.magicreset.world.GameWorlds;
import dev.magicclutch.magicreset.world.SpawnFinder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the next game overworld generated in the background (reset.preload), so a reset can move the players
 * straight into it instead of waiting in the limbo. A preloaded world that no longer matches the settings
 * (seed mode, fixed seed, world type, ...) is thrown away and generated again.
 */
final class Preloader {

    /** A ready overworld in the standby slot. */
    record Ready(long seed, Location spawn) {
    }

    private enum State {
        NONE,
        PREPARING,
        READY,
        DISCARDING
    }

    private static final long CHECK_TICKS = 100L;

    private final MagicReset plugin;
    private final GameWorlds worlds;
    private final Executor io;
    private final LongSupplier nextSeed;
    private final BooleanSupplier canPrepare;

    private State state = State.NONE;
    private long seed;
    private String signature = "";
    private @Nullable Location spawn;
    private @Nullable BukkitTask task;

    Preloader(MagicReset plugin, GameWorlds worlds, Executor io, LongSupplier nextSeed, BooleanSupplier canPrepare) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.io = io;
        this.nextSeed = nextSeed;
        this.canPrepare = canPrepare;
    }

    private Settings settings() {
        return this.plugin.settings();
    }

    void start() {
        // a standby saved as ready is loaded again; anything else in the standby folder is stale
        if (this.state == State.READY && this.worlds.createStandby(this.seed) != null && this.spawn != null) {
            Location target = this.spawnIn(this.worlds.standby());
            SpawnFinder.preload(this.plugin, target, this.settings().spawn().preloadRadius());
        } else {
            this.state = State.NONE;
            this.discard();
        }
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::check, CHECK_TICKS, CHECK_TICKS);
    }

    void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    /** Settings a preloaded world depends on; when they change, it is generated again. */
    private String currentSignature() {
        Settings settings = this.settings();
        Settings.Worlds worlds = settings.worlds();
        String seedPart = settings.seedMode() == SeedMode.FIXED ? "fixed:" + settings.fixedSeed() : "random";
        return String.join("|", seedPart, worlds.type().name(), Boolean.toString(worlds.generateStructures()),
            Boolean.toString(worlds.bonusChest()), Boolean.toString(worlds.hardcoreHearts()),
            Double.toString(worlds.borderSize()), worlds.gameRules().toString());
    }

    private void check() {
        boolean enabled = this.settings().reset().preload();
        if (this.state == State.READY && (!enabled || !this.signature.equals(this.currentSignature()))) {
            this.plugin.getLogger().info(enabled
                ? "Settings changed, generating the preloaded overworld again"
                : "Preloading disabled, removing the preloaded overworld");
            this.discard();
            return;
        }
        if (enabled && this.state == State.NONE && this.canPrepare.getAsBoolean()) {
            this.prepare();
        }
    }

    private void prepare() {
        this.state = State.PREPARING;
        this.seed = this.nextSeed.getAsLong();
        this.signature = this.currentSignature();
        World world = this.worlds.createStandby(this.seed);
        if (world == null) {
            this.state = State.NONE;
            return;
        }
        long preparedSeed = this.seed;
        SpawnFinder.find(world, this.settings().spawn().safeSearchRadius())
            .thenCompose(found -> SpawnFinder.preload(this.plugin, found, this.settings().spawn().preloadRadius()).thenApply(ignored -> found))
            .whenComplete((found, error) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (this.state != State.PREPARING || this.seed != preparedSeed) {
                    return; // discarded meanwhile
                }
                this.spawn = error != null || found == null ? world.getSpawnLocation().toCenterLocation() : found;
                this.state = State.READY;
                this.plugin.getLogger().info("The next overworld is preloaded (seed " + preparedSeed + ")");
            }));
    }

    /**
     * Hands out the preloaded world if it matches the current settings and the requested seed.
     *
     * @param requestedSeed a seed chosen for this reset, {@code null} for the configured seed mode
     */
    @Nullable Ready take(@Nullable Long requestedSeed) {
        if (!this.settings().reset().preload() || this.state != State.READY || this.spawn == null
            || this.worlds.standby() == null || !this.signature.equals(this.currentSignature())
            || (requestedSeed != null && requestedSeed != this.seed)) {
            return null;
        }
        Ready ready = new Ready(this.seed, this.spawn.clone());
        this.state = State.NONE;
        this.spawn = null;
        return ready;
    }

    /** Unloads and deletes whatever is in the standby slot. No backup: it was never played. */
    private void discard() {
        this.state = State.DISCARDING;
        this.spawn = null;
        World standby = this.worlds.standby();
        if (standby != null && (!standby.getPlayers().isEmpty() || !Bukkit.unloadWorld(standby, false))) {
            this.plugin.getLogger().warning("Could not unload the preloaded overworld " + standby.getKey());
            this.state = State.NONE;
            return;
        }
        Path folder;
        try {
            folder = this.worlds.standbyFolder();
        } catch (IllegalStateException e) {
            this.state = State.NONE;
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                this.worlds.wipeOverworldSlot(folder, null);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, this.io).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (error != null) {
                this.plugin.getLogger().log(Level.WARNING, "Could not delete the preloaded overworld", error);
            }
            this.state = State.NONE;
        }));
    }

    private Location spawnIn(@Nullable World world) {
        Location location = this.spawn == null ? new Location(world, 0, 64, 0) : this.spawn.clone();
        location.setWorld(world);
        return location;
    }

    boolean isReady() {
        return this.state == State.READY;
    }

    boolean isBusy() {
        return this.state == State.PREPARING || this.state == State.DISCARDING;
    }

    void save(ConfigurationSection section) {
        section.set("ready", this.state == State.READY);
        section.set("seed", this.seed);
        section.set("signature", this.signature);
        if (this.spawn != null) {
            section.set("x", this.spawn.getX());
            section.set("y", this.spawn.getY());
            section.set("z", this.spawn.getZ());
        }
    }

    void load(@Nullable ConfigurationSection section) {
        if (section == null || !section.getBoolean("ready")) {
            return;
        }
        this.state = State.READY;
        this.seed = section.getLong("seed");
        this.signature = section.getString("signature", "");
        this.spawn = new Location(null, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"));
    }
}
