package dev.magicclutch.magicreset.world;

import io.papermc.paper.event.entity.EntityPortalReadyEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Links the game dimensions with each other. Vanilla only knows the main overworld/nether/end, so portals in
 * plugin worlds are redirected here.
 *
 * <ul>
 *   <li>Nether portals: the target world is swapped in {@link EntityPortalReadyEvent}; vanilla still does the
 *   coordinate scaling, portal search and portal creation.</li>
 *   <li>End portals: vanilla would always use the main end (and does nothing if it is disabled), so the travel
 *   is done here, including the obsidian platform.</li>
 * </ul>
 */
public final class PortalListener implements Listener {

    /** Vanilla {@code ServerLevel.END_SPAWN_POINT}. */
    private static final int END_SPAWN_X = 100;
    private static final int END_SPAWN_Y = 50;
    private static final int END_SPAWN_Z = 0;

    private final Plugin plugin;
    private final GameWorlds worlds;
    private final Supplier<@Nullable Location> spawn;
    private final LongSupplier seed;
    private final Set<UUID> pendingEndTravel = new HashSet<>();

    public PortalListener(Plugin plugin, GameWorlds worlds, Supplier<@Nullable Location> spawn, LongSupplier seed) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.spawn = spawn;
        this.seed = seed;
    }

    /**
     * The target dimension of a portal, loading or generating it if it is missing (e.g. it failed to load, or
     * the server's own nether/end is disabled). Must not run inside an entity tick.
     */
    private @Nullable World targetWorld(GameWorlds.Dimension dimension) {
        if (!this.worlds.isEnabled(dimension)) {
            return null;
        }
        World world = this.worlds.world(dimension);
        if (world == null) {
            this.plugin.getLogger().warning("The " + this.worlds.key(dimension) + " world was not loaded, loading it for a portal");
            world = this.worlds.loadOrCreate(dimension, this.seed.getAsLong());
        }
        return world;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNetherPortal(EntityPortalReadyEvent event) {
        if (event.getPortalType() != PortalType.NETHER) {
            return;
        }
        GameWorlds.Dimension from = this.worlds.dimensionOf(event.getEntity().getWorld());
        if (from == null) {
            return;
        }
        GameWorlds.Dimension to = from == GameWorlds.Dimension.NETHER ? GameWorlds.Dimension.OVERWORLD : GameWorlds.Dimension.NETHER;
        World target = this.worlds.isEnabled(to) ? this.worlds.world(to) : null;
        if (target == null) {
            event.setCancelled(true);
            if (this.worlds.isEnabled(to)) {
                // worlds cannot be created during the entity tick: load it now, the next portal attempt works
                Bukkit.getScheduler().runTask(this.plugin, () -> this.targetWorld(to));
            }
            return;
        }
        event.setTargetWorld(target);
    }

    /**
     * Second line of defence: whatever chose the target before, a nether portal in a game world never leads out
     * of the game. Vanilla searches/creates the exit portal in the world of the location set here.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            return;
        }
        Location fixed = this.netherTarget(event.getFrom(), event.getTo());
        if (fixed == null) {
            event.setCancelled(true);
        } else {
            event.setTo(fixed);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (event.getPortalType() != PortalType.NETHER || event.getTo() == null) {
            return;
        }
        Location fixed = this.netherTarget(event.getFrom(), event.getTo());
        if (fixed == null) {
            event.setCancelled(true);
        } else {
            event.setTo(fixed);
        }
    }

    /** {@code to} if it already is the right game world, a corrected location, or {@code null} to cancel. */
    private @Nullable Location netherTarget(Location from, Location to) {
        GameWorlds.Dimension source = this.worlds.dimensionOf(from.getWorld());
        if (source == null || this.worlds.isGameWorld(to.getWorld())) {
            return to;
        }
        GameWorlds.Dimension wanted = source == GameWorlds.Dimension.NETHER ? GameWorlds.Dimension.OVERWORLD : GameWorlds.Dimension.NETHER;
        World target = this.worlds.isEnabled(wanted) ? this.worlds.world(wanted) : null;
        if (target == null) {
            return null;
        }
        double scale = wanted == GameWorlds.Dimension.NETHER ? 1.0 / 8.0 : 8.0;
        return new Location(target, from.getX() * scale, to.getY(), from.getZ() * scale, to.getYaw(), to.getPitch());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEndPortal(EntityPortalEnterEvent event) {
        if (event.getPortalType() != PortalType.ENDER) {
            return;
        }
        Entity entity = event.getEntity();
        GameWorlds.Dimension from = this.worlds.dimensionOf(entity.getWorld());
        if (from == null) {
            return;
        }
        // vanilla must not handle it: it would send the entity to the main end / main overworld
        event.setCancelled(true);
        if (entity.isInsideVehicle() || !entity.getPassengers().isEmpty() || !this.pendingEndTravel.add(entity.getUniqueId())) {
            return;
        }
        // teleporting inside the collision check is unsafe, do it on the next tick
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (!entity.isValid()) {
                this.pendingEndTravel.remove(entity.getUniqueId());
                return;
            }
            CompletableFuture<?> travel = from == GameWorlds.Dimension.END ? this.leaveEnd(entity) : this.enterEnd(entity);
            // stays "pending" until the teleport finished, otherwise every tick inside the portal queues another one
            travel.whenComplete((ignored, error) -> {
                this.pendingEndTravel.remove(entity.getUniqueId());
                entity.setPortalCooldown(20);
            });
        });
    }

    private CompletableFuture<?> enterEnd(Entity entity) {
        World end = this.targetWorld(GameWorlds.Dimension.END);
        if (end == null) {
            return CompletableFuture.completedFuture(null);
        }
        // the platform spans chunks (6,-1) and (6,0); generate them off the main thread first
        int chunkX = END_SPAWN_X >> 4;
        return CompletableFuture.allOf(
                end.getChunkAtAsync(chunkX, -1, true, true),
                end.getChunkAtAsync(chunkX, 0, true, true))
            .thenCompose(ignored -> {
                if (!entity.isValid()) {
                    return CompletableFuture.completedFuture(false);
                }
                createEndPlatform(end);
                // facing west like vanilla
                Location target = new Location(end, END_SPAWN_X + 0.5, END_SPAWN_Y - 1, END_SPAWN_Z + 0.5, 90f, 0f);
                return entity.teleportAsync(target, PlayerTeleportEvent.TeleportCause.END_PORTAL);
            });
    }

    private CompletableFuture<?> leaveEnd(Entity entity) {
        Location target = null;
        if (entity instanceof Player player) {
            Location respawn = player.getRespawnLocation();
            if (respawn != null && this.worlds.isGameWorld(respawn.getWorld())) {
                target = respawn;
            }
        }
        if (target == null) {
            target = this.spawn.get();
        }
        if (target == null) {
            return CompletableFuture.completedFuture(null);
        }
        return entity.teleportAsync(target, PlayerTeleportEvent.TeleportCause.END_PORTAL);
    }

    /**
     * Vanilla grants the dimension advancements only for the exact keys minecraft:the_nether / minecraft:the_end,
     * so travelling into the game dimensions would never unlock them.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDimensionChange(PlayerChangedWorldEvent event) {
        GameWorlds.Dimension to = this.worlds.dimensionOf(event.getPlayer().getWorld());
        if (to == null || this.worlds.dimensionOf(event.getFrom()) == null) {
            return;
        }
        switch (to) {
            case NETHER -> {
                award(event.getPlayer(), "story/enter_the_nether");
                award(event.getPlayer(), "nether/root");
            }
            case END -> {
                award(event.getPlayer(), "story/enter_the_end");
                award(event.getPlayer(), "end/root");
            }
            case OVERWORLD -> {
            }
        }
    }

    private static void award(Player player, String key) {
        Advancement advancement = Bukkit.getAdvancement(NamespacedKey.minecraft(key));
        if (advancement == null) {
            return;
        }
        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        for (String criterion : progress.getRemainingCriteria()) {
            progress.awardCriteria(criterion);
        }
    }

    /** Same as vanilla {@code EndPlatformFeature}: 5x5 obsidian with three layers of air above. */
    private static void createEndPlatform(World end) {
        int baseY = END_SPAWN_Y - 2;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int dy = 0; dy <= 3; dy++) {
                    Block block = end.getBlockAt(END_SPAWN_X + x, baseY + dy, END_SPAWN_Z + z);
                    Material wanted = dy == 0 ? Material.OBSIDIAN : Material.AIR;
                    if (block.getType() != wanted) {
                        if (wanted == Material.AIR) {
                            block.breakNaturally();
                        } else {
                            block.setType(wanted);
                        }
                    }
                }
            }
        }
    }
}
