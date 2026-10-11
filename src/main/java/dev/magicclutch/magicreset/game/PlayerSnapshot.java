package dev.magicclutch.magicreset.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.jspecify.annotations.Nullable;

/**
 * Complete gameplay state of a player, taken when the run is paused and restored when it continues.
 */
public record PlayerSnapshot(
    NamespacedKey world,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    GameMode gameMode,
    double health,
    double absorption,
    int food,
    float saturation,
    float exhaustion,
    int level,
    float exp,
    int totalExperience,
    int fireTicks,
    int freezeTicks,
    int remainingAir,
    float fallDistance,
    boolean allowFlight,
    boolean flying,
    List<PotionEffect> effects,
    ItemStack[] inventory
) {

    public static PlayerSnapshot capture(Player player) {
        Location location = player.getLocation();
        ItemStack[] contents = player.getInventory().getContents();
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? ItemStack.empty() : contents[i].clone();
        }
        return new PlayerSnapshot(
            location.getWorld().getKey(),
            location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch(),
            player.getGameMode(),
            player.getHealth(),
            player.getAbsorptionAmount(),
            player.getFoodLevel(),
            player.getSaturation(),
            player.getExhaustion(),
            player.getLevel(),
            player.getExp(),
            player.getTotalExperience(),
            player.getFireTicks(),
            player.getFreezeTicks(),
            player.getRemainingAir(),
            player.getFallDistance(),
            player.getAllowFlight(),
            player.isFlying(),
            List.copyOf(player.getActivePotionEffects()),
            copy
        );
    }

    public @Nullable Location location() {
        World target = Bukkit.getWorld(this.world);
        return target == null ? null : new Location(target, this.x, this.y, this.z, this.yaw, this.pitch);
    }

    /** Restores everything except the location (teleporting is up to the caller). */
    public void apply(Player player) {
        player.setGameMode(this.gameMode);
        // inventory and effects first: armor attributes and Health Boost raise the maximum health
        player.getInventory().setContents(Arrays.copyOf(this.inventory, player.getInventory().getSize()));
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.addPotionEffects(this.effects);
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double cap = maxHealth == null ? 20d : maxHealth.getValue();
        player.setHealth(Math.clamp(this.health, 0.5d, cap));
        player.setAbsorptionAmount(this.absorption);
        player.setFoodLevel(this.food);
        player.setSaturation(this.saturation);
        player.setExhaustion(this.exhaustion);
        player.setLevel(this.level);
        player.setExp(Math.clamp(this.exp, 0f, 1f));
        player.setTotalExperience(this.totalExperience);
        player.setFireTicks(this.fireTicks);
        player.setFreezeTicks(this.freezeTicks);
        player.setRemainingAir(this.remainingAir);
        player.setFallDistance(this.fallDistance);
        player.setAllowFlight(this.allowFlight || this.gameMode == GameMode.CREATIVE || this.gameMode == GameMode.SPECTATOR);
        player.setFlying(this.flying && player.getAllowFlight());
        player.updateInventory();
    }

    public void save(ConfigurationSection section) {
        section.set("world", this.world.asString());
        section.set("x", this.x);
        section.set("y", this.y);
        section.set("z", this.z);
        section.set("yaw", this.yaw);
        section.set("pitch", this.pitch);
        section.set("game-mode", this.gameMode.name());
        section.set("health", this.health);
        section.set("absorption", this.absorption);
        section.set("food", this.food);
        section.set("saturation", this.saturation);
        section.set("exhaustion", this.exhaustion);
        section.set("level", this.level);
        section.set("exp", this.exp);
        section.set("total-experience", this.totalExperience);
        section.set("fire-ticks", this.fireTicks);
        section.set("freeze-ticks", this.freezeTicks);
        section.set("remaining-air", this.remainingAir);
        section.set("fall-distance", this.fallDistance);
        section.set("allow-flight", this.allowFlight);
        section.set("flying", this.flying);
        section.set("effects", new ArrayList<>(this.effects));
        section.set("inventory", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(this.inventory)));
    }

    public static @Nullable PlayerSnapshot load(@Nullable ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        NamespacedKey world = NamespacedKey.fromString(section.getString("world", ""));
        if (world == null) {
            return null;
        }
        List<PotionEffect> effects = new ArrayList<>();
        for (Object entry : section.getList("effects", List.of())) {
            if (entry instanceof PotionEffect effect) {
                effects.add(effect);
            }
        }
        ItemStack[] inventory;
        try {
            inventory = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(section.getString("inventory", "")));
        } catch (RuntimeException e) {
            return null;
        }
        GameMode gameMode;
        try {
            gameMode = GameMode.valueOf(section.getString("game-mode", "SURVIVAL"));
        } catch (IllegalArgumentException e) {
            gameMode = GameMode.SURVIVAL;
        }
        return new PlayerSnapshot(
            world,
            section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
            (float) section.getDouble("yaw"), (float) section.getDouble("pitch"),
            gameMode,
            section.getDouble("health", 20d),
            section.getDouble("absorption"),
            section.getInt("food", 20),
            (float) section.getDouble("saturation", 5d),
            (float) section.getDouble("exhaustion"),
            section.getInt("level"),
            (float) section.getDouble("exp"),
            section.getInt("total-experience"),
            section.getInt("fire-ticks"),
            section.getInt("freeze-ticks"),
            section.getInt("remaining-air", 300),
            (float) section.getDouble("fall-distance"),
            section.getBoolean("allow-flight"),
            section.getBoolean("flying"),
            List.copyOf(effects),
            inventory
        );
    }
}
