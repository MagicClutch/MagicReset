package dev.magicclutch.magicreset.manhunt;

import dev.magicclutch.magicreset.MagicReset;
import dev.magicclutch.magicreset.config.Settings;
import dev.magicclutch.magicreset.world.GameWorlds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Magic Manhunt: one or more targets (runners) try to reach the goal, everybody else hunts them.
 *
 * <ul>
 *   <li>Targets reach the run's goal first → the targets win.</li>
 *   <li>Every target lost all lives → the hunters win.</li>
 *   <li>Locator bar: hunters see only the targets, targets see nobody (waypoint transmit/receive range).</li>
 * </ul>
 */
public final class ManhuntService implements Listener {

    public enum Role {
        TARGET,
        HUNTER,
        SPECTATOR
    }

    public enum Winner {
        TARGETS,
        HUNTERS
    }

    /** Vanilla player default for both waypoint ranges. */
    private static final double DEFAULT_WAYPOINT_RANGE = 6.0E7;

    private final MagicReset plugin;
    private final GameWorlds worlds;

    private final Set<UUID> targets = new LinkedHashSet<>();
    private final Set<UUID> spectators = new LinkedHashSet<>();
    /** Lives left per target in the current run. */
    private final Map<UUID, Integer> lives = new HashMap<>();
    private long headStartEndsAt;
    private @Nullable BukkitTask headStartTask;

    public ManhuntService(MagicReset plugin, GameWorlds worlds) {
        this.plugin = plugin;
        this.worlds = worlds;
    }

    private Settings.Manhunt settings() {
        return this.plugin.settings().manhunt();
    }

    public boolean isEnabled() {
        return this.settings().enabled();
    }

    public Role role(UUID player) {
        if (this.targets.contains(player)) {
            return Role.TARGET;
        }
        return this.spectators.contains(player) ? Role.SPECTATOR : Role.HUNTER;
    }

    public Role role(Player player) {
        return this.role(player.getUniqueId());
    }

    public Set<UUID> targets() {
        return Set.copyOf(this.targets);
    }

    public void setRole(UUID player, Role role) {
        this.targets.remove(player);
        this.spectators.remove(player);
        switch (role) {
            case TARGET -> {
                this.targets.add(player);
                this.lives.putIfAbsent(player, this.settings().targetLives());
            }
            case SPECTATOR -> this.spectators.add(player);
            case HUNTER -> {
            }
        }
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            this.applyWaypoints(online);
        }
        this.refreshAll();
    }

    public void clearTargets() {
        new ArrayList<>(this.targets).forEach(uuid -> this.setRole(uuid, Role.HUNTER));
    }

    public int livesLeft(UUID target) {
        return this.lives.getOrDefault(target, this.settings().targetLives());
    }

    /** Names of the targets for messages and the sidebar. */
    public String targetNames() {
        List<String> names = new ArrayList<>();
        for (UUID uuid : this.targets) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            names.add(player.getName() == null ? uuid.toString().substring(0, 8) : player.getName());
        }
        return names.isEmpty() ? "-" : String.join(", ", names);
    }

    /** Only targets may complete the goal in a manhunt. */
    public boolean mayCompleteGoal(Player player) {
        return !this.isEnabled() || this.role(player) == Role.TARGET;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Run life cycle (called by the GameManager)
    // ---------------------------------------------------------------------------------------------------------

    /** A new run starts: lives are refilled, roles applied and the head start begins. */
    public void onRunStart() {
        this.lives.clear();
        this.targets.forEach(uuid -> this.lives.put(uuid, this.settings().targetLives()));
        if (!this.isEnabled()) {
            this.refreshAll();
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.prepare(player);
        }
        int headStart = this.settings().headStart();
        if (headStart > 0 && !this.targets.isEmpty()) {
            this.headStartEndsAt = System.currentTimeMillis() + headStart * 1000L;
            this.startHeadStartTask();
        }
        this.plugin.messages().send(Bukkit.getServer(), "manhunt.started",
            Placeholder.unparsed("targets", this.targetNames()),
            Placeholder.unparsed("lives", Integer.toString(this.settings().targetLives())));
    }

    private void startHeadStartTask() {
        if (this.headStartTask != null) {
            this.headStartTask.cancel();
        }
        this.headStartTask = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            long left = (this.headStartEndsAt - System.currentTimeMillis() + 999L) / 1000L;
            if (left <= 0) {
                this.headStartEndsAt = 0;
                this.headStartTask.cancel();
                this.headStartTask = null;
                Title title = Title.title(this.plugin.messages().get("manhunt.hunt-begins"), net.kyori.adventure.text.Component.empty(),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(300)));
                Bukkit.getOnlinePlayers().forEach(player -> player.showTitle(title));
                return;
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (this.role(player) == Role.HUNTER) {
                    player.sendActionBar(this.plugin.messages().get("manhunt.head-start", Placeholder.unparsed("seconds", Long.toString(left))));
                }
            }
        }, 0L, 20L);
    }

    public boolean isHeadStart() {
        return this.headStartEndsAt > System.currentTimeMillis();
    }

    /**
     * A target died.
     *
     * @return {@code true} if all targets are out now (the hunters win)
     */
    public boolean onTargetDeath(Player target) {
        int left = Math.max(0, this.livesLeft(target.getUniqueId()) - 1);
        this.lives.put(target.getUniqueId(), left);
        if (left > 0) {
            this.plugin.messages().send(Bukkit.getServer(), "manhunt.target-life-lost",
                Placeholder.unparsed("player", target.getName()), Placeholder.unparsed("lives", Integer.toString(left)));
            return false;
        }
        this.plugin.messages().send(Bukkit.getServer(), "manhunt.target-out", Placeholder.unparsed("player", target.getName()));
        return this.targets.stream().allMatch(uuid -> this.livesLeft(uuid) <= 0);
    }

    /** {@code true} if the target lost all lives and only watches. */
    public boolean isEliminated(Player player) {
        return this.role(player) == Role.TARGET && this.livesLeft(player.getUniqueId()) <= 0;
    }

    public void onRunEnd() {
        this.headStartEndsAt = 0;
        if (this.headStartTask != null) {
            this.headStartTask.cancel();
            this.headStartTask = null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Players
    // ---------------------------------------------------------------------------------------------------------

    /** Applies role specific setup: waypoints, spectator mode, hunter compass. */
    public void prepare(Player player) {
        this.applyWaypoints(player);
        if (!this.isEnabled() || !this.worlds.isGameWorld(player.getWorld())) {
            return;
        }
        Role role = this.role(player);
        if (role == Role.SPECTATOR || this.isEliminated(player)) {
            player.setGameMode(GameMode.SPECTATOR);
        } else if (role == Role.HUNTER && this.settings().hunterCompass() && !player.getInventory().contains(Material.COMPASS)) {
            player.getInventory().addItem(new ItemStack(Material.COMPASS));
        }
    }

    private void refreshAll() {
        Bukkit.getOnlinePlayers().forEach(this::applyWaypoints);
    }

    /**
     * Locator bar: targets are visible but see nobody, hunters see but are invisible. Outside a manhunt
     * everybody gets the vanilla ranges back.
     */
    private void applyWaypoints(Player player) {
        double transmit = DEFAULT_WAYPOINT_RANGE;
        double receive = DEFAULT_WAYPOINT_RANGE;
        if (this.isEnabled()) {
            switch (this.role(player)) {
                case TARGET -> receive = 0;
                case HUNTER -> transmit = 0;
                case SPECTATOR -> {
                    transmit = 0;
                    receive = 0;
                }
            }
        }
        setBase(player, Attribute.WAYPOINT_TRANSMIT_RANGE, transmit);
        setBase(player, Attribute.WAYPOINT_RECEIVE_RANGE, receive);
    }

    private static void setBase(Player player, Attribute attribute, double value) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null && instance.getBaseValue() != value) {
            instance.setBaseValue(value);
        }
    }

    /** Called when the manhunt mode is switched on or off. */
    public void onModeChanged() {
        this.refreshAll();
        this.worlds.applyLocatorBar();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        this.applyWaypoints(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (player.isOnline()) {
                this.prepare(player);
            }
        });
    }

    /** Hunters stay frozen during the head start. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!this.isEnabled() || !this.isHeadStart() || !event.hasChangedPosition()) {
            return;
        }
        Player player = event.getPlayer();
        if (this.role(player) == Role.HUNTER && this.worlds.isGameWorld(player.getWorld())) {
            Location from = event.getFrom();
            Location to = event.getTo().clone();
            to.setX(from.getX());
            to.setZ(from.getZ());
            if (to.getY() > from.getY()) {
                to.setY(from.getY());
            }
            event.setTo(to);
        }
    }

    /** Nobody can hurt anybody during the head start. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (this.isEnabled() && this.isHeadStart() && event.getEntity() instanceof Player player && this.worlds.isGameWorld(player.getWorld())) {
            event.setCancelled(true);
        }
    }

    /** Right-click with a compass: it points to the nearest target in the same dimension. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCompass(PlayerInteractEvent event) {
        if (!this.isEnabled() || event.getItem() == null || event.getItem().getType() != Material.COMPASS) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player hunter = event.getPlayer();
        if (this.role(hunter) != Role.HUNTER) {
            return;
        }
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (UUID uuid : this.targets) {
            Player target = Bukkit.getPlayer(uuid);
            if (target == null || target.getWorld() != hunter.getWorld() || target.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            double distance = target.getLocation().distanceSquared(hunter.getLocation());
            if (distance < best) {
                best = distance;
                nearest = target;
            }
        }
        if (nearest == null) {
            this.plugin.messages().send(hunter, "manhunt.compass-none");
            return;
        }
        ItemStack compass = event.getItem();
        CompassMeta meta = (CompassMeta) compass.getItemMeta();
        meta.setLodestone(nearest.getLocation());
        meta.setLodestoneTracked(false);
        compass.setItemMeta(meta);
        event.setCancelled(true);
        this.plugin.messages().send(hunter, "manhunt.compass-tracking",
            Placeholder.unparsed("player", nearest.getName()),
            Placeholder.unparsed("distance", Long.toString(Math.round(Math.sqrt(best)))));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------------------------

    public void save(ConfigurationSection section) {
        section.set("targets", this.targets.stream().map(UUID::toString).toList());
        section.set("spectators", this.spectators.stream().map(UUID::toString).toList());
        ConfigurationSection livesSection = section.createSection("lives");
        this.lives.forEach((uuid, value) -> livesSection.set(uuid.toString(), value));
    }

    public void load(@Nullable ConfigurationSection section) {
        if (section == null) {
            return;
        }
        readUuids(section.getStringList("targets"), this.targets);
        readUuids(section.getStringList("spectators"), this.spectators);
        ConfigurationSection livesSection = section.getConfigurationSection("lives");
        if (livesSection != null) {
            for (String key : livesSection.getKeys(false)) {
                try {
                    this.lives.put(UUID.fromString(key), livesSection.getInt(key));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed entries
                }
            }
        }
    }

    private static void readUuids(List<String> raw, Set<UUID> into) {
        for (String value : raw) {
            try {
                into.add(UUID.fromString(value));
            } catch (IllegalArgumentException ignored) {
                // skip malformed entries
            }
        }
    }
}
