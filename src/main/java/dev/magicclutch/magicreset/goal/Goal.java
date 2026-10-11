package dev.magicclutch.magicreset.goal;

import org.bukkit.NamespacedKey;
import org.jspecify.annotations.Nullable;

/**
 * The objective of a run.
 *
 * @param type   what has to be done
 * @param target registry key of the entity/block/item/advancement or the dimension name, {@code null} for {@link GoalType#NONE}
 * @param amount how often it has to be done (only for types that {@link GoalType#supportsAmount() support amounts})
 */
public record Goal(GoalType type, @Nullable NamespacedKey target, int amount) {

    public static final Goal NONE = new Goal(GoalType.NONE, null, 1);

    public Goal {
        amount = type.supportsAmount() ? Math.max(1, amount) : 1;
        if (!type.needsTarget()) {
            target = null;
        } else if (target == null) {
            throw new IllegalArgumentException("Goal type " + type + " requires a target");
        }
    }

    public boolean isNone() {
        return this.type == GoalType.NONE;
    }

    public boolean matches(NamespacedKey key) {
        return this.target != null && this.target.equals(key);
    }
}
