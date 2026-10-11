package dev.magicclutch.magicreset.world;

import dev.magicclutch.magicreset.config.Settings;
import dev.magicclutch.magicreset.util.WorldFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * Creates, loads, unloads and wipes the game worlds.
 *
 * <p>The game overworld is always a plugin world ({@code <namespace>:overworld}) because the server's main
 * overworld can never be unloaded while the server runs. Nether and end are by default the server's real
 * dimensions {@code minecraft:the_nether} and {@code minecraft:the_end} (these can be unloaded), so the vanilla
 * end with its dragon fight is really reset. With {@code worlds.use-main-nether-end: false} plugin worlds are
 * used for them as well.</p>
 *
 * <p>Since 26.1 every dimension lives inside the main level folder:
 * {@code <level-name>/dimensions/<namespace>/<path>/} containing {@code region/}, {@code entities/}, {@code poi/},
 * {@code data/} and optionally {@code paper-world.yml}. Player data ({@code <level-name>/players/}) and shared
 * data ({@code <level-name>/data/}) are global and never touched by a reset. Only the folders of the game
 * dimensions below are wiped; {@code paper-world.yml} is kept so per-world Paper settings survive resets.</p>
 */
public final class GameWorlds {

    public enum Dimension {
        OVERWORLD("overworld", World.Environment.NORMAL),
        NETHER("the_nether", World.Environment.NETHER),
        END("the_end", World.Environment.THE_END);

        private final String path;
        private final World.Environment environment;

        Dimension(String path, World.Environment environment) {
            this.path = path;
            this.environment = environment;
        }

        public String path() {
            return this.path;
        }

        public World.Environment environment() {
            return this.environment;
        }
    }

    public static final String LIMBO_PATH = "limbo";
    /**
     * The game overworld alternates between two folders so the next one can be generated in the background
     * (reset.preload) while the current one is played.
     */
    public static final List<String> OVERWORLD_SLOTS = List.of("overworld", "overworld_b");
    private static final Set<String> KEPT_FILES = Set.of("paper-world.yml");

    private final Logger logger;
    private final Supplier<Settings> settings;
    private final String namespace;
    private final boolean mainNetherEnd;
    private String overworldSlot = OVERWORLD_SLOTS.getFirst();

    /**
     * @param namespace     fixed for the lifetime of the plugin instance; changing it requires a restart
     * @param mainNetherEnd use minecraft:the_nether / minecraft:the_end as game dimensions (restart to change)
     */
    public GameWorlds(Logger logger, Supplier<Settings> settings, String namespace, boolean mainNetherEnd) {
        this.logger = logger;
        this.settings = settings;
        this.namespace = namespace;
        this.mainNetherEnd = mainNetherEnd;
    }

    /** Folder name of the overworld of the current run. */
    public String overworldSlot() {
        return this.overworldSlot;
    }

    public void setOverworldSlot(String slot) {
        this.overworldSlot = OVERWORLD_SLOTS.contains(slot) ? slot : OVERWORLD_SLOTS.getFirst();
    }

    /** The slot that is not played right now, used for the preloaded next overworld. */
    public String standbySlot() {
        return this.overworldSlot.equals(OVERWORLD_SLOTS.get(0)) ? OVERWORLD_SLOTS.get(1) : OVERWORLD_SLOTS.get(0);
    }

    public NamespacedKey standbyKey() {
        return new NamespacedKey(this.namespace, this.standbySlot());
    }

    public @Nullable World standby() {
        return Bukkit.getWorld(this.standbyKey());
    }

    public Path standbyFolder() {
        return this.dimensionsRoot().resolve(this.namespace).resolve(this.standbySlot());
    }

    /** Makes the preloaded overworld the game overworld. */
    public void swapOverworldSlots() {
        this.overworldSlot = this.standbySlot();
    }

    /** Creates (or loads) the next overworld in the standby slot. */
    public @Nullable World createStandby(long seed) {
        World existing = this.standby();
        return existing != null ? existing : this.create(WorldCreator.ofKey(this.standbyKey()), Dimension.OVERWORLD, seed);
    }

    /**
     * Deletes an overworld slot folder (an old game overworld or a discarded standby), optionally backing it
     * up first. May run off the main thread.
     */
    public void wipeOverworldSlot(Path folder, @Nullable Path backup) throws IOException {
        Path normalized = folder.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        boolean expected = parent != null && parent.getFileName().toString().equals(this.namespace)
            && OVERWORLD_SLOTS.contains(normalized.getFileName().toString())
            && parent.getParent() != null && parent.getParent().getFileName().toString().equals("dimensions")
            && !this.namespace.equals(NamespacedKey.MINECRAFT);
        if (!expected) {
            throw new IOException("Refusing to delete unexpected folder " + normalized);
        }
        if (backup != null) {
            WorldFiles.moveContents(normalized, backup, KEPT_FILES);
        }
        WorldFiles.wipe(normalized, KEPT_FILES);
    }

    public boolean usesMainNetherEnd() {
        return this.mainNetherEnd;
    }

    public String namespace() {
        return this.namespace;
    }

    public NamespacedKey key(Dimension dimension) {
        if (dimension == Dimension.OVERWORLD) {
            return new NamespacedKey(this.namespace, this.overworldSlot);
        }
        if (this.mainNetherEnd) {
            return NamespacedKey.minecraft(dimension.path());
        }
        return new NamespacedKey(this.namespace(), dimension.path());
    }

    public NamespacedKey limboKey() {
        return new NamespacedKey(this.namespace(), LIMBO_PATH);
    }

    public @Nullable World world(Dimension dimension) {
        return Bukkit.getWorld(this.key(dimension));
    }

    public @Nullable World overworld() {
        return this.world(Dimension.OVERWORLD);
    }

    public @Nullable World limbo() {
        return Bukkit.getWorld(this.limboKey());
    }

    public boolean isEnabled(Dimension dimension) {
        return switch (dimension) {
            case OVERWORLD -> true;
            case NETHER -> this.settings.get().worlds().nether();
            case END -> this.settings.get().worlds().end();
        };
    }

    /** @return {@code true} for the overworld, nether and end of the current run. */
    public boolean isGameWorld(@Nullable World world) {
        return this.dimensionOf(world) != null;
    }

    public boolean isLimbo(@Nullable World world) {
        return world != null && world.getKey().equals(this.limboKey());
    }

    public @Nullable Dimension dimensionOf(@Nullable World world) {
        if (world == null) {
            return null;
        }
        NamespacedKey key = world.getKey();
        for (Dimension dimension : Dimension.values()) {
            if (this.key(dimension).equals(key)) {
                // a deselected main nether/end is just the vanilla dimension again, not part of the game
                if (this.mainNetherEnd && !this.isEnabled(dimension)) {
                    return null;
                }
                return dimension;
            }
        }
        return null;
    }

    public List<World> loadedGameWorlds() {
        List<World> worlds = new ArrayList<>(3);
        for (Dimension dimension : Dimension.values()) {
            World world = this.world(dimension);
            if (world != null) {
                worlds.add(world);
            }
        }
        return worlds;
    }

    /**
     * {@code <level-name>/dimensions}, derived from the main overworld so it always matches the server's
     * {@code level-name} and world container.
     */
    public Path dimensionsRoot() {
        World main = Bukkit.getWorlds().getFirst();
        Path overworldPath = main.getWorldPath().toAbsolutePath().normalize();
        // <level>/dimensions/minecraft/overworld -> <level>/dimensions
        Path root = overworldPath.getParent() == null ? null : overworldPath.getParent().getParent();
        if (root == null || !root.getFileName().toString().equals("dimensions")) {
            throw new IllegalStateException("Unexpected world layout: " + overworldPath + " (expected <level>/dimensions/minecraft/overworld)");
        }
        return root;
    }

    public Path folder(Dimension dimension) {
        NamespacedKey key = this.key(dimension);
        return this.dimensionsRoot().resolve(key.getNamespace()).resolve(key.getKey());
    }

    /**
     * Plugin nether/end folders from before {@code use-main-nether-end} was switched on; deleted with the next
     * reset so they do not waste disk space.
     */
    public List<Path> leftoverFolders() {
        if (!this.mainNetherEnd) {
            return List.of();
        }
        Path root = this.dimensionsRoot().resolve(this.namespace());
        return List.of(root.resolve(Dimension.NETHER.path()), root.resolve(Dimension.END.path()));
    }

    /** Deletes a folder returned by {@link #leftoverFolders()}. May run off the main thread. */
    public void wipeLeftover(Path folder) throws IOException {
        Path parent = folder.getParent();
        boolean expected = parent != null && parent.getFileName().toString().equals(this.namespace)
            && (folder.getFileName().toString().equals(Dimension.NETHER.path()) || folder.getFileName().toString().equals(Dimension.END.path()))
            && !this.namespace.equals(NamespacedKey.MINECRAFT);
        if (!expected) {
            throw new IOException("Refusing to delete unexpected folder " + folder);
        }
        WorldFiles.wipe(folder, Set.of());
    }

    public boolean hasSavedData(Dimension dimension) {
        return WorldFiles.hasWorldData(this.folder(dimension), KEPT_FILES);
    }

    /**
     * Loads the dimension if it exists on disk or generates it with {@code seed}. For existing worlds the
     * stored seed wins.
     */
    public @Nullable World loadOrCreate(Dimension dimension, long seed) {
        World existing = this.world(dimension);
        if (existing != null) {
            return existing;
        }
        return this.create(this.creator(dimension), dimension, seed);
    }

    private @Nullable World create(WorldCreator base, Dimension dimension, long seed) {
        Settings.Worlds config = this.settings.get().worlds();
        WorldCreator creator = base
            .environment(dimension.environment())
            .seed(seed)
            .type(config.type())
            .generateStructures(config.generateStructures())
            .bonusChest(dimension == Dimension.OVERWORLD && config.bonusChest())
            .hardcore(config.hardcoreHearts());
        World world;
        try {
            world = Bukkit.createWorld(creator);
        } catch (RuntimeException e) {
            this.logger.log(Level.SEVERE, "Failed to create world " + creator.key(), e);
            return null;
        }
        if (world == null) {
            this.logger.severe("Failed to create world " + creator.key());
            return null;
        }
        this.applySettings(world);
        return world;
    }

    private WorldCreator creator(Dimension dimension) {
        if (this.mainNetherEnd && dimension != Dimension.OVERWORLD) {
            // the server's own names "<level>_nether" / "<level>_the_end" map to minecraft:the_nether / the_end
            String level = Bukkit.getWorlds().getFirst().getName();
            WorldCreator creator = new WorldCreator(level + (dimension == Dimension.NETHER ? "_nether" : "_the_end"));
            if (!creator.key().equals(this.key(dimension))) {
                throw new IllegalStateException("Unexpected key " + creator.key() + " for the main " + dimension.path());
            }
            return creator;
        }
        return WorldCreator.ofKey(this.key(dimension));
    }

    private void applySettings(World world) {
        Settings.Worlds config = this.settings.get().worlds();
        world.setDifficulty(config.difficulty());
        if (config.borderSize() > 0) {
            Location center = world.getSpawnLocation();
            world.getWorldBorder().setCenter(center.getBlockX() + 0.5, center.getBlockZ() + 0.5);
            world.getWorldBorder().setSize(config.borderSize());
        }
        world.setGameRule(GameRules.LOCATOR_BAR, config.locatorBar());
        config.gameRules().forEach((name, value) -> this.applyGameRule(world, name, value));
    }

    /** Applies worlds.locator-bar to the loaded game worlds right away. */
    public void applyLocatorBar() {
        boolean enabled = this.settings.get().worlds().locatorBar();
        this.loadedGameWorlds().forEach(world -> world.setGameRule(GameRules.LOCATOR_BAR, enabled));
    }

    @SuppressWarnings("unchecked")
    private void applyGameRule(World world, String name, String value) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        GameRule<?> rule = key == null ? null : Registry.GAME_RULE.get(key);
        if (rule == null) {
            this.logger.warning("Unknown game rule '" + name + "' in worlds.game-rules");
            return;
        }
        if (rule.getType() == Boolean.class) {
            world.setGameRule((GameRule<Boolean>) rule, Boolean.parseBoolean(value));
        } else if (rule.getType() == Integer.class) {
            try {
                world.setGameRule((GameRule<Integer>) rule, Integer.parseInt(value.trim()));
            } catch (NumberFormatException e) {
                this.logger.warning("Game rule '" + name + "' needs a number, got '" + value + "'");
            }
        }
    }

    /**
     * Unloads a game world without saving. Players must have left it before.
     *
     * @return {@code true} if the world is no longer loaded
     */
    public boolean unload(Dimension dimension) {
        World world = this.world(dimension);
        if (world == null) {
            return true;
        }
        // thrown pearls belong to their owner and would follow them into the new world with the same key
        world.getEntitiesByClass(EnderPearl.class).forEach(Entity::remove);
        return Bukkit.unloadWorld(world, false);
    }

    /**
     * Deletes the stored data of a game dimension. Must run after {@link #unload(Dimension)}; may run off the
     * main thread.
     */
    public void wipe(Dimension dimension, Path folder) throws IOException {
        this.wipe(dimension, folder, null);
    }

    /**
     * Deletes the stored data of a game dimension, optionally moving it into {@code backup} first. Must run
     * after {@link #unload(Dimension)}; may run off the main thread.
     */
    public void wipe(Dimension dimension, Path folder, @Nullable Path backup) throws IOException {
        Path normalized = folder.toAbsolutePath().normalize();
        NamespacedKey key = this.key(dimension);
        // Only ever <level>/dimensions/<namespace>/<path> of a game dimension, never the main overworld
        if (!normalized.equals(this.folder(dimension).toAbsolutePath().normalize())
            || key.equals(NamespacedKey.minecraft("overworld"))) {
            throw new IOException("Refusing to delete unexpected folder " + normalized);
        }
        if (backup != null) {
            WorldFiles.moveContents(normalized, backup, KEPT_FILES);
        }
        WorldFiles.wipe(normalized, KEPT_FILES);
    }

    /**
     * Loads or creates the void limbo world and makes sure its platform exists.
     */
    public @Nullable World loadLimbo() {
        World limbo = this.limbo();
        if (limbo == null) {
            WorldCreator creator = WorldCreator.ofKey(this.limboKey())
                .environment(World.Environment.NORMAL)
                .generator(new VoidGenerator(this.settings.get().limbo().platformHeight()))
                .generateStructures(false);
            try {
                limbo = Bukkit.createWorld(creator);
            } catch (RuntimeException e) {
                this.logger.log(Level.SEVERE, "Failed to create the limbo world " + this.limboKey(), e);
                return null;
            }
            if (limbo == null) {
                this.logger.log(Level.SEVERE, "Failed to create the limbo world " + this.limboKey());
                return null;
            }
        }
        limbo.setDifficulty(org.bukkit.Difficulty.PEACEFUL);
        limbo.setGameRule(GameRules.ADVANCE_TIME, false);
        limbo.setGameRule(GameRules.ADVANCE_WEATHER, false);
        limbo.setGameRule(GameRules.SPAWN_MOBS, false);
        limbo.setGameRule(GameRules.SPAWN_MONSTERS, false);
        limbo.setGameRule(GameRules.SPAWN_PHANTOMS, false);
        limbo.setGameRule(GameRules.SPAWN_PATROLS, false);
        limbo.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        limbo.setGameRule(GameRules.SHOW_DEATH_MESSAGES, false);
        limbo.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        limbo.setStorm(false);
        limbo.setThundering(false);
        this.buildPlatform(limbo);
        limbo.setSpawnLocation(this.limboSpawn(limbo));
        return limbo;
    }

    private void buildPlatform(World limbo) {
        Settings.Limbo config = this.settings.get().limbo();
        int y = config.platformHeight() - 1;
        int radius = config.platformRadius();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z > radius * radius + radius) {
                    continue;
                }
                Block block = limbo.getBlockAt(x, y, z);
                if (block.getType().isAir()) {
                    block.setType(config.platformBlock(), false);
                }
            }
        }
    }

    public Location limboSpawn(World limbo) {
        return new Location(limbo, 0.5, this.settings.get().limbo().platformHeight(), 0.5, 0f, 0f);
    }
}
