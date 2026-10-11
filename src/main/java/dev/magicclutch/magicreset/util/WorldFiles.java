package dev.magicclutch.magicreset.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/**
 * File operations for dimension folders.
 */
public final class WorldFiles {

    private WorldFiles() {
    }

    /**
     * Deletes the content of a dimension folder.
     *
     * @param dimensionFolder folder to wipe
     * @param keepTopLevel    file names directly inside {@code dimensionFolder} that must survive (e.g. paper-world.yml)
     * @throws IOException if anything could not be deleted
     */
    public static void wipe(Path dimensionFolder, Set<String> keepTopLevel) throws IOException {
        if (!Files.isDirectory(dimensionFolder)) {
            return;
        }
        Files.walkFileTree(dimensionFolder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                boolean topLevel = dimensionFolder.equals(file.getParent());
                if (topLevel && keepTopLevel.contains(file.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }
                deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                if (dir.equals(dimensionFolder)) {
                    return FileVisitResult.CONTINUE;
                }
                deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
        try (var remaining = Files.list(dimensionFolder)) {
            if (remaining.findAny().isEmpty()) {
                Files.deleteIfExists(dimensionFolder);
            }
        }
    }

    private static void deleteIfExists(Path path) throws IOException {
        try {
            Files.deleteIfExists(path);
        } catch (NoSuchFileException ignored) {
            // already gone
        }
    }

    /**
     * @return {@code true} if the folder contains generated world data (anything besides the kept files)
     */
    public static boolean hasWorldData(Path dimensionFolder, Set<String> ignored) {
        if (!Files.isDirectory(dimensionFolder)) {
            return false;
        }
        try (var entries = Files.list(dimensionFolder)) {
            return entries.anyMatch(p -> !ignored.contains(p.getFileName().toString()));
        } catch (IOException e) {
            return true;
        }
    }
}
