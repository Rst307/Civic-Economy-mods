package org.civiceconomy.platform.neoforge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class DatabaseRestoreFiles {
    private DatabaseRestoreFiles() {}

    static String sha256(Path file) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to hash database snapshot " + file, failure);
        }
    }

    static void requireEvidence(Path file, long expectedSize, String expectedSha256) {
        try {
            if (!Files.isRegularFile(file)
                    || Files.size(file) != expectedSize
                    || !sha256(file).equals(expectedSha256)) {
                throw new IllegalStateException(
                        "Database snapshot file evidence does not match " + file);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to inspect database snapshot " + file, failure);
        }
    }

    static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read database snapshot size " + file, failure);
        }
    }

    static void move(Path source, Path destination) {
        try {
            Files.createDirectories(destination.getParent());
            try {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(source, destination);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to move database snapshot " + source + " to " + destination,
                    failure);
        }
    }

    static void deleteIfExists(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to delete database file " + file, failure);
        }
    }
}
