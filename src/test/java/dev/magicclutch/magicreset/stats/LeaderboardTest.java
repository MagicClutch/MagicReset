package dev.magicclutch.magicreset.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeaderboardTest {

    private static final String BOARD = "beat_game|-|1|rta";

    @TempDir
    Path folder;

    @Test
    void sortedFastestFirstAndOnlyPersonalBests() {
        Leaderboard board = new Leaderboard(this.folder.toFile(), Logger.getAnonymousLogger());
        UUID alex = UUID.randomUUID();
        UUID steve = UUID.randomUUID();

        assertEquals(1, board.record(BOARD, new Leaderboard.Entry(alex, "Alex", 900_000, 1, 42)));
        assertEquals(1, board.record(BOARD, new Leaderboard.Entry(steve, "Steve", 600_000, 2, 42)));
        // slower than Alex's own best: ignored
        assertEquals(0, board.record(BOARD, new Leaderboard.Entry(alex, "Alex", 950_000, 3, 42)));
        // faster: replaces the old time
        assertEquals(2, board.record(BOARD, new Leaderboard.Entry(alex, "Alex", 700_000, 4, 42)));

        assertEquals(2, board.top(BOARD).size());
        assertEquals("Steve", board.top(BOARD).get(0).name());
        assertEquals(700_000, board.top(BOARD).get(1).millis());
    }

    @Test
    void survivesReload() {
        File dir = this.folder.toFile();
        Leaderboard board = new Leaderboard(dir, Logger.getAnonymousLogger());
        board.record(BOARD, new Leaderboard.Entry(UUID.randomUUID(), "Alex", 123_456, 5, -7));

        Leaderboard reloaded = new Leaderboard(dir, Logger.getAnonymousLogger());
        reloaded.load();
        assertEquals(1, reloaded.top(BOARD).size());
        assertEquals(123_456, reloaded.top(BOARD).getFirst().millis());
        assertEquals(-7, reloaded.top(BOARD).getFirst().seed());
    }
}
