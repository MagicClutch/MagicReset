package dev.magicclutch.magicreset.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
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

    /**
     * Moves everything inside {@code source} into {@code target} (created if needed). Files in
     * {@code keepTopLevel} are copied instead of moved so they stay in place. Moving is instant on the same
     * drive; across drives it falls back to copy and delete.
     */
    public static void moveContents(Path source, Path target, Set<String> keepTopLevel) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        Files.createDirectories(target);
        try (var entries = Files.list(source)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                Path destination = target.resolve(entry.getFileName().toString());
                if (keepTopLevel.contains(entry.getFileName().toString())) {
                    Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING);
                    continue;
                }
                try {
                    Files.move(entry, destination);
                } catch (IOException moveFailed) {
                    copyRecursively(entry, destination);
                    deleteRecursively(entry);
                }
            }
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (!Files.isDirectory(path)) {
            deleteIfExists(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Deletes the oldest sub folders of {@code root} so that at most {@code keep} remain. Folder names must sort
     * chronologically.
     */
    public static void prune(Path root, int keep) throws IOException {
        if (keep <= 0 || !Files.isDirectory(root)) {
            return;
        }
        List<Path> folders;
        try (var entries = Files.list(root)) {
            folders = entries.filter(Files::isDirectory).sorted().toList();
        }
        for (int i = 0; i < folders.size() - keep; i++) {
            deleteRecursively(folders.get(i));
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
