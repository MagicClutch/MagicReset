package dev.magicclutch.magicreset.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationsTest {

    @Test
    void plainSeconds() {
        assertEquals(Duration.ofSeconds(90), Durations.parse("90"));
    }

    @Test
    void units() {
        assertEquals(Duration.ofSeconds(45), Durations.parse("45s"));
        assertEquals(Duration.ofMinutes(90), Durations.parse("1h30m"));
        assertEquals(Duration.ofMillis(1_500), Durations.parse("1.5s"));
        assertEquals(Duration.ofDays(1).plusHours(2), Durations.parse("1d2h"));
        assertEquals(Duration.ofMillis(250), Durations.parse("250ms"));
    }

    @Test
    void clock() {
        assertEquals(Duration.ofSeconds(3_723).plusMillis(500), Durations.parse("1:02:03.5"));
        assertEquals(Duration.ofSeconds(125), Durations.parse("2:05"));
    }

    @Test
    void invalid() {
        assertNull(Durations.parse(""));
        assertNull(Durations.parse("abc"));
        assertNull(Durations.parse("1x"));
        assertNull(Durations.parse("1:75"));
        assertNull(Durations.parse(null));
    }
}
