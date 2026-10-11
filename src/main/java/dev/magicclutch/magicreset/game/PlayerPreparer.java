package dev.magicclutch.magicreset.game;

import dev.magicclutch.magicreset.config.Settings;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

/**
 * Resets players for a fresh run and prepares them for the limbo.
 */
final class PlayerPreparer {

    private PlayerPreparer() {
    }

    static void resetForNewRun(Player player, Settings.PlayerReset config) {
        if (config.clearInventory()) {
            player.getInventory().clear();
            player.setItemOnCursor(null);
        }
        if (config.clearEnderChest()) {
            player.getEnderChest().clear();
        }
        if (config.resetHealth()) {
            AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
            player.setHealth(maxHealth == null ? 20d : maxHealth.getValue());
            player.setAbsorptionAmount(0d);
        }
        if (config.resetHunger()) {
            player.setFoodLevel(20);
            player.setSaturation(5f);
            player.setExhaustion(0f);
        }
        if (config.resetExperience()) {
            player.setLevel(0);
            player.setExp(0f);
            player.setTotalExperience(0);
        }
        if (config.clearEffects()) {
            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        }
        if (config.revokeAdvancements()) {
            revokeAdvancements(player);
        }
        if (config.revokeRecipes()) {
            List<NamespacedKey> recipes = new ArrayList<>(player.getDiscoveredRecipes());
            player.undiscoverRecipes(recipes);
        }
        if (config.resetRespawnPoint()) {
            player.setRespawnLocation(null, true);
        }
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0f);
        player.setRemainingAir(player.getMaximumAir());
        player.setVelocity(new org.bukkit.util.Vector());
        player.setGameMode(config.gameMode());
        player.setAllowFlight(config.gameMode() == GameMode.CREATIVE || config.gameMode() == GameMode.SPECTATOR);
        player.setFlying(false);
        player.updateInventory();
    }

    private static void revokeAdvancements(Player player) {
        Iterator<Advancement> iterator = Bukkit.advancementIterator();
        while (iterator.hasNext()) {
            Advancement advancement = iterator.next();
            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            for (String criterion : progress.getAwardedCriteria()) {
                progress.revokeCriteria(criterion);
            }
        }
    }

    /** Puts a player into a harmless state for the limbo. Inventory is left alone. */
    static void prepareForLimbo(Player player, GameMode limboMode) {
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        player.setGameMode(limboMode);
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth == null ? 20d : maxHealth.getValue());
        player.setFoodLevel(20);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0f);
        player.setVelocity(new org.bukkit.util.Vector());
        player.setAllowFlight(limboMode == GameMode.CREATIVE || limboMode == GameMode.SPECTATOR);
        player.setFlying(false);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
    }

}
