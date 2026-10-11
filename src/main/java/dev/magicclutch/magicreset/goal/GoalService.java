package dev.magicclutch.magicreset.goal;

import dev.magicclutch.magicreset.config.Messages;
import dev.magicclutch.magicreset.timer.TimerScope;
import dev.magicclutch.magicreset.world.GameWorlds;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.advancement.Advancement;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Tracks goal progress and reports completions.
 */
public final class GoalService implements Listener {

    public static final NamespacedKey OVERWORLD = NamespacedKey.minecraft("overworld");
    public static final NamespacedKey NETHER = NamespacedKey.minecraft("the_nether");
    public static final NamespacedKey END = NamespacedKey.minecraft("the_end");

    private final Supplier<Goal> goal;
    private final Supplier<TimerScope> scope;
    private final Supplier<Messages> messages;
    private final GameWorlds worlds;
    private final Predicate<Player> canProgress;
    private final Consumer<Player> onComplete;

    private int globalProgress;
    private final Map<UUID, Integer> playerProgress = new HashMap<>();
    /** Tick of the last respawn per player; a respawn is not "entering" a dimension. */
    private final Map<UUID, Integer> respawnTick = new HashMap<>();

    public GoalService(
        Supplier<Goal> goal,
        Supplier<TimerScope> scope,
        Supplier<Messages> messages,
        GameWorlds worlds,
        Predicate<Player> canProgress,
        Consumer<Player> onComplete
    ) {
        this.goal = goal;
        this.scope = scope;
        this.messages = messages;
        this.worlds = worlds;
        this.canProgress = canProgress;
        this.onComplete = onComplete;
    }

    public Goal goal() {
        return this.goal.get();
    }

    public void resetProgress() {
        this.globalProgress = 0;
        this.playerProgress.clear();
    }

    /** Progress shown to {@code player}: shared in global mode, personal in individual mode. */
    public int progress(@Nullable Player player) {
        Goal current = this.goal();
        if (current.type() == GoalType.OBTAIN_ITEM) {
            if (this.scope.get() == TimerScope.GLOBAL || player == null) {
                int best = 0;
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (this.worlds.isGameWorld(online.getWorld())) {
                        best = Math.max(best, this.count(online, current));
                    }
                }
                return Math.min(best, current.amount());
            }
            return Math.min(this.count(player, current), current.amount());
        }
        if (this.scope.get() == TimerScope.GLOBAL || player == null) {
            return this.globalProgress;
        }
        return this.playerProgress.getOrDefault(player.getUniqueId(), 0);
    }

    private void advance(Player player, int by) {
        Goal current = this.goal();
        if (!this.canProgress.test(player)) {
            return;
        }
        int value;
        if (this.scope.get() == TimerScope.GLOBAL) {
            this.globalProgress = Math.min(current.amount(), this.globalProgress + by);
            value = this.globalProgress;
        } else {
            value = this.playerProgress.merge(player.getUniqueId(), by, Integer::sum);
        }
        if (value >= current.amount()) {
            this.onComplete.accept(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        Goal current = this.goal();
        if (current.type() != GoalType.KILL_ENTITY || !current.matches(event.getEntityType().getKey())) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (!this.worlds.isGameWorld(entity.getWorld())) {
            return;
        }
        Player killer = entity.getKiller();
        if (killer != null) {
            this.advance(killer, 1);
            return;
        }
        // Kills without a direct player killer (bed or crystal explosions on the dragon, fall damage, ...):
        // in global mode it counts once, in individual mode it counts for everyone in that world.
        if (this.scope.get() == TimerScope.GLOBAL) {
            entity.getWorld().getPlayers().stream().filter(this.canProgress).findFirst().ifPresent(player -> this.advance(player, 1));
        } else {
            for (Player player : entity.getWorld().getPlayers()) {
                this.advance(player, 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Goal current = this.goal();
        if (current.type() != GoalType.ENTER_DIMENSION) {
            return;
        }
        Player player = event.getPlayer();
        Integer respawnedAt = this.respawnTick.remove(player.getUniqueId());
        if (respawnedAt != null && respawnedAt == Bukkit.getCurrentTick()) {
            return;
        }
        GameWorlds.Dimension from = this.worlds.dimensionOf(event.getFrom());
        GameWorlds.Dimension to = this.worlds.dimensionOf(player.getWorld());
        if (from == null || to == null) {
            return; // limbo transfers do not count
        }
        if (current.matches(dimensionKey(to))) {
            this.advance(player, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        this.respawnTick.put(event.getPlayer().getUniqueId(), Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Goal current = this.goal();
        if (current.type() == GoalType.ADVANCEMENT && current.matches(event.getAdvancement().getKey())) {
            this.advance(event.getPlayer(), 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Goal current = this.goal();
        if (current.type() == GoalType.BREAK_BLOCK && current.matches(event.getBlock().getType().getKey())) {
            this.advance(event.getPlayer(), 1);
        }
    }

    /**
     * Inventory based goals are polled, which covers every way of obtaining an item (pickup, crafting,
     * smelting, trading, commands, ...).
     */
    public void pollInventories() {
        Goal current = this.goal();
        if (current.type() != GoalType.OBTAIN_ITEM) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (this.canProgress.test(player) && this.count(player, current) >= current.amount()) {
                this.onComplete.accept(player);
            }
        }
    }

    private int count(Player player, Goal current) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && current.matches(item.getType().getKey())) {
                count += item.getAmount();
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (current.matches(cursor.getType().getKey())) {
            count += cursor.getAmount();
        }
        return count;
    }

    public static NamespacedKey dimensionKey(GameWorlds.Dimension dimension) {
        return switch (dimension) {
            case OVERWORLD -> OVERWORLD;
            case NETHER -> NETHER;
            case END -> END;
        };
    }

    /**
     * Validates a target for a goal type.
     *
     * @return {@code null} if valid, otherwise the message key describing the problem
     */
    public static @Nullable String validate(GoalType type, NamespacedKey target) {
        return switch (type) {
            case NONE -> null;
            case KILL_ENTITY -> {
                EntityType entity = Registry.ENTITY_TYPE.get(target);
                yield entity != null && entity.isAlive() ? null : "goal.invalid-entity";
            }
            case ENTER_DIMENSION -> target.equals(OVERWORLD) || target.equals(NETHER) || target.equals(END) ? null : "goal.invalid-dimension";
            case ADVANCEMENT -> Bukkit.getAdvancement(target) != null ? null : "goal.invalid-advancement";
            case BREAK_BLOCK -> {
                Material material = Registry.MATERIAL.get(target);
                yield material != null && material.isBlock() ? null : "goal.invalid-block";
            }
            case OBTAIN_ITEM -> {
                Material material = Registry.MATERIAL.get(target);
                yield material != null && material.isItem() ? null : "goal.invalid-item";
            }
        };
    }

    /** Human readable description, e.g. "Kill the Ender Dragon". */
    public Component describe(Goal current) {
        Messages text = this.messages.get();
        if (current.isNone() || current.target() == null) {
            return text.get("goal.describe.none");
        }
        Component target = this.targetName(current);
        String key = "goal.describe." + current.type().name().toLowerCase(Locale.ROOT).replace('_', '-');
        return text.get(key,
            Placeholder.component("target", target),
            Placeholder.unparsed("amount", Integer.toString(current.amount())),
            Placeholder.unparsed("count", current.amount() > 1 ? current.amount() + "× " : ""));
    }

    private Component targetName(Goal current) {
        NamespacedKey target = current.target();
        if (target == null) {
            return Component.empty();
        }
        return switch (current.type()) {
            case KILL_ENTITY -> {
                EntityType entity = Registry.ENTITY_TYPE.get(target);
                yield entity == null ? Component.text(target.asString()) : Component.translatable(entity.translationKey());
            }
            case ENTER_DIMENSION -> this.messages.get().get("dimension." + target.getKey().replace('_', '-'));
            case ADVANCEMENT -> {
                Advancement advancement = Bukkit.getAdvancement(target);
                if (advancement == null) {
                    yield Component.text(target.asString());
                }
                yield advancement.getDisplay() != null ? advancement.getDisplay().title() : Component.text(target.getKey());
            }
            case BREAK_BLOCK, OBTAIN_ITEM -> {
                Material material = Registry.MATERIAL.get(target);
                yield material == null ? Component.text(target.asString()) : Component.translatable(material.translationKey());
            }
            case NONE -> Component.empty();
        };
    }

    public void save(ConfigurationSection section) {
        section.set("global", this.globalProgress);
        ConfigurationSection players = section.createSection("players");
        this.playerProgress.forEach((uuid, value) -> players.set(uuid.toString(), value));
    }

    public void load(@Nullable ConfigurationSection section) {
        this.resetProgress();
        if (section == null) {
            return;
        }
        this.globalProgress = section.getInt("global");
        ConfigurationSection players = section.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                try {
                    this.playerProgress.put(UUID.fromString(key), players.getInt(key));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed entries
                }
            }
        }
    }
}
