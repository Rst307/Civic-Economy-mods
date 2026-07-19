package org.civiceconomy.platform.neoforge;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.UUID;
import org.civiceconomy.persistence.StoredDatabaseRestoreOperation;

record DatabaseRestoreManifest(
        String phase,
        UUID operationId,
        String administratorIdentity,
        String requestId,
        UUID sourceBackupOperationId,
        String sourceFileName,
        long sourceSizeBytes,
        String sourceSha256,
        UUID rollbackBackupOperationId,
        String rollbackFileName,
        long rollbackSizeBytes,
        String rollbackSha256,
        String reason,
        long stagedAtEpochMillis) {
    private static final String FORMAT = "1";

    static DatabaseRestoreManifest staged(StoredDatabaseRestoreOperation operation) {
        return new DatabaseRestoreManifest(
                "STAGED",
                operation.operationId(),
                operation.administratorIdentity(),
                operation.requestId(),
                operation.sourceBackupOperationId(),
                operation.sourceFileName(),
                operation.sourceSizeBytes(),
                operation.sourceSha256(),
                null,
                null,
                0L,
                null,
                operation.reason(),
                operation.stagedAtEpochMillis());
    }

    DatabaseRestoreManifest ready(
            UUID rollbackOperationId,
            String rollbackFile,
            long rollbackSize,
            String rollbackSha) {
        return new DatabaseRestoreManifest(
                "READY",
                operationId,
                administratorIdentity,
                requestId,
                sourceBackupOperationId,
                sourceFileName,
                sourceSizeBytes,
                sourceSha256,
                rollbackOperationId,
                rollbackFile,
                rollbackSize,
                rollbackSha,
                reason,
                stagedAtEpochMillis);
    }

    static DatabaseRestoreManifest read(Path file) {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read staged database restore " + file, failure);
        }
        if (!FORMAT.equals(required(properties, "format"))) {
            throw new IllegalArgumentException("Unsupported database restore manifest format");
        }
        String phase = required(properties, "phase");
        if (!"STAGED".equals(phase) && !"READY".equals(phase)) {
            throw new IllegalArgumentException("Unknown database restore manifest phase " + phase);
        }
        UUID rollbackId = optionalUuid(properties.getProperty("rollbackBackupOperationId"));
        String rollbackFile = optional(properties.getProperty("rollbackFileName"));
        long rollbackSize = Long.parseLong(properties.getProperty("rollbackSizeBytes", "0"));
        String rollbackSha = optional(properties.getProperty("rollbackSha256"));
        if ("READY".equals(phase)
                && (rollbackId == null
                        || rollbackFile == null
                        || rollbackSize <= 0L
                        || rollbackSha == null
                        || rollbackSha.length() != 64)) {
            throw new IllegalArgumentException("READY restore manifest lacks rollback evidence");
        }
        return new DatabaseRestoreManifest(
                phase,
                UUID.fromString(required(properties, "operationId")),
                required(properties, "administratorIdentity"),
                required(properties, "requestId"),
                UUID.fromString(required(properties, "sourceBackupOperationId")),
                required(properties, "sourceFileName"),
                Long.parseLong(required(properties, "sourceSizeBytes")),
                required(properties, "sourceSha256"),
                rollbackId,
                rollbackFile,
                rollbackSize,
                rollbackSha,
                required(properties, "reason"),
                Long.parseLong(required(properties, "stagedAtEpochMillis")));
    }

    void write(Path file) {
        Properties properties = new Properties();
        properties.setProperty("format", FORMAT);
        properties.setProperty("phase", phase);
        properties.setProperty("operationId", operationId.toString());
        properties.setProperty("administratorIdentity", administratorIdentity);
        properties.setProperty("requestId", requestId);
        properties.setProperty("sourceBackupOperationId", sourceBackupOperationId.toString());
        properties.setProperty("sourceFileName", sourceFileName);
        properties.setProperty("sourceSizeBytes", Long.toString(sourceSizeBytes));
        properties.setProperty("sourceSha256", sourceSha256);
        properties.setProperty("reason", reason);
        properties.setProperty("stagedAtEpochMillis", Long.toString(stagedAtEpochMillis));
        if (rollbackBackupOperationId != null) {
            properties.setProperty(
                    "rollbackBackupOperationId", rollbackBackupOperationId.toString());
            properties.setProperty("rollbackFileName", rollbackFileName);
            properties.setProperty("rollbackSizeBytes", Long.toString(rollbackSizeBytes));
            properties.setProperty("rollbackSha256", rollbackSha256);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Civic Economy staged database restore");
            }
            atomicReplace(temporary, file);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw new IllegalStateException("Unable to write staged database restore " + file, failure);
        }
    }

    static void archive(Path pending, Path history) {
        try {
            Files.createDirectories(history.getParent());
            try {
                Files.move(pending, history, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(pending, history);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to archive database restore manifest", failure);
        }
    }

    private static void atomicReplace(Path source, Path destination) throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String required(Properties properties, String key) {
        String value = optional(properties.getProperty(key));
        if (value == null) {
            throw new IllegalArgumentException("Database restore manifest lacks " + key);
        }
        return value;
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static UUID optionalUuid(String value) {
        String optional = optional(value);
        return optional == null ? null : UUID.fromString(optional);
    }
}
