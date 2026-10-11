package dev.magicclutch.magicreset.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldFilesTest {

    @TempDir
    Path temp;

    private Path dimension() throws IOException {
        Path dimension = this.temp.resolve("dimensions/minecraft/the_end");
        Files.createDirectories(dimension.resolve("region"));
        Files.writeString(dimension.resolve("region/r.0.0.mca"), "chunks");
        Files.createDirectories(dimension.resolve("data/minecraft"));
        Files.writeString(dimension.resolve("data/minecraft/ender_dragon_fight.dat"), "killed");
        Files.writeString(dimension.resolve("paper-world.yml"), "settings");
        return dimension;
    }

    @Test
    void backupThenWipeKeepsOnlyPaperConfig() throws IOException {
        Path dimension = this.dimension();
        Path backup = this.temp.resolve("backups/2026-10-11_18-00-00_run-1/minecraft_the_end");

        WorldFiles.moveContents(dimension, backup, Set.of("paper-world.yml"));
        WorldFiles.wipe(dimension, Set.of("paper-world.yml"));

        assertEquals("chunks", Files.readString(backup.resolve("region/r.0.0.mca")));
        assertEquals("killed", Files.readString(backup.resolve("data/minecraft/ender_dragon_fight.dat")));
        assertEquals("settings", Files.readString(backup.resolve("paper-world.yml")));
        assertTrue(Files.exists(dimension.resolve("paper-world.yml")));
        assertFalse(Files.exists(dimension.resolve("region")));
        assertFalse(Files.exists(dimension.resolve("data")));
    }

    @Test
    void pruneKeepsNewest() throws IOException {
        Path root = this.temp.resolve("backups");
        for (String name : new String[]{"2026-10-01_10-00-00_run-1", "2026-10-02_10-00-00_run-2", "2026-10-03_10-00-00_run-3"}) {
            Files.createDirectories(root.resolve(name).resolve("x"));
            Files.writeString(root.resolve(name).resolve("x/file"), name);
        }
        WorldFiles.prune(root, 2);
        assertFalse(Files.exists(root.resolve("2026-10-01_10-00-00_run-1")));
        assertTrue(Files.exists(root.resolve("2026-10-02_10-00-00_run-2")));
        assertTrue(Files.exists(root.resolve("2026-10-03_10-00-00_run-3")));
    }
}
