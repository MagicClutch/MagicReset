package dev.magicclutch.magicreset.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SeedsTest {

    @Test
    void numbersAreUsedDirectly() {
        assertEquals(-4530634556500121041L, Seeds.parse("-4530634556500121041"));
        assertEquals(42L, Seeds.parse(" 42 "));
    }

    @Test
    void textIsHashedLikeVanilla() {
        assertEquals("glacier".hashCode(), Seeds.parse("glacier"));
    }
}
