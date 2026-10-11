package dev.magicclutch.magicreset.util;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses human written durations such as {@code 90}, {@code 45s}, {@code 1h30m} or {@code 1:02:03.5}.
 */
public final class Durations {

    private static final Pattern UNIT_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|d|h|m|s)");
    private static final Pattern UNITS = Pattern.compile("(?:\\d+(?:\\.\\d+)?(?:ms|d|h|m|s))+");
    private static final Pattern CLOCK = Pattern.compile("(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,3}))?");

    private Durations() {
    }

    /**
     * @return the parsed duration or {@code null} if the input is not a valid duration
     */
    public static Duration parse(String input) {
        try {
            return parseUnchecked(input);
        } catch (ArithmeticException | NumberFormatException e) {
            return null; // absurdly large values
        }
    }

    private static Duration parseUnchecked(String input) {
        if (input == null) {
            return null;
        }
        String text = input.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        if (text.chars().allMatch(Character::isDigit)) {
            try {
                return Duration.ofSeconds(Long.parseLong(text));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        Matcher clock = CLOCK.matcher(text);
        if (clock.matches()) {
            long hours = clock.group(1) == null ? 0 : Long.parseLong(clock.group(1));
            long minutes = Long.parseLong(clock.group(2));
            long seconds = Long.parseLong(clock.group(3));
            if (seconds >= 60 || (clock.group(1) != null && minutes >= 60)) {
                return null;
            }
            long millis = 0;
            if (clock.group(4) != null) {
                String fraction = (clock.group(4) + "00").substring(0, 3);
                millis = Long.parseLong(fraction);
            }
            return Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds).plusMillis(millis);
        }

        if (!UNITS.matcher(text).matches()) {
            return null;
        }
        double totalMillis = 0;
        Matcher part = UNIT_PART.matcher(text);
        while (part.find()) {
            double value = Double.parseDouble(part.group(1));
            totalMillis += switch (part.group(2)) {
                case "d" -> value * 86_400_000d;
                case "h" -> value * 3_600_000d;
                case "m" -> value * 60_000d;
                case "s" -> value * 1_000d;
                default -> value;
            };
        }
        if (totalMillis > Long.MAX_VALUE / 2d) {
            return null;
        }
        return Duration.ofMillis(Math.round(totalMillis));
    }
}
