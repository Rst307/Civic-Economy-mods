package org.civiceconomy.platform.neoforge;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

final class MatchedWorldRollbackSnapshot {
    private MatchedWorldRollbackSnapshot() {}

    static void capture(
            Path ownedRunDirectory,
            Path worldDirectory,
            Path snapshotDirectory,
            Path databaseBackup) {
        Path root = root(ownedRunDirectory);
        Path world = owned(root, worldDirectory, "world directory");
        Path snapshot = owned(root, snapshotDirectory, "snapshot directory");
        Path database = owned(root, databaseBackup, "database backup");
        requireSeparateTrees(world, snapshot);
        requireDirectory(world, "World directory");
        requireRegularFile(database, "Matched Civic database backup");

        Path candidate = owned(
                root,
                snapshot.resolveSibling(snapshot.getFileName() + ".pending"),
                "snapshot candidate");
        deleteTree(candidate);
        copyWorldTree(world, candidate);
        Path snapshotDatabase = candidate
                .resolve("civiceconomy")
                .resolve("civic.sqlite3");
        copyFile(database, snapshotDatabase);
        deleteFile(snapshotDatabase.resolveSibling("civic.sqlite3-wal"));
        deleteFile(snapshotDatabase.resolveSibling("civic.sqlite3-shm"));
        deleteTree(snapshot);
        move(candidate, snapshot);
    }

    static void restore(
            Path ownedRunDirectory,
            Path snapshotDirectory,
            Path worldDirectory) {
        Path root = root(ownedRunDirectory);
        Path snapshot = owned(root, snapshotDirectory, "snapshot directory");
        Path world = owned(root, worldDirectory, "world directory");
        requireSeparateTrees(world, snapshot);
        requireDirectory(snapshot, "Matched world snapshot");
        requireRegularFile(
                snapshot.resolve("civiceconomy").resolve("civic.sqlite3"),
                "Matched snapshot Civic database");

        Path candidate = owned(
                root,
                world.resolveSibling(world.getFileName() + ".matched-rollback.pending"),
                "restore candidate");
        Path previous = owned(
                root,
                world.resolveSibling(world.getFileName() + ".matched-rollback.previous"),
                "restore archive");
        deleteTree(candidate);
        copyTree(snapshot, candidate);
        deleteTree(previous);
        boolean archived = false;
        try {
            if (Files.exists(world)) {
                move(world, previous);
                archived = true;
            }
            move(candidate, world);
        } catch (RuntimeException failure) {
            if (archived && !Files.exists(world) && Files.exists(previous)) {
                try {
                    move(previous, world);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
        deleteTree(previous);
    }

    private static Path root(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Owned run directory cannot be null");
        }
        Path root = path.toAbsolutePath().normalize();
        requireDirectory(root, "Owned run directory");
        return root;
    }

    private static Path owned(Path root, Path path, String label) {
        if (path == null) {
            throw new IllegalArgumentException(label + " cannot be null");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(root) || !normalized.startsWith(root)) {
            throw new IllegalArgumentException(label + " escaped the owned run directory");
        }
        return normalized;
    }

    private static void requireSeparateTrees(Path first, Path second) {
        if (first.startsWith(second) || second.startsWith(first)) {
            throw new IllegalArgumentException(
                    "World and matched snapshot directories must be separate");
        }
    }

    private static void requireDirectory(Path directory, String label) {
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) {
            throw new IllegalArgumentException(label + " is not a safe directory");
        }
    }

    private static void requireRegularFile(Path file, String label) {
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException(label + " is not a safe regular file");
        }
    }

    private static void copyTree(Path source, Path target) {
        copyTree(source, target, false);
    }

    private static void copyWorldTree(Path source, Path target) {
        copyTree(source, target, true);
    }

    private static void copyTree(Path source, Path target, boolean omitLiveFiles) {
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                        Path directory, BasicFileAttributes attributes) throws IOException {
                    rejectSymbolicLink(directory);
                    Files.createDirectories(target.resolve(source.relativize(directory)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(
                        Path file, BasicFileAttributes attributes) throws IOException {
                    rejectSymbolicLink(file);
                    Path relative = source.relativize(file);
                    if (omitLiveFiles && isLiveOnlyWorldFile(relative)) {
                        return FileVisitResult.CONTINUE;
                    }
                    copyFile(file, target.resolve(relative));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to copy matched world tree from " + source + " to " + target,
                    failure);
        }
    }

    private static boolean isLiveOnlyWorldFile(Path relative) {
        String normalized = relative.toString().replace('\\', '/');
        return normalized.equals("session.lock")
                || normalized.equals("civiceconomy/civic.sqlite3")
                || normalized.equals("civiceconomy/civic.sqlite3-wal")
                || normalized.equals("civiceconomy/civic.sqlite3-shm");
    }

    private static void rejectSymbolicLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Matched world snapshot cannot contain symbolic links: " + path);
        }
    }

    private static void copyFile(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.copy(
                        source,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
            } catch (UnsupportedOperationException unsupported) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to copy matched rollback file from " + source + " to " + target,
                    failure);
        }
    }

    private static void move(Path source, Path target) {
        try {
            Files.createDirectories(target.getParent());
            try {
                Files.move(
                        source,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to move matched rollback path from " + source + " to " + target,
                    failure);
        }
    }

    private static void deleteTree(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        if (Files.isSymbolicLink(directory)) {
            deleteFile(directory);
            return;
        }
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(
                        Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path path, IOException failure)
                        throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(path);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to delete matched rollback tree " + directory,
                    failure);
        }
    }

    private static void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to delete matched rollback file " + file,
                    failure);
        }
    }
}
