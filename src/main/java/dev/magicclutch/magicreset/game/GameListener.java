package dev.magicclutch.magicreset.game;

import dev.magicclutch.magicreset.timer.TimerService;
import dev.magicclutch.magicreset.world.GameWorlds;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.util.function.IntSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

/**
 * Join, respawn, death and input handling plus the protection of the limbo.
 */
public final class GameListener implements Listener {

    private static final String LIMBO_BYPASS = "magicreset.limbo.bypass";

    private final Plugin plugin;
    private final GameManager game;
    private final GameWorlds worlds;
    private final TimerService timers;
    private final IntSupplier limboHeight;

    public GameListener(Plugin plugin, GameManager game, GameWorlds worlds, TimerService timers, IntSupplier limboHeight) {
        this.plugin = plugin;
        this.game = game;
        this.worlds = worlds;
        this.timers = timers;
        this.limboHeight = limboHeight;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        Location current = event.getSpawnLocation();
        Location target = this.game.joinLocation(event.getConnection().getProfile().getId(), current);
        if (target != current && target.getWorld() != null) {
            event.setSpawnLocation(target);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        this.game.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Location target = this.game.respawnLocation(player, event.getRespawnLocation(), event.isBedSpawn() || event.isAnchorSpawn());
        if (target != null) {
            event.setRespawnLocation(target);
        }
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (player.isOnline()) {
                this.game.afterRespawn(player);
                this.timers.onRespawn(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        this.game.onDeath(event.getPlayer());
        if (this.game.isActiveRunner(event.getPlayer())) {
            this.timers.onDeath(event.getPlayer());
        }
    }

    // --- timer start on first input -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (this.worlds.isLimbo(player.getWorld())) {
            if (event.getTo().getY() < this.limboHeight.getAsInt() - 32) {
                player.teleport(this.worlds.limboSpawn(player.getWorld()));
            }
            return;
        }
    }

    /** Real key presses only: falling or being knocked back must not start the timer. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        Input input = event.getInput();
        boolean pressed = input.isForward() || input.isBackward() || input.isLeft() || input.isRight()
            || input.isJump() || input.isSneak() || input.isSprint();
        if (pressed && this.timers.isWaitingForInput(player) && this.game.isActiveRunner(player)) {
            this.timers.onInput(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (this.timers.isWaitingForInput(player) && this.game.isActiveRunner(player)) {
            this.timers.onInput(player);
        }
    }

    // --- limbo protection -----------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && this.worlds.isLimbo(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (this.worlds.isLimbo(event.getEntity().getWorld()) && event.getFoodLevel() < event.getEntity().getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (this.worlds.isLimbo(event.getBlock().getWorld()) && !event.getPlayer().hasPermission(LIMBO_BYPASS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (this.worlds.isLimbo(event.getBlock().getWorld()) && !event.getPlayer().hasPermission(LIMBO_BYPASS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (this.worlds.isLimbo(event.getPlayer().getWorld()) && !event.getPlayer().hasPermission(LIMBO_BYPASS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLimboInteract(PlayerInteractEvent event) {
        if (this.worlds.isLimbo(event.getPlayer().getWorld()) && !event.getPlayer().hasPermission(LIMBO_BYPASS)) {
            event.setCancelled(true);
        }
    }
}
