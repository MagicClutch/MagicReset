package dev.magicclutch.magicreset.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

class GoalTargetsTest {

    private static final List<NamespacedKey> ITEMS = List.of(
        NamespacedKey.minecraft("deepslate_diamond_ore"),
        NamespacedKey.minecraft("diamond_sword"),
        NamespacedKey.minecraft("diamond"),
        NamespacedKey.minecraft("diamond_ore"),
        NamespacedKey.minecraft("ender_pearl"),
        NamespacedKey.minecraft("story/enter_the_end"),
        NamespacedKey.minecraft("end/kill_dragon"));

    @Test
    void exactMatchFirstThenPrefixThenWords() {
        List<NamespacedKey> result = GoalTargets.search(ITEMS, "diamond", 10);
        assertEquals("diamond", result.get(0).getKey());
        assertEquals("diamond_ore", result.get(1).getKey());
        assertEquals("deepslate_diamond_ore", result.getLast().getKey());
    }

    @Test
    void everyWordMustMatch() {
        assertEquals(List.of(NamespacedKey.minecraft("diamond_ore"), NamespacedKey.minecraft("deepslate_diamond_ore")),
            GoalTargets.search(ITEMS, "diamond ore", 10));
    }

    @Test
    void searchesAdvancementPathsAndIgnoresCase() {
        List<NamespacedKey> result = GoalTargets.search(ITEMS, "END", 10);
        assertTrue(result.contains(NamespacedKey.minecraft("story/enter_the_end")));
        assertEquals("end/kill_dragon", result.getFirst().getKey());
    }

    @Test
    void limitAndEmptyQuery() {
        assertEquals(2, GoalTargets.search(ITEMS, "d", 2).size());
        assertTrue(GoalTargets.search(ITEMS, "   ", 10).isEmpty());
        assertTrue(GoalTargets.search(ITEMS, "netherite", 10).isEmpty());
    }
}
