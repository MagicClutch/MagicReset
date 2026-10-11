package dev.magicclutch.magicreset.util;

import java.util.Locale;

/**
 * Formats elapsed times for the timer display and chat output.
 */
public final class TimeFormat {

    public enum Style {
        /** {@code 1:02:03.45} – hours are only shown once the time reaches one hour. */
        DIGITAL,
        /** {@code 1h 02m 03.45s} */
        TEXT
    }

    private TimeFormat() {
    }

    /**
     * Formats {@code millis} using the given style. Fractions are truncated, never rounded, so the
     * displayed value never runs ahead of the real time.
     *
     * @param millis   elapsed milliseconds, negative values are treated as zero
     * @param decimals number of fractional second digits (0-3)
     * @param style    output style
     * @return the formatted time
     */
    public static String format(long millis, int decimals, Style style) {
        long ms = Math.max(0L, millis);
        int digits = Math.clamp(decimals, 0, 3);

        long hours = ms / 3_600_000L;
        long minutes = (ms / 60_000L) % 60L;
        long seconds = (ms / 1_000L) % 60L;
        String fraction = fraction(ms % 1_000L, digits);

        return switch (style) {
            case DIGITAL -> hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d%s", hours, minutes, seconds, fraction)
                : String.format(Locale.ROOT, "%02d:%02d%s", minutes, seconds, fraction);
            case TEXT -> {
                if (hours > 0) {
                    yield String.format(Locale.ROOT, "%dh %02dm %02d%ss", hours, minutes, seconds, fraction);
                }
                if (minutes > 0) {
                    yield String.format(Locale.ROOT, "%dm %02d%ss", minutes, seconds, fraction);
                }
                yield String.format(Locale.ROOT, "%d%ss", seconds, fraction);
            }
        };
    }

    private static String fraction(long millisOfSecond, int digits) {
        if (digits == 0) {
            return "";
        }
        String padded = String.format(Locale.ROOT, "%03d", millisOfSecond);
        return "." + padded.substring(0, digits);
    }

    /**
     * Formats a countdown or scheduler duration in a compact human form, e.g. {@code 1h 5m 3s}.
     */
    public static String compact(long totalSeconds) {
        long s = Math.max(0L, totalSeconds);
        long days = s / 86_400L;
        long hours = (s / 3_600L) % 24L;
        long minutes = (s / 60L) % 60L;
        long seconds = s % 60L;
        StringBuilder out = new StringBuilder();
        if (days > 0) {
            out.append(days).append("d ");
        }
        if (hours > 0) {
            out.append(hours).append("h ");
        }
        if (minutes > 0) {
            out.append(minutes).append("m ");
        }
        if (seconds > 0 || out.isEmpty()) {
            out.append(seconds).append('s');
        }
        return out.toString().trim();
    }
}
