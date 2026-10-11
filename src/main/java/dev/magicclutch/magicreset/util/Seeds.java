package dev.magicclutch.magicreset.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Seed parsing that mirrors the vanilla "Seed for the World Generator" field.
 */
public final class Seeds {

    private Seeds() {
    }

    /**
     * Numbers are used as-is, any other text is hashed with {@link String#hashCode()} exactly like vanilla does.
     * Blank input produces a random seed.
     */
    public static long parse(String input) {
        if (input == null || input.isBlank()) {
            return random();
        }
        String text = input.trim();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return text.hashCode();
        }
    }

    public static long random() {
        return ThreadLocalRandom.current().nextLong();
    }
}
