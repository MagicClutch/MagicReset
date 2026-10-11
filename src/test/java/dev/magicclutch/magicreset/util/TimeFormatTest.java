package dev.magicclutch.magicreset.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TimeFormatTest {

    @Test
    void digitalWithoutHours() {
        assertEquals("00:00.00", TimeFormat.format(0, 2, TimeFormat.Style.DIGITAL));
        assertEquals("02:03.45", TimeFormat.format(123_456, 2, TimeFormat.Style.DIGITAL));
        assertEquals("59:59.999", TimeFormat.format(3_599_999, 3, TimeFormat.Style.DIGITAL));
    }

    @Test
    void digitalWithHours() {
        assertEquals("1:00:00", TimeFormat.format(3_600_000, 0, TimeFormat.Style.DIGITAL));
        assertEquals("12:34:56.7", TimeFormat.format(45_296_789, 1, TimeFormat.Style.DIGITAL));
    }

    @Test
    void fractionsAreTruncated() {
        assertEquals("00:01.99", TimeFormat.format(1_999, 2, TimeFormat.Style.DIGITAL));
        assertEquals("00:01", TimeFormat.format(1_999, 0, TimeFormat.Style.DIGITAL));
    }

    @Test
    void text() {
        assertEquals("5.20s", TimeFormat.format(5_200, 2, TimeFormat.Style.TEXT));
        assertEquals("2m 03s", TimeFormat.format(123_456, 0, TimeFormat.Style.TEXT));
        assertEquals("1h 02m 03.004s", TimeFormat.format(3_723_004, 3, TimeFormat.Style.TEXT));
    }

    @Test
    void negativeIsZero() {
        assertEquals("00:00.000", TimeFormat.format(-5, 3, TimeFormat.Style.DIGITAL));
    }

    @Test
    void compact() {
        assertEquals("0s", TimeFormat.compact(0));
        assertEquals("1m 5s", TimeFormat.compact(65));
        assertEquals("1d 1h", TimeFormat.compact(90_000));
    }

    @Test
    void patterns() {
        long time = 3_723_456L; // 1h 2m 3.456s
        assertEquals("01:02:03", TimeFormat.pattern(time, "HH:mm:ss"));
        assertEquals("1:02:03.45", TimeFormat.pattern(time, "H:mm:ss.SS"));
        // without hours the minutes hold the whole rest
        assertEquals("62:03.456", TimeFormat.pattern(time, "mm:ss.SSS"));
        // only seconds: they hold everything
        assertEquals("3723", TimeFormat.pattern(time, "ss"));
        assertEquals("00:05", TimeFormat.pattern(5_999, "mm:ss"));
    }

    @Test
    void patternValidation() {
        org.junit.jupiter.api.Assertions.assertTrue(TimeFormat.isValidPattern("HH:mm:ss"));
        org.junit.jupiter.api.Assertions.assertFalse(TimeFormat.isValidPattern("SSS"));
        org.junit.jupiter.api.Assertions.assertFalse(TimeFormat.isValidPattern(""));
    }
}
