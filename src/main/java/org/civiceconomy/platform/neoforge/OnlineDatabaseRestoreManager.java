package org.civiceconomy.platform.neoforge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredDatabaseBackupOperation;
import org.civiceconomy.persistence.StoredDatabaseRestoreOperation;

final class OnlineDatabaseRestoreManager {
    private final CivicDatabase database;
    private final Path databaseDirectory;
    private final Clock clock;

    OnlineDatabaseRestoreManager(
            CivicDatabase database, Path databaseDirectory, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.databaseDirectory = Objects.requireNonNull(databaseDirectory, "databaseDirectory")
                .toAbsolutePath()
                .normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    StoredDatabaseRestoreOperation stage(
            String administratorIdentity,
            String requestId,
            UUID sourceBackupOperationId,
            String reason) {
        requireText(administratorIdentity, "administratorIdentity");
        requireText(requestId, "requestId");
        Objects.requireNonNull(sourceBackupOperationId, "sourceBackupOperationId");
        requireText(reason, "reason");
        StoredDatabaseRestoreOperation replay =
                database.databaseRestoreOperation(administratorIdentity, requestId);
        if (replay != null) {
            if (!replay.sourceBackupOperationId().equals(sourceBackupOperationId)
                    || !replay.reason().equals(reason)) {
                throw new IllegalArgumentException(
                        "Database restore request replay changed its immutable payload");
            }
            return replay;
        }
        StoredDatabaseBackupOperation source =
                database.databaseBackupOperation(sourceBackupOperationId);
        if (source == null) {
            throw new IllegalArgumentException(
                    "Unknown database backup operation " + sourceBackupOperationId);
        }
        if (!"COMMITTED".equals(source.state())) {
            throw new IllegalArgumentException(
                    "Only a committed retained database backup may be staged");
        }
        Path sourceFile = backupFile(source.fileName());
        DatabaseRestoreFiles.requireEvidence(sourceFile, source.sizeBytes(), source.sha256());
        CivicDatabase.validateBackup(sourceFile, database.identity());

        StoredDatabaseRestoreOperation staged = database.prepareDatabaseRestoreOperation(
                UUID.randomUUID(),
                administratorIdentity,
                requestId,
                source.operationId(),
                source.fileName(),
                source.sizeBytes(),
                source.sha256(),
                reason,
                clock.millis());
        if ("STAGED".equals(staged.state())) {
            Path pending = pendingManifest();
            if (Files.exists(pending)) {
                DatabaseRestoreManifest existing = DatabaseRestoreManifest.read(pending);
                if (!existing.operationId().equals(staged.operationId())) {
                    throw new IllegalStateException(
                            "A different external database restore manifest is already staged");
                }
            }
            DatabaseRestoreManifest.staged(staged).write(pending);
        }
        return staged;
    }

    StoredDatabaseRestoreOperation cancel(
            String administratorIdentity,
            String requestId,
            UUID operationId,
            String reason) {
        requireText(administratorIdentity, "administratorIdentity");
        requireText(requestId, "requestId");
        Objects.requireNonNull(operationId, "operationId");
        requireText(reason, "reason");
        Path pending = pendingManifest();
        if (Files.exists(pending)) {
            DatabaseRestoreManifest manifest = DatabaseRestoreManifest.read(pending);
            if (!manifest.operationId().equals(operationId)) {
                throw new IllegalArgumentException(
                        "Cancellation does not match the staged database restore");
            }
            DatabaseRestoreManifest.archive(
                    pending,
                    historyDirectory().resolve(operationId + "-cancelled.properties"));
        }
        return database.cancelDatabaseRestoreOperation(
                operationId, administratorIdentity, requestId, reason, clock.millis());
    }

    private Path backupFile(String fileName) {
        Path backups = databaseDirectory.resolve("backups");
        Path resolved = backups.resolve(fileName).normalize();
        if (!resolved.getParent().equals(backups)) {
            throw new IllegalArgumentException("Backup file escaped its owned directory");
        }
        return resolved;
    }

    private Path pendingManifest() {
        return databaseDirectory.resolve("restore/pending.properties");
    }

    private Path historyDirectory() {
        return databaseDirectory.resolve("restore/history");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
