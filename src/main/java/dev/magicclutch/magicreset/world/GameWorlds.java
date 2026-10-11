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
import org.jspecify.annotations.Nullable;

/**
 * Creates, loads, unloads and wipes the plugin's worlds.
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
    private static final Set<String> KEPT_FILES = Set.of("paper-world.yml");

    private final Logger logger;
    private final Supplier<Settings> settings;
    private final String namespace;

    /**
     * @param namespace fixed for the lifetime of the plugin instance; changing it requires a restart
     */
    public GameWorlds(Logger logger, Supplier<Settings> settings, String namespace) {
        this.logger = logger;
        this.settings = settings;
        this.namespace = namespace;
    }

    public String namespace() {
        return this.namespace;
    }

    public NamespacedKey key(Dimension dimension) {
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
        if (!key.getNamespace().equals(this.namespace())) {
            return null;
        }
        for (Dimension dimension : Dimension.values()) {
            if (dimension.path().equals(key.getKey())) {
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
        return this.dimensionsRoot().resolve(this.namespace()).resolve(dimension.path());
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
        Settings.Worlds config = this.settings.get().worlds();
        WorldCreator creator = WorldCreator.ofKey(this.key(dimension))
            .environment(dimension.environment())
            .seed(seed)
            .type(config.type())
            .generateStructures(config.generateStructures())
            .bonusChest(dimension == Dimension.OVERWORLD && config.bonusChest())
            .hardcore(config.hardcoreHearts());
        World world = Bukkit.createWorld(creator);
        if (world == null) {
            this.logger.severe("Failed to create world " + this.key(dimension));
            return null;
        }
        this.applySettings(world);
        return world;
    }

    private void applySettings(World world) {
        Settings.Worlds config = this.settings.get().worlds();
        world.setDifficulty(config.difficulty());
        if (config.borderSize() > 0) {
            Location center = world.getSpawnLocation();
            world.getWorldBorder().setCenter(center.getBlockX() + 0.5, center.getBlockZ() + 0.5);
            world.getWorldBorder().setSize(config.borderSize());
        }
        config.gameRules().forEach((name, value) -> this.applyGameRule(world, name, value));
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
        return Bukkit.unloadWorld(world, false);
    }

    /**
     * Deletes the stored data of a game dimension. Must run after {@link #unload(Dimension)}; may run off the
     * main thread.
     */
    public void wipe(Dimension dimension, Path folder) throws IOException {
        Path normalized = folder.toAbsolutePath().normalize();
        // Never delete anything that is not <...>/dimensions/<our namespace>/<dimension>
        if (!normalized.getFileName().toString().equals(dimension.path())
            || normalized.getParent() == null
            || !normalized.getParent().getFileName().toString().equals(this.namespace())
            || normalized.getParent().getParent() == null
            || !normalized.getParent().getParent().getFileName().toString().equals("dimensions")
            || this.namespace().equals(NamespacedKey.MINECRAFT)) {
            throw new IOException("Refusing to delete unexpected folder " + normalized);
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
            limbo = Bukkit.createWorld(creator);
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
        limbo.setTime(6000L);
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
